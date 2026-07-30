#!/usr/bin/env python3
"""Verify a real query consumed the exact published shared definition and matches independent SQL."""
import argparse,json
from decimal import Decimal
from pathlib import Path
from result_acceptance_evidence import final_query_receipt, final_result_artifact, final_receipt_matches_artifact
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
from acceptance_fixture_scope import business_database
sql=SourceFileLoader('governed_query_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence',type=Path,required=True);p.add_argument('--publication-proof',type=Path,required=True)
    p.add_argument('--oracle-sql',type=Path,required=True);p.add_argument('--oracle-column',required=True)
    p.add_argument('--expected-value',type=Decimal,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--account',default='semevosql-acceptance-owner')
    p.add_argument('--namespace',help='Explicit ordinary synthetic fixture namespace');a=p.parse_args()
    if a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Fresh evidence output required')
    e=json.loads(a.evidence.read_bytes());publication=json.loads(a.publication_proof.read_bytes());run=e['run'][0]
    database=business_database(run['project_id'],sql,a.namespace)
    if publication.get('status')!='PASS' or publication['version']['projectId']!=run['project_id'] or publication['baseline']['project']!=run['project_id']:
        raise ValueError('Actual same-project isolated publication proof required')
    plans=[json.loads(x['payload']) for x in e['events'] if x['event_type']=='SEMANTIC_PLAN_SNAPSHOT']
    approved=[json.loads(x['payload']) for x in e['events'] if x['event_type']=='APPROVAL_PLAN_SNAPSHOT']
    metric=publication['publicMetric'];code=metric['metricCode'];ref=metric['definitionBinding']
    query=a.oracle_sql.read_text().strip().rstrip(';');oracle=sql(database,query)
    expected=Decimal(str(oracle[0][a.oracle_column])) if len(oracle)==1 else None
    def equal(rows):
        try:return len(rows)==1 and Decimal(str(rows[0][code]))==expected
        except (KeyError,TypeError,ValueError,ArithmeticError):return False
    receipt=final_query_receipt(e);artifact=final_result_artifact(e)
    selected=next((m for m in plans[-1]['metrics'] if m['metricCode']==code),{}) if plans else {}
    checks={
        'actual_run_succeeded':run['status']=='SUCCEEDED',
        'owner_can_read_private_query':LocalAcceptanceClient(a.account).request('/api/semevosql/runs/'+run['run_id'])['status']=='SUCCEEDED',
        'exact_published_version_frozen':run['project_version_id']==publication['version']['id'] and bool(plans) and plans[-1]['projectVersionId']==publication['version']['id'],
        'public_identity_and_shared_revision_consumed':selected.get('definitionBinding')==ref,
        'public_unit_and_time_field_match':selected.get('unit')==metric.get('unit') and selected.get('timeColumn')==metric.get('timeColumn'),
        'no_private_candidate_pointer_used':bool(plans) and not plans[-1]['bindingDependencies'],
        'normal_browser_plan_approval_frozen':bool(approved) and bool(plans) and approved[-1]==plans[-1],
        'deterministic_controlled_compilation_used':bool(plans) and plans[-1]['compilerMode']=='DETERMINISTIC',
        'independent_business_oracle_matches_expected':expected==a.expected_value,
        'actual_query_matches_oracle':bool(receipt) and receipt['status']=='SUCCEEDED' and equal(receipt['result_json']['data']),
        'durable_final_result_matches_oracle':bool(artifact) and equal(artifact['data_json']),
        'final_sql_and_display_artifact_agree':final_receipt_matches_artifact(e),
        'native_checkpoints_and_no_spurious_meaning_reconfirmation':len(e['checkpoints'])>=10 and not e['questions'],
        'actual_model_planning_recorded':any(json.loads(x['payload']).get('planningTrace',{}).get('modelCallCount',0)>0 for x in e['events'] if x['event_type']=='PLANNING_TRACE')}
    a.output.with_suffix('.sql').write_text('-- Read-only '+database+' independent oracle\n'+query+';\n')
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'runId':run['run_id'],'oracle':oracle,'metric':selected,
      'boundary':'Actual browser approval, real model planning, database execution and frozen public revision; no generated success states.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'output':str(a.output)},ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)
if __name__=='__main__':main()
