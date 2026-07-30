#!/usr/bin/env python3
"""Validate persisted channel receipts for a real, single-task browser acceptance Run."""
import argparse
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--require-vector', action='store_true')
    parser.add_argument('--expected-fallback', choices=['none', 'embedding', 'rerank', 'both'], default='none')
    parser.add_argument('--model-receipts', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Retain earlier evidence; choose a fresh output')
    raw = args.evidence.read_bytes()
    data = json.loads(raw)
    traces = [json.loads(e['payload']) for e in data['events'] if e['event_type'] == 'PLANNING_TRACE']
    hits = [h for t in traces for h in t.get('retrievalCandidates', [])]
    channels = {c for h in hits for c in h.get('channelRanks', {})}
    snapshots = data.get('caseHistorySnapshots', [])
    consumed = [json.loads(e['payload']) for e in data['events'] if e['event_type'] == 'CASE_HISTORY_CONSUMED']
    refs = {v for e in consumed for v in e['snapshotReferences'].values()}
    checks = {
        'run_succeeded': data['run'][0]['status'] == 'SUCCEEDED',
        'persisted_planning_trace_present': bool(traces),
        'fts_really_contributed': 'FTS' in channels,
        'rrf_really_contributed': 'RRF' in channels,
        'rerank_channel_matches_expected_state': ('RERANK' in channels) == (args.expected_fallback not in ('rerank', 'both')),
        'no_legacy_memory_ranking': not {'EXACT', 'BM25'}.intersection(channels),
        'one_root_snapshot_for_single_task': len(snapshots) == 1 and snapshots[0]['snapshot_json']['stage'] == 'REQUEST'
            and not data['queryTasks'],
        'same_snapshot_consumed_by_analysis_and_planning': len(refs) == 1
            and refs == {s['snapshot_id'] for s in snapshots}
            and {e['consumer'] for e in consumed} >= {'REQUEST_ANALYSIS', 'SEMANTIC_BLUEPRINT'},
        'actual_source_execution_completed': bool(data['sourceSubRuns'])
            and all(s['status'] == 'COMPLETED' for s in data['sourceSubRuns']),
    }
    if args.require_vector:
        checks['vector_really_contributed'] = 'VECTOR' in channels
    if args.expected_fallback in ('embedding', 'both'):
        checks['embedding_fallback_did_not_claim_vectors'] = 'VECTOR' not in channels
    receipts = []
    if args.model_receipts:
        import re
        log = args.model_receipts.read_text()
        for kind, model, budget, elapsed in re.findall(
                r'Retrieval model HTTP finished kind=(\w+) model=(\S+) budgetMs=(\d+) elapsedMs=(\d+)', log):
            receipts.append(dict(kind=kind, model=model, budgetMs=int(budget), elapsedMs=int(elapsed)))
        checks['actual_model_http_receipts_present'] = {r['kind'] for r in receipts} == {'EMBEDDING', 'RERANK'}
        checks['each_request_budget_at_most_five_seconds'] = bool(receipts) and all(0 < r['budgetMs'] <= 5000 for r in receipts)
        # A small scheduling/cleanup allowance is separate from the configured 5000-ms request deadline.
        checks['observed_http_completion_within_budget_plus_250ms'] = bool(receipts) and all(r['elapsedMs'] <= r['budgetMs'] + 250 for r in receipts)
        if args.expected_fallback in ('rerank', 'both'):
            checks['real_rerank_timeout_recorded'] = bool(re.search(r'Rerank unavailable;.*TimeoutException', log))
        if args.expected_fallback in ('embedding', 'both'):
            checks['real_embedding_unavailable_recorded'] = 'Semantic query embedding is unavailable;' in log
    terminal = data['run'][0]['status'] not in ('RUNNING', 'QUEUED', 'WAITING_HUMAN', 'CANCEL_REQUESTED')
    status = ('PASS' if all(checks.values()) else 'FAIL') if terminal else 'NOT_COMPLETE'
    result = {'status': status, 'checks': checks, 'observedChannels': sorted(channels),
              'expectedFallback': args.expected_fallback, 'modelHttpReceipts': receipts,
              'modelReceiptsSha256': hashlib.sha256(args.model_receipts.read_bytes()).hexdigest() if args.model_receipts else None,
              'input': str(args.evidence.resolve()), 'inputSha256': hashlib.sha256(raw).hexdigest(),
              'boundary': 'Channel participation and frozen root reuse, not all B.7/B.9 quality or permission testing.'}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if status == 'PASS' else (1 if terminal else 2))


if __name__ == '__main__':
    main()
