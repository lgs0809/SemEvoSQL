#!/usr/bin/env python3
"""Read-only proof of the actual background model assessment and authorized logical-query contributions."""
import argparse,hashlib,json,subprocess
from urllib.error import HTTPError
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
from acceptance_fixture_scope import business_database

sql=SourceFileLoader('project_assessment_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def contribution_receipts(candidate, personal, uses, trusted):
    # Revision-wide sharing is only the default; an explicit per-query decision wins.
    authorized={(x['id'],x['definition_revision']) for x in personal
        if not x['archived'] and x['candidate_content_revision']==candidate['content_revision']
        and x['authorization_principal']==x['user_id'] and x['user_id'] in trusted
        and (x['sharing']=='ALLOWED' or x['retained_allowed_use'])}
    return [u for u in uses if (u['preference_id'],u['definition_revision']) in authorized
        and u['run_owner']==u['user_id'] and u['project_id']==candidate['project_id']
        and u['sharing_allowed'] and u['valid'] and u['event_type']=='COUNTED'
        and u['actual_query'] and u['run_type'] in ('INTERACTIVE_QUERY','EXTERNAL_MCP_QUERY')
        and u['status'] in ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')]

def archived_alignment_receipts(candidate, path, expected_sha):
    raw=path.read_bytes()
    if not expected_sha or hashlib.sha256(raw).hexdigest()!=expected_sha:
        raise ValueError('Archived alignment proof SHA mismatch')
    prior=json.loads(raw)
    old=prior['facts']['candidate'][0]
    identity=('id','project_id','content_revision','content_hash','definition_text',
        'base_version_id','representation_hash','dependency_fingerprint')
    now=candidate['assessment_json'];then=old['assessment_json']
    if prior.get('status')!='PASS' or not prior.get('checks',{}).get('actual_terra_alignment_gateway_receipt'):
        raise ValueError('Archived model receipt was not verified')
    if any(candidate.get(k)!=old.get(k) for k in identity) or any(now.get(k)!=then.get(k)
            for k in ('alignment','baseVersionId','catalogHash')):
        raise ValueError('Archived alignment identity no longer applies')
    call=now['alignment']['callId']
    rows=[line for line in prior.get('gatewayReceipts',[]) if f'callId={call} ' in line
        and 'Model HTTP request' in line and 'purpose=PROJECT_DEFINITION_ALIGNMENT' in line]
    if not rows:raise ValueError('Archived alignment HTTP receipt missing')
    return rows

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidate',type=int,required=True)
    p.add_argument('--expected-users',type=int,required=True)
    p.add_argument('--expected-uses',type=int,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--namespace',help='Explicit ordinary synthetic quality fixture namespace')
    p.add_argument('--account',default='semevosql-acceptance-owner')
    p.add_argument('--alignment-proof',type=Path,help='Earlier verified receipt reused by the current immutable alignment')
    p.add_argument('--alignment-proof-sha256',help='Required exact archived receipt SHA')
    a=p.parse_args()
    if a.candidate<=0 or a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Positive candidate and fresh evidence output required')
    queries={
      'candidate':f'SELECT * FROM qw_project_definition_candidate WHERE id={a.candidate}',
      'history':f'SELECT * FROM qw_project_definition_assessment WHERE candidate_id={a.candidate} ORDER BY id',
      'personal':f'''SELECT p.id,p.user_id,p.current_revision,p.archived,s.definition_revision,s.candidate_content_revision,s.equivalence_kind,
        d.definition_text,d.content_hash,r.representation_state,r.structured_json,
        (SELECT choice FROM qw_user_semantic_authorization a WHERE a.preference_id=p.id AND a.definition_revision=d.revision
          ORDER BY authorization_revision DESC LIMIT 1) AS sharing,
        (SELECT principal_id FROM qw_user_semantic_authorization a WHERE a.preference_id=p.id AND a.definition_revision=d.revision
          ORDER BY authorization_revision DESC LIMIT 1) AS authorization_principal,
        EXISTS(SELECT 1 FROM qw_personal_sharing_use_decision x
          JOIN qw_user_semantic_preference_usage u ON u.preference_id=x.preference_id AND u.definition_revision=x.definition_revision
            AND u.run_id=x.run_id AND u.valid AND u.event_type='COUNTED'
          WHERE x.preference_id=p.id AND x.definition_revision=d.revision AND x.allowed
            AND NOT EXISTS(SELECT 1 FROM qw_personal_sharing_use_decision n WHERE n.preference_id=x.preference_id
              AND n.definition_revision=x.definition_revision AND n.run_id=x.run_id AND n.id>x.id)) AS retained_allowed_use
        FROM qw_project_definition_source s JOIN qw_user_semantic_preference p ON p.id=s.preference_id
        JOIN qw_user_semantic_definition_revision d ON d.preference_id=p.id AND d.revision=s.definition_revision
        JOIN qw_user_semantic_representation r ON r.preference_id=p.id AND r.source_revision=d.revision
        WHERE s.candidate_id={a.candidate} ORDER BY p.id''',
      'uses':f'''SELECT p.user_id,u.preference_id,u.definition_revision,u.run_id,u.event_type,u.valid,r.status,
        r.project_id,r.run_type,COALESCE(NULLIF(TRIM(r.request_payload::jsonb->>'principalId'),''),
          NULLIF(TRIM(r.request_payload::jsonb->>'userId'),''),NULLIF(TRIM(r.request_payload::jsonb->>'createdBy'),''),
          NULLIF(TRIM(v.created_by),'')) AS run_owner,
        COALESCE((SELECT x.allowed FROM qw_personal_sharing_use_decision x WHERE x.preference_id=u.preference_id
          AND x.definition_revision=u.definition_revision AND x.run_id=u.run_id ORDER BY x.id DESC LIMIT 1),
          (SELECT a.choice='ALLOWED' FROM qw_user_semantic_authorization a WHERE a.preference_id=u.preference_id
          AND a.definition_revision=u.definition_revision ORDER BY authorization_revision DESC LIMIT 1),FALSE) AS sharing_allowed,
        EXISTS(SELECT 1 FROM qw_sql_execution_attempt x WHERE x.run_id=r.run_id AND x.phase='QUERY') AS actual_query
        FROM qw_project_definition_source s JOIN qw_user_semantic_preference p ON p.id=s.preference_id
        JOIN qw_user_semantic_preference_usage u ON u.preference_id=p.id AND u.definition_revision=s.definition_revision
        JOIN qw_query_run r ON r.run_id=u.run_id
        LEFT JOIN qw_project_conversation v ON v.conversation_id=r.thread_id AND v.project_id=r.project_id AND v.status<>'DELETED'
        WHERE s.candidate_id={a.candidate} ORDER BY p.id,u.run_id'''
    }
    a.output.with_suffix('.sql').write_text('-- Read-only metadata database: semevosql_acceptance\n'+';\n\n'.join(queries.values())+';\n')
    facts={name:sql('semevosql_acceptance',query) for name,query in queries.items()}
    if len(facts['candidate'])!=1:raise ValueError('Candidate missing')
    c=facts['candidate'][0]
    business_database(c['project_id'],sql,a.namespace)
    client=LocalAcceptanceClient(a.account)
    view=next(x for x in client.request(f"/api/semevosql/projects/{c['project_id']}/definition-candidates") if x['id']==a.candidate)
    trusted={x['username'] for x in json.loads(Path('deploy/.acceptance-private/accounts.json').read_bytes())['accounts']
      if c['project_id'] in x['projectIds'] or (x['administrator'] and not x['projectIds'])}
    counted=contribution_receipts(c,facts['personal'],facts['uses'],trusted)
    users={u['user_id'] for u in counted};uses={u['run_id'] for u in counted}
    alignment=(c['assessment_json'] or {}).get('alignment',{});call=alignment.get('callId','')
    logs=subprocess.run(['docker','logs','--since','3h','semevosql-acceptance-backend-1'],capture_output=True,text=True,check=True)
    receipt=[line for line in (logs.stdout+logs.stderr).splitlines() if call and f'callId={call} ' in line and 'Model HTTP request' in line and 'purpose=PROJECT_DEFINITION_ALIGNMENT' in line]
    receipt_kind='CURRENT_CONTAINER_HTTP_LOG'
    archived=None
    if not receipt and a.alignment_proof:
        receipt=archived_alignment_receipts(c,a.alignment_proof,a.alignment_proof_sha256)
        receipt_kind='MODEL_ALIGNMENT_RECEIPT_REUSED'
        archived={'path':str(a.alignment_proof),'sha256':a.alignment_proof_sha256}
    denied=[]
    for username in ('sem_member_a','sem_outsider'):
        try:LocalAcceptanceClient(username).request(f"/api/semevosql/projects/{c['project_id']}/definition-candidates")
        except HTTPError as error:denied.append(error.code==403)
        else:denied.append(False)
    checks={
      'actual_assessment_completed':c['assessment_state']=='DONE',
      'assessment_is_current':view['assessment_current'],
      'actual_terra_alignment_gateway_receipt':alignment.get('model')=='gpt-5.6-terra' and bool(receipt),
      'assessment_history_is_retained':bool(facts['history']),
      'counts_match_query_and_authorization_receipts':view['contributions']['validUsers']==len(users) and view['contributions']['validUses']==len(uses),
      'expected_distinct_trusted_users':len(users)==a.expected_users,
      'expected_distinct_logical_queries':len(uses)==a.expected_uses,
      'threshold_is_separate_from_state':view['threshold_reached']==(len(users)>=3 and len(uses)>=5),
      'all_personal_meanings_and_revisions_remain':bool(facts['personal']) and all(x['definition_text'] and x['current_revision']>=x['definition_revision'] for x in facts['personal']),
      'private_query_without_explicit_historic_authorization_does_not_count':all(u['sharing_allowed'] for u in counted),
      'confirmations_without_query_do_not_count':all(u['actual_query'] for u in counted),
      'member_and_outsider_cannot_read_admin_governance':len(denied)==2 and all(denied)}
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'candidateId':a.candidate,
      'validUsers':len(users),'validUses':len(uses),'gatewayReceipts':receipt,'receiptKind':receipt_kind,
      'archivedAlignmentProof':archived,'newModelExecutionsByVerifier':0,
      'verifierSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'api':view,'facts':facts,
      'boundary':'Read-only actual model, database and normal authenticated API evidence. No confirmation, use, approval or publication is manufactured.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'users':len(users),'uses':len(uses),'output':str(a.output)},ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)

if __name__=='__main__':main()
