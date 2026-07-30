#!/usr/bin/env python3
"""Freeze read-only immutable ATTRIBUTE memberships for a reviewed published benchmark."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from importlib.machinery import SourceFileLoader
from frozen_output_identity import verify_frozen_catalog
from frozen_governed_output_identity import verify_governed_snapshot

sql = SourceFileLoader('governed_source', str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--suite', type=Path, required=True)
    p.add_argument('--expected-suite-sha256', required=True)
    p.add_argument('--catalog', type=Path, required=True)
    p.add_argument('--expected-catalog-sha256', required=True)
    p.add_argument('--import-proof', type=Path, required=True)
    p.add_argument('--published-proof', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.output.exists() or a.output.with_suffix('.sql').exists(): raise ValueError('Fresh evidence path required')
    suite_bytes, package_bytes = a.suite.read_bytes(), a.catalog.read_bytes()
    if hashlib.sha256(suite_bytes).hexdigest() != a.expected_suite_sha256:
        raise ValueError('Reviewed frozen suite changed')
    suite = json.loads(suite_bytes)
    _, binding = verify_frozen_catalog(suite, package_bytes, a.expected_catalog_sha256,
        json.loads(a.import_proof.read_bytes()), json.loads(a.published_proof.read_bytes()))
    project, version = binding['projectId'], binding['projectVersionId']
    if not isinstance(project, int) or not isinstance(version, int): raise ValueError('Numeric frozen scope required')
    version_sql = f'''SELECT project_id,id AS version_id,status,catalog_hash FROM qw_project_version
      WHERE project_id={project} AND id={version}'''
    source_sql = f'''SELECT b.project_id,b.project_version_id,b.model_code,b.binding_code,b.definition_code,
      b.definition_revision,b.asset_type,b.asset_key,b.dictionary_code,b.dictionary_revision,b.binding_json,
      d.definition_format,d.definition_json,d.content_hash AS definition_content_hash,
      'sha256:' || encode(sha256(convert_to(d.definition_json::text,'UTF8')),'hex') AS actual_definition_content_hash,
      to_jsonb(c)-ARRAY['id','project_id','project_version_id','create_time','update_time','evidence'] AS column_projection,
      m.physical_table,m.datasource_id,m.status AS model_status
      FROM qw_semantic_model_asset_binding b
      JOIN qw_semantic_version_definition vd ON vd.project_id=b.project_id AND vd.project_version_id=b.project_version_id
        AND vd.definition_code=b.definition_code AND vd.definition_revision=b.definition_revision
      JOIN qw_semantic_definition_revision d ON d.project_id=b.project_id
        AND d.definition_code=b.definition_code AND d.revision=b.definition_revision
      JOIN qw_semantic_column c ON c.project_id=b.project_id AND c.project_version_id=b.project_version_id
        AND c.model_code=b.model_code AND c.column_name=b.asset_key
      JOIN qw_semantic_model m ON m.project_id=b.project_id AND m.project_version_id=b.project_version_id
        AND m.model_code=b.model_code
      WHERE b.project_id={project} AND b.project_version_id={version} AND b.asset_type='ATTRIBUTE'
        AND d.definition_format='LEGACY_PROJECTION'
      ORDER BY b.model_code,b.binding_code'''
    versions, rows = sql('semevosql_acceptance', version_sql), sql('semevosql_acceptance', source_sql)
    if len(versions) != 1: raise ValueError('One actual frozen published version required')
    proof = {'formatVersion': 'frozen-governed-attributes-v1', 'capturedAt': datetime.now(timezone.utc).isoformat(),
        'suiteSha256': hashlib.sha256(suite_bytes).hexdigest(), 'catalogPackageSha256': hashlib.sha256(package_bytes).hexdigest(),
        'catalogBinding': binding, 'version': versions[0], 'attributes': rows,
        'sourceSqlSha256': hashlib.sha256(source_sql.encode()).hexdigest(),
        'boundary': 'Read-only actual published version memberships/immutable revisions and all 19 column properties. No answers, model outputs or learning writes.'}
    encoded = (json.dumps(proof, ensure_ascii=False, indent=2) + '\n').encode()
    digest = hashlib.sha256(encoded).hexdigest()
    verify_governed_snapshot(encoded, digest, binding)
    a.output.with_suffix('.sql').write_text('-- Read-only metadata database: semevosql_acceptance\n'+version_sql+';\n'+source_sql+';\n')
    a.output.write_bytes(encoded)
    print(json.dumps({'status': 'PASS', 'attributes': len(rows), 'sha256': digest, 'output': str(a.output)}))


if __name__ == '__main__': main()
