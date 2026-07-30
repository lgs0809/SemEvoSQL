#!/usr/bin/env python3
"""Read-only before/after fingerprints of the isolated published catalog, including raw rows."""
import argparse,hashlib,json
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
sql=SourceFileLoader('offline_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--baseline',type=Path);a=p.parse_args()
    if a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Retain existing proof; choose a new path')
    c=LocalAcceptanceClient();queries={};facts={}
    for version in (1,4):
        def digest(obj):return hashlib.sha256(json.dumps(obj,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        view=c.request(f'/api/semevosql/projects/1/versions/{version}')
        facts[str(version)]={'status':view['status'],'catalogHash':view['catalogHash'],
          'apiSnapshotSha256':digest(c.request(f'/api/semevosql/projects/1/versions/{version}/semantic-catalog')),'rawTables':{}}
        for table in ('model','column','metric','dimension','relationship','grain','enum_value','rule'):
            query=f'SELECT * FROM qw_semantic_{table} WHERE project_id=1 AND project_version_id={version} ORDER BY id'
            queries[f'{version}/{table}']=query;rows=sql('semevosql_acceptance',query)
            facts[str(version)]['rawTables'][table]={'rows':len(rows),'sha256':digest(rows)}
    a.output.with_suffix('.sql').write_text('\n\n'.join(q+';' for q in queries.values())+'\n')
    checks={'both_historical_versions_published':all(v['status']=='PUBLISHED' for v in facts.values())}
    if a.baseline:checks['all_old_facts_and_fingerprints_unchanged']=facts==json.loads(a.baseline.read_bytes())['facts']
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'facts':facts,
      'boundary':'Read-only evidence; no business rows or publication states changed.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':report['status'],'checks':checks,'output':str(a.output)}));raise SystemExit(0 if all(checks.values()) else 1)
if __name__=='__main__':main()
