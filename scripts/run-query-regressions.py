#!/usr/bin/env python3
"""Real development queries with normal approval and independent numeric oracles.

Reuse the deployed conversation/Run APIs, native checkpoint inspector and result
validators. This is explicitly a development smoke suite, not the held-out 300-case
benchmark: the existing project's learning history is retained. No feedback or
public definitions are created, and unexpected clarification is never invented away.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import threading
import time
import uuid

from acceptance_http import LocalAcceptanceClient, ROOT
from result_acceptance_evidence import (
    final_query_receipt, final_result_artifact, final_receipt_matches_artifact, numeric_table_matches,
)

spec = importlib.util.spec_from_file_location('regression_sql', Path(__file__).with_name('verify-offline-catalog.py'))
sql_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sql_module)


def validate_suite(path):
    suite = json.loads(path.read_bytes())
    if (suite['formatVersion'] != 1 or suite['scope'] != 'DEVELOPMENT_SMOKE_NOT_HELD_OUT_BENCHMARK'
            or suite['projectId'] != 3 or suite['projectVersionId'] != 11
            or suite['datasourceId'] != 2 or suite['account'] != 'sem_member_b'
            or suite['database'] != 'semevosql_quality_business_v1'):
        raise ValueError('Only the exact named local development fixture is supported')
    ids, families = set(), set()
    for case in suite['cases']:
        if (case['id'] in ids or case['family'] in families or not case['question'].strip()
                or not case['metrics'] or not case['columns']
                or not case['oracleSql'].strip().upper().startswith('SELECT ')
                or ';' in case['oracleSql']):
            raise ValueError('Each smoke case needs a distinct family, question and standalone SELECT oracle')
        ids.add(case['id']); families.add(case['family'])
    if not ids:
        raise ValueError('No cases')
    identity = sql_module.sql(suite['database'], 'SELECT fixture FROM fixture_identity WHERE id=1')
    if identity != [{'fixture': 'SEMEVOSQL_QUALITY_V1'}]:
        raise ValueError('Isolated synthetic business identity mismatch')
    return suite


def run_case(suite, case, directory, persist):
    case_directory = directory / case['id']
    case_directory.mkdir()
    oracle = sql_module.sql(suite['database'], case['oracleSql'], preserve_decimals=True)
    (case_directory / 'oracle.sql').write_text('BEGIN READ ONLY;\n' + case['oracleSql'] + ';\nROLLBACK;\n')
    (case_directory / 'oracle.json').write_text(json.dumps(oracle, ensure_ascii=False, indent=2) + '\n')
    record = {'caseId': case['id'], 'family': case['family'], 'question': case['question'],
              'startedAt': datetime.now(timezone.utc).isoformat(), 'status': 'RUNNING', 'checks': {}}
    persist(record)
    client = LocalAcceptanceClient(suite['account'])
    base = '/api/semevosql/projects/3/conversations'
    conversation = client.request(base, 'POST', {'title': '开发真实回归 · ' + case['id']})
    cid = conversation['conversationId']
    key = 'development-regression-' + str(uuid.uuid4())
    record.update(conversationId=cid, requestId=key)
    persist(record)
    submitted = client.request(base + '/' + cid + '/messages', 'POST', {
        'content': case['question'], 'idempotencyKey': key, 'requestId': key,
        'approvalMode': 'REQUIRE_APPROVAL'})
    run_id = submitted['run']['runId']
    record['runId'] = run_id
    persist(record)
    deadline = time.monotonic() + 720
    approved = False
    while True:
        run = client.request('/api/semevosql/runs/' + run_id)
        if run['status'] in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'CANCELED'):
            break
        if run['status'] == 'WAITING_HUMAN':
            events = client.request('/api/semevosql/runs/' + run_id + '/events?limit=200')
            plans = [json.loads(row['payload']) for row in events if row['eventType'] == 'APPROVAL_PLAN_SNAPSHOT']
            if not approved and plans:
                plan = plans[-1]
                if ({metric['metricCode'] for metric in plan.get('metrics', [])} != set(case['metrics'])
                        or plan.get('projectVersionId') != suite['projectVersionId']
                        or plan.get('bindingDependencies')):
                    record['reason'] = 'UNEXPECTED_APPROVAL_PLAN'
                    client.request('/api/semevosql/runs/' + run_id + '/cancel', 'POST', {
                        'idempotencyKey': key + ':cancel'})
                    break
                client.request(base + '/' + cid + '/runs/' + run_id + '/human-review', 'POST', {
                    'approved': True, 'feedback': '', 'idempotencyKey': key + ':approve'})
                approved = True
                record['approval'] = 'NORMAL_API_APPROVED_EXPECTED_FROZEN_PLAN'
                persist(record)
            else:
                record['reason'] = 'UNEXPECTED_CLARIFICATION_OR_SECOND_APPROVAL'
                client.request('/api/semevosql/runs/' + run_id + '/cancel', 'POST', {'idempotencyKey': key + ':cancel'})
                break
        if time.monotonic() >= deadline:
            record['reason'] = 'CLIENT_WAIT_LIMIT'
            client.request('/api/semevosql/runs/' + run_id + '/cancel', 'POST', {'idempotencyKey': key + ':cancel'})
            break
        time.sleep(2)
    evidence_path = case_directory / 'run.json'
    with (case_directory / 'inspection.log').open('x') as log:
        subprocess.run(['python3', str(ROOT / 'scripts/inspect-native-recovery.py'),
                        '--run-id', run_id, '--output', str(evidence_path)], cwd=ROOT,
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    evidence = json.loads(evidence_path.read_bytes())
    receipt, artifact = final_query_receipt(evidence), final_result_artifact(evidence)
    plans = [json.loads(row['payload']) for row in evidence['events']
             if row['event_type'] == 'APPROVAL_PLAN_SNAPSHOT']
    record['checks'] = {
        'run_succeeded': evidence['run'][0]['status'] == 'SUCCEEDED',
        'normal_expected_plan_approved': approved and bool(plans)
            and {m['metricCode'] for m in plans[-1]['metrics']} == set(case['metrics']),
        'exact_published_version_used': evidence['run'][0]['project_version_id'] == suite['projectVersionId'],
        'only_named_source_queried': {r['datasource_id'] for r in evidence['sqlExecutionAttempts']
                                    if r['phase'] == 'QUERY'} == {suite['datasourceId']},
        'sql_matches_independent_oracle': bool(receipt) and receipt['status'] == 'SUCCEEDED'
            and numeric_table_matches(receipt['result_json']['data'], oracle, case['columns'],
                                      date_columns=case.get('dateColumns', []), exact_columns=True),
        'artifact_matches_independent_oracle': bool(artifact)
            and numeric_table_matches(artifact['data_json'], oracle, case['columns'],
                                      date_columns=case.get('dateColumns', []), exact_columns=True),
        'accepted_receipt_and_artifact_match': final_receipt_matches_artifact(evidence),
        'native_checkpoints_preserved': len(evidence['checkpoints']) >= 10,
        'real_model_planning_recorded': any(json.loads(row['payload']).get('planningTrace', {})
            .get('modelCallCount', 0) > 0 for row in evidence['events'] if row['event_type'] == 'PLANNING_TRACE'),
        'no_unexpected_clarification': not evidence['questions'],
    }
    record.update(status='PASS' if all(record['checks'].values()) else 'FAIL',
                  runStatus=evidence['run'][0]['status'], errorCode=evidence['run'][0]['error_code'],
                  finishedAt=datetime.now(timezone.utc).isoformat(), evidence=str(evidence_path))
    persist(record)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', type=Path, default=ROOT / 'deploy/acceptance/query-regressions-v1.json')
    parser.add_argument('--output-directory', type=Path, required=True)
    parser.add_argument('--workers', type=int, choices=(1, 2), default=1)
    parser.add_argument('--case', action='append')
    args = parser.parse_args()
    if args.output_directory.exists():
        parser.error('Preserve previous runs; choose a new directory')
    suite = validate_suite(args.suite)
    cases = [case for case in suite['cases'] if not args.case or case['id'] in args.case]
    if not cases or (args.case and set(args.case) != {case['id'] for case in cases}):
        parser.error('Unknown case selection')
    args.output_directory.mkdir(parents=True)
    report = {'status': 'RUNNING', 'scope': suite['scope'], 'suite': str(args.suite.resolve()),
              'suiteSha256': hashlib.sha256(args.suite.read_bytes()).hexdigest(),
              'plannedCases': len(cases), 'cases': {},
              'boundary': 'Actual development API/model/SQL runs, not computer-use runs or held-out evaluation. '
                          'Existing learning history preserved; no manual success-state writes.'}
    lock = threading.Lock()

    def persist(record):
        with lock:
            report['cases'][record['caseId']] = dict(record)
            temporary = args.output_directory / 'summary.tmp'
            temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            temporary.replace(args.output_directory / 'summary.json')

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        pending = {pool.submit(run_case, suite, case, args.output_directory, persist): case for case in cases}
        for future in as_completed(pending):
            case = pending[future]
            try:
                result = future.result()
                print(json.dumps({'caseId': case['id'], 'status': result['status'], 'runId': result['runId']}), flush=True)
            except Exception as error:
                prior = report['cases'].get(case['id'], {'caseId': case['id'], 'family': case['family']})
                prior.update(status='BLOCKED_HARNESS', errorType=type(error).__name__)
                persist(prior)
                print(json.dumps({'caseId': case['id'], 'status': prior['status'], 'errorType': type(error).__name__}), flush=True)
    counts = {status: sum(row['status'] == status for row in report['cases'].values())
              for status in ('PASS', 'FAIL', 'BLOCKED_HARNESS')}
    report.update(status='PASS_DEVELOPMENT_REGRESSION' if counts['PASS'] == len(cases) else 'COMPLETED_WITH_FAILURES',
                  counts=counts, independentDevelopmentCases=len(cases), heldOutCases=0)
    (args.output_directory / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'counts': counts}), flush=True)
    return 0 if counts['PASS'] == len(cases) else 1


if __name__ == '__main__':
    raise SystemExit(main())
