#!/usr/bin/env python3
"""Create isolated UX replay fixtures through authenticated APIs; never overwrite existing cases."""
import argparse
import json
from pathlib import Path
from acceptance_http import LocalAcceptanceClient, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Preserve previous evidence; choose a fresh output')
    fixture = json.loads((ROOT / 'deploy/acceptance/product-ux-golden-cases.json').read_text())
    if fixture['projectId'] != 2:
        raise ValueError('This fixture is restricted to isolated acceptance project 2')
    client = LocalAcceptanceClient()
    path = '/api/semevosql/operations/projects/2/golden-cases'
    before = client.request(path)
    actions = []
    for case in fixture['cases']:
        matches = [row for row in before if row['case_code'] == case['caseCode']]
        if len(matches) > 1:
            raise ValueError('Duplicate existing fixture code; require inspection')
        if matches:
            row = matches[0]
            expected = json.loads(row['expected_json']) if isinstance(row['expected_json'], str) else row['expected_json']
            if expected.get('type') in ('json', 'jsonb') and isinstance(expected.get('value'), str):
                expected = json.loads(expected['value'])
            if 'schemaVersion' in expected:
                if expected['schemaVersion'] != 1:
                    raise ValueError('Unsupported existing assertion revision')
                expected = expected['payload']
            if (row['question'] != case['question'] or row['replay_mode'] != case['replayMode']
                    or row['enabled'] != case['enabled'] or expected != case['expected']):
                raise ValueError('Existing fixture differs; do not overwrite it')
            actions.append({'caseCode': case['caseCode'], 'id': row['id'], 'action': 'REUSED'})
        else:
            row = client.request(path, 'POST', case)
            actions.append({'caseCode': case['caseCode'], 'id': row['id'], 'action': 'CREATED'})
    result = {'status': 'PASS', 'projectId': 2, 'actions': actions, 'cases': client.request(path),
              'boundary': 'LIVE invariants only. Exact business amount is checked separately with read-only oracle SQL.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': result['status'], 'actions': actions}, ensure_ascii=False))


if __name__ == '__main__':
    main()
