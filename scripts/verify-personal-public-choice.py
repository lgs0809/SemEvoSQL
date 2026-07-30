#!/usr/bin/env python3
"""Read-only proof of an actual browser personal/public choice, with independent query evidence."""
import argparse,json,uuid
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
sql=SourceFileLoader('public_choice_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence',type=Path,required=True);p.add_argument('--account',required=True)
    p.add_argument('--choice',choices=('KEEP_PERSONAL','ADOPT_PUBLIC'),required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();e=json.loads(a.evidence.read_bytes());r=e['run'][0];run=str(uuid.UUID(r['run_id']))
    if r['project_id']!=2 or a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Fresh isolated project-2 proof required')
    queries={'base':f"SELECT q.* FROM qw_personal_public_question q JOIN qw_runtime_clarification c USING(clarification_id) WHERE c.run_id='{run}'",
        'choice':f"SELECT q.* FROM qw_personal_public_choice q JOIN qw_runtime_clarification c USING(clarification_id) WHERE c.run_id='{run}'"}
    base=sql('semevosql_acceptance',queries['base'])
    if len(base)!=1:raise ValueError('One actual immutable personal/public question required')
    b=base[0];id=int(b['preference_id']);revision=int(b['personal_revision'])
    queries.update({'head':f'SELECT * FROM qw_user_semantic_preference WHERE id={id}',
        'definitions':f'SELECT * FROM qw_user_semantic_definition_revision WHERE preference_id={id} ORDER BY revision',
        'authorizations':f'SELECT * FROM qw_user_semantic_authorization WHERE preference_id={id} ORDER BY definition_revision,authorization_revision',
        'sources':f'SELECT * FROM qw_project_definition_source WHERE preference_id={id} ORDER BY definition_revision',
        'uses':f'SELECT * FROM qw_user_semantic_preference_usage WHERE preference_id={id} ORDER BY definition_revision,run_id',
        'document':f'SELECT preference_id,source_revision,source_content_hash,task_state FROM qw_personal_definition_document WHERE preference_id={id}'})
    facts={k:sql('semevosql_acceptance',q) for k,q in queries.items()};old=next(d for d in facts['definitions'] if d['revision']==revision)
    current=next(d for d in facts['definitions'] if d['revision']==facts['head'][0]['current_revision']);choices=facts['choice']
    questions=e['questions'];answers=e['answers'];q=questions[0] if len(questions)==1 else {};answer=answers[0] if len(answers)==1 else {}
    plan_events=[json.loads(x['payload']) for x in e['events'] if x['event_type']=='SEMANTIC_PLAN_SNAPSHOT'];refs=plan_events[-1]['bindingDependencies'] if plan_events else []
    query_receipts=[x for x in e['sqlExecutionAttempts'] if x['phase']=='QUERY']
    expected=revision if a.choice=='KEEP_PERSONAL' else revision+1
    choice=choices[0] if len(choices)==1 else {}
    checks={
        'actual_query_finished_successfully':r['status']=='SUCCEEDED' and LocalAcceptanceClient(a.account).request('/api/semevosql/runs/'+run)['status']=='SUCCEEDED',
        'one_native_update_question_before_any_plan':len(questions)==1 and q.get('asset_type')=='PERSONAL_PUBLIC_UPDATE' and b['clarification_id']==q.get('clarification_id') and bool(plan_events),
        'both_complete_meanings_were_shown':old['definition_text'] in q.get('question','') and b['public_definition_text'] in q.get('question',''),
        'normal_owner_answer_without_extra_scope_or_retyped_definition':len(answers)==1 and answer.get('selected_option')==a.choice and answer.get('answered_by')==a.account and answer.get('selected_scope')=='QUERY' and not answer.get('custom_answer'),
        'immutable_choice_bound_to_exact_personal_and_public_inputs':choice.get('choice')==a.choice and choice.get('principal_id')==a.account and choice.get('personal_revision')==expected and choice.get('personal_content_hash')==current['content_hash'] and all(choice.get(k)==b[k] for k in ('project_id','preference_id','asset_type','asset_key','public_version_id','public_meaning_fingerprint')),
        'old_revision_is_retained_exactly':old['content_hash']==b['personal_content_hash'] and old['revision']==revision,
        'current_definition_matches_selected_revision':current['revision']==expected,
        'keep_does_not_modify_or_reauthorize_personal_definition':current==old if a.choice=='KEEP_PERSONAL' else current['definition_snapshot']==b['public_snapshot'] and current['definition_text']==b['public_definition_text'] and current['source_id']=='clarification:'+b['clarification_id'],
        'adopting_public_does_not_create_new_promotion':a.choice=='KEEP_PERSONAL' or not any(s['definition_revision']==expected for s in facts['sources']) and [x for x in facts['authorizations'] if x['definition_revision']==expected][-1]['choice']=='PRIVATE',
        'old_uses_are_not_retracted_by_future_default_choice':all(x['valid'] for x in facts['uses'] if x['definition_revision']==revision),
        'exact_chosen_definition_is_actually_used':any(x.get('source')=='USER' and x.get('sourceRecordId')==id and x.get('sourceRevision')==expected and x.get('sourceContentHash')==current['content_hash'] for x in refs),
        'actual_query_runs_after_answer_and_normal_approval':bool(query_receipts) and all(x['create_time']>=answer.get('create_time','9999') for x in query_receipts) and any(x['event_type']=='APPROVAL_PLAN_SNAPSHOT' for x in e['events']),
        'one_actual_logical_use_not_exposure':len([x for x in facts['uses'] if x['run_id']==run and x['definition_revision']==expected and x['valid'] and x['event_type']=='COUNTED'])==1,
        'current_lexical_projection_does_not_return_old_personal_head':len(facts['document'])==1 and facts['document'][0]['source_revision']==expected and facts['document'][0]['source_content_hash']==current['content_hash'],
        'one_actual_data_task':r['task_budget_count']==1 and not e['queryTasks']}
    # Exact ordering uses the persisted event sequence rather than a screenshot timestamp.
    requested=next((x['sequence'] for x in e['events'] if x['event_type']=='CLARIFICATION_REQUIRED'),None)
    planned=next((x['sequence'] for x in e['events'] if x['event_type']=='SEMANTIC_PLAN_SNAPSHOT'),None)
    checks['update_question_precedes_blueprint']=requested is not None and planned is not None and requested<planned
    a.output.with_suffix('.sql').write_text('-- Read-only actual runtime and immutable choice proof\n'+';\n\n'.join(queries.values())+';\n')
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'facts':facts,'runId':run,
        'boundary':'The browser made the original choice and normal execution approval. This script reads only; numeric SQL is checked by the independent query oracle.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'output':str(a.output)},ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)

if __name__=='__main__':main()
