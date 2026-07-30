#!/usr/bin/env python3
"""Read-only proof that normal browser feedback reaches its actual query and learning case."""
import argparse
import json
import uuid
from pathlib import Path
from acceptance_http import LocalAcceptanceClient


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--account', required=True)
    p.add_argument('--rating', type=int, choices=range(1, 6), required=True)
    p.add_argument('--adopted', action='store_true')
    p.add_argument('--case-status', choices=('APPROVED', 'QUARANTINED'), required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.output.exists():
        raise ValueError('Retain earlier proof; choose a fresh output')
    e = json.loads(a.evidence.read_bytes())
    if len(e['run']) != 1 or e['run'][0]['project_id'] not in (1, 2):
        raise ValueError('One actual isolated acceptance Run required')
    r = e['run'][0]
    rid = str(uuid.UUID(r['run_id']))
    feedback = [f for f in e['feedback'] if f['idempotency_key'].endswith(':' + a.account)]
    cases = [c for c in e['queryCases'] if c['run_id'] == rid]
    checks = {
        'actual_run_and_owner_read_succeeded': r['status'] == 'SUCCEEDED'
            and LocalAcceptanceClient(a.account).request('/api/semevosql/runs/' + rid)['status'] == 'SUCCEEDED',
        'one_expected_normal_feedback_from_owner': len(feedback) == 1
            and feedback[0]['rating'] == a.rating and feedback[0]['adopted'] is a.adopted,
        'feedback_is_bound_to_actual_query_episode': len(feedback) == 1
            and r.get('episode_id') == feedback[0]['episode_id']
            and any(t['phase'] == 'QUERY' and t['status'] == 'SUCCEEDED' for t in e['sqlExecutionAttempts']),
        'learning_case_matches_expected_quality_state': len(cases) == 1 and cases[0]['status'] == a.case_status,
    }
    report = {'status': 'PASS' if all(checks.values()) else 'FAIL', 'checks': checks,
              'runId': rid, 'feedback': feedback, 'queryCases': cases,
              'boundary': 'The browser submits feedback. This script only reads its persisted evidence and the owner-visible Run.'}
    a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'checks': len(checks),
                      'failed': [k for k, v in checks.items() if not v], 'output': str(a.output)}, ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)


if __name__ == '__main__':
    main()
