#!/usr/bin/env python3
"""Read-only actual browser cancellation and unchanged personal definition proof."""
import argparse
from datetime import datetime,timezone
from importlib.machinery import SourceFileLoader
import hashlib
import json
from pathlib import Path
import uuid
from acceptance_http import LocalAcceptanceClient
from acceptance_fixture_scope import business_database

sql=SourceFileLoader('definition_cancel_sql',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence',type=Path,required=True);p.add_argument('--before',type=Path,required=True)
    p.add_argument('--after',type=Path,required=True);p.add_argument('--account',required=True)
    p.add_argument('--namespace',required=True);p.add_argument('--preference-id',type=int,required=True)
    p.add_argument('--expected-active-version',type=int,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    if a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Retain earlier proof; choose a fresh output')
    e=json.loads(a.evidence.read_bytes());before=json.loads(a.before.read_bytes());after=json.loads(a.after.read_bytes())
    r=e['run'][0];run=str(uuid.UUID(r['run_id']));project=int(r['project_id']);business_database(project,sql,a.namespace)
    queries={
        'proposals':f"SELECT c.* FROM qw_personal_definition_change c JOIN qw_runtime_clarification q USING(clarification_id) WHERE q.run_id='{run}'",
        'receipts':f"SELECT c.* FROM qw_personal_definition_change_receipt c JOIN qw_runtime_clarification q USING(clarification_id) WHERE q.run_id='{run}'",
        'head':f'SELECT id,project_id,user_id,current_revision FROM qw_user_semantic_preference WHERE id={a.preference_id}',
        'project':f'SELECT id,active_version_id FROM qw_project WHERE id={project}'}
    facts={k:sql('semevosql_acceptance',v) for k,v in queries.items()}
    head=facts['head'][0] if len(facts['head'])==1 else {}
    proposal=facts['proposals'][0] if len(facts['proposals'])==1 else {}
    q=e['questions'][0] if len(e['questions'])==1 else {};answer=e['answers'][0] if len(e['answers'])==1 else {}
    current=[d for d in before['definitions'] if d['preference_id']==a.preference_id and d['revision']==head.get('current_revision')]
    analyses=[json.loads(v['payload']) for v in e['events'] if v['event_type']=='REQUEST_ANALYSIS_COMPLETED']
    checks={
        'actual_owned_run_cancelled':r['status']=='CANCELLED' and LocalAcceptanceClient(a.account).request('/api/semevosql/runs/'+run)['status']=='CANCELLED',
        'definition_belongs_to_same_project_and_account':head.get('project_id')==project and head.get('user_id')==a.account,
        'normal_request_analysis_routes_management':len(analyses)==1 and analyses[0]['requestType']=='SEMANTIC_UPDATE' and not analyses[0]['needsTodo'] and not analyses[0]['tasks'],
        'one_frozen_owned_revision_was_presented':len(facts['proposals'])==1 and len(current)==1 and proposal.get('preference_id')==a.preference_id and proposal.get('definition_revision')==head.get('current_revision') and proposal.get('source_content_hash')==current[0]['content_hash'],
        'one_explicit_owner_cancel_answer':len(e['questions'])==1 and len(e['answers'])==1 and q.get('asset_type')=='SEMANTIC_DEFINITION_UPDATE' and q.get('status')=='ANSWERED' and answer.get('clarification_id')==q.get('clarification_id') and answer.get('selected_option')=='CANCEL' and answer.get('answered_by')==a.account,
        'no_transactional_definition_change_receipt':not facts['receipts'],
        'all_text_revisions_and_representation_state_unchanged':before['definitions']==after['definitions'],
        'all_immutable_structures_unchanged':before['structures']==after['structures'],
        'all_scope_authorization_revisions_unchanged':before['authorizations']==after['authorizations'],
        'all_actual_use_and_withdrawal_records_unchanged':before['uses']==after['uses'],
        'public_active_version_unchanged':len(facts['project'])==1 and facts['project'][0]['active_version_id']==a.expected_active_version,
        'no_data_query_or_fake_result_created':not e['sqlExecutionAttempts'] and not e['resultArtifacts'] and not e['queryCases'] and not e['queryTasks'],
        'native_framework_checkpointed_cancel_flow':len(e['checkpoints'])>=3 and bool(e['binding'])}
    report={'at':datetime.now(timezone.utc).isoformat(),'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'facts':facts,'runId':run,
        'evidenceSha256':hashlib.sha256(a.evidence.read_bytes()).hexdigest(),'beforeSha256':hashlib.sha256(a.before.read_bytes()).hexdigest(),'afterSha256':hashlib.sha256(a.after.read_bytes()).hexdigest(),
        'verifierSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'boundary':'The original CUA owner chose CANCEL; this verifier only reads actual PG/API and retained snapshots.'}
    a.output.with_suffix('.sql').write_text(';\n'.join(queries.values())+';\n');a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'output':str(a.output)}))
    raise SystemExit(0 if all(checks.values()) else 1)

if __name__=='__main__':main()
