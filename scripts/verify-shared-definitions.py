#!/usr/bin/env python3
"""Read-only proof of the synthetic shared catalog and its actual model documents/vectors."""
import argparse,hashlib,json
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
sql=SourceFileLoader('offline_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--version',type=int,required=True);p.add_argument('--package',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.version<=4 or a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('New synthetic version and unused evidence path required')
    pack=json.loads(a.package.read_bytes());c=LocalAcceptanceClient();catalog=c.request(f'/api/semevosql/projects/1/versions/{a.version}/semantic-catalog');view=c.request(f'/api/semevosql/projects/1/versions/{a.version}');project=c.request('/api/semevosql/projects/1')
    queries={
      'imports':f"SELECT import_id,status,input_hash,receipt_json FROM qw_semantic_catalog_import WHERE project_id=1 AND project_version_id={a.version} AND status='COMMITTED'",
      'documents':f"SELECT d.model_code,d.semantic_text,w.status,w.attempt_count,e.dimension,e.content_hash=d.content_hash AS current_content FROM qw_semantic_retrieval_document d JOIN qw_semantic_document_index_work w ON w.document_id=d.id LEFT JOIN qw_semantic_retrieval_embedding e ON e.document_id=d.id WHERE d.project_id=1 AND d.project_version_id={a.version}",
      'bindings':f"SELECT b.model_code,b.binding_code,b.asset_type,b.asset_key,b.definition_code,b.definition_revision,b.dictionary_code,b.dictionary_revision,d.definition_format,b.binding_json FROM qw_semantic_model_asset_binding b JOIN qw_semantic_definition_revision d USING(project_id,definition_code) WHERE b.project_id=1 AND b.project_version_id={a.version} AND d.revision=b.definition_revision ORDER BY b.model_code,b.binding_code",
      'audit':f"SELECT activity_type,operator_name FROM qw_project_version_activity WHERE project_id=1 AND project_version_id={a.version}"}
    a.output.with_suffix('.sql').write_text('\n\n'.join(q+';' for q in queries.values())+'\n');facts={k:sql('semevosql_acceptance',q) for k,q in queries.items()}
    expected='sha256:'+hashlib.sha256(json.dumps(pack,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()
    source=pack['catalog'];checks={'strict_protocol_1_2':pack['formatVersion']=='1.2',
      'exact_package_committed':len(facts['imports'])==1 and facts['imports'][0]['input_hash']==expected,
      'published_and_active':view['status']=='PUBLISHED' and project['project']['activePublishedVersionId']==a.version,
      'normal_publication_audit':{'PUBLISHED','ACTIVATED'}<={r['activity_type'] for r in facts['audit']},
      'definitions_roundtrip':sorted(catalog['sharedDefinitions'],key=lambda d:d['code'])==sorted(source['definitions'],key=lambda d:d['code']),
      'bindings_roundtrip':sorted(catalog['modelBindings'],key=lambda b:(b['model'],b['code']))==sorted(source['bindings'],key=lambda b:(b['model'],b['code'])),
      'dictionaries_roundtrip':catalog['enumDictionaries']==source['enumDictionaries'],
      'seven_explicit_shared_roles':len([b for b in facts['bindings'] if b['definition_format']=='AST_V1_2'])==7,
      'remaining_legacy_assets_have_independent_bindings':any(b['definition_format']=='LEGACY_PROJECTION' for b in facts['bindings']),
      'three_actual_current_qwen_vectors':len(facts['documents'])==3 and all(d['status']=='DONE' and d['dimension']==1024 and d['current_content'] for d in facts['documents'])}
    report=view.get('releaseReport');report=json.loads(report) if isinstance(report,str) else report;checks['release_validation_passed']=bool(report and report.get('passed'))
    documents={d['model_code']:json.loads(d['semantic_text']) for d in facts['documents']}
    for model,doc in documents.items():
        shared=[b for b in source['bindings'] if b['model']==model]
        checks[f'{model}_bindings_scoped']=sorted(doc.get('modelBindings',[]),key=lambda b:b['code'])==sorted(shared,key=lambda b:b['code'])
        refs={b['definition'] for b in shared};checks[f'{model}_only_applicable_definitions']={d['code'] for d in doc.get('sharedDefinitions',[])}==refs
        dictrefs={b['dictionary']['code'] for b in shared if b.get('dictionary')};checks[f'{model}_only_applicable_dictionaries']={d['code'] for d in doc.get('enumDictionaries',[])}==dictrefs
        for b in shared:
            definition=next(d for d in source['definitions'] if d['code']==b['definition']);kind={'METRIC':'metrics','DIMENSION':'dimensions','ATTRIBUTE':'columns'}[definition['type']]
            checks[f'{model}_{b["code"]}_frozen_projection_ref']=any(r.get('definitionBinding',{}).get('definitionCode')==b['definition'] and r['definitionBinding']['definitionRevision']==b['definitionRevision'] and r['definitionBinding']['bindingCode']==b['code'] and r['definitionBinding']['modelCode']==model for r in catalog[kind])
            checks[f'{model}_{b["code"]}_role_in_model_text']=b['roleName'] in json.dumps(doc,ensure_ascii=False)
            for alias in b.get('aliases',[]):checks[f'{model}_{b["code"]}_confirmed_alias_{alias}']=alias in json.dumps(doc,ensure_ascii=False)
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'facts':facts,'versionId':a.version,
       'packageBytesSha256':hashlib.sha256(a.package.read_bytes()).hexdigest(),'boundary':'Browser publication and activation; API, DB and actual vectors independently read. No manufactured outcomes.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'output':str(a.output)},ensure_ascii=False));raise SystemExit(0 if all(checks.values()) else 1)
if __name__=='__main__':main()
