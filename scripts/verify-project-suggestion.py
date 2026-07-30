#!/usr/bin/env python3
"""Read-only proof of real shared-suggestion HITL adoption and distinct authorized query contributions."""
import argparse,json,uuid
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient

sql=SourceFileLoader('shared_suggestion_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate',type=int,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--confirmation-evidence',type=Path,help='Earlier actual confirmation Run, when a later query reuses that saved definition')
    parser.add_argument('--account',required=True)
    parser.add_argument('--scope',choices=('USER','PROJECT'),required=True)
    parser.add_argument('--publication-proof',type=Path,help='Separately verified actual automatic publication, when the candidate has since become public')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.candidate<=0 or args.output.exists() or args.output.with_suffix('.sql').exists():raise ValueError('New output and positive candidate required')
    records=json.loads(args.evidence.read_bytes());run=records['run'][0];run_id=str(uuid.UUID(run['run_id']))
    confirmation_records=json.loads(args.confirmation_evidence.read_bytes()) if args.confirmation_evidence else records
    confirmation_run=confirmation_records['run'][0];confirmation_id=str(uuid.UUID(confirmation_run['run_id']))
    if run['project_id'] not in (1,2):raise ValueError('Isolated acceptance projects only')
    account="'"+args.account.replace("'","''")+"'";candidate=args.candidate
    queries={
      'candidate':f"SELECT id,project_id,content_revision,evidence_revision,lifecycle,business_name,definition_text,content_hash,source_preference_id,source_revision,blocked_reason,published_version_id,index_state,index_attempt_count,embedding_model,embedding_version,embedding_dimensions FROM qw_project_definition_candidate WHERE id={candidate}",
      'confirmation':f"SELECT b.*,q.run_id,q.status,q.selected_option,q.selected_scope,q.answered_by,a.create_time AS answered_at FROM qw_clarification_candidate_base b JOIN qw_runtime_clarification q USING(clarification_id) JOIN qw_runtime_clarification_answer a USING(clarification_id) WHERE q.run_id='{confirmation_id}' AND b.candidate_id={candidate} AND b.option_code=a.selected_option AND b.principal_id={account}",
      'ownDefinition':f"SELECT p.id,p.project_id,p.user_id,p.current_revision,p.archived,d.revision,d.definition_text,d.content_hash,d.source_kind,d.source_id,d.definition_snapshot,s.candidate_id,s.candidate_content_revision,s.equivalence_kind,(SELECT a.choice FROM qw_user_semantic_authorization a WHERE a.preference_id=p.id AND a.definition_revision=d.revision ORDER BY a.authorization_revision DESC LIMIT 1) AS sharing FROM qw_user_semantic_preference p JOIN qw_user_semantic_definition_revision d ON d.preference_id=p.id JOIN qw_project_definition_source s ON s.preference_id=p.id AND s.definition_revision=d.revision WHERE p.user_id={account} AND s.candidate_id={candidate} AND d.source_id IN (SELECT 'clarification:'||clarification_id FROM qw_runtime_clarification WHERE run_id='{confirmation_id}')",
      'usage':f"SELECT * FROM qw_user_semantic_preference_usage WHERE run_id='{run_id}' ORDER BY preference_id,definition_revision",
      'contributions':f"SELECT p.user_id,s.preference_id,s.definition_revision,u.run_id,r.status FROM qw_project_definition_source s JOIN qw_user_semantic_preference p ON p.id=s.preference_id AND NOT p.archived JOIN qw_user_semantic_preference_usage u ON u.preference_id=s.preference_id AND u.definition_revision=s.definition_revision AND u.valid AND u.event_type='COUNTED' JOIN qw_query_run r ON r.run_id=u.run_id AND r.status IN ('SUCCEEDED','FAILED','CANCELLED') WHERE s.candidate_id={candidate} AND s.candidate_content_revision=(SELECT content_revision FROM qw_project_definition_candidate WHERE id={candidate}) AND (SELECT a.choice FROM qw_user_semantic_authorization a WHERE a.preference_id=s.preference_id AND a.definition_revision=s.definition_revision ORDER BY a.authorization_revision DESC LIMIT 1)='ALLOWED' AND EXISTS(SELECT 1 FROM qw_sql_execution_attempt x WHERE x.run_id=r.run_id AND x.phase='QUERY') ORDER BY p.user_id,u.run_id",
      'versions':f"SELECT id,version_number,status FROM qw_project_version WHERE project_id={int(run['project_id'])} ORDER BY id"}
    args.output.with_suffix('.sql').write_text('-- Metadata database: semevosql_acceptance; all statements are read-only.\n'+';\n\n'.join(queries.values())+';\n')
    facts={name:sql('semevosql_acceptance',query) for name,query in queries.items()}
    c=facts['candidate'][0] if len(facts['candidate'])==1 else {}
    confirmations=facts['confirmation'];own=facts['ownDefinition'];usage=facts['usage'];contributions=facts['contributions']
    plans=[json.loads(e['payload']) for e in records['events'] if e['event_type']=='SEMANTIC_PLAN_SNAPSHOT']
    refs=[b for b in plans[-1].get('bindingDependencies',[]) if b.get('source')=='USER'] if plans else []
    checks={
      'actual_run_succeeded':run['status']=='SUCCEEDED',
      'owner_can_read_private_run':LocalAcceptanceClient(args.account).request('/api/semevosql/runs/'+run_id)['runId']==run_id,
      'candidate_matches_frozen_project':c.get('project_id')==run['project_id'],
      'confirmation_is_same_project':confirmation_run['project_id']==run['project_id'],
      'unpublished_suggestion_has_no_public_version':c.get('published_version_id') is None and c.get('lifecycle')!='PUBLISHED',
      'program_created_exact_candidate_confirmation':len(confirmations)==1 and confirmations[0]['content_revision']==c.get('content_revision') and confirmations[0]['content_hash']==c.get('content_hash'),
      'normal_reader_answer_records_selected_scope':len(confirmations)==1 and confirmations[0]['status']=='ANSWERED' and confirmations[0]['answered_by']==args.account and confirmations[0]['selected_scope']==args.scope,
      'reader_has_own_immutable_complete_definition':len(own)==1 and own[0]['definition_text']==c.get('definition_text') and own[0]['source_kind']=='PROJECT_ADOPTION' and own[0]['id']!=c.get('source_preference_id'),
      'reader_sharing_choice_is_independent':len(own)==1 and own[0]['sharing']==('PRIVATE' if args.scope=='USER' else 'ALLOWED'),
      'explicit_equivalence_source_link':len(own)==1 and own[0]['candidate_content_revision']==c.get('content_revision') and own[0]['equivalence_kind']=='USER_CONFIRMED',
      'plan_uses_own_definition_instead_of_candidate_pointer':len(own)==1 and any(b.get('sourceRecordId')==own[0]['id'] and b.get('sourceRevision')==own[0]['revision'] and b.get('principalId')==args.account and b.get('sourceContentHash')==own[0]['content_hash'] for b in refs) and not any(b.get('source')=='PROJECT_CANDIDATE' for b in plans[-1].get('bindingDependencies',[])),
      'actual_query_occurs_after_confirmation':len(confirmations)==1 and any(x['phase']=='QUERY' for x in records['sqlExecutionAttempts']) and all(x['create_time']>=confirmations[0]['answered_at'] for x in records['sqlExecutionAttempts'] if x['phase']=='QUERY'),
      'one_counted_use_for_reader_definition':len(own)==1 and len([u for u in usage if u['preference_id']==own[0]['id'] and u['definition_revision']==own[0]['revision'] and u['event_type']=='COUNTED' and u['valid']])==1,
      'scope_controls_project_contribution':any(r['run_id']==run_id and r['user_id']==args.account for r in contributions)==(args.scope=='PROJECT'),
      'normal_plan_approval_and_native_resume_recorded':len(records['checkpoints'])>=10 and any(e['event_type']=='APPROVAL_PLAN_SNAPSHOT' for e in records['events'])}
    if args.publication_proof:
        publication=json.loads(args.publication_proof.read_bytes())
        checks.pop('unpublished_suggestion_has_no_public_version')
        checks['subsequent_publication_has_independent_actual_proof']=(publication.get('status')=='PASS'
            and publication.get('baseline',{}).get('candidate')==candidate
            and publication.get('version',{}).get('id')==c.get('published_version_id')
            and c.get('lifecycle')=='PUBLISHED'
            and all(publication.get('checks',{}).values()))
    if confirmation_id!=run_id:
        checks['reuse_does_not_repeat_suggestion_confirmation']=not records['questions']
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'runId':run_id,'confirmationRunId':confirmation_id,
        'confirmationRunStatus':confirmation_run['status'],'candidateId':candidate,'facts':facts,
        'validUsers':len({r['user_id'] for r in contributions}),'validUses':len({r['run_id'] for r in contributions}),
        'boundary':'Real browser confirmation, actual QUERY receipts and read-only facts. No usage, approval or publication is manufactured. Public promotion and result oracle are separately checked.'}
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'output':str(args.output)},ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)

if __name__=='__main__':main()
