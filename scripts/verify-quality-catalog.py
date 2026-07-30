#!/usr/bin/env python3
"""Read-only proof of normal import/publication of the named isolated quality fixture."""
import argparse,hashlib,json
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
sql=SourceFileLoader('quality_catalog_sql',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def fingerprint(value):
    return 'sha256:'+hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--package',type=Path,required=True);p.add_argument('--report',type=Path,required=True)
    p.add_argument('--require-published',action='store_true');p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    if a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Retain earlier evidence')
    pack=json.loads(a.package.read_bytes());report=json.loads(a.report.read_bytes())
    identity=sql('semevosql_acceptance',"SELECT id FROM qw_project WHERE project_code='semevosql-quality-v1'")
    if len(identity)!=1:raise ValueError('One named synthetic quality project required')
    pid=int(identity[0]['id'])
    versions=sql('semevosql_acceptance',f'SELECT id,status FROM qw_project_version WHERE project_id={pid} ORDER BY id')
    if len(versions)!=1:raise ValueError('This bootstrap proof requires the original isolated first version')
    vid=int(versions[0]['id'])
    queries={
        'project':f'SELECT id,project_code,active_version_id FROM qw_project WHERE id={pid}',
        'version':f'SELECT id,status,analysis_status,catalog_hash,validated_time,release_report FROM qw_project_version WHERE project_id={pid} AND id={vid}',
        'imports':f"SELECT import_id,status,operator_name,input_hash,source_fingerprint,input_json,receipt_json FROM qw_semantic_catalog_import WHERE project_id={pid} AND project_version_id={vid} ORDER BY create_time",
        'definitions':f"SELECT definition_code,revision,asset_type,definition_json FROM qw_semantic_definition_revision WHERE project_id={pid} AND definition_format='AST_V1_2' ORDER BY definition_code,revision",
        'bindings':f"SELECT b.* FROM qw_semantic_model_asset_binding b JOIN qw_semantic_definition_revision d ON d.project_id=b.project_id AND d.definition_code=b.definition_code AND d.revision=b.definition_revision WHERE b.project_id={pid} AND b.project_version_id={vid} AND d.definition_format='AST_V1_2' ORDER BY b.model_code,b.binding_code",
        'dictionaries':f'SELECT dictionary_code,revision,metadata_json FROM qw_semantic_enum_dictionary_revision WHERE project_id={pid} ORDER BY dictionary_code,revision',
        'dictionaryEntries':f'SELECT dictionary_code,revision,ordinal_no,entry_json FROM qw_semantic_enum_dictionary_entry WHERE project_id={pid} ORDER BY dictionary_code,revision,ordinal_no',
        'vectors':f"SELECT d.model_code,w.status,w.attempt_count,e.dimension,e.content_hash=d.content_hash AS current_content FROM qw_semantic_retrieval_document d LEFT JOIN qw_semantic_document_index_work w ON w.document_id=d.id LEFT JOIN qw_semantic_retrieval_embedding e ON e.document_id=d.id WHERE d.project_id={pid} AND d.project_version_id={vid} ORDER BY d.model_code",
        'activities':f'SELECT activity_type,operator_name FROM qw_project_version_activity WHERE project_id={pid} AND project_version_id={vid} ORDER BY id',
        'sourceBinding':f'SELECT b.datasource_id,d.database_name,d.username FROM qw_project_datasource_binding b JOIN datasource d ON d.id=b.datasource_id WHERE b.project_id={pid} AND b.project_version_id={vid}',
        'oldProject':"SELECT active_version_id FROM qw_project WHERE id=2",
    }
    facts={k:sql('semevosql_acceptance',v) for k,v in queries.items()}
    source=LocalAcceptanceClient().request(f'/api/semevosql/projects/{pid}/versions/{vid}/offline-catalog/source-schema')
    catalog=LocalAcceptanceClient().request(f'/api/semevosql/projects/{pid}/versions/{vid}/semantic-catalog')
    committed=[x for x in facts['imports'] if x['status']=='COMMITTED'];receipt=committed[0] if len(committed)==1 else {}
    expected_defs={(d['code'],d['revision']):d for d in pack['catalog']['definitions']}
    actual_defs={(d['definition_code'],d['revision']):d['definition_json'] for d in facts['definitions']}
    expected_bindings={(b['model'],b['code']):b for b in pack['catalog']['bindings']}
    actual_bindings={(b['model_code'],b['binding_code']):b['binding_json'] for b in facts['bindings']}
    actual_dictionaries={}
    for row in facts['dictionaries']:
        key=(row['dictionary_code'],row['revision'])
        entries=[e for e in facts['dictionaryEntries'] if (e['dictionary_code'],e['revision'])==key]
        if [e['ordinal_no'] for e in entries]!=list(range(len(entries))):
            raise ValueError('Dictionary ordinal sequence is incomplete')
        actual_dictionaries[key]={**row['metadata_json'],'entries':[e['entry_json'] for e in entries]}
    checks={
        'exact_local_package_passed_real_offline_validation':report['valid'] and not report['errors'] and report['catalogSha256']=='sha256:'+hashlib.sha256(a.package.read_bytes()).hexdigest(),
        'normal_import_receipt_has_exact_input_and_admin':len(committed)==1 and receipt['operator_name']=='semevosql-acceptance-owner' and receipt['input_hash']==fingerprint(pack) and receipt['input_json']==pack,
        'current_actual_jdbc_structure_matches_frozen_snapshot':fingerprint(source)==pack['sourceSchemaFingerprint']==report['sourceSchemaFingerprint']==receipt.get('source_fingerprint'),
        'immutable_shared_definitions_match_exact_authored_specs':actual_defs==expected_defs,
        'exact_model_roles_are_bound_to_explicit_definition_revisions':actual_bindings==expected_bindings,
        'independent_status_dictionaries_are_preserved':actual_dictionaries=={(d['code'],d['revision']):d for d in pack['catalog']['enumDictionaries']},
        'isolated_readonly_business_source_is_bound':len(facts['sourceBinding'])==1 and facts['sourceBinding'][0]['database_name']=='semevosql_quality_business_v1' and facts['sourceBinding'][0]['username']=='semevosql_quality_reader_v1',
        'original_project_public_version_is_preserved':facts['oldProject']==[{'active_version_id':10}],
        'compiled_catalog_hash_matches_durable_receipt':fingerprint(catalog).removeprefix('sha256:')==receipt.get('receipt_json',{}).get('catalogHash'),
    }
    if a.require_published:
        checks.update({
            'normal_published_version_is_active':facts['version'][0]['status']=='PUBLISHED' and facts['project'][0]['active_version_id']==vid,
            'normal_validation_publication_activation_audit':facts['version'][0]['validated_time'] is not None and facts['version'][0]['release_report'] is not None and {'PUBLISHED','ACTIVATED'}<={x['activity_type'] for x in facts['activities']} and all(x['operator_name']=='semevosql-acceptance-owner' for x in facts['activities'] if x['activity_type'] in ('PUBLISHED','ACTIVATED')),
            'published_hash_matches_actual_compiled_catalog':facts['version'][0]['catalog_hash']==fingerprint(catalog).removeprefix('sha256:'),
            'five_complete_actual_model_vectors_are_ready':len(facts['vectors'])==5 and all(x['status']=='DONE' and x['dimension']==1024 and x['current_content'] for x in facts['vectors']),
        })
    a.output.with_suffix('.sql').write_text('-- Read-only synthetic quality bootstrap lifecycle evidence\n'+';\n\n'.join(queries.values())+';\n')
    out={'status':'PASS' if all(checks.values()) else 'FAIL','stage':'published' if a.require_published else 'imported',
        'projectId':pid,'versionId':vid,'checks':checks,'facts':facts,'compiledCatalog':catalog,
        'boundary':'Normal browser actions produced lifecycle states. This script performs only SELECT and authenticated GET. Business query quality is separately tested.'}
    a.output.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':out['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v]},ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)

if __name__=='__main__':main()
