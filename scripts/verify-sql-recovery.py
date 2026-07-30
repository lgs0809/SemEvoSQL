#!/usr/bin/env python3
"""Cross-check retained real-browser SQL recovery evidence; never mutates application state."""
import argparse, hashlib, json
from decimal import Decimal
from pathlib import Path

def obj(v):return json.loads(v) if isinstance(v,str) else (v or {})
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence',type=Path,required=True);p.add_argument('--expected-amount',required=True)
    p.add_argument('--amount-column',action='append',help='Exact result column to verify; repeat for equivalent aliases. Default retains the paid-amount checks.')
    p.add_argument('--fault-evidence',type=Path,required=True);p.add_argument('--require-case',action='store_true')
    p.add_argument('--require-model-review',action='store_true',help='Also require actual post-execution model call evidence; a deterministic PASS is insufficient')
    p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists():raise ValueError('Retain earlier evidence; choose a new output')
    raw=a.evidence.read_bytes();e=json.loads(raw);fault=json.loads(a.fault_evidence.read_bytes());run=e['run'][0]
    failed=[s for s in e['sqlTraces'] if s['status']=='FAILED'];success=[s for s in e['sqlTraces'] if s['status']=='SUCCEEDED']
    reviews=[obj(x['payload']) for x in e['events'] if x['event_type']=='POST_EXECUTION_REVIEW'];last=reviews[-1] if reviews else {}
    finals=[x for x in e['resultArtifacts'] if x['artifact_type'] in ('DIRECT_RESULT','MERGED_RESULT') and x['status']=='READY']
    amount_columns=a.amount_column or ['paid_amount','支付金额','total_paid_amount']
    amounts=[Decimal(str(v)) for ar in finals for row in ar['data_json'] for k,v in row.items() if k in amount_columns]
    checks={
        'same_run_completed':run['run_id']==fault['runId'] and run['status']=='SUCCEEDED',
        'fault_actually_held_and_released':fault['lockConfirmed'] and fault['rollbackProcessSucceeded'],
        'browser_approval_recorded':any(x['event_type']=='HUMAN_FEEDBACK_APPLIED' for x in e['events']),
        'failed_and_succeeded_sql_both_retained':bool(failed) and bool(success),
        'failure_retains_state_and_cleanup':any(obj(x.get('result_summary')).get('sqlState')=='57014' and obj(x.get('result_summary')).get('cleanupStatus') in ('ROLLBACK_CONFIRMED','SESSION_TERMINATION_CONFIRMED') for x in failed),
        'final_amount_matches_business_oracle':amounts==[Decimal(a.expected_amount)],
        'final_review_passed':last.get('review',{}).get('decision')=='PASS',
        'one_shared_sql_repair_consumed':last.get('repairBudget',{}).get('sqlRepairsUsed')==1,
        'no_new_clarification_required':not e['questions'],
        'no_finalization_warning':not any(x['event_type']=='RUN_FINALIZATION_WARNING' for x in e['events']),
        'all_source_subruns_settled':all(x['status'] in ('COMPLETED','FAILED','CANCELLED') for x in e['sourceSubRuns']),
    }
    attempts=e.get('sqlExecutionAttempts',[])
    if attempts:
        checks['all_sql_attempts_settled']=all(x['status'] in ('SUCCEEDED','FAILED') for x in attempts)
        checks['logical_attempt_identity_unique']=len({(x['scope_key'],x['phase']) for x in attempts})==len(attempts)
        checks['adjusted_query_has_persisted_deadline']=any(x['phase']=='QUERY' and x['status']=='SUCCEEDED' and x['deadline_epoch_ms'] for x in attempts)
    if fault.get('backendKilled'):
        old=[x for snapshot in fault['observations'] for x in snapshot.get('sqlExecutionAttempts',[]) if x['status']=='RUNNING']
        checks['backend_restarted']=fault['backendRestarted']
        checks['crashed_attempt_deadline_not_reset']=bool(old) and all(any(x['sql_attempt_id']==before['sql_attempt_id'] and x['deadline_epoch_ms']==before['deadline_epoch_ms'] and x['status']=='FAILED' and obj(x.get('error_json')).get('cleanupStatus')=='SESSION_TERMINATION_CONFIRMED' for x in attempts) for before in old)
    if a.require_case:
        checks['validated_request_auto_admitted']=len(e['queryCases'])==1 and e['queryCases'][0]['status']=='APPROVED'
        checks['no_manual_feedback_needed']=not e['feedback']
    if a.require_model_review:
        reviewed=last.get('review',{});model=reviewed.get('modelEvidence') or {}
        checks['post_execution_model_review_recorded']=reviewed.get('semanticReviewerUsed') is True and bool(model.get('callId'))
        checks['post_execution_model_tokens_recorded']=model.get('inputTokens',0)>0 and model.get('outputTokens',0)>0
    result={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'amountColumns':amount_columns,
        'expectedAmount':a.expected_amount,'actualAmounts':[str(v) for v in amounts],
        'inputSha256':hashlib.sha256(raw).hexdigest(),'input':str(a.evidence.resolve()),
        'faultEvidenceSha256':hashlib.sha256(a.fault_evidence.read_bytes()).hexdigest()}
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False))
    raise SystemExit(0 if result['status']=='PASS' else 1)
if __name__=='__main__':main()
