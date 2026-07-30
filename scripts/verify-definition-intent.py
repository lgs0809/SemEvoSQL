#!/usr/bin/env python3
"""Read-only proof that one natural-language definition is confirmed, saved, and actually used."""
import argparse,json,uuid
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
sql=SourceFileLoader('definition_intent_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence',type=Path,required=True);p.add_argument('--account',required=True)
    p.add_argument('--phrase',required=True);p.add_argument('--definition',required=True)
    p.add_argument('--scope',choices=('USER','PROJECT'),required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--expected-task-count',type=int,help='Actual data-result count, excluding definition/save management actions')
    a=p.parse_args()
    if a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Use a fresh output; retain prior results')
    e=json.loads(a.evidence.read_bytes());r=e['run'][0];run=str(uuid.UUID(r['run_id']))
    if r['project_id'] not in (1,2):raise ValueError('Isolated acceptance projects only')
    q=lambda s:"'"+s.replace("'","''")+"'"
    queries={
      'definition':f"SELECT p.id,p.project_id,p.user_id,p.current_revision,p.display_phrase,d.revision,d.definition_text,d.content_hash,d.source_kind,d.source_id,(SELECT choice FROM qw_user_semantic_authorization a WHERE a.preference_id=p.id AND a.definition_revision=d.revision ORDER BY authorization_revision DESC LIMIT 1) AS sharing FROM qw_user_semantic_preference p JOIN qw_user_semantic_definition_revision d ON d.preference_id=p.id WHERE p.project_id={int(r['project_id'])} AND p.user_id={q(a.account)} AND p.display_phrase={q(a.phrase)} AND d.source_id IN (SELECT 'clarification:'||clarification_id FROM qw_runtime_clarification WHERE run_id='{run}')",
      'sources':f"SELECT s.*,c.definition_text,c.published_version_id,c.lifecycle FROM qw_project_definition_source s JOIN qw_project_definition_candidate c ON c.id=s.candidate_id JOIN qw_user_semantic_preference p ON p.id=s.preference_id WHERE p.user_id={q(a.account)} AND (s.preference_id,s.definition_revision) IN (SELECT d.preference_id,d.revision FROM qw_user_semantic_definition_revision d WHERE d.source_id IN (SELECT 'clarification:'||clarification_id FROM qw_runtime_clarification WHERE run_id='{run}'))",
      'usage':f"SELECT * FROM qw_user_semantic_preference_usage WHERE run_id='{run}' ORDER BY preference_id,definition_revision"}
    a.output.with_suffix('.sql').write_text('-- Read-only metadata evidence\n'+';\n'.join(queries.values())+';\n')
    facts={name:sql('semevosql_acceptance',query) for name,query in queries.items()};d=facts['definition'];s=facts['sources'];u=facts['usage']
    plans=[json.loads(x['payload']) for x in e['events'] if x['event_type']=='SEMANTIC_PLAN_SNAPSHOT']
    refs=plans[-1]['bindingDependencies'] if plans else []
    questions=e['questions'];answers=e['answers'];turns=[t for t in e['conversationTurns'] if t['run_id']==run]
    question=questions[0] if len(questions)==1 else {};answer=answers[0] if len(answers)==1 else {}
    query_receipts=[x for x in e['sqlExecutionAttempts'] if x['phase']=='QUERY']
    checks={
      'actual_terminal_query_succeeded':r['status']=='SUCCEEDED',
      'authenticated_owner_can_read_run':LocalAcceptanceClient(a.account).request('/api/semevosql/runs/'+run)['status']=='SUCCEEDED',
      'ordinary_message_includes_complete_definition':len(turns)==1 and a.definition in turns[0]['user_question'] and not turns[0]['user_question'].lstrip().startswith('{'),
      'one_native_definition_confirmation':len(questions)==1 and question.get('raw_expression')==a.phrase and question.get('asset_type')=='TEXT_DEFINITION',
      'normal_answer_selected_definition_without_retyping':len(answers)==1 and question.get('status')=='ANSWERED' and answer.get('selected_option')=='CONFIRM_DEFINITION' and not answer.get('custom_answer'),
      'save_scope_and_actor_are_recorded':question.get('selected_scope')==a.scope and question.get('answered_by')==a.account,
      'complete_original_text_saved_as_immutable_revision':len(d)==1 and d[0]['definition_text']==a.definition and d[0]['source_kind']=='TEXT_CONFIRMATION' and d[0]['revision']==d[0]['current_revision'],
      'scope_controls_independent_sharing_authorization':len(d)==1 and d[0]['sharing']==('ALLOWED' if a.scope=='PROJECT' else 'PRIVATE'),
      'exact_personal_source_used_in_plan':len(d)==1 and any(b.get('source')=='USER' and b.get('sourceRecordId')==d[0]['id'] and b.get('sourceRevision')==d[0]['revision'] and b.get('sourceContentHash')==d[0]['content_hash'] and b.get('definitionText')==a.definition for b in refs),
      'actual_query_after_human_confirmation':bool(query_receipts) and len(answers)==1 and all(x['create_time']>=answer['create_time'] for x in query_receipts),
      'normal_plan_approval_and_native_resume':len(e['checkpoints'])>=10 and any(x['event_type']=='APPROVAL_PLAN_SNAPSHOT' for x in e['events']),
      'one_actual_counted_use':len(d)==1 and len([x for x in u if x['preference_id']==d[0]['id'] and x['definition_revision']==d[0]['revision'] and x['valid'] and x['event_type']=='COUNTED'])==1,
      'project_suggestion_created_only_when_authorized':(len(s)==1 and s[0]['definition_text']==a.definition) if a.scope=='PROJECT' else not s}
    if a.expected_task_count is not None:
        checks['only_requested_data_results_allocate_tasks']=r['task_budget_count']==a.expected_task_count and len(e['queryTasks'])==(a.expected_task_count if a.expected_task_count>1 else 0)
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'runId':run,'facts':facts,
      'boundary':'Actual computer-use input and normal HITL submission; real QUERY and immutable source evidence. Independent numerical oracle is verified separately.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'output':str(a.output)},ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)
if __name__=='__main__':main()
