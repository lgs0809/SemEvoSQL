#!/usr/bin/env python3
"""Seed only the isolated acceptance database; retain rows on conflict and verify actual values."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CONTAINER = 'semevosql-acceptance-metadata-db-1'
DB = 'semevosql_acceptance_business'
READER = 'semevosql_acceptance_reader'
PRIVATE = ROOT / 'deploy/.env.acceptance-business.local'
TABLES = ['customers','customer_extensions','channels','orders','refunds','marketing_spend','customer_extensions_duplicate']


def sql(query, database='semevosql_acceptance', user='acceptance', password=None, check=True):
    args = ['docker','exec','-i']
    if password:
        args += ['-e','PGPASSWORD']
    args += [CONTAINER,'psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-U',user,'-d',database]
    if password:
        args += ['-h','127.0.0.1']
    result = subprocess.run(args,input=query,text=True,capture_output=True,
        env={**os.environ,**({'PGPASSWORD':password} if password else {})},timeout=60)
    if check and result.returncode:
        # Never echo SQL text containing a password.
        raise RuntimeError('Acceptance database operation failed: ' + result.stderr.splitlines()[0][:120])
    return result


def main(output):
    if output.exists():
        raise ValueError('Use a fresh evidence filename')
    if not PRIVATE.exists():
        with os.fdopen(os.open(PRIVATE,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as f:
            f.write('SEMEVOSQL_BUSINESS_PASSWORD='+secrets.token_hex(24)+'\n')
    password = PRIVATE.read_text().strip().split('=',1)[1]
    if sql("SELECT 1 FROM pg_roles WHERE rolname='"+READER+"'").stdout.strip() != '1':
        sql('CREATE ROLE '+READER+" LOGIN PASSWORD '"+password+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;")
    if sql("SELECT 1 FROM pg_database WHERE datname='"+DB+"'").stdout.strip() != '1':
        sql('CREATE DATABASE '+DB+' OWNER acceptance;')
    sql('REVOKE CONNECT ON DATABASE semevosql_acceptance FROM PUBLIC; REVOKE ALL ON DATABASE '+DB+' FROM PUBLIC; GRANT CONNECT ON DATABASE '+DB+' TO '+READER+';')
    seed = (ROOT/'deploy/acceptance/sql/business-seed.sql').read_text()
    sql(seed,DB)
    sql('REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO '+READER+'; GRANT SELECT ON '+','.join(TABLES)+' TO '+READER+';',DB)
    def snapshot():
        return {table:sql('SELECT coalesce(jsonb_agg(t ORDER BY '+ {'customers':'customer_id','customer_extensions':'customer_id','channels':'channel_id','orders':'order_id','refunds':'refund_id','marketing_spend':'spend_id','customer_extensions_duplicate':'evidence_id'}[table]+"),'[]'::jsonb) FROM "+table+' t',DB).stdout.strip() for table in TABLES}
    before=snapshot()
    sql(seed,DB)
    after=snapshot()
    oracle=sql((ROOT/'deploy/acceptance/sql/business-oracle.sql').read_text(),DB,READER,password).stdout.strip()
    expected='华东|150.00\n华南|80.00\n未知地区|40.00\n2026-01-01|270.00\n2026-02-01|300.00\n2026-03-01|360.00\n250.00'
    denial=sql("BEGIN; INSERT INTO customers VALUES(99999,'must not persist','2026-01-01'); ROLLBACK;",DB,READER,password,False)
    metadata=sql('SELECT 1','semevosql_acceptance',READER,password,False)
    checks={'repeatSeedPreservesAllRows':before==after,'businessOracle':oracle==expected,
        'readerWriteDenied':denial.returncode!=0 and 'permission denied' in denial.stderr,
        'readerMetadataDenied':metadata.returncode!=0 and 'permission denied' in metadata.stderr,
        'negativeWriteDidNotPersist':sql('SELECT count(*) FROM customers WHERE customer_id=99999',DB).stdout.strip()=='0'}
    result={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,
        'counts':{t:len(json.loads(v)) for t,v in after.items()},'oracle':oracle,
        'seedSha256':hashlib.sha256(seed.encode()).hexdigest(),'dataSha256':hashlib.sha256(json.dumps(after,sort_keys=True).encode()).hexdigest(),
        'database':DB,'reader':READER,'fixture':'SYNTHETIC_BUSINESS_ONLY_NO_RUN_OR_APPROVAL_STATES'}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False))
    if not all(checks.values()):
        raise SystemExit(1)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True,type=Path)
    main(p.parse_args().output)
