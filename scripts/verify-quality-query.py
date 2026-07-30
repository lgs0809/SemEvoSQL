#!/usr/bin/env python3
"""Compare an actual isolated quality-project query with independent business SQL."""
import argparse
import json
from importlib.machinery import SourceFileLoader
from pathlib import Path

from acceptance_http import LocalAcceptanceClient
from result_acceptance_evidence import (
    final_query_receipt, final_result_artifact, final_receipt_matches_artifact,
    numeric_table_matches, query_result_contract_matches_answers,
)

sql = SourceFileLoader('quality_query_sql', str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--publication-proof', type=Path, required=True)
    parser.add_argument('--oracle-sql', type=Path, required=True)
    parser.add_argument('--column-map', required=True, help='JSON: actual result column -> independent oracle column')
    parser.add_argument('--date-column', action='append', default=[])
    parser.add_argument('--expected-metric', action='append', required=True)
    parser.add_argument('--require-clarification-answer', action='store_true')
    parser.add_argument('--require-query-result-contract', action='store_true')
    parser.add_argument('--require-positive-feedback', action='store_true')
    parser.add_argument('--account', default='semevosql-acceptance-owner')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or args.output.with_suffix('.sql').exists():
        raise ValueError('Preserve earlier evidence; choose a fresh output')

    evidence = json.loads(args.evidence.read_bytes())
    publication = json.loads(args.publication_proof.read_bytes())
    run = evidence['run'][0]
    identity = sql('semevosql_acceptance', "SELECT id FROM qw_project WHERE project_code='semevosql-quality-v1'")
    if (len(identity) != 1 or run['project_id'] != identity[0]['id']
            or publication.get('status') != 'PASS' or publication.get('stage') != 'published'
            or publication['projectId'] != run['project_id']):
        raise ValueError('Exact named isolated quality-project publication proof required')

    column_map = json.loads(args.column_map)
    if not column_map or len(set(column_map.values())) != len(column_map):
        raise ValueError('A nonempty one-to-one output/oracle column mapping is required')
    query = args.oracle_sql.read_text().strip().rstrip(';')
    oracle = sql('semevosql_quality_business_v1', query, preserve_decimals=True)
    events = evidence['events']
    plans = [json.loads(row['payload']) for row in events if row['event_type'] == 'SEMANTIC_PLAN_SNAPSHOT']
    approvals = [json.loads(row['payload']) for row in events if row['event_type'] == 'APPROVAL_PLAN_SNAPSHOT']
    plan = plans[-1] if plans else {}
    receipt = final_query_receipt(evidence)
    artifact = final_result_artifact(evidence)
    source_ids = {row['datasource_id'] for row in evidence['sqlExecutionAttempts'] if row['phase'] == 'QUERY'}
    bindings = publication['facts']['sourceBinding']

    def matches(rows):
        return bool(oracle) and numeric_table_matches(
            rows, [{column: row[column] for column in column_map.values()} for row in oracle],
            column_map, date_columns=args.date_column, exact_columns=True)

    checks = {
        'actual_run_succeeded': run['status'] == 'SUCCEEDED',
        'owner_can_read_actual_query': LocalAcceptanceClient(args.account).request(
            '/api/semevosql/runs/' + run['run_id'])['status'] == 'SUCCEEDED',
        'exact_published_version_frozen': run['project_version_id'] == publication['versionId']
            and plan.get('projectVersionId') == publication['versionId'],
        'normal_browser_plan_approval_matches_executed_plan': bool(approvals) and approvals[-1] == plan,
        'expected_public_metrics_consumed': {m['metricCode'] for m in plan.get('metrics', [])}
            == set(args.expected_metric),
        'no_private_candidate_pointer_used': bool(plan) and not plan.get('bindingDependencies'),
        'controlled_compilation_used': plan.get('compilerMode') in ('DETERMINISTIC', 'CONSTRAINED_GENERATION'),
        'only_isolated_readonly_source_was_queried': len(bindings) == 1
            and source_ids == {bindings[0]['datasource_id']}
            and bindings[0]['database_name'] == 'semevosql_quality_business_v1'
            and bindings[0]['username'] == 'semevosql_quality_reader_v1',
        'actual_sql_result_matches_independent_oracle': bool(receipt)
            and receipt['status'] == 'SUCCEEDED' and matches(receipt['result_json']['data']),
        'actual_display_artifact_matches_independent_oracle': bool(artifact)
            and matches(artifact['data_json']),
        'final_sql_and_display_artifact_agree': final_receipt_matches_artifact(evidence),
        'native_framework_checkpoints_exist': len(evidence['checkpoints']) >= 10,
        'actual_model_planning_recorded': any(
            json.loads(row['payload']).get('planningTrace', {}).get('modelCallCount', 0) > 0
            for row in events if row['event_type'] == 'PLANNING_TRACE'),
    }
    if args.require_query_result_contract:
        checks['temporary_outputs_match_exact_owned_submitted_answers'] = query_result_contract_matches_answers(
            plan, evidence, args.account)
    if args.require_clarification_answer:
        checks['normal_owned_clarification_answer_preserved'] = bool(evidence['questions']) and bool(evidence['answers'])
    else:
        checks['complete_known_business_request_was_not_reconfirmed'] = not evidence['questions']
    if args.require_positive_feedback:
        feedback = [row for row in evidence['feedback'] if row['idempotency_key'].endswith(':' + args.account)]
        cases = [row for row in evidence['queryCases'] if row['run_id'] == run['run_id']]
        checks.update({
            'one_normal_positive_owner_feedback': len(feedback) == 1
                and feedback[0]['rating'] == 5 and feedback[0]['adopted'] is True,
            'feedback_is_bound_to_actual_query_episode': len(feedback) == 1
                and feedback[0]['episode_id'] == run['episode_id'],
            'query_case_approved_from_actual_quality_chain': len(cases) == 1 and cases[0]['status'] == 'APPROVED',
        })
    report = {
        'status': 'PASS' if all(checks.values()) else 'FAIL', 'checks': checks,
        'runId': run['run_id'], 'oracle': oracle, 'columnMap': column_map, 'plan': plan,
        'boundary': 'Actual browser/model/SQL receipts from the named synthetic quality fixture. '
                    'The oracle is separate from model inputs; no success or approval state is written.',
    }
    args.output.with_suffix('.sql').write_text('-- Read-only independent quality-business oracle\n' + query + ';\n')
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'checks': len(checks),
                      'failed': [key for key, passed in checks.items() if not passed]}, ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)


if __name__ == '__main__':
    main()
