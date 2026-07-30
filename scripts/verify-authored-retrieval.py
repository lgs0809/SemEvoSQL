#!/usr/bin/env python3
"""Read-only evidence for the synthetic 1.1 import, version and complete current vectors."""
import argparse,json,hashlib
from pathlib import Path
from acceptance_http import LocalAcceptanceClient
from importlib.machinery import SourceFileLoader
sql=SourceFileLoader('offline_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--version',type=int,required=True);p.add_argument('--package',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.version<=1 or a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('New synthetic version and unused evidence path required')
    package=json.loads(a.package.read_bytes());c=LocalAcceptanceClient();catalog=c.request(f'/api/semevosql/projects/1/versions/{a.version}/semantic-catalog');version=c.request(f'/api/semevosql/projects/1/versions/{a.version}');project=c.request('/api/semevosql/projects/1')
    q={
      'imports':f"SELECT import_id,status,input_hash,receipt_json FROM qw_semantic_catalog_import WHERE project_id=1 AND project_version_id={a.version} AND status='COMMITTED'",
      'documents':f"SELECT d.model_code,d.semantic_text,w.status,e.dimension,e.content_hash=d.content_hash AS current_content FROM qw_semantic_retrieval_document d JOIN qw_semantic_document_index_work w ON w.document_id=d.id LEFT JOIN qw_semantic_retrieval_embedding e ON e.document_id=d.id WHERE d.project_id=1 AND d.project_version_id={a.version}",
      'legacy':"SELECT id,status,catalog_hash FROM qw_project_version WHERE project_id=1 AND id=1",
      'audit':f"SELECT activity_type,operator_name FROM qw_project_version_activity WHERE project_id=1 AND project_version_id={a.version}"}
    a.output.with_suffix('.sql').write_text('\n\n'.join(query+';' for query in q.values())+'\n');facts={k:sql('semevosql_acceptance',v) for k,v in q.items()}
    expected_hash='sha256:'+hashlib.sha256(json.dumps(package,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()
    checks={'strict_protocol_1_1':package['formatVersion']=='1.1','exact_committed_package':len(facts['imports'])==1 and facts['imports'][0]['input_hash']==expected_hash,
      'published_and_active':version['status']=='PUBLISHED' and project['project']['activePublishedVersionId']==a.version,
      'all_current_vectors':len(facts['documents'])==3 and all(v['status']=='DONE' and v['dimension']==1024 and v['current_content'] for v in facts['documents']),
      'legacy_published_hash_unchanged':facts['legacy']==[{'id':1,'status':'PUBLISHED','catalog_hash':'cde4c4eb5ee352bed2891303f27a5956f0a2711d4524788b3082f9e571a0d7e5'}],
      'normal_publication_audit':{'PUBLISHED','ACTIVATED'}<={r['activity_type'] for r in facts['audit']}}
    report=version.get('releaseReport');report=json.loads(report) if isinstance(report,str) else report
    checks['persisted_release_validation_passed']=bool(report and report.get('passed'))
    by_model={r['model_code']:json.loads(r['semantic_text']) for r in facts['documents']}
    for kind,internal,key in [('entities','models','modelCode'),('metrics','metrics','metricCode'),('dimensions','dimensions','dimensionCode')]:
        actual={r[key]:r for r in catalog[internal]}
        for asset in package['catalog'][kind]:
            if 'retrieval' not in asset:continue
            code=asset['code'];checks[f'{kind}_{code}_roundtrip']=actual[code].get('retrieval')==asset['retrieval']
            model=code if kind=='entities' else asset['entity'];content=by_model[model];records=[content['model']] if kind=='entities' else content[internal]
            checks[f'{kind}_{code}_complete_text']=any(r.get(key)==code and r.get('retrieval')==asset['retrieval'] for r in records)
    metric=next(r for r in catalog['metrics'] if r['metricCode']=='customer_count');checks['explicit_range_preserved']=metric.get('minimumValue')==0 and metric.get('minimumInclusive') is True
    evidence={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'versionId':a.version,'facts':facts,'packageBytesSha256':hashlib.sha256(a.package.read_bytes()).hexdigest(),'boundary':'Normal browser import, validation, publication and activation; no DB status changes.'}
    a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':evidence['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'output':str(a.output)},ensure_ascii=False));raise SystemExit(0 if all(checks.values()) else 1)
if __name__=='__main__':main()
