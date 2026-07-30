#!/usr/bin/env python3
"""Check actual root/Todo recall and restart evidence saved by inspect-native-recovery.py.

Read-only: compares frozen revisions and event order. It does not assert semantic
model quality or authorize reuse; those need the business oracle and application checks.
"""
import argparse
import hashlib
import json
from pathlib import Path


def obj(value):
    return json.loads(value) if isinstance(value, str) else value


def verify(evidence, before, expected_tasks):
    run = evidence['run'][0]
    snapshots = evidence.get('caseHistorySnapshots', [])
    saved = {row['snapshot_id']: row for row in snapshots}
    roots = [row for row in snapshots if row['snapshot_json']['stage'] == 'REQUEST']
    tasks = [row for row in snapshots if row['snapshot_json']['stage'] == 'TASK']
    events = [{**e, 'data': obj(e['payload'])} for e in evidence['events']
              if e['event_type'] in ('CASE_RECALL_COMPLETED', 'CASE_HISTORY_CONSUMED',
                                    'REQUEST_ANALYSIS_COMPLETED', 'TODO_ACTIVATED')]
    recalls = [e for e in events if e['event_type'] == 'CASE_RECALL_COMPLETED']
    consumed = [e for e in events if e['event_type'] == 'CASE_HISTORY_CONSUMED']
    analyses = [e for e in events if e['event_type'] == 'REQUEST_ANALYSIS_COMPLETED']
    root_events = [e for e in recalls if e['data']['stage'] == 'REQUEST']
    root_id = roots[0]['snapshot_id'] if len(roots) == 1 else None
    used_root = [e for e in consumed if e['data']['consumer'] == 'REQUEST_ANALYSIS']
    planned = [e for e in consumed if e['data']['consumer'] == 'SEMANTIC_BLUEPRINT']
    activations = {e['data']['taskId']: e['sequence'] for e in events if e['event_type'] == 'TODO_ACTIVATED'}
    actual_tasks = {t['task_id'] for t in evidence['queryTasks']}
    cases = [c for s in snapshots for c in s['snapshot_json']['cases']]
    checks = {
        'request_succeeded': run['status'] == 'SUCCEEDED',
        'one_root_snapshot': len(roots) == 1,
        'one_snapshot_per_todo_without_extra_retrieval': len(tasks) == expected_tasks
            and {s['snapshot_json']['taskId'] for s in tasks} == actual_tasks
            and all(s['snapshot_json']['retrievalRevision'] == 0 for s in tasks),
        'root_at_most_one_todo_at_most_two': bool(roots)
            and all(len(s['snapshot_json']['cases']) <= 1 for s in roots)
            and all(len(s['snapshot_json']['cases']) <= 2 for s in tasks),
        'case_revisions_and_frozen_source_agree': bool(cases) and all(
            c['caseId'] == c['details']['caseId'] and c['caseRevision'] == c['details']['caseRevision']
            and c['details']['requestEvidence']['request']['run_id'] == c['details']['sourceRunId']
            and c['details']['requestEvidence']['request']['status'] == 'SUCCEEDED' for c in cases),
        'each_snapshot_has_one_completion_event': len(recalls) == len(snapshots)
            and {e['data']['snapshotId'] for e in recalls} == set(saved),
        'root_consumed_before_request_analysis': len(root_events) == 1 and len(used_root) == 1 and len(analyses) == 1
            and root_events[0]['sequence'] < used_root[0]['sequence'] < analyses[0]['sequence'],
        'todo_recall_follows_activation': all(
            activations.get(e['data']['taskId'], 10**20) < e['sequence']
            for e in recalls if e['data']['stage'] == 'TASK'),
        'each_todo_planned_with_same_root': len(planned) == expected_tasks
            and {e['data']['taskId'] for e in planned} == actual_tasks
            and all(e['data']['snapshotReferences'].get('request') == root_id for e in planned),
        'merged_cases_deduplicated_max_three': all(
            len(e['data']['caseIds']) == len(set(e['data']['caseIds'])) <= 3 for e in planned),
        'consumption_references_are_persisted': all(
            set(e['data']['snapshotReferences'].values()) <= set(saved) for e in consumed),
        'no_case_sql_in_public_recall_events': all(
            not any(k in e['data'] for k in ('details', 'sql', 'sqlText', 'sourceExecutionDetails'))
            for e in recalls + consumed),
    }
    if before is not None:
        old = before.get('caseHistorySnapshots', [])
        checks['restart_same_run'] = before['run'][0]['run_id'] == run['run_id']
        checks['restart_was_waiting_for_approval'] = before['run'][0]['status'] == 'WAITING_HUMAN'
        checks['restart_retains_identical_frozen_snapshots'] = bool(old) and all(
            s['snapshot_id'] in saved and saved[s['snapshot_id']]['content_hash'] == s['content_hash']
            and saved[s['snapshot_id']]['snapshot_json'] == s['snapshot_json'] for s in old)
        checks['restart_keeps_existing_answers'] = all(
            a in evidence['answers'] for a in before['answers'])
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--before-restart', type=Path)
    parser.add_argument('--expected-tasks', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Retain earlier evidence; use a new output')
    raw = args.evidence.read_bytes()
    evidence = json.loads(raw)
    before = json.loads(args.before_restart.read_bytes()) if args.before_restart else None
    checks = verify(evidence, before, args.expected_tasks)
    terminal = evidence['run'][0]['status'] not in ('QUEUED', 'RUNNING', 'WAITING_HUMAN', 'CANCEL_REQUESTED')
    status = ('PASS' if all(checks.values()) else 'FAIL') if terminal else 'NOT_COMPLETE'
    result = {'status': status, 'checks': checks, 'input': str(args.evidence.resolve()),
              'inputSha256': hashlib.sha256(raw).hexdigest(),
              'scope': 'Frozen case provenance, flow ordering and optional restart persistence; not all B.10 acceptance.'}
    if args.before_restart:
        result['beforeRestart'] = str(args.before_restart.resolve())
        result['beforeRestartSha256'] = hashlib.sha256(args.before_restart.read_bytes()).hexdigest()
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if status == 'PASS' else (1 if terminal else 2))


if __name__ == '__main__':
    main()
