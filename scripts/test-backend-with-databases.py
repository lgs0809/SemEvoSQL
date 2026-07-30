#!/usr/bin/env python3
"""Run all backend tests, including database ITs, using disposable databases only."""
import argparse
import json
import os
import hashlib
import re
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--tests',default='*Test,*IT',help='Surefire selector; default includes every test and IT')
    parser.add_argument('--java-home',default='/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home')
    args=parser.parse_args()
    if args.output.exists(): raise ValueError('Retain earlier evidence; choose a fresh output')
    root=Path(__file__).resolve().parents[1]
    args.output.parent.mkdir(parents=True,exist_ok=True)
    reports=root/'backend/target/surefire-reports'
    archived=args.output.with_suffix('.previous-reports')
    if reports.exists():
        if archived.exists():raise ValueError('Keep previous reports; choose a new output prefix')
        shutil.move(str(reports),str(archived))
    name='semevosql-upgrade-it-'+uuid.uuid4().hex[:12]
    # These credentials exist only for the newly created disposable test database.
    password=uuid.uuid4().hex
    container=None
    try:
        container=subprocess.check_output(['docker','run','--rm','-d','--name',name,
            '--label','semevosql.purpose=disposable-upgrade-test','-p','127.0.0.1::5432',
            '-e','POSTGRES_USER=fixture','-e','POSTGRES_DB=fixture','-e','POSTGRES_PASSWORD='+password,
            'pgvector/pgvector:pg16'],text=True).strip()
        binding=json.loads(subprocess.check_output(['docker','inspect','--format',
            '{{json .NetworkSettings.Ports}}',container],text=True))['5432/tcp'][0]
        if binding['HostIp']!='127.0.0.1':raise RuntimeError('Test database must bind only to localhost')
        for _ in range(60):
            if subprocess.run(['docker','exec',container,'pg_isready','-U','fixture','-d','fixture'],capture_output=True).returncode==0:break
            time.sleep(0.5)
        else:raise RuntimeError('Disposable upgrade database did not become ready')
        env=os.environ.copy()
        env.update(JAVA_HOME=args.java_home,
            # Use the same local hostname as Testcontainers; macOS proxy exclusions commonly include localhost.
            SEMEVOSQL_UPGRADE_MATRIX_JDBC_URL='jdbc:postgresql://localhost:'+binding['HostPort']+'/fixture',
            SEMEVOSQL_UPGRADE_MATRIX_DB_USER='fixture',SEMEVOSQL_UPGRADE_MATRIX_DB_PASSWORD=password)
        with args.output.open('x') as log:
            log.write('Disposable upgrade PostgreSQL on localhost; other IT databases are owned by Testcontainers.\n');log.flush()
            started=time.time_ns()
            run=subprocess.run(['./mvnw','-o','-pl','backend','-am','-Dtest='+args.tests,
                '-Dsurefire.failIfNoSpecifiedTests=false','package'],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
        rows=[]
        for path in sorted(reports.glob('TEST-*.xml')):
            suite=ET.parse(path).getroot()
            rows.append({'class':suite.attrib['name'],**{key:int(suite.attrib[key]) for key in ('tests','failures','errors','skipped')},
                'fresh':path.stat().st_mtime_ns>=started,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
        totals={key:sum(row[key] for row in rows) for key in ('tests','failures','errors','skipped')}
        summaries=re.findall(r'^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$',args.output.read_text(),re.M)
        summary=dict(zip(('tests','failures','errors','skipped'),map(int,summaries[-1]))) if summaries else None
        verified=bool(rows) and all(row['fresh'] for row in rows) and totals==summary
        result={'exitCode':run.returncode,'log':str(args.output.resolve()),'disposableDatabase':name,
            'archivedReports':str(archived) if archived.exists() else None,'freshReportCount':len(rows),
            'totals':totals,'mavenTotals':summary,'reportsVerified':verified,'classes':rows}
        args.output.with_suffix('.result.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps({key:value for key,value in result.items() if key!='classes'}))
        raise SystemExit(run.returncode if run.returncode else 0 if verified else 1)
    finally:
        if container:
            # Stop only the exact container created above. --rm removes its synthetic test data.
            subprocess.run(['docker','stop',container],stdout=subprocess.DEVNULL,check=False)


if __name__=='__main__':main()
