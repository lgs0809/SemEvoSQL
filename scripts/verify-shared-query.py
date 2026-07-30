#!/usr/bin/env python3
"""Read-only independent SQL oracle and frozen reference proof for the synthetic cancellation query."""
import argparse,json
from decimal import Decimal
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
sql=SourceFileLoader('offline_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Retain all prior evidence')
    evidence=json.loads(a.evidence.read_bytes());run=evidence['run'][0]
    if run['project_id']!=1 or run['project_version_id']!=5:raise ValueError('Restricted to the isolated 1.2 fixture version')
    query="""SELECT sum(amount) AS cancelled_creation_amount FROM public.orders
        WHERE status='CANCELLED' AND ordered_at >= TIMESTAMP '2026-01-01 00:00:00'
        AND ordered_at < TIMESTAMP '2026-02-01 00:00:00'"""
    a.output.with_suffix('.sql').write_text('-- Database: semevosql_acceptance_business\n'+query+';\n')
    oracle=sql('semevosql_acceptance_business',query);expected=Decimal(str(oracle[0]['cancelled_creation_amount']))
    plans=[json.loads(e['payload']) for e in evidence['events'] if e['event_type']=='SEMANTIC_PLAN_SNAPSHOT']
    approved=[json.loads(e['payload']) for e in evidence['events'] if e['event_type']=='APPROVAL_PLAN_SNAPSHOT']
    results=[r for r in evidence['resultArtifacts'] if r['artifact_type']=='MERGED_RESULT' and r['status']=='READY']
    def equals(value):
        try:
            if len(value)!=1:return False
            row=dict(value[0]);amount=row.pop('b_f7bfab336132bd98e6b424e2b8fc786f')
            # The approved MONTH time bucket is an actual result column, not a second amount.
            return Decimal(str(amount))==expected and row=={'ordered_at_month':'2026-01-01 00:00:00'}
        except (TypeError,ValueError,ArithmeticError):return False
    metric=plans[-1]['metrics'][0] if plans and len(plans[-1]['metrics'])==1 else {}
    ref=metric.get('definitionBinding',{});checks={
      'actual_run_succeeded':run['status']=='SUCCEEDED',
      'owner_can_read_actual_run':LocalAcceptanceClient().request('/api/semevosql/runs/'+run['run_id'])['status']=='SUCCEEDED',
      'exact_shared_revision_and_model_role':ref.get('definitionCode')=='entity_order_amount' and ref.get('definitionRevision')==1 and ref.get('modelCode')=='cancelled_orders' and ref.get('bindingCode')=='amount_total',
      'confirmed_alias_carried_in_plan':'已取消订单总金额' in ref.get('confirmedAliases',[]),
      'explicit_creation_time_and_yuan':metric.get('timeColumn')=='ordered_at' and metric.get('unit')=='yuan',
      'frozen_version_5':bool(plans) and plans[-1]['projectVersionId']==5 and bool(approved) and approved[-1]==plans[-1],
      'normal_user_execution_approval':any(e['event_type'] in ('REQUEST_APPROVED','HUMAN_FEEDBACK_ANSWERED','HUMAN_FEEDBACK_APPLIED') for e in evidence['events']),
      'independent_oracle_is_100_yuan':expected==100,
      'persisted_final_result_equals_oracle':len(results)==1 and equals(results[0]['data_json']),
      'actual_query_receipt_equals_oracle':any(s['phase']=='QUERY' and s['status']=='SUCCEEDED' and equals(s['result_json']['data']) for s in evidence['sqlExecutionAttempts']),
      'actual_sql_keeps_fixed_population_and_creation_time':any("CANCELLED" in s['sql_text'] and 'ordered_at' in s['sql_text'] for s in evidence['sqlTraces']),
      'native_checkpoints_survived_new_types':len(evidence['checkpoints'])>=10,
      'no_heuristic_preemptive_clarification':not any(q['question']=='您所说的指标具体指哪一个？' for q in evidence['questions']),
      'real_model_planning_recorded':any(json.loads(e['payload']).get('planningTrace',{}).get('modelCallCount',0)>0 for e in evidence['events'] if e['event_type']=='PLANNING_TRACE')}
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'oracle':oracle,'runId':run['run_id'],'frozenMetric':metric,
      'boundary':'Real model and browser approval, PostgreSQL business oracle, durable SQL receipts/results/checkpoints. No fabricated success.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'output':str(a.output)},ensure_ascii=False));raise SystemExit(0 if all(checks.values()) else 1)
if __name__=='__main__':main()
