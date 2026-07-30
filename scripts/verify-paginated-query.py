#!/usr/bin/env python3
"""Cross-check a real accepted pagination Run against the fixed business seed and trusted account isolation."""
import argparse
import hashlib
import json
import subprocess
import urllib.error
from decimal import Decimal
from pathlib import Path
from acceptance_http import LocalAcceptanceClient


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    if args.output.exists() or args.output.with_suffix('.sql').exists(): raise ValueError('Retain earlier evidence')
    evidence=json.loads(args.evidence.read_bytes());run=evidence['run'][0]
    query="""SELECT row_to_json(q) FROM (
        SELECT customer_id, SUM(amount) AS paid_amount FROM orders
        WHERE status='PAID' AND paid_at >= TIMESTAMP '2026-01-01' AND paid_at < TIMESTAMP '2026-02-01'
        GROUP BY customer_id ORDER BY customer_id ASC NULLS LAST LIMIT 2 OFFSET 1
        ) q"""
    args.output.with_suffix('.sql').write_text('-- Independent fixed seed oracle, business database only.\n'+query+';\n')
    raw=subprocess.check_output(['docker','exec','semevosql-acceptance-metadata-db-1','psql','-X','-A','-t','-v','ON_ERROR_STOP=1',
        '-U','acceptance','-d','semevosql_acceptance_business','-c',query],text=True)
    oracle=[json.loads(row) for row in raw.splitlines() if row]
    canonical=lambda rows:[(str(r['customer_id']),Decimal(str(r['paid_amount']))) for r in rows]
    artifacts=[a for a in evidence['resultArtifacts'] if a['artifact_type']=='MERGED_RESULT' and a['status']=='READY']
    approvals=[e for e in evidence['events'] if e['event_type']=='APPROVAL_PLAN_SNAPSHOT']
    plan=json.loads(approvals[0]['payload']) if approvals else {}
    checks={
        'successful_real_run':run['status']=='SUCCEEDED',
        'independent_seed_expected':canonical(oracle)==[('1002',Decimal('80')),('1003',Decimal('40'))],
        'persisted_result_matches_independent_query':len(artifacts)==1 and canonical(artifacts[0]['data_json'])==canonical(oracle),
        'approved_exact_page':plan.get('offset')==1 and plan.get('limit')==2
            and plan.get('orderBy')==[{'direction':'ASC','expression':'customer_id','nulls':'LAST'}],
        'frozen_catalog_version':run['project_version_id']==plan.get('projectVersionId')==3,
        'actual_query_receipt_matches':any(a['phase']=='QUERY' and a['status']=='SUCCEEDED'
            and canonical(a['result_json']['data'])==canonical(oracle) for a in evidence['sqlExecutionAttempts']),
        'post_execution_review_pass':any(e['event_type']=='POST_EXECUTION_REVIEW'
            and json.loads(e['payload'])['review']['decision']=='PASS' for e in evidence['events']),
        'native_checkpoints_retained':len(evidence['checkpoints'])>=10,
    }
    clients={name:LocalAcceptanceClient(name) for name in ('sem_member_a','sem_member_b','semevosql-acceptance-owner')}
    base='/api/semevosql/runs/'+run['run_id']
    http=[]
    def access(name,client,path,expected,headers=None,method='GET',body=None):
        try: result=client.request(path,method,body,headers);actual=200
        except urllib.error.HTTPError as error:actual=error.code;result=None
        passed=actual==expected;checks[name]=passed;http.append({'check':name,'expected':expected,'actual':actual})
        return result
    owner=access('owner_reads_actual_run',clients['sem_member_a'],base,200)
    if owner:checks['public_run_succeeded']=owner.get('status')=='SUCCEEDED'
    for principal in ('sem_member_b','semevosql-acceptance-owner'):
        for suffix in ('','/events'):
            access(principal+' cannot read '+suffix,clients[principal],base+suffix,403)
    access('spoofed_header_cannot_read_actual_run',clients['sem_member_b'],base,403,{'X-User-ID':'sem_member_a'})
    access('other_member_cannot_cancel_actual_run',clients['sem_member_b'],base+'/cancel',403,
        method='POST',body={'idempotencyKey':'forbidden-acceptance-'+run['run_id']})
    conversation='/api/semevosql/projects/'+str(run['project_id'])+'/conversations/'+run['thread_id']
    view=access('owner_reads_persisted_result',clients['sem_member_a'],conversation,200)
    checks['conversation_durable_owner']=bool(view and view['conversation']['createdBy']=='sem_member_a')
    result={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'httpChecks':http,'runId':run['run_id'],
        'oracle':oracle,'evidenceSha256':hashlib.sha256(args.evidence.read_bytes()).hexdigest(),
        'boundary':'Actual model/browser query, business SQL oracle, durable receipt and trusted principal checks. Not an assessment of 300-question quality.'}
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False));raise SystemExit(0 if result['status']=='PASS' else 1)


if __name__=='__main__':main()
