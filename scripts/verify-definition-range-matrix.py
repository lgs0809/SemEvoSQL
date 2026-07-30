#!/usr/bin/env python3
"""Verify four real historical confirmation transitions without confusing old receipts with today's head."""
import argparse
import json
import uuid
from importlib.machinery import SourceFileLoader
from pathlib import Path
from acceptance_http import LocalAcceptanceClient
from acceptance_fixture_scope import business_database

sql = SourceFileLoader('range_matrix_sql', str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', action='append', required=True, help='OLD_SCOPE:NEW_SCOPE:actual_run_uuid; USER or PROJECT')
    p.add_argument('--account', required=True)
    p.add_argument('--project-id',type=int,default=2)
    p.add_argument('--namespace',help='Explicit ordinary synthetic fixture namespace')
    p.add_argument('--preference-id', type=int, required=True)
    p.add_argument('--expected-active-version', type=int, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    cases = [value.split(':') for value in a.run]
    required = {(old, new) for old in ('USER', 'PROJECT') for new in ('USER', 'PROJECT')}
    if len(cases) != 4 or any(len(case) != 3 for case in cases) or {(c[0], c[1]) for c in cases} != required:
        raise ValueError('Exactly four distinct scope transitions required')
    if a.preference_id <= 0 or a.output.exists() or a.output.with_suffix('.sql').exists():
        raise ValueError('Exact personal identity and fresh evidence output required')
    business_database(a.project_id,sql,a.namespace)
    client = LocalAcceptanceClient(a.account)
    queries, result = {}, []
    sharing = {'USER': 'PRIVATE', 'PROJECT': 'ALLOWED'}
    for old_scope, new_scope, identity in cases:
        run = str(uuid.UUID(identity))
        query = f"""SELECT c.*,d.proposal_json,r.status AS run_status,r.project_id,
            v.user_id,q.status AS question_status,q.answered_by,q.selected_scope AS answer_scope,
            q.selected_option,q.question,original.user_question,
            before.definition_text AS old_text,before.content_hash AS old_hash,
            after.definition_text AS new_text,after.content_hash AS new_hash,after.source_id AS new_source,
            auth.choice AS submitted_sharing,oldauth.choice AS previous_sharing,
            (SELECT count(*) FROM qw_sql_execution_attempt x WHERE x.run_id=r.run_id) AS sql_attempts,
            (SELECT count(*) FROM qw_user_semantic_preference_usage u WHERE u.run_id=r.run_id) AS new_uses,
            (SELECT count(*) FROM graphcheckpoint cp JOIN graphthread t ON t.thread_id=cp.thread_id
                JOIN qw_native_graph_binding b ON b.graph_thread_id::text=t.thread_name WHERE b.run_id=r.run_id) AS checkpoints
            FROM qw_personal_definition_change_receipt c
            JOIN qw_personal_definition_change d USING(clarification_id)
            JOIN qw_runtime_clarification q USING(clarification_id)
            JOIN qw_query_run r ON r.run_id=q.run_id
            JOIN qw_user_semantic_preference v ON v.id=c.result_preference_id
            JOIN qw_user_semantic_definition_revision before ON before.preference_id=d.preference_id AND before.revision=d.definition_revision
            JOIN qw_user_semantic_definition_revision after ON after.preference_id=c.result_preference_id AND after.revision=c.result_revision
            JOIN qw_user_semantic_authorization auth ON auth.preference_id=c.result_preference_id
                AND auth.definition_revision=c.result_revision AND auth.source_id='clarification:'||c.clarification_id
            JOIN qw_user_semantic_authorization oldauth ON oldauth.preference_id=d.preference_id
                AND oldauth.definition_revision=d.definition_revision AND oldauth.authorization_revision=d.authorization_revision
            JOIN qw_conversation_turn original ON original.run_id=r.run_id
            WHERE r.run_id='{run}' AND v.project_id={a.project_id} AND v.id={a.preference_id}"""
        queries[run] = query
        rows = sql('semevosql_acceptance', query)
        if len(rows) != 1:
            raise ValueError('Exactly one actual committed receipt and source authorization required: ' + run)
        row = rows[0]
        proposal = row['proposal_json']
        evidence = proposal.get('modelEvidence') or {}
        checks = {
            'actual_completed_owner_run': row['run_status'] == 'SUCCEEDED' and row['user_id'] == a.account
                and client.request('/api/semevosql/runs/' + run)['status'] == 'SUCCEEDED',
            'old_scope_is_the_frozen_authorization_seen_at_confirmation': proposal['oldSharing'] == sharing[old_scope]
                and row['previous_sharing'] == sharing[old_scope],
            'normal_owner_confirmation_selected_new_scope': row['question_status'] == 'ANSWERED'
                and row['answered_by'] == a.account and row['answer_scope'] == new_scope and row['selected_scope'] == new_scope
                and row['selected_option'] == 'CONFIRM_FUTURE',
            'new_immutable_computation_does_not_overwrite_old_definition': proposal.get('newText') == row['new_text']
                and proposal['oldText'] == row['old_text'] and proposal['contentHash'] == row['old_hash']
                and row['new_hash'] != row['old_hash'] and row['result_revision'] > proposal['revision'],
            'confirmation_saved_exact_new_revision_authorization': row['submitted_sharing'] == sharing[new_scope]
                and row['new_source'] == 'clarification:' + row['clarification_id'],
            'natural_language_and_actual_model_proposal_are_retained': proposal['newText'] in row['user_question']
                and not row['user_question'].lstrip().startswith('{') and bool(evidence.get('callId'))
                and evidence.get('purpose') == 'SEMANTIC_PLANNING' and bool(evidence.get('response')),
            'management_does_not_manufacture_sql_or_query_use': row['sql_attempts'] == 0 and row['new_uses'] == 0,
            'native_graph_was_checkpointed': row['checkpoints'] >= 8,
        }
        result.append({'runId': run, 'transition': old_scope + ' → ' + new_scope, 'checks': checks, 'facts': row})
    queries['project'] = f'SELECT active_version_id FROM qw_project WHERE id={a.project_id}'
    project = sql('semevosql_acceptance', queries['project'])
    preserved = len(project) == 1 and project[0]['active_version_id'] == a.expected_active_version
    passed = preserved and all(all(case['checks'].values()) for case in result)
    report = {'status': 'PASS' if passed else 'FAIL', 'cases': result, 'publishedVersionPreserved': preserved,
        'boundary': 'Read-only historical frozen proposals, owner answers and immutable revision receipts. Later valid changes do not make an earlier confirmation fail or rewrite its scope. No data-query or model-quality result is inferred from management.'}
    a.output.with_suffix('.sql').write_text('-- Read-only historical range matrix\n' + ';\n\n'.join(queries.values()) + ';\n')
    a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'cases': len(result), 'checks': 1 + sum(len(c['checks']) for c in result),
        'failed': {c['transition']: [k for k, v in c['checks'].items() if not v] for c in result if not all(c['checks'].values())}}, ensure_ascii=False))
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
