#!/usr/bin/env python3
"""Read-only proof that a natural-language public override executes without altering a saved personal default."""
import argparse
import json
import uuid
from decimal import Decimal
from importlib.machinery import SourceFileLoader
from pathlib import Path
from acceptance_http import LocalAcceptanceClient
from acceptance_fixture_scope import business_database
from result_acceptance_evidence import final_query_receipt, final_result_artifact, final_receipt_matches_artifact

sql = SourceFileLoader('public_selection_proof', str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--personal-baseline', type=Path, required=True)
    p.add_argument('--publication-proof', type=Path, required=True)
    p.add_argument('--account', required=True)
    p.add_argument('--oracle-sql', type=Path, required=True)
    p.add_argument('--oracle-column', required=True)
    p.add_argument('--expected-value', type=Decimal, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--namespace', help='Explicit ordinary synthetic fixture namespace')
    p.add_argument('--expected-version', type=int, help='Exact expected current published version for a fresh browser Run')
    a = p.parse_args()
    if a.output.exists() or a.output.with_suffix('.sql').exists():
        raise ValueError('Retain earlier evidence; choose a fresh output')
    e = json.loads(a.evidence.read_bytes()); run = e['run'][0]
    baseline = json.loads(a.personal_baseline.read_bytes()); published = json.loads(a.publication_proof.read_bytes())
    project = int(run['project_id'])
    database = business_database(project, sql, a.namespace)
    if published['status'] != 'PASS' or published['baseline']['project'] != project or not baseline['definitions']:
        raise ValueError('Actual same-project isolated baseline and publication proof required')
    rid = str(uuid.UUID(run['run_id'])); personal = baseline['definitions'][-1]; pid = int(personal['preference_id'])
    if personal['project_id'] != run['project_id'] or personal['user_id'] != a.account:
        raise ValueError('Personal baseline owner mismatch')
    queries = {
        'definitions': f'SELECT * FROM qw_user_semantic_definition_revision WHERE preference_id={pid} ORDER BY revision',
        'head': f'SELECT current_revision,archived FROM qw_user_semantic_preference WHERE id={pid}',
        'authorizations': f'SELECT * FROM qw_user_semantic_authorization WHERE preference_id={pid} ORDER BY definition_revision,authorization_revision',
        'uses': f'SELECT * FROM qw_user_semantic_preference_usage WHERE preference_id={pid} ORDER BY create_time,id',
        'choicesForRun': f"SELECT c.* FROM qw_personal_public_choice c JOIN qw_runtime_clarification q USING(clarification_id) WHERE q.run_id='{rid}'",
        'version': f"SELECT id,status,catalog_hash FROM qw_project_version WHERE id={int(run['project_version_id'])} AND project_id={project}",
        'frozenProject': f"SELECT execution_snapshot::jsonb->'payload'->'project' AS project FROM qw_query_run WHERE run_id='{rid}'",
    }
    facts = {k: sql('semevosql_acceptance', query) for k, query in queries.items()}
    client = LocalAcceptanceClient(a.account)
    catalog = client.request(f"/api/semevosql/projects/{project}/versions/{run['project_version_id']}/semantic-catalog")
    original_public = published['publicMetric']; code = original_public['metricCode']
    public = next((m for m in catalog['metrics'] if m['metricCode'] == code), {})
    plans = [json.loads(x['payload']) for x in e['events'] if x['event_type'] == 'SEMANTIC_PLAN_SNAPSHOT']
    approvals = [json.loads(x['payload']) for x in e['events'] if x['event_type'] == 'APPROVAL_PLAN_SNAPSHOT']
    plan = plans[-1] if plans else {}
    traces = [json.loads(x['payload']).get('planningTrace', {}) for x in e['events'] if x['event_type'] == 'PLANNING_TRACE']
    frozen_project = facts['frozenProject'][0].get('project') or {} if len(facts['frozenProject']) == 1 else {}
    selected = next((m for m in plan.get('metrics', []) if m['metricCode'] == code), {})
    query = a.oracle_sql.read_text().strip().rstrip(';')
    oracle = sql(database, query)
    expected = Decimal(str(oracle[0][a.oracle_column])) if len(oracle) == 1 else None
    def matches(rows):
        try:
            return len(rows) == 1 and Decimal(str(rows[0][code])) == expected
        except (TypeError, ValueError, KeyError, ArithmeticError):
            return False
    receipt = final_query_receipt(e); artifact = final_result_artifact(e)
    keys = ('preference_id', 'revision', 'content_hash', 'definition_text', 'definition_snapshot',
            'source_kind', 'source_id', 'source_version_id', 'dependency_fingerprint')
    frozen = lambda rows: [{k: row.get(k) for k in keys} for row in rows]
    public_keys = ('modelCode', 'expression', 'filterExpression', 'aggregation', 'timeColumn', 'unit', 'definitionBinding', 'minimumValue', 'maximumValue', 'minimumInclusive', 'maximumInclusive')
    checks = {
        'exact_expected_version_if_required': a.expected_version is None or run['project_version_id'] == a.expected_version,
        'original_publication_metric_belongs_to_same_project': original_public.get('projectId') == project,
        'actual_run_and_authenticated_read_succeeded': run['status'] == 'SUCCEEDED' and client.request('/api/semevosql/runs/' + rid)['status'] == 'SUCCEEDED',
        'no_default_update_question_or_answer': not e['questions'] and not e['answers'] and not facts['choicesForRun'],
        'normal_approval_freezes_exact_selected_plan': bool(approvals) and bool(plans) and approvals[-1] == plan,
        'published_catalog_and_frozen_hash_match': len(facts['version']) == 1 and facts['version'][0]['status'] == 'PUBLISHED'
            and frozen_project.get('projectId') == run['project_id'] and frozen_project.get('projectVersionId') == run['project_version_id']
            and bool(traces) and frozen_project.get('catalogHash') == facts['version'][0]['catalog_hash'] == traces[-1].get('catalogHash')
            and plan.get('catalogHash') in (None, frozen_project.get('catalogHash')),
        'public_meaning_and_shared_revision_unchanged_since_real_publication': bool(public) and all(public.get(k) == original_public.get(k) for k in public_keys),
        'exact_public_metric_selected': bool(selected) and all(selected.get(k) == public.get(k) for k in public_keys),
        'no_personal_definition_used_or_counted': bool(plans) and not plan['bindingDependencies'] and not any(u['run_id'] == rid for u in facts['uses']),
        'saved_default_and_all_revisions_unchanged': frozen(facts['definitions']) == frozen(baseline['definitions']) and len(facts['head']) == 1 and facts['head'][0]['current_revision'] == personal['current_revision'] and facts['head'][0]['archived'] == personal['archived'],
        'sharing_and_previous_uses_unchanged': facts['authorizations'] == baseline['authorizations'] and facts['uses'] == baseline['uses'],
        'independent_oracle_matches_expected': expected == a.expected_value,
        'last_actual_query_matches_independent_oracle': bool(receipt) and receipt['status'] == 'SUCCEEDED' and matches(receipt['result_json']['data']),
        'selected_durable_result_matches_independent_oracle': bool(artifact) and matches(artifact['data_json']),
        'last_sql_and_selected_result_agree': final_receipt_matches_artifact(e),
        'real_model_planning_and_native_checkpoints': len(e['checkpoints']) >= 10 and any(json.loads(x['payload']).get('planningTrace', {}).get('modelCallCount', 0) > 0 for x in e['events'] if x['event_type'] == 'PLANNING_TRACE'),
    }
    a.output.with_suffix('.sql').write_text('-- Read-only metadata evidence\n' + ';\n\n'.join(queries.values()) + ';\n\n-- Independent business oracle, different database\n' + query + ';\n')
    report = {'status': 'PASS' if all(checks.values()) else 'FAIL', 'checks': checks, 'runId': rid,
              'facts': facts, 'selectedMetric': selected, 'oracle': oracle, 'project': project, 'businessDatabase': database, 'verifierSha256': __import__('hashlib').sha256(Path(__file__).read_bytes()).hexdigest(),
              'boundary': 'Read-only evidence; the natural-language request and normal approval come from the real browser.'}
    a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'checks': len(checks), 'failed': [k for k, v in checks.items() if not v], 'output': str(a.output)}, ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)


if __name__ == '__main__':
    main()
