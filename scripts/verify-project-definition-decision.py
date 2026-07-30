#!/usr/bin/env python3
"""Verify an actual browser decision through normal authenticated API replay and denied writes."""
import argparse,json
from concurrent.futures import ThreadPoolExecutor
import threading,time
from urllib.error import HTTPError
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
from acceptance_fixture_scope import business_database
sql=SourceFileLoader('decision_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--candidate',type=int,required=True)
    p.add_argument('--decision-id',type=int,help='Exact committed receipt when this candidate has several decisions')
    p.add_argument('--baseline',type=Path,help='Prior evidence from the same candidate, for immutable personal/public comparison')
    p.add_argument('--require-reassessment',action='store_true',help='For RESUME, require actual completed worker history after the decision')
    p.add_argument('--action',required=True);p.add_argument('--expected-users',type=int,required=True)
    p.add_argument('--expected-uses',type=int,required=True)
    p.add_argument('--namespace',help='Explicit ordinary synthetic fixture namespace')
    p.add_argument('--account',default='semevosql-acceptance-owner')
    p.add_argument('--concurrent-replays',type=int,default=8,help='Concurrent exact receipt replays using separate normal login sessions')
    p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if not 1<=a.concurrent_replays<=32:raise ValueError('Bounded local concurrency required')
    if a.candidate<=0 or a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Fresh proof path required')
    queries={
      'decision':f'SELECT * FROM qw_project_definition_decision WHERE candidate_id={a.candidate} ORDER BY id',
      'candidate':f'SELECT id,project_id,lifecycle,blocked_reason,assessment_state,assessment_attempt_count,published_version_id FROM qw_project_definition_candidate WHERE id={a.candidate}',
      'assessments':f'SELECT id,create_time FROM qw_project_definition_assessment WHERE candidate_id={a.candidate} ORDER BY id',
      'jobs':f'SELECT id,state,decision_id,action,operator,reason,prepared_version_id FROM qw_project_definition_publication WHERE candidate_id={a.candidate} ORDER BY id',
      'uses':f"SELECT u.* FROM qw_user_semantic_preference_usage u JOIN qw_project_definition_source s ON s.preference_id=u.preference_id AND s.definition_revision=u.definition_revision WHERE s.candidate_id={a.candidate} ORDER BY u.run_id",
      'personal':f"SELECT p.id,p.current_revision,p.archived,d.revision,d.definition_text,d.content_hash,d.source_id FROM qw_user_semantic_preference p JOIN qw_user_semantic_definition_revision d ON d.preference_id=p.id WHERE p.id IN (SELECT preference_id FROM qw_project_definition_source WHERE candidate_id={a.candidate}) ORDER BY p.id,d.revision",
      'public':f"SELECT p.id,p.active_version_id,v.catalog_hash FROM qw_project p JOIN qw_project_version v ON v.id=p.active_version_id WHERE p.id=(SELECT project_id FROM qw_project_definition_candidate WHERE id={a.candidate})"
    }
    facts={k:sql('semevosql_acceptance',q) for k,q in queries.items()}
    selected=[d for d in facts['decision'] if a.decision_id is None or d['id']==a.decision_id]
    if len(selected)!=1 or len(facts['candidate'])!=1:raise ValueError('One exact actual browser decision required')
    d=selected[0];project=d['project_id'];candidate=facts['candidate'][0]
    business_database(project,sql,a.namespace)
    registry=json.loads(Path('deploy/.acceptance-private/accounts.json').read_bytes())['accounts']
    administrators={r['username'] for r in registry if r['administrator'] and (not r['projectIds'] or project in r['projectIds'])}
    if a.account not in administrators or d['operator']!=a.account:
        raise ValueError('Actual named fixture administrator decision required')
    body=d['seen_inputs']['request'];path=f'/api/semevosql/projects/{project}/definition-candidates/{a.candidate}/decisions'
    headers={'Idempotency-Key':d['idempotency_key']};owner=LocalAcceptanceClient(a.account)
    repeated=owner.request(path,'POST',body,headers)
    clients=[LocalAcceptanceClient(a.account) for _ in range(a.concurrent_replays)]
    barrier=threading.Barrier(a.concurrent_replays)
    def replay(client):
        barrier.wait(timeout=15)
        return client.request(path,'POST',body,headers)['id']
    started=time.monotonic()
    with ThreadPoolExecutor(max_workers=a.concurrent_replays) as executor:
        concurrent_receipts=list(executor.map(replay,clients))
    concurrent_elapsed_ms=round((time.monotonic()-started)*1000)
    def denied(client,path,body,headers,code):
        try:client.request(path,'POST',body,headers)
        except HTTPError as error:return error.code==code
        return False
    spoofed=dict(headers,**{'X-User-ID':d['operator'],'X-Role':'ADMINISTRATOR'})
    changed=dict(body,reason='Different meaning must not reuse an accepted decision identity')
    totals=next(r['contributions'] for r in owner.request(f'/api/semevosql/projects/{project}/definition-candidates') if r['id']==a.candidate)
    decision_jobs=[j for j in facts['jobs'] if j['decision_id']==d['id']]
    publishes=a.action not in ('DEFER','RESUME','REJECT')
    checks={
      'actual_browser_decision_action_and_reason_retained':d['action']==a.action and bool(d['reason']) and body['action']==a.action and body['reason']==d['reason'],
      'exact_seen_content_evidence_and_base_retained':body['contentRevision']>0 and body['evidenceRevision']>0 and body['baseVersion']>0 and bool(body['catalogHash']) and bool(body['contributionFingerprint']) and bool(body['representationHash']),
      'exact_duplicate_is_idempotent':repeated['id']==d['id'],
      'concurrent_duplicates_return_one_original_decision':concurrent_receipts==[d['id']]*a.concurrent_replays,
      'changed_duplicate_is_rejected':denied(owner,path,changed,headers,409),
      'spoofed_member_cannot_approve':denied(LocalAcceptanceClient('sem_member_a'),path,body,spoofed,403),
      'outsider_cannot_approve':denied(LocalAcceptanceClient('sem_outsider'),path,body,spoofed,403),
      'scoped_administrator_cannot_approve_other_project':denied(owner,f'/api/semevosql/projects/1/definition-candidates/{a.candidate}/decisions',body,headers,403),
      'real_contributions_not_invented_by_approval':totals['validUsers']==a.expected_users and totals['validUses']==a.expected_uses,
      'publication_job_matches_actual_action':(len(decision_jobs)==1 and decision_jobs[0]['reason']==d['reason'] and decision_jobs[0]['action']==a.action) if publishes else not decision_jobs,
      'denied_and_duplicate_requests_add_no_decisions_or_jobs':len(sql('semevosql_acceptance',queries['decision']))==len(facts['decision']) and len(sql('semevosql_acceptance',queries['jobs']))==len(facts['jobs'])}
    if a.action in ('DEFER','REJECT'):
        checks['normal_nonpublication_state_recorded']=candidate['lifecycle']==('REJECTED' if a.action=='REJECT' else 'NEEDS_ADMIN_REVIEW') and candidate['blocked_reason']=='ADMINISTRATOR_'+a.action and candidate['published_version_id'] is None
    if a.action=='RESUME':
        checks['background_resumed_without_publication_or_fake_threshold']=candidate['lifecycle'] in ('ACCUMULATING','NEEDS_ADMIN_REVIEW') and candidate['blocked_reason'] not in ('ADMINISTRATOR_DEFER','ADMINISTRATOR_REJECT') and candidate['published_version_id'] is None
    if a.require_reassessment:
        if a.action!='RESUME':raise ValueError('Worker reassessment proof applies to RESUME')
        checks['actual_worker_finished_after_resume']=candidate['assessment_state']=='DONE' and any(s['create_time']>=d['create_time'] for s in facts['assessments'])
    if a.baseline:
        previous=json.loads(a.baseline.read_bytes())
        if previous['facts']['candidate'][0]['id']!=a.candidate:raise ValueError('Baseline must belong to this candidate')
        checks['personal_revisions_and_text_preserved']=facts['personal']==previous['facts']['personal']
        checks['public_version_and_catalog_unchanged']=facts['public']==previous['facts']['public']
        checks['existing_actual_usage_preserved']=facts['uses']==previous['facts']['uses']
    a.output.with_suffix('.sql').write_text('-- Read-only actual records; API only replays the already committed decision or exercises denied requests.\n'+';\n\n'.join(queries.values())+';\n')
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'facts':facts,'contributions':totals,
      'concurrentReplay':{'requests':a.concurrent_replays,'receiptIds':concurrent_receipts,'elapsedMs':concurrent_elapsed_ms},
      'boundary':'Browser performed the original administrator action. API replays the exact receipt and tests denied writes; no new approvals or contribution records are created.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'output':str(a.output)},ensure_ascii=False));raise SystemExit(0 if all(checks.values()) else 1)
if __name__=='__main__':main()
