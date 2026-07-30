#!/usr/bin/env python3
"""Cross-check a real Run's scoped catalog receipts against the acceptance database (read only)."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists() or args.output.with_suffix('.sql').exists():
        raise ValueError('Retain earlier evidence; choose a fresh output')
    raw = args.evidence.read_bytes()
    data = json.loads(raw)
    run = data['run'][0]
    project, version = int(run['project_id']), int(run['project_version_id'])
    sql = f"""BEGIN READ ONLY;
SELECT json_build_object('version', (SELECT row_to_json(v) FROM
 (SELECT id,project_id,status,catalog_hash FROM qw_project_version WHERE project_id={project} AND id={version}) v),
 'enabledModels', (SELECT coalesce(json_agg(model_code ORDER BY model_code),'[]'::json)
 FROM qw_semantic_model WHERE project_id={project} AND project_version_id={version} AND status='ENABLED'));
ROLLBACK;
"""
    args.output.with_suffix('.sql').write_text(sql)
    command = ['docker', 'exec', '-i', 'semevosql-acceptance-metadata-db-1', 'psql', '-X', '-q', '-A', '-t',
               '-v', 'ON_ERROR_STOP=1', '-U', 'acceptance', '-d', 'semevosql_acceptance']
    database = subprocess.run(command, input=sql, capture_output=True, text=True, check=True)
    authority = json.loads(database.stdout)
    traces = [json.loads(e['payload']) for e in data['events'] if e['event_type'] == 'PLANNING_TRACE']
    scopes = [t.get('catalogLoadScope') for t in traces]
    checks = {
        'run_succeeded': run['status'] == 'SUCCEEDED',
        'all_planning_traces_have_scope_receipt': bool(scopes) and all(isinstance(s, dict) for s in scopes),
        'authority_version_exists': bool(authority['version']),
        'source_execution_completed': bool(data['sourceSubRuns']) and all(s['status'] == 'COMPLETED' for s in data['sourceSubRuns']),
    }
    for i, (trace, scope) in enumerate(zip(traces, scopes)):
        if not isinstance(scope, dict):
            continue
        enabled = set(authority['enabledModels'])
        loaded = set(scope.get('loadedModelCodes', []))
        checks[f'trace_{i}_scoped_to_run_project_and_version'] = scope.get('projectId') == project and scope.get('projectVersionId') == version
        checks[f'trace_{i}_hash_is_authoritative'] = bool(authority['version']) and bool(trace.get('catalogHash')) and trace['catalogHash'] == scope.get('authoritativeCatalogHash') == authority['version']['catalog_hash']
        checks[f'trace_{i}_models_exist_and_enabled'] = bool(loaded) and loaded <= enabled
        checks[f'trace_{i}_no_budget_truncation'] = scope.get('budgetTruncated') is False
        checks[f'trace_{i}_complete_details_with_bounded_relationship_scope'] = scope.get('modelDetailsComplete') is True and scope.get('relationshipNeighborhoodDepth') == 2
        recalled = {h['modelCode'] for h in trace.get('retrievalCandidates', []) if h.get('modelCode')}
        checks[f'trace_{i}_recalled_model_identities_retained'] = recalled <= loaded
    status = 'PASS' if all(checks.values()) else 'FAIL'
    result = {'status': status, 'checks': checks, 'catalogLoadScopes': scopes, 'authority': authority,
              'input': str(args.evidence.resolve()), 'inputSha256': hashlib.sha256(raw).hexdigest(),
              'boundary': 'Read-only real Run scope/hash verification. Full catalog scale and mutation races are covered separately by PostgreSQL integration tests; this does not prove all B.8 requirements.'}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': status, 'checks': len(checks), 'failed': [k for k,v in checks.items() if not v]}, ensure_ascii=False))
    raise SystemExit(0 if status == 'PASS' else 1)


if __name__ == '__main__':
    main()
