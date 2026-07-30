#!/usr/bin/env python3
"""Read the actual conversation response and compare each Todo's table/scope with accepted DB evidence."""
import argparse
import hashlib
import json
from pathlib import Path
from acceptance_http import LocalAcceptanceClient


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--account', default='semevosql-acceptance-owner')
    args = parser.parse_args()
    response_path = args.output.with_name(args.output.stem + '-response.json')
    if args.output.exists() or response_path.exists():
        raise ValueError('Retain previous evidence; select a new output path')
    raw = args.evidence.read_bytes()
    evidence = json.loads(raw)
    run = evidence['run'][0]
    reply = LocalAcceptanceClient(args.account).request(
        f"/api/semevosql/projects/{run['project_id']}/conversations/{run['thread_id']}")
    response_path.write_text(json.dumps(reply, ensure_ascii=False, indent=2) + '\n')
    message = next(m for m in reply['messages'] if m['runId'] == run['run_id'] and m['role'] == 'ASSISTANT')
    metadata = json.loads(message['metadataJson'])
    answers = metadata.get('taskAnswers', [])
    tasks = [t for t in evidence['queryTasks'] if t['status'] == 'DONE' and t['review_json']['decision'] == 'PASS']
    by_id = {a['taskId']: a for a in answers}
    checks = {
        'same_successful_request': run['status'] == message['status'] == 'SUCCEEDED',
        'exact_accepted_todo_order': [a['taskId'] for a in answers] == [t['task_id'] for t in tasks],
        'no_single_artifact_for_multiple_todos': len(tasks) > 1 and 'artifactId' not in metadata,
        'request_does_not_borrow_last_task_time': not metadata.get('executionExplanation', {}).get('time'),
        'request_diagnostics_retained': bool(metadata.get('executionExplanation', {}).get('sqlExecutions')),
    }
    for task in tasks:
        answer = by_id.get(task['task_id'], {})
        plan = task['semantic_plan_json']
        root = json.loads(task['result_summary_json']['resultPayload'])
        table = root.get('resultSet', root)
        label = task['task_id']
        checks[label + '_question_and_ordinal'] = answer.get('question') == task['question'] and answer.get('ordinal') == task['ordinal_no']
        checks[label + '_exact_accepted_data'] = answer.get('columns') == table['column'] and answer.get('rows') == table['data'] and not answer.get('error')
        time = (answer.get('explanation') or {}).get('time', {})
        expected_time = plan.get('timeRange') or {}
        checks[label + '_own_time_range'] = all(time.get(k) == expected_time.get(k) for k in ('startInclusive', 'endExclusive'))
    result = {'status': 'PASS' if all(checks.values()) else 'FAIL', 'checks': checks,
              'input': str(args.evidence.resolve()), 'inputSha256': hashlib.sha256(raw).hexdigest(),
              'httpResponse': str(response_path.resolve()),
              'boundary': 'Actual HTTP/accepted DB association; browser layout is checked separately through computer use.'}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result['status'] == 'PASS' else 1)


if __name__ == '__main__':
    main()
