#!/usr/bin/env python3
"""Read-only linkage of the final SQL, durable model review, result receipt, conversation and learned case."""
import argparse
import hashlib
import json
from pathlib import Path
from result_acceptance_evidence import final_query_receipt, final_result_artifact, final_receipt_matches_artifact


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--require-model-review', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Preserve previous evidence')
    e = json.loads(args.evidence.read_bytes())
    run = e['run'][0]
    receipt, artifact = final_query_receipt(e), final_result_artifact(e)
    events = sorted(e['events'], key=lambda r: r['sequence'])
    reviews = [r for r in events if r['event_type'] == 'POST_EXECUTION_REVIEW']
    accepted = [r for r in events if r['event_type'] == 'RESULT_ARTIFACT_ACCEPTED']
    approvals = [json.loads(r['payload']) for r in events if r['event_type'] == 'APPROVAL_PLAN_SNAPSHOT']
    review_event = reviews[-1] if reviews else {}
    review = json.loads(review_event.get('payload', '{}'))
    payload = review.get('review', {})
    effects = [r for r in e.get('nodeEffects', []) if r['node_key'] == 'post-execution-review:' + str(review.get('step'))]
    effect = effects[0] if len(effects) == 1 else {}
    result = json.loads(effect.get('result_json', '{}'))
    effect_set = json.loads(result.get('resultPayload', '{}')).get('resultSet')
    final_id = artifact['artifact_id'] if artifact else None
    cases = [r for r in e['queryCases'] if r['status'] == 'APPROVED']
    case_proofs = [r['quality_proof_json'].get('payload', r['quality_proof_json']) for r in cases]
    turns = [r for r in e['conversationTurns'] if r['run_id'] == run['run_id']]
    traces = sorted(e['sqlTraces'], key=lambda r: (r['create_time'], r['id']))
    trace = traces[-1] if traces else {}
    model = payload.get('modelEvidence') or {}
    checks = {
        'actual_run_succeeded': run['status'] == 'SUCCEEDED',
        'last_query_succeeded': bool(receipt) and receipt['status'] == 'SUCCEEDED',
        'last_query_matches_selected_artifact': final_receipt_matches_artifact(e),
        'exact_approved_plan_reviewed': bool(approvals) and approvals[-1] == review.get('typedPlan'),
        'last_review_passed': payload.get('decision') == 'PASS',
        'durable_effect_contains_exact_review': effect.get('status') == 'COMPLETED' and result.get('review') == payload,
        'review_event_belongs_to_exact_effect_input': bool(effect) and review_event.get('idempotency_key') == 'post-review:' + str(review.get('step')) + ':' + effect['input_hash'],
        'accepted_receipt_belongs_to_exact_effect': bool(accepted) and bool(effect) and accepted[-1]['idempotency_key'] == 'result-artifact-accepted:' + str(final_id) + ':' + hashlib.sha256(effect['input_hash'].encode()).hexdigest(),
        'reviewed_sql_is_last_executed_trace': bool(trace) and trace['status'] == 'SUCCEEDED' and trace['sql_text'].strip().rstrip(';') == result.get('sql', '').strip().rstrip(';'),
        'reviewed_rows_are_last_query_rows': bool(receipt) and effect_set == receipt.get('result_json'),
        'conversation_uses_same_accepted_artifact': len(turns) == 1 and turns[0]['status'] == 'COMPLETED' and turns[0]['result_artifact_id'] == final_id,
        'learned_case_uses_same_accepted_artifact': bool(case_proofs) and all(p.get('postExecutionReviewPassed') is True and p.get('finalResultArtifact', {}).get('artifact_id') == final_id for p in case_proofs),
    }
    if args.require_model_review:
        checks['real_model_review_evidence_preserved'] = payload.get('semanticReviewerUsed') is True and bool(model.get('callId')) and model.get('inputTokens', 0) > 0 and model.get('latencyMs', 0) > 0
    report = {'status': 'PASS' if all(checks.values()) else 'FAIL', 'checks': checks, 'runId': run['run_id'],
              'artifactId': final_id, 'reviewModelEvidence': model,
              'boundary': 'Real database snapshots only. Single-source final SQL/result lineage; independent numeric oracle remains separately required.'}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'checks': len(checks), 'failed': [k for k, v in checks.items() if not v], 'output': str(args.output)}, ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)


if __name__ == '__main__':
    main()
