#!/usr/bin/env python3
"""Read-only actual natural-language definition-management/HITL and per-query sharing evidence."""
import argparse,json,uuid
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
from acceptance_fixture_scope import business_database

sql=SourceFileLoader('definition_change_sql',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def supplement_matches_frozen_proposal(proposal,answer):
    """Scope-only supplements need a fresh source-bound proposal, not invented text."""
    def unique_object(pairs):
        result={}
        for key,value in pairs:
            if key in result:raise ValueError('Duplicate model response key')
            result[key]=value
        return result
    try:
        response=json.loads(proposal['modelEvidence']['response'],object_pairs_hook=unique_object)
        if not isinstance(response,dict) or set(response)!={'targetDefinitionId','sourceRevision','sourceContentHash','definitionText','intentExcerpt','affectedRunIds'}:
            return False
        excerpt=response['intentExcerpt'];text=proposal.get('newText')
        return type(response['targetDefinitionId']) is int and response['targetDefinitionId']==proposal['preferenceId'] and \
            type(response['sourceRevision']) is int and response['sourceRevision']==proposal['revision'] and \
            response['sourceContentHash']==proposal['contentHash'] and \
            isinstance(excerpt,str) and bool(excerpt.strip()) and excerpt in answer and \
            response['definitionText']==text and (text is None or isinstance(text,str) and bool(text.strip()) and text in answer) and \
            response['affectedRunIds']==proposal['affectedRuns']
    except (KeyError,TypeError,ValueError):return False

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence',type=Path,required=True);p.add_argument('--baseline',type=Path,required=True)
    p.add_argument('--account',required=True);p.add_argument('--namespace',help='Explicit ordinary synthetic fixture namespace');p.add_argument('--scope',choices=('USER','PROJECT'),required=True)
    p.add_argument('--history-choice',choices=('CONFIRM_FUTURE','CONFIRM_VALID_HISTORY','CONFIRM_INVALIDATE_TARGETS','CONFIRM_WITHDRAW_TARGETS'),required=True)
    p.add_argument('--expected-revision',type=int,required=True);p.add_argument('--expected-shared-uses',type=int,required=True)
    p.add_argument('--require-human-readable',action='store_true')
    p.add_argument('--require-history-labels',action='store_true')
    preceding=p.add_mutually_exclusive_group()
    preceding.add_argument('--expected-other-answer',help='Exact preceding owner-authored supplement; requires one OTHER followed by one confirmation')
    preceding.add_argument('--expected-superseded-question',help='Exact rejected old question ID; requires replacement and only one committed answer')
    p.add_argument('--expected-historical-shared-uses',type=int,help='Actual retained shared query uses on older computation revisions')
    p.add_argument('--expected-active-version',type=int)
    p.add_argument('--preserve-approved-run',action='append',default=[])
    p.add_argument('--expected-affected-run',action='append',default=[],help='Exact actual historical targets; requires no extra target')
    p.add_argument('--expect-quarantined-run',action='append',default=[],help='Corrected historical case must be isolated by this confirmation')
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();e=json.loads(a.evidence.read_bytes());baseline=json.loads(a.baseline.read_bytes());r=e['run'][0]
    run=str(uuid.UUID(r['run_id']))
    project=int(r['project_id']);business_database(project,sql,a.namespace)
    if a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Fresh isolated evidence required')
    queries={'proposals':f"SELECT c.* FROM qw_personal_definition_change c JOIN qw_runtime_clarification q USING(clarification_id) WHERE q.run_id='{run}'",
        'receipts':f"SELECT c.* FROM qw_personal_definition_change_receipt c JOIN qw_runtime_clarification q USING(clarification_id) WHERE q.run_id='{run}'",
        'assistantMessages':f"SELECT content,status,metadata_json FROM qw_project_message WHERE run_id='{run}' AND role='ASSISTANT'",
        'project':f"SELECT id,active_version_id FROM qw_project WHERE id={project}"}
    preserved=[str(uuid.UUID(value)) for value in a.preserve_approved_run]
    affected_expected={str(uuid.UUID(value)) for value in a.expected_affected_run}
    quarantined={str(uuid.UUID(value)) for value in a.expect_quarantined_run}
    if quarantined.intersection(preserved) or quarantined and (a.history_choice!='CONFIRM_INVALIDATE_TARGETS' or not quarantined.issubset(affected_expected)):
        raise ValueError('Quarantined targets require exact invalidation scope and cannot also be preserved')
    if preserved:
        queries['preservedCases']=f"SELECT run_id,status FROM qw_query_example WHERE project_id={project} AND run_id IN ("+','.join("'"+value+"'" for value in preserved)+")"
    if quarantined:
        queries['quarantinedCases']=f"SELECT run_id,status,quarantine_reason FROM qw_query_example WHERE project_id={project} AND run_id IN ("+','.join("'"+value+"'" for value in sorted(quarantined))+")"
    proposals=sql('semevosql_acceptance',queries['proposals'])
    receipts=sql('semevosql_acceptance',queries['receipts'])
    if len(receipts)!=1:raise ValueError('Exactly one actual committed definition-change receipt required')
    committed=[x for x in proposals if x['clarification_id']==receipts[0]['clarification_id']]
    if len(committed)!=1:raise ValueError('Committed receipt must identify exactly one frozen proposal')
    frozen=committed[0];id=int(frozen['preference_id']);revision=int(frozen['definition_revision']);proposal=frozen['proposal_json']
    queries.update({'head':f'SELECT * FROM qw_user_semantic_preference WHERE id={id}',
        'definitions':f'SELECT * FROM qw_user_semantic_definition_revision WHERE preference_id={id} ORDER BY revision',
        'authorizations':f'SELECT * FROM qw_user_semantic_authorization WHERE preference_id={id} ORDER BY definition_revision,authorization_revision',
        'uses':f'SELECT * FROM qw_user_semantic_preference_usage WHERE preference_id={id} ORDER BY definition_revision,run_id',
        'sharingDecisions':f'SELECT * FROM qw_personal_sharing_use_decision WHERE preference_id={id} ORDER BY id',
        'sources':f'SELECT * FROM qw_project_definition_source WHERE preference_id={id} ORDER BY definition_revision',
        'sharedUses':f"""SELECT u.run_id,u.definition_revision,r.status,COALESCE(r.request_payload::jsonb->>'principalId',v.created_by) AS principal,
            COALESCE((SELECT x.allowed FROM qw_personal_sharing_use_decision x WHERE x.preference_id=u.preference_id
                AND x.definition_revision=u.definition_revision AND x.run_id=u.run_id ORDER BY x.id DESC LIMIT 1),
                (SELECT a.choice='ALLOWED' FROM qw_user_semantic_authorization a WHERE a.preference_id=u.preference_id
                  AND a.definition_revision=u.definition_revision ORDER BY a.authorization_revision DESC LIMIT 1),FALSE) AS allowed
            FROM qw_user_semantic_preference_usage u JOIN qw_query_run r ON r.run_id=u.run_id
            LEFT JOIN qw_project_conversation v ON v.conversation_id=r.thread_id AND v.project_id=r.project_id
            WHERE u.preference_id={id} AND u.definition_revision={a.expected_revision} AND u.valid AND u.event_type='COUNTED'
              AND r.status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED') AND r.project_id={project}
              AND r.run_type IN ('INTERACTIVE_QUERY','EXTERNAL_MCP_QUERY')
              AND EXISTS(SELECT 1 FROM qw_sql_execution_attempt x WHERE x.run_id=r.run_id AND x.phase='QUERY')
            ORDER BY u.run_id"""})
    if a.expected_historical_shared_uses is not None:
        queries['historicalSharedUses']=queries['sharedUses'].replace(
            f'u.definition_revision={a.expected_revision} AND u.valid',
            f'u.definition_revision<{a.expected_revision} AND u.valid')
    facts={key:sql('semevosql_acceptance',query) for key,query in queries.items()}
    head=facts['head'][0];current=next(d for d in facts['definitions'] if d['revision']==a.expected_revision)
    old=next(d for d in facts['definitions'] if d['revision']==revision)
    before=next(d for d in baseline['definitions'] if d['revision']==revision and d['preference_id']==id)
    immutable=('definition_text','asset_type','asset_key','business_label','source_kind','source_id','base_version_id','definition_snapshot','content_hash','dependency_fingerprint','create_time')
    receipt=facts['receipts'][0] if len(facts['receipts'])==1 else {}
    committed_questions=[x for x in e['questions'] if x['clarification_id']==frozen['clarification_id']]
    committed_answers=[x for x in e['answers'] if x['clarification_id']==frozen['clarification_id']]
    q=committed_questions[0] if len(committed_questions)==1 else {};answer=committed_answers[0] if len(committed_answers)==1 else {}
    previous_questions=[x for x in e['questions'] if x['clarification_id']!=frozen['clarification_id']]
    previous_answers=[x for x in e['answers'] if x['clarification_id']!=frozen['clarification_id']]
    expected_count=2 if a.expected_other_answer or a.expected_superseded_question else 1
    expected_answers=2 if a.expected_other_answer else 1
    analyses=[json.loads(x['payload']) for x in e['events'] if x['event_type']=='REQUEST_ANALYSIS_COMPLETED']
    shared={u['run_id'] for u in facts['sharedUses'] if u['allowed'] and u['principal']==a.account}
    authorizations=[x for x in facts['authorizations'] if x['definition_revision']==a.expected_revision]
    scope_only=proposal.get('newText') is None
    affected=set(proposal.get('affectedRuns',[]));uses={(x['definition_revision'],x['run_id']):x for x in facts['uses']}
    checks={
        'actual_run_and_authenticated_api_succeeded':r['status']=='SUCCEEDED' and LocalAcceptanceClient(a.account).request('/api/semevosql/runs/'+run)['status']=='SUCCEEDED',
        'normal_request_analysis_routes_semantic_management':len(analyses)==1 and analyses[0]['requestType']=='SEMANTIC_UPDATE' and not analyses[0]['needsTodo'] and not analyses[0]['tasks'],
        'native_graph_was_actually_checkpointed':len(e['checkpoints'])>=3 and bool(e['binding']),
        'exact_question_count_and_owner_confirmation':len(proposals)==expected_count and len(e['questions'])==expected_count and len(e['answers'])==expected_answers and len(committed_questions)==1 and len(committed_answers)==1 and q.get('asset_type')=='SEMANTIC_DEFINITION_UPDATE' and answer.get('answered_by')==a.account,
        'displayed_complete_old_meaning_and_history_impact':proposal['oldText'] in q.get('question','') and '原范围' in q.get('question','') and '历史' in q.get('reason',''),
        'submitted_exact_scope_history_choice_without_retyped_definition':answer.get('selected_scope')==a.scope and answer.get('selected_option')==a.history_choice and not answer.get('custom_answer'),
        'immutable_proposal_binds_old_revision_hash_and_owner':frozen['clarification_id']==q.get('clarification_id') and frozen['source_content_hash']==before['content_hash'] and head['user_id']==a.account and head['project_id']==project,
        'real_model_proposal_evidence_preserved':bool(proposal.get('modelEvidence',{}).get('callId')) and proposal['modelEvidence']['purpose']=='SEMANTIC_PLANNING' and bool(proposal['modelEvidence'].get('response')),
        'one_transactional_submission_receipt':len(facts['receipts'])==1 and receipt.get('clarification_id')==q.get('clarification_id') and receipt.get('result_preference_id')==id and receipt.get('result_revision')==a.expected_revision and receipt.get('selected_scope')==a.scope and receipt.get('history_choice')==a.history_choice,
        'current_computation_revision_matches_confirmation':head['current_revision']==a.expected_revision,
        'old_immutable_definition_and_source_are_preserved':all(old[k]==before[k] for k in immutable),
        'future_scope_has_revisioned_authorization':bool(authorizations) and authorizations[-1]['choice']==('ALLOWED' if a.scope=='PROJECT' else 'PRIVATE'),
        'scope_only_never_creates_new_computation_or_resets_use_history':not scope_only or a.expected_revision==revision and current['content_hash']==before['content_hash'] and len(facts['definitions'])==len(baseline['definitions']),
        'changed_meaning_does_not_migrate_old_query_uses':scope_only or a.expected_revision>revision and not any(x['definition_revision']==a.expected_revision for x in facts['uses']),
        'historical_usage_changes_are_limited_to_confirmed_targets':all((x['definition_revision'],x['run_id']) in uses and uses[x['definition_revision'],x['run_id']]['valid']==(False if a.history_choice=='CONFIRM_INVALIDATE_TARGETS' and x['run_id'] in affected else x['valid']) for x in baseline['uses']),
        'actual_shared_query_count_matches_expected':len(shared)==a.expected_shared_uses,
        'no_new_data_use_is_manufactured_for_management':not any(x['run_id']==run for x in facts['uses']) and not e['queryTasks'],
        'no_sql_execution_or_execution_approval_for_scope_management':not e['sqlExecutionAttempts'] and not e['sqlTraces'] and not any(x['event_type'] in ('SEMANTIC_PLAN_SNAPSHOT','APPROVAL_PLAN_SNAPSHOT','RESULT_ARTIFACT_ACCEPTED') for x in e['events']),
        'management_does_not_create_query_case_or_fake_data_result':not e['queryCases'] and not e['resultArtifacts'],
    }
    if a.expected_other_answer:
        previous=previous_answers[0] if len(previous_answers)==1 else {}
        previous_question=previous_questions[0] if len(previous_questions)==1 else {}
        prior_proposals=[x for x in proposals if x['clarification_id']==previous.get('clarification_id')]
        prior=prior_proposals[0]['proposal_json'] if len(prior_proposals)==1 else {}
        checks['other_is_exact_owner_supplement_without_committed_definition']=previous.get('answered_by')==a.account and \
            previous.get('selected_option')=='OTHER' and previous.get('custom_answer')==a.expected_other_answer and \
            previous_question.get('asset_type')=='SEMANTIC_DEFINITION_UPDATE' and previous_question.get('status')=='ANSWERED' and \
            previous_question.get('clarification_id')==previous.get('clarification_id') and \
            not any(x['clarification_id']==previous.get('clarification_id') for x in facts['receipts'])
        checks['supplement_produces_new_model_proposal_and_requires_separate_confirmation']=supplement_matches_frozen_proposal(proposal,a.expected_other_answer) and \
            proposal['modelEvidence']['callId']!=prior.get('modelEvidence',{}).get('callId') and \
            previous_question.get('create_time','')<q.get('create_time','')
    if a.expected_historical_shared_uses is not None:
        checks['historical_shared_query_uses_are_preserved']=sum(
            bool(x['allowed']) and x['principal']==a.account for x in facts['historicalSharedUses'])==a.expected_historical_shared_uses
    if a.expected_superseded_question:
        old_question_id=str(uuid.UUID(a.expected_superseded_question))
        previous_question=previous_questions[0] if len(previous_questions)==1 else {}
        prior_proposals=[x for x in proposals if x['clarification_id']==old_question_id]
        prior=prior_proposals[0]['proposal_json'] if len(prior_proposals)==1 else {}
        checks['outdated_answer_was_not_saved_or_applied']=previous_question.get('clarification_id')==old_question_id and \
            previous_question.get('status')=='SUPERSEDED' and not previous_question.get('answered_by') and \
            not previous_answers and previous_question.get('resolution_source')=='REPLACED' and \
            previous_question.get('resolved_value')==q.get('clarification_id')
        checks['fresh_question_preserves_requested_change_and_shows_new_base']=proposal.get('previousQuestionId')==old_question_id and \
            proposal.get('newText')==prior.get('newText') and \
            (proposal['revision']!=prior.get('revision') or proposal['authorizationRevision']!=prior.get('authorizationRevision') or proposal['history']!=prior.get('history')) and \
            prior.get('oldText','') in q.get('question','') and '刚才的问题已过期，旧答案没有保存' in q.get('question','')
        checks['rebase_does_not_claim_new_model_result']=proposal.get('modelEvidence')==prior.get('modelEvidence')
        replacements=[json.loads(x['payload']) for x in e['events'] if x['event_type']=='CLARIFICATION_SUPERSEDED']
        checks['actual_question_replacement_event_is_recorded']=len(replacements)==1 and \
            old_question_id in json.dumps(replacements[0]) and q['clarification_id'] in json.dumps(replacements[0])
    if a.require_human_readable:
        messages=facts['assistantMessages'];message=messages[0] if len(messages)==1 else {}
        metadata=message.get('metadata_json') or {}
        if isinstance(metadata,str):metadata=json.loads(metadata)
        checks['persisted_reply_contains_confirmed_meaning_and_actual_scope']=len(messages)==1 and message.get('status')=='SUCCEEDED' and \
            proposal['phrase'] in message.get('content','') and (proposal.get('newText') or proposal['oldText']) in message.get('content','') and \
            ('允许分享为项目建议' if a.scope=='PROJECT' else '以后的使用仅供本人') in message.get('content','')
        checks['management_reply_has_exact_kind_without_fake_query_artifact']=metadata.get('requestKind')=='SEMANTIC_UPDATE' and not metadata.get('artifactId') and \
            '结果工件' not in message.get('content','') and '持久化 Run' not in message.get('content','')
    if a.require_history_labels:
        checks['history_confirmation_displays_query_text_instead_of_opaque_run_id']=all(
            use.get('question') and use['question'].replace('\n',' ').strip()[:180] in q.get('question','') and use['runId'] not in q.get('question','')
            for use in proposal.get('history',[]))
    if a.expected_active_version is not None:
        checks['published_project_version_is_preserved']=len(facts['project'])==1 and facts['project'][0]['active_version_id']==a.expected_active_version
    if preserved:
        checks['previous_correct_query_cases_are_preserved']=all(any(c['run_id']==value and c['status']=='APPROVED'
            for c in facts['preservedCases']) for value in preserved)
    if affected_expected:
        checks['model_targets_exact_requested_history_without_collateral_targets']=affected==affected_expected
    if quarantined:
        checks['only_requested_historical_cases_are_isolated_by_this_confirmation']=all(
            any(c['run_id']==value and c['status']=='QUARANTINED' and
                c['quarantine_reason']=='Confirmed semantic correction: '+q['clarification_id']
                for c in facts['quarantinedCases']) for value in quarantined)
    a.output.with_suffix('.sql').write_text('-- Read-only actual proposal, receipt, immutable definitions and per-query sharing proof\n'+';\n\n'.join(queries.values())+';\n')
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'facts':facts,'runId':run,'baseline':str(a.baseline),
        'boundary':'Natural-language browser action and normal HITL produced the change. This script performs only SELECT and authenticated GET, never creates state, counters or a successful model outcome.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'output':str(a.output)},ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)

if __name__=='__main__':main()
