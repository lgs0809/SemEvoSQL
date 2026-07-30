#!/usr/bin/env python3
"""Replay an already submitted browser answer through the normal authenticated API, without fabricating state."""
import argparse,json,uuid
from concurrent.futures import ThreadPoolExecutor
from importlib.machinery import SourceFileLoader
from pathlib import Path
from urllib.error import HTTPError
from acceptance_http import LocalAcceptanceClient

sql=SourceFileLoader('answer_replay_sql',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--evidence',type=Path,required=True)
    p.add_argument('--account',required=True);p.add_argument('--other-account',required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    if a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Fresh evidence path required')
    e=json.loads(a.evidence.read_bytes());r=e['run'][0]
    if r['project_id']!=2 or r['status']!='SUCCEEDED' or len(e['answers'])!=1:raise ValueError('One completed actual project-2 answer required')
    answer=e['answers'][0];question=e['questions'][0];run=str(uuid.UUID(r['run_id']));qid=str(uuid.UUID(question['clarification_id']))
    if question['asset_type']!='SEMANTIC_DEFINITION_UPDATE' or answer['answered_by']!=a.account or a.account==a.other_account:
        raise ValueError('Definition-management owner answer and distinct actual account required')
    body={'revision':answer['clarification_revision'],'idempotencyKey':answer['idempotency_key'],
        'selectedOption':answer['selected_option'],'customAnswer':answer['custom_answer'],'scope':answer['selected_scope']}
    queries={
        'answers':f"SELECT * FROM qw_runtime_clarification_answer WHERE clarification_id='{qid}' ORDER BY id",
        'receipts':f"SELECT * FROM qw_personal_definition_change_receipt WHERE clarification_id='{qid}'",
        'authorizations':f"SELECT x.* FROM qw_user_semantic_authorization x JOIN qw_personal_definition_change c USING(preference_id) WHERE c.clarification_id='{qid}' ORDER BY x.definition_revision,x.authorization_revision",
        'definitions':f"SELECT x.* FROM qw_user_semantic_definition_revision x JOIN qw_personal_definition_change c USING(preference_id) WHERE c.clarification_id='{qid}' ORDER BY revision",
        'sharing':f"SELECT * FROM qw_personal_sharing_use_decision WHERE clarification_id='{qid}' ORDER BY id",
        'events':f"SELECT event_type,idempotency_key FROM qw_run_event WHERE run_id='{run}' ORDER BY sequence",
        'checkpoints':f"SELECT c.checkpoint_id,md5(c.state_data::text) AS state_hash FROM graphcheckpoint c JOIN graphthread t ON t.thread_id=c.thread_id JOIN qw_native_graph_binding b ON b.graph_thread_id::text=t.thread_name WHERE b.run_id='{run}' ORDER BY c.saved_at,c.checkpoint_id"}
    def snapshot():return {key:sql('semevosql_acceptance',query) for key,query in queries.items()}
    # Establish actual independent sessions before concurrent delivery.
    clients=[LocalAcceptanceClient(a.account) for _ in range(3)];other=LocalAcceptanceClient(a.other_account)
    before=snapshot();path='/api/semevosql/runs/'+run+'/clarification/'+qid+'/answer'
    def submit(client,payload):
        try:return {'status':200,'body':client.request(path,'POST',payload)}
        except HTTPError as error:return {'status':error.code}
    with ThreadPoolExecutor(max_workers=3) as pool:replays=list(pool.map(lambda client:submit(client,body),clients))
    changed=submit(clients[0],{**body,'customAnswer':'不同答案不能覆盖已经提交的确认'})
    unauthorized=submit(other,body);after=snapshot()
    checks={'three_concurrent_exact_replays_return_same_answer':all(x['status']==200 and x['body']['clarificationId']==qid
            and x['body']['selectedOption']==body['selectedOption'] and x['body']['status']=='ANSWERED' for x in replays),
        'different_content_cannot_reuse_submitted_key':400<=changed['status']<500,
        'other_project_member_cannot_replay_owner_answer':unauthorized['status']==403,
        'all_replays_preserve_answer_revision_sharing_and_dispatch':before==after,
        'only_one_actual_transactional_receipt_remains':len(after['answers'])==1 and len(after['receipts'])==1}
    a.output.with_suffix('.sql').write_text('-- Read-only before/after state comparison; mutations use authenticated answer API only.\n'+';\n\n'.join(queries.values())+';\n')
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'runId':run,'submittedAnswer':body,
        'responses':{'exact':[{'status':x['status']} for x in replays],'changed':changed,'unauthorized':unauthorized},
        'before':before,'after':after,'boundary':'Replays the real browser submission only; never creates successful Run, definition, approval or usage via SQL.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v]},ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)

if __name__=='__main__':main()
