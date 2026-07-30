#!/usr/bin/env python3
"""Read-only automatic publication proof, with a retained pre-publication baseline."""
import argparse, hashlib, json
from importlib.machinery import SourceFileLoader
from pathlib import Path
from acceptance_http import LocalAcceptanceClient
from acceptance_fixture_scope import business_database

sql = SourceFileLoader('publication_proof', str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidate', type=int, required=True)
    p.add_argument('--baseline', type=Path)
    p.add_argument('--operator', default='semevosql-system')
    p.add_argument('--action', default='AUTO_CREATE')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--namespace',help='Explicit ordinary synthetic quality fixture namespace')
    p.add_argument('--account',default='semevosql-acceptance-owner')
    a = p.parse_args()
    if a.candidate <= 0 or a.output.exists() or a.output.with_suffix('.sql').exists():
        raise ValueError('Positive candidate and fresh proof path required')
    queries = {'candidate': f'SELECT * FROM qw_project_definition_candidate WHERE id={a.candidate}'}
    candidate = sql('semevosql_acceptance', queries['candidate'])
    if len(candidate) != 1:
        raise ValueError('Isolated acceptance candidate required')
    c = candidate[0]; project = c['project_id']; business_database(project,sql,a.namespace)
    client = LocalAcceptanceClient(a.account)
    queries.update({
        'published': f"SELECT id,status,catalog_hash FROM qw_project_version WHERE project_id={project} AND status='PUBLISHED' ORDER BY id",
        'definitions': f'SELECT d.* FROM qw_user_semantic_definition_revision d JOIN qw_user_semantic_preference p ON p.id=d.preference_id WHERE p.project_id={project} ORDER BY d.preference_id,d.revision',
        'structures': f'SELECT d.* FROM qw_user_semantic_structure_revision d JOIN qw_user_semantic_preference p ON p.id=d.preference_id WHERE p.project_id={project} ORDER BY d.preference_id,d.source_revision,d.representation_hash',
        'jobs': f'SELECT * FROM qw_project_definition_publication WHERE candidate_id={a.candidate} ORDER BY id',
        'events': f'SELECT * FROM qw_project_definition_publication_event WHERE candidate_id={a.candidate} ORDER BY publication_id',
        'projectActivities': f'SELECT id,project_version_id,activity_type,operator_name FROM qw_project_version_activity WHERE project_id={project} ORDER BY id',
        'retrievalIndex': f'SELECT d.id,d.project_version_id,d.content_hash,w.status,e.dimension,e.content_hash AS embedding_content_hash FROM qw_semantic_retrieval_document d LEFT JOIN qw_semantic_document_index_work w ON w.document_id=d.id LEFT JOIN qw_semantic_retrieval_embedding e ON e.document_id=d.id WHERE d.project_id={project} ORDER BY d.id'
    })
    facts = {k: sql('semevosql_acceptance', q) for k, q in queries.items()}
    catalogs = {str(v['id']): {'catalogHash': v['catalog_hash'], 'snapshotSha256': digest(client.request(f'/api/semevosql/projects/{project}/versions/{v["id"]}/semantic-catalog'))} for v in facts['published']}
    baseline = {'project': project, 'candidate': a.candidate, 'catalogs': catalogs,
                'definitions': facts['definitions'], 'structures': facts['structures'],
                'projectActivities': facts['projectActivities'], 'retrievalIndex': facts['retrievalIndex'],
                'activeVersion': client.request(f'/api/semevosql/projects/{project}')['project']['activePublishedVersionId']}
    checks = {'actual_baseline_has_published_catalog': bool(catalogs)}
    report = {'baseline': baseline, 'facts': facts, 'checks': checks}
    if a.baseline:
        before = json.loads(a.baseline.read_bytes())['baseline']
        if before['project'] != project or before['candidate'] != a.candidate:
            raise ValueError('Baseline identity mismatch')
        jobs = [j for j in facts['jobs'] if j['state'] == 'DONE']
        version = c['published_version_id']
        view = client.request(f'/api/semevosql/projects/{project}/versions/{version}') if version else {}
        catalog = client.request(f'/api/semevosql/projects/{project}/versions/{version}/semantic-catalog') if version else {}
        project_view = client.request(f'/api/semevosql/projects/{project}')
        release = view.get('releaseReport')
        release = json.loads(release) if isinstance(release, str) else release
        if version:
            queries.update({
                'audit': f'SELECT activity_type,operator_name FROM qw_project_version_activity WHERE project_id={project} AND project_version_id={version}',
                'documents': f'SELECT d.model_code,d.semantic_text,w.status,w.attempt_count,e.dimension,e.content_hash=d.content_hash AS current_content FROM qw_semantic_retrieval_document d JOIN qw_semantic_document_index_work w ON w.document_id=d.id LEFT JOIN qw_semantic_retrieval_embedding e ON e.document_id=d.id WHERE d.project_id={project} AND d.project_version_id={version}'
            })
            facts.update({k: sql('semevosql_acceptance', queries[k]) for k in ('audit', 'documents')})
        metric = next((m for m in catalog.get('metrics', []) if m['metricCode'] == c['public_asset_key']), {})
        binding = metric.get('definitionBinding', {})
        definition = next((d for d in catalog.get('sharedDefinitions', []) if d['code'] == binding.get('definitionCode') and d['revision'] == binding.get('definitionRevision')), {})
        source = (jobs[0]['source_structure'].get('metric', {}) if len(jobs) == 1 else {})
        identity_ok = bool(metric) and not c['public_asset_key'].startswith('p_') and binding.get('bindingCode') == f'candidate_{a.candidate}'
        if a.action == 'OVERWRITE' and len(jobs) == 1:
            old_catalog = client.request(f'/api/semevosql/projects/{project}/versions/{jobs[0]["base_version_id"]}/semantic-catalog')
            old_metric = next((m for m in old_catalog['metrics'] if m['metricCode'] == c['public_asset_key']), {})
            old_binding = old_metric.get('definitionBinding', {})
            identity_ok = bool(old_metric) and bool(binding) and all(binding.get(k) == old_binding.get(k) for k in ('definitionCode', 'modelCode', 'bindingCode', 'roleName')) and binding['definitionRevision'] == old_binding['definitionRevision'] + 1
            report['oldPublicMetric'] = old_metric
        checks.update({
            'candidate_published': c['lifecycle'] == 'PUBLISHED',
            'one_completed_job_has_exact_authority': len(jobs) == 1 and jobs[0]['operator'] == a.operator and jobs[0]['action'] == a.action,
            'immutable_event_matches_exact_job_and_versions': len(jobs) == 1 and len(facts['events']) == 1 and facts['events'][0]['publication_id'] == jobs[0]['id'] and facts['events'][0]['from_version_id'] == jobs[0]['base_version_id'] and facts['events'][0]['to_version_id'] == version and facts['events'][0]['payload']['evidenceRevision'] == jobs[0]['evidence_revision'] and facts['events'][0]['payload']['contributionFingerprint'] == jobs[0]['contribution_fingerprint'],
            'old_public_snapshots_unchanged': all(catalogs.get(k) == v for k, v in before['catalogs'].items()),
            'all_personal_text_revisions_unchanged': facts['definitions'] == before['definitions'],
            'all_personal_structure_revisions_unchanged': facts['structures'] == before['structures'],
        })
        if a.action == 'ASSOCIATE':
            # Association reuses the existing public asset; no cloned catalog, new formula or index is expected.
            checks.update({
                'association_reuses_exact_existing_target': bool(metric) and len(jobs)==1
                    and jobs[0]['target_asset_key']==c['public_asset_key']==jobs[0]['public_asset_key']
                    and jobs[0]['base_version_id']==version==before['activeVersion']
                    and project_view['project']['activePublishedVersionId']==version,
                'no_public_catalog_or_version_added_or_changed': catalogs==before['catalogs'],
                'no_publish_activate_or_rollback_activity_created': facts['projectActivities']==before['projectActivities'],
                'existing_retrieval_documents_and_vectors_unchanged': facts['retrievalIndex']==before['retrievalIndex'],
                'immutable_association_event_identifies_target_and_decision': len(facts['events'])==1 and len(jobs)==1
                    and facts['events'][0]['payload']['action']=='ASSOCIATE'
                    and facts['events'][0]['payload']['targetAsset']==c['public_asset_key']
                    and facts['events'][0]['payload']['decisionId']==jobs[0]['decision_id'],
            })
        else:
            checks.update({
                'new_public_version_is_active': view.get('status') == 'PUBLISHED' and project_view['project']['activePublishedVersionId'] == version and str(version) not in before['catalogs'],
                'release_validation_passed': bool(release and release.get('passed')),
                'normal_publish_and_activate_audits': {'PUBLISHED', 'ACTIVATED'} <= {r['activity_type'] for r in facts.get('audit', [])},
                'public_identity_separate_from_private': identity_ok,
                'exact_shared_formula_and_full_meaning': definition.get('description') == c['definition_text'] and definition.get('specification', {}).get('expression') == source.get('expression') and definition.get('specification', {}).get('filters') == source.get('filters') and definition.get('specification', {}).get('timeAttribute') == source.get('timeAttribute') and definition.get('specification', {}).get('unit') == source.get('unit'),
                'all_new_model_documents_have_current_qwen_vectors': bool(facts.get('documents')) and all(d['status'] == 'DONE' and d['dimension'] == 1024 and d['current_content'] for d in facts['documents'])
            })
        report.update({'version': view, 'publicMetric': metric, 'sharedDefinition': definition})
    a.output.with_suffix('.sql').write_text('-- Read-only semevosql_acceptance metadata proof\n' + ';\n\n'.join(queries.values()) + ';\n')
    report['status'] = 'PASS' if all(checks.values()) else 'FAIL'
    report['boundary'] = 'Actual lifecycle, artifacts and unchanged history; no publication states, approvals or contributions are written by this script.'
    a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'checks': len(checks), 'failed': [k for k, v in checks.items() if not v], 'output': str(a.output)}, ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)

if __name__ == '__main__':
    main()
