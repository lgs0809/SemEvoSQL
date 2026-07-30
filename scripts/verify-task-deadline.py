#!/usr/bin/env python3
"""Verify the retained Run evidence of one-time per-Todo budget allocation. Read-only."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--expected-tasks', type=int, required=True)
    parser.add_argument('--expected-unit-ms', type=int, default=300000)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Choose a new evidence path; do not replace earlier results')
    raw = args.evidence.read_bytes()
    evidence = json.loads(raw)
    run = evidence['run'][0]
    events = [e for e in evidence['events'] if e['event_type'] == 'TASK_EXECUTION_BUDGET_ALLOCATED']
    allocation = json.loads(events[0]['payload']) if len(events) == 1 else {}
    expected = args.expected_tasks * args.expected_unit_ms
    used = None
    if run.get('finish_time') and run.get('deadline_epoch_millis'):
        finish = datetime.fromisoformat(run['finish_time']).replace(tzinfo=timezone.utc).timestamp() * 1000
        used = expected - (run['deadline_epoch_millis'] - finish)
    checks = {
        'request_succeeded': run['status'] == 'SUCCEEDED',
        'unit_frozen': run.get('task_budget_unit_ms') == args.expected_unit_ms,
        'count_frozen': run.get('task_budget_count') == args.expected_tasks,
        'one_allocation_event': len(events) == 1,
        'event_matches_durable_budget': allocation.get('taskCount') == args.expected_tasks
            and allocation.get('unitMs') == args.expected_unit_ms
            and allocation.get('executionBudgetMs') == expected,
        'all_required_tasks_done': len(evidence['queryTasks']) == args.expected_tasks
            and all(task['status'] == 'DONE' for task in evidence['queryTasks']),
        'each_task_actually_approved': len([e for e in evidence['events'] if e['event_type'] == 'HUMAN_FEEDBACK_APPLIED']) == args.expected_tasks,
        'completed_within_execution_budget': used is not None and 0 <= used <= expected,
        'no_timeout_event': not any(e['event_type'] == 'RUN_FAILED' for e in evidence['events']),
    }
    result = {'status': 'PASS' if all(checks.values()) else 'FAIL', 'checks': checks,
              'executionBudgetSeconds': expected / 1000, 'executionSecondsUsed': None if used is None else round(used / 1000, 3),
              'input': str(args.evidence.resolve()), 'inputSha256': hashlib.sha256(raw).hexdigest(),
              'boundary': 'Budget/approval checks only. Amounts and SQL need independent business-oracle verification.'}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result['status'] == 'PASS' else 1)


if __name__ == '__main__':
    main()
