#!/usr/bin/env python3
"""Request actual Qwen reindexing and cross-check its durable state. No success-state SQL writes.

An unfinished build is PENDING, not passed. Use a fresh evidence path and rerun after background retry.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import time
from acceptance_http import LocalAcceptanceClient


def inspect(output, request, wait_seconds):
    if output.exists():
        raise ValueError('Retain earlier evidence; choose a fresh output')
    client=LocalAcceptanceClient()
    statement="""SELECT json_build_object(
      'registry',(SELECT row_to_json(r) FROM qw_embedding_index_registry r WHERE index_scope='SEMANTIC_CATALOG'),
      'work',(SELECT row_to_json(w) FROM qw_semantic_reindex_work w WHERE index_scope='SEMANTIC_CATALOG'),
      'documents',(SELECT count(*) FROM qw_semantic_retrieval_document),
      'vectors',(SELECT json_agg(row_to_json(v)) FROM (SELECT embedding_model,embedding_version,dimension,count(*) AS count
        FROM qw_semantic_retrieval_embedding GROUP BY embedding_model,embedding_version,dimension) v),
      'alignedVectors',(SELECT count(*) FROM qw_semantic_retrieval_document d JOIN qw_semantic_retrieval_embedding e
        ON e.document_id=d.id AND e.content_hash=d.content_hash JOIN qw_semantic_reindex_work w
        ON e.embedding_model=w.embedding_model AND e.embedding_version=w.embedding_version),
      'migration',(SELECT version FROM flyway_schema_history WHERE version='45' AND success));"""
    def database():
        return json.loads(subprocess.check_output(['docker','exec','semevosql-acceptance-metadata-db-1','psql',
            '-X','-A','-t','-v','ON_ERROR_STOP=1','-U','acceptance','-d','semevosql_acceptance','-c',statement],text=True))
    before=database();evidence={'status':'PENDING','before':before,'observations':[],
        'boundary':'Real configured local Qwen; asynchronous reindex only. Catalog publication and SQL use require separate acceptance.'}
    if request:
        start=time.monotonic()
        with ThreadPoolExecutor(max_workers=4) as pool:
            results=list(pool.map(lambda _:client.request('/api/semevosql/operations/semantic-index/reindex','POST',{}),range(4)))
        evidence['requests']=results;evidence['requestSeconds']=round(time.monotonic()-start,3)
        assert len({r['revision'] for r in results})==1
    deadline=time.monotonic()+wait_seconds
    output.parent.mkdir(parents=True,exist_ok=True);output.with_suffix('.sql').write_text(statement+'\n')
    while True:
        start=time.monotonic();state=client.request('/api/semevosql/operations/semantic-index/reindex')
        health=client.request('/api/semevosql/auth/session')
        evidence['observations'].append({'state':state,'metadataResponseSeconds':round(time.monotonic()-start,3),
            'authenticatedIdentity':health.get('username')})
        evidence['database']=database()
        if state.get('status')=='DONE':
            db=evidence['database'];registry=db['registry'];work=db['work']
            assert work['status']=='DONE' and registry['embedding_version']==work['embedding_version']
            assert registry['embedding_model']==work['embedding_model'] and registry['dimension']==1024
            assert db['alignedVectors']==db['documents']==work['indexed_documents'] and db['migration']=='45'
            assert all(any(v['embedding_version']==old['embedding_version'] and v['count']>=old['count']
                for v in db['vectors']) for old in before.get('vectors') or [])
            evidence['status']='PASS_REAL_REINDEX';break
        output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
        if time.monotonic()>=deadline:break
        time.sleep(min(10,max(0.1,deadline-time.monotonic())))
    output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':evidence['status'],'requestSeconds':evidence.get('requestSeconds'),
        'workStatus':evidence['database']['work']['status'] if evidence['database']['work'] else None,
        'alignedVectors':evidence['database']['alignedVectors'],'documents':evidence['database']['documents']}))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--request',action='store_true')
    parser.add_argument('--wait-seconds',type=int,default=0)
    args=parser.parse_args();inspect(args.output,args.request,args.wait_seconds)
