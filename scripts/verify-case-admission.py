#!/usr/bin/env python3
"""Verify saved, read-only local acceptance evidence without changing application state.

First run inspect-native-recovery.py, then pass its JSON here. Expected amounts must come
from the retained business oracle, not from the application response being checked.
"""
import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path


def obj(value):
    return json.loads(value) if isinstance(value, str) else (value or {})


def verify(evidence, expected_amounts, expected_tasks, expected_clarifications):
    run = evidence['run'][0]
    cases = evidence['queryCases']
    case = cases[0] if len(cases) == 1 else {}
    proof = case.get('quality_proof_json', {}).get('payload', {})
    request = proof.get('requestEvidence', {})
    sources = evidence['sourceSubRuns']
    merged = [a for a in evidence['resultArtifacts'] if a['artifact_type'] == 'MERGED_RESULT']
    actual = []
    for artifact in merged:
        for row in artifact['data_json']:
            for key, value in row.items():
                if key in ('paid_amount', '支付金额', 'total_paid_amount'):
                    actual.append(Decimal(str(value)))
    sql_traces = [s for s in evidence['sqlTraces'] if s['status'] == 'SUCCEEDED']
    checks = {
        'request_succeeded': run['status'] == 'SUCCEEDED',
        'no_feedback_submitted': not evidence['feedback'],
        'one_automatically_approved_case': len(cases) == 1 and case['status'] == 'APPROVED',
        'proof_belongs_to_request': request.get('request', {}).get('run_id') == run['run_id'],
        'proof_reports_eligible': request.get('eligibleAtCapture') is True,
        'proof_does_not_claim_adoption': proof.get('userAdopted') is False and proof.get('userRating') == 0,
        'all_expected_tasks_done': len(evidence['queryTasks']) == expected_tasks and all(t['status'] == 'DONE' for t in evidence['queryTasks']),
        'all_tasks_preserved': len(request.get('tasks', [])) == expected_tasks,
        'expected_initial_clarifications_answered': len(evidence['questions']) == expected_clarifications and all(q['status'] == 'ANSWERED' for q in evidence['questions']),
        'source_executions_preserved': len(request.get('sourceExecutions', [])) == len(sources) > 0,
        'source_execution_keys_unique': len({s['execution_key'] for s in sources}) == len(sources),
        'source_executions_succeeded': all(s['status'] == 'COMPLETED' for s in sources),
        'artifacts_preserved_and_ready': len(request.get('artifacts', [])) == len(evidence['resultArtifacts']) and all(a['status'] == 'READY' for a in evidence['resultArtifacts']),
        'executed_sql_has_guard_and_cost_validation': bool(sql_traces) and all(obj(s.get('guard_summary')).get('decision') == 'PASS' and obj(s.get('cost_summary')).get('decision') == 'PASS' for s in sql_traces),
        'merged_amounts_match_independent_oracle': sorted(actual) == sorted(Decimal(value) for value in expected_amounts),
        'no_finalization_warning': not any(e['event_type'] == 'RUN_FINALIZATION_WARNING' for e in evidence['events']),
    }
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--expected-amount', required=True, action='append')
    parser.add_argument('--expected-tasks', type=int, default=0)
    parser.add_argument('--expected-clarifications', type=int, default=0)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Keep earlier evidence; choose a new output path')
    raw = args.evidence.read_bytes()
    evidence = json.loads(raw)
    checks = verify(evidence, args.expected_amount, args.expected_tasks, args.expected_clarifications)
    terminal = evidence['run'][0]['status'] not in ('QUEUED', 'RUNNING', 'WAITING_HUMAN', 'CANCEL_REQUESTED')
    status = ('PASS' if all(checks.values()) else 'FAIL') if terminal else 'NOT_COMPLETE'
    result = {'status': status, 'checks': checks,
              'input': str(args.evidence.resolve()), 'inputSha256': hashlib.sha256(raw).hexdigest(),
              'scope': 'Request completion case admission; not full B.10 recall or model-quality evaluation.'}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if status == 'PASS' else (1 if terminal else 2))


if __name__ == '__main__':
    main()
