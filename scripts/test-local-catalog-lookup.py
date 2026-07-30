#!/usr/bin/env python3
"""Exercise read-only scoped catalog endpoints against the real isolated local deployment."""
import argparse
import concurrent.futures
import json
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-id', type=int, default=2)
    parser.add_argument('--version-id', type=int, default=2)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or args.output.with_suffix('.sql').exists():
        raise ValueError('Retain earlier evidence; choose a fresh output')
    project, version = args.project_id, args.version_id
    if project <= 0 or version <= 0:
        raise ValueError('Positive project and version are required')
    sql = f"""BEGIN READ ONLY;
SELECT json_build_object('hash',(SELECT catalog_hash FROM qw_project_version WHERE project_id={project} AND id={version}),
 'models',(SELECT coalesce(json_agg(model_code ORDER BY model_code),'[]'::json) FROM qw_semantic_model
 WHERE project_id={project} AND project_version_id={version} AND status='ENABLED'));
ROLLBACK;
"""
    raw = subprocess.run(['docker','exec','-i','semevosql-acceptance-metadata-db-1','psql','-X','-q','-A','-t',
        '-v','ON_ERROR_STOP=1','-U','acceptance','-d','semevosql_acceptance'], input=sql, capture_output=True, text=True, check=True)
    authority = json.loads(raw.stdout)
    if not authority['hash'] or not authority['models']:
        raise ValueError('A real initialized catalog is required')
    args.output.with_suffix('.sql').write_text(sql)
    endpoint = f'http://127.0.0.1:18093/api/semevosql/projects/{project}/versions/{version}/semantic-catalog'
    checks = {}
    receipts = []

    def request(path, body=None):
        req = urllib.request.Request(path, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type':'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as ex:
            return ex.code, json.loads(ex.read())

    def page(cursor=None, size=1, hash_value=None):
        params = {'catalogHash':hash_value or authority['hash'], 'pageSize':size}
        if cursor is not None: params['afterModelCode'] = cursor
        return request(endpoint + '/models?' + urllib.parse.urlencode(params))

    all_codes, cursor = [], None
    for number in range(len(authority['models']) + 1):
        status, result = page(cursor)
        receipts.append({'operation':'model_page','status':status,'response':result})
        checks[f'page_{number}_valid'] = status == 200 and result.get('catalogHash') == authority['hash'] and len(result.get('models',[])) <= 1
        if not checks[f'page_{number}_valid']: break
        checks[f'page_{number}_summary_only'] = all(set(m) == {'modelCode','businessName','modelType'} for m in result['models'])
        all_codes.extend(m['modelCode'] for m in result['models'])
        if not result['hasMore']: break
        cursor = result['nextModelCode']
    checks['all_enabled_models_once_in_order'] = all_codes == authority['models']
    for label, call in [('stale_hash', lambda:page(hash_value='0'*64)),
                        ('zero_page_size', lambda:page(size=0)), ('over_page_size', lambda:page(size=101))]:
        status, result = call()
        checks[label + '_rejected'] = status in (400,409)
        receipts.append({'operation':label,'status':status,'response':result})
    body = {'catalogHash':authority['hash'], 'assets':[{'assetType':'MODEL','assetKey':authority['models'][0]}]}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _:request(endpoint+'/details',body), range(4)))
    checks['four_concurrent_detail_reads_succeeded'] = all(status == 200 for status,_ in results)
    checks['repeated_details_are_identical'] = len({json.dumps(value,sort_keys=True) for _,value in results}) == 1
    checks['details_contain_only_requested_model'] = all([m['modelCode'] for m in value.get('models',[])] == [authority['models'][0]] for _,value in results)
    receipts.append({'operation':'concurrent_details','count':len(results),'statusCodes':[s for s,_ in results],
                     'modelCodes':[[m['modelCode'] for m in v.get('models',[])] for _,v in results]})
    invalids = {
        'unknown_asset': {'catalogHash':authority['hash'],'assets':[{'assetType':'METRIC','assetKey':'missing-acceptance-asset'}]},
        'unknown_type': {'catalogHash':authority['hash'],'assets':[{'assetType':'TABLE_INJECTION','assetKey':'orders'}]},
        'empty_assets': {'catalogHash':authority['hash'],'assets':[]},
        'stale_detail_hash': dict(body,catalogHash='0'*64),
    }
    for label,payload in invalids.items():
        status, result = request(endpoint+'/details',payload)
        checks[label + '_rejected'] = status in (400,409)
        receipts.append({'operation':label,'status':status,'response':result})
    status,result=request(endpoint.replace(f'/projects/{project}/',f'/projects/{project+100000000}/')+'/details',body)
    checks['cross_project_version_rejected'] = status in (400,409)
    receipts.append({'operation':'cross_project_version','status':status,'response':result})
    status = 'PASS' if all(checks.values()) else 'FAIL'
    output = {'status':status,'checks':checks,'receipts':receipts,'authority':authority,
              'boundary':'Read-only localhost HTTP and real database oracle. Scope integrity in the current single-user deployment; not multi-user object ACL or a registered MCP tool call.'}
    args.output.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':status,'checks':len(checks),'failed':[k for k,v in checks.items() if not v]},ensure_ascii=False))
    raise SystemExit(0 if status=='PASS' else 1)


if __name__=='__main__':
    main()
