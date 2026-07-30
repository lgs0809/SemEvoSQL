#!/usr/bin/env python3
"""Read-only actual offline import/publication/query receipts and independent seeded-source oracle."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from acceptance_http import LocalAcceptanceClient

def sql(database,query, *, preserve_decimals=False):
    raw=subprocess.check_output(['docker','exec','semevosql-acceptance-metadata-db-1','psql','-X','-A','-t',
        '-v','ON_ERROR_STOP=1','-U','acceptance','-d',database,'-c',
        'SELECT row_to_json(proof.*) FROM ('+query+') proof'],text=True)
    # PostgreSQL NUMERIC can exceed binary floating-point precision. The model
    # and JDBC receipts retain decimal text; exact oracle checks must do so too.
    return [json.loads(line, parse_float=str) if preserve_decimals else json.loads(line)
            for line in raw.splitlines() if line]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=Path,required=True);parser.add_argument('--package',type=Path,required=True)
    parser.add_argument('--null-label',help='Exact literal requested by the real user; omit for the earlier untransformed oracle')
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    if args.output.exists() or args.output.with_suffix('.sql').exists():raise ValueError('Retain previous evidence')
    evidence=json.loads(args.evidence.read_bytes());run=evidence['run'][0]
    if run['project_id']!=1:raise ValueError('Only the isolated synthetic business project')
    raw_oracle_query="""SELECT x.region AS customer_region,count(*) AS customer_count
        FROM public.customers c LEFT JOIN public.customer_extensions x USING(customer_id)
        WHERE c.created_at >= DATE '2025-12-01' AND c.created_at < DATE '2026-01-01'
        GROUP BY x.region"""
    label=args.null_label
    if label is not None and (len(label)>128 or any(ord(c)<32 for c in label)):raise ValueError('Invalid oracle label')
    oracle_query=raw_oracle_query if label is None else raw_oracle_query.replace('x.region AS customer_region',
        "COALESCE(x.region,'"+label.replace("'","''")+"') AS customer_region").replace('GROUP BY x.region',
        "GROUP BY COALESCE(x.region,'"+label.replace("'","''")+"')")
    imported_query="""SELECT import_id,status,input_hash,source_fingerprint,receipt_json
        FROM qw_semantic_catalog_import WHERE project_id=1 AND project_version_id=1 AND status='COMMITTED'"""
    version_query="""SELECT v.id,v.status,v.analysis_status,v.catalog_hash,p.active_version_id
        FROM qw_project_version v JOIN qw_project p ON p.id=v.project_id WHERE v.id=1"""
    source_query="""SELECT (SELECT count(*) FROM public.customers) AS customers,
        (SELECT count(*) FROM public.orders) AS orders,(SELECT sum(amount) FROM public.orders) AS total_order_amount"""
    vector_query="""SELECT d.model_code,w.status,w.attempt_count,e.dimension,
        e.content_hash=d.content_hash AS current_content FROM qw_semantic_retrieval_document d
        JOIN qw_semantic_document_index_work w ON w.document_id=d.id
        JOIN qw_semantic_retrieval_embedding e ON e.document_id=d.id WHERE d.project_id=1 AND d.project_version_id=1"""
    activity_query="SELECT activity_type,operator_name FROM qw_project_version_activity WHERE project_id=1 AND project_version_id=1"
    queries=[('semevosql_acceptance_business',oracle_query),('semevosql_acceptance',imported_query),
        ('semevosql_acceptance',version_query),('semevosql_acceptance_business',source_query),
        ('semevosql_acceptance',vector_query),('semevosql_acceptance',activity_query)]
    args.output.with_suffix('.sql').write_text('\n\n'.join('-- Database: '+db+'\n'+query+';' for db,query in queries)+'\n')
    oracle,imports,versions,business,vectors,activities=[sql(db,q) for db,q in queries]
    raw_oracle=sql('semevosql_acceptance_business',raw_oracle_query)
    def rows(value):return sorted((str(r['customer_region']),int(r['customer_count'])) for r in value)
    result=[a for a in evidence['resultArtifacts'] if a['artifact_type']=='MERGED_RESULT' and a['status']=='READY']
    approvals=[json.loads(e['payload']) for e in evidence['events'] if e['event_type']=='APPROVAL_PLAN_SNAPSHOT']
    pack=json.loads(args.package.read_bytes())
    canonical_hash='sha256:'+hashlib.sha256(json.dumps(pack,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()
    checks={
        'actual_run_succeeded':run['status']=='SUCCEEDED',
        'one_committed_import_matches_exact_package':len(imports)==1 and imports[0]['input_hash']==canonical_hash,
        'actual_publication_and_activation':len(versions)==1 and versions[0]['status']=='PUBLISHED' and versions[0]['active_version_id']==1,
        'published_hash_matches_durable_receipt':len(imports)==1 and versions[0]['catalog_hash']==imports[0]['receipt_json']['catalogHash'],
        'seed_business_rows_still_match_retained_oracle':business==[{'customers':3,'orders':9,'total_order_amount':1080}],
        'all_three_complete_model_vectors_ready':len(vectors)==3 and all(v['status']=='DONE' and v['dimension']==1024 and v['current_content'] for v in vectors),
        'normal_admin_publication_audit':{'PUBLISHED','ACTIVATED'} <= {a['activity_type'] for a in activities},
        'approved_exact_frozen_catalog':len(approvals)==1 and approvals[0]['projectVersionId']==1,
        'compiled_source_keeps_left_extension_join':any('LEFT JOIN "public"."customer_extensions"' in t['sql_text'] for t in evidence['sqlTraces']),
        'persisted_result_matches_independent_sql':len(result)==1 and rows(result[0]['data_json'])==rows(oracle),
        'missing_extension_customer_retained':any(r['customer_region'] is None and r['customer_count']==1 for r in raw_oracle)
            and any(r['customer_region']==label and r['customer_count']==1 for r in oracle),
        'actual_sql_receipt_matches_oracle':any(a['phase']=='QUERY' and a['status']=='SUCCEEDED'
            and rows(a['result_json']['data'])==rows(oracle) for a in evidence['sqlExecutionAttempts']),
        'native_checkpoints_saved':len(evidence['checkpoints'])>=10,
        'authorized_owner_reads_actual_completed_run':LocalAcceptanceClient().request('/api/semevosql/runs/'+run['run_id'])['status']=='SUCCEEDED',
    }
    display={'requested_unknown_label_materialized':len(result)==1 and any(r['customer_region']==(label or '未知地区') for r in result[0]['data_json'])}
    summary={'coreStatus':'PASS' if all(checks.values()) else 'FAIL','displayStatus':'PASS' if all(display.values()) else 'FAIL',
        'checks':checks,'displayChecks':display,'runId':run['run_id'],'oracle':oracle,'vectors':vectors,'importReceipts':imports,
        'packageBytesSha256':hashlib.sha256(args.package.read_bytes()).hexdigest(),
        'boundary':'Actual model/browser approval, retained SQL and durable records. Core and requested literal checks are reported independently.'}
    args.output.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n');print(json.dumps(summary,ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) and all(display.values()) else 1)

if __name__=='__main__':main()
