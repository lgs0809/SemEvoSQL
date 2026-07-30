#!/usr/bin/env python3
"""Reproducible isolated 200/50/2000 quality data; never write Run/approval/semantic state."""
import argparse,hashlib,json,os,re,secrets,subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
CONTAINER='semevosql-acceptance-metadata-db-1'
DB='semevosql_quality_business_v1';READER='semevosql_quality_reader_v1'
PRIVATE=ROOT/'deploy/.env.acceptance-quality.local'
TABLES={'customers':'customer_id','products':'product_id','orders':'order_id','order_items':'item_id','refunds':'refund_id'}

def sql(query,database='semevosql_acceptance',reader_password=None,check=True):
    args=['docker','exec','-i']
    if reader_password:args+=['-e','PGPASSWORD']
    args+=[CONTAINER,'psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-U',READER if reader_password else 'acceptance','-d',database]
    if reader_password:args+=['-h','127.0.0.1']
    result=subprocess.run(args,input=query,text=True,capture_output=True,timeout=60,
        env={**os.environ,**({'PGPASSWORD':reader_password} if reader_password else {})})
    if check and result.returncode:raise RuntimeError('Isolated quality database operation failed: '+result.stderr.splitlines()[0][:120])
    return result

def main():
    global DB, READER, PRIVATE
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--namespace',help='Create a separate synthetic business database; lower-case letters, digits and underscores only')
    a=p.parse_args()
    if a.namespace:
        if not re.fullmatch(r'[a-z][a-z0-9_]{0,23}',a.namespace):raise ValueError('Invalid synthetic namespace')
        DB='semevosql_quality_'+a.namespace;READER='semevosql_reader_'+a.namespace
        PRIVATE=ROOT/('deploy/.env.acceptance-quality-'+a.namespace+'.local')
    if a.output.exists():raise ValueError('Use a fresh report; earlier evidence is retained')
    existing=sql("SELECT 1 FROM pg_database WHERE datname='"+DB+"'").stdout.strip()=='1'
    if existing:
        identity=sql("SELECT fixture FROM public.fixture_identity WHERE id=1",DB,check=False)
        if identity.returncode or identity.stdout.strip()!='SEMEVOSQL_QUALITY_V1':
            raise ValueError('Existing database has no matching synthetic fixture identity; refusing to seed')
    else:sql('CREATE DATABASE '+DB+' OWNER acceptance;')
    if not PRIVATE.exists():
        with os.fdopen(os.open(PRIVATE,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as f:
            f.write('SEMEVOSQL_QUALITY_PASSWORD='+secrets.token_hex(24)+'\n')
    password=PRIVATE.read_text().strip().split('=',1)[1]
    if sql("SELECT 1 FROM pg_roles WHERE rolname='"+READER+"'").stdout.strip()!='1':
        sql('CREATE ROLE '+READER+" LOGIN PASSWORD '"+password+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;")
    seed=(ROOT/'deploy/acceptance/sql/quality-business-seed.sql').read_text()
    sql(seed,DB)
    sql('REVOKE ALL ON DATABASE '+DB+' FROM PUBLIC; GRANT CONNECT ON DATABASE '+DB+' TO '+READER+';',DB)
    sql('REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO '+READER+
        '; GRANT SELECT ON '+','.join(TABLES)+' TO '+READER+';',DB)
    def snapshot():
        return {table:sql('SELECT COALESCE(jsonb_agg(t ORDER BY '+key+"),'[]'::jsonb) FROM "+table+' t',DB).stdout.strip()
            for table,key in TABLES.items()}
    before=snapshot();sql(seed,DB)
    maintenance=(ROOT/'deploy/acceptance/sql/quality-business-maintenance.sql').read_text()
    sql(maintenance,DB);after=snapshot()
    oracle=sql((ROOT/'deploy/acceptance/sql/quality-business-oracle.sql').read_text(),DB,password).stdout.strip()
    expected='2025-12|100.00\n2026-01|250.00\n2026-02|30.00\n2026-03|80.00\n2026-04|40.00\n250.00\n300.00\n180.00'
    counts={k:len(json.loads(v)) for k,v in after.items()}
    denied=sql("BEGIN; INSERT INTO customers VALUES(999999,'must not persist',NULL,'2026-01-01'); ROLLBACK;",DB,password,False)
    statistics={table:int(sql("SELECT reltuples::bigint FROM pg_class WHERE oid='public."+table+"'::regclass",DB).stdout.strip()) for table in TABLES}
    checks={'repeat_seed_preserves_existing_rows':before==after,'business_statistics_current':statistics==counts,'independent_boundary_oracle':oracle==expected,
        'business_scale':all(counts[k]>=v for k,v in {'customers':200,'products':50,'orders':2000,'order_items':4000,'refunds':204}.items()),
        'reader_write_denied':denied.returncode!=0 and 'permission denied' in denied.stderr,
        'negative_write_did_not_persist':sql('SELECT count(*) FROM customers WHERE customer_id=999999',DB).stdout.strip()=='0',
        'reader_metadata_denied':sql('SELECT 1','semevosql_acceptance',password,False).returncode!=0,
        'foreign_keys_and_nullable_edges_present':sql("SELECT (SELECT count(*) FROM orders WHERE amount IS NULL)>0 AND (SELECT count(*) FROM customers WHERE region IS NULL)>0 AND (SELECT count(*) FROM order_items i LEFT JOIN orders o USING(order_id) WHERE o.order_id IS NULL)=0",DB).stdout.strip()=='t'}
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'counts':counts,'database':DB,
        'reader':READER,'oracle':oracle,'seedSha256':hashlib.sha256(seed.encode()).hexdigest(),
        'maintenanceSha256':hashlib.sha256(maintenance.encode()).hexdigest(),'statisticsEstimatedRows':statistics,
        'dataSha256':hashlib.sha256(json.dumps(after,sort_keys=True).encode()).hexdigest(),
        'boundary':'Synthetic business data only. No semantic initialization, model quality, Run success, publication or approval is implied.'}
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False));raise SystemExit(0 if all(checks.values()) else 1)

if __name__=='__main__':main()
