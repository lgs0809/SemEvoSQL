#!/usr/bin/env python3
"""Register the isolated quality database through normal local APIs and retain test-account scopes.

Creates only a datasource and an empty draft project. It never imports, publishes, approves, creates
Run outcomes or writes semantic state. Existing accounts and passwords are retained; only the named
synthetic fixture becomes accessible to the existing owner and three test members after redeployment.
"""
import argparse, json, os, re
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient, ROOT

sql=SourceFileLoader('quality_project_sql',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql
CODE='semevosql-quality-v1'
DATABASE='semevosql_quality_business_v1'
READER='semevosql_quality_reader_v1'
NAME='SemEvoSQL 三个月关联业务验收'
MEMBERS=('semevosql-acceptance-owner','sem_member_a','sem_member_b','sem_member_c')

def main():
    global CODE, DATABASE, READER, NAME
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--namespace',help='Same isolated namespace passed to seed-quality-business.py')
    args=parser.parse_args()
    if args.namespace:
        if not re.fullmatch(r'[a-z][a-z0-9_]{0,23}',args.namespace):raise ValueError('Invalid synthetic namespace')
        CODE='semevosql-quality-'+args.namespace;DATABASE='semevosql_quality_'+args.namespace
        READER='semevosql_reader_'+args.namespace;NAME='SemEvoSQL 独立初始化 '+args.namespace
    if args.output.exists():raise ValueError('Retain earlier setup evidence; choose a fresh output')
    if sql(DATABASE,"SELECT fixture FROM fixture_identity WHERE id=1")!=[{'fixture':'SEMEVOSQL_QUALITY_V1'}]:
        raise ValueError('Expected isolated synthetic business database')
    client=LocalAcceptanceClient()
    sources=[x for x in client.request('/api/datasource') if x['databaseName']==DATABASE]
    if len(sources)>1:raise ValueError('Ambiguous existing quality datasource')
    if sources:
        source=sources[0]
        if source['name']!=NAME or source['username']!=READER or source['host']!='metadata-db' or source['port']!=5432:
            raise ValueError('Retain differently configured existing datasource')
    else:
        password_file=ROOT/('deploy/.env.acceptance-quality-'+args.namespace+'.local' if args.namespace else 'deploy/.env.acceptance-quality.local')
        password=password_file.read_text().strip().split('=',1)[1]
        source=client.request('/api/datasource','POST',{'name':NAME,'type':'postgresql','host':'metadata-db',
            'port':5432,'databaseName':DATABASE,'username':READER,'password':password,
            'description':'独立合成质量数据：200客户、50商品、2000订单、4000明细、204退款；仅只读访问。'})
    test=client.request(f'/api/datasource/{source["id"]}/test','POST')
    if not test.get('success'):raise ValueError('Actual readonly datasource connection failed')
    matches=sql('semevosql_acceptance',f"SELECT id,project_code,name FROM qw_project WHERE project_code='{CODE}'")
    if len(matches)>1:raise ValueError('Ambiguous quality project identity')
    if not matches:
        view=client.request('/api/semevosql/projects','POST',{'projectCode':CODE,'name':NAME,
            'businessDomain':'synthetic-commerce-quality','description':'仅合成验收。五类业务对象、三个业务月份及边界；从真实元信息和建模材料离线初始化。',
            'firstVersionNumber':'1.0.0','source':'deploy/acceptance/sql/quality-business-seed.sql',
            'datasourceBindings':[{'datasourceId':source['id'],'domainCode':'quality-commerce','domainName':'合成交易业务',
                'responsibility':'只读查询合成客户、商品、订单、明细、退款；不连接元数据库。','priority':100,
                'exposedTables':['customers','products','orders','order_items','refunds']}]})
        project=view['project']
        project_id=project['id']
    else:
        project_id=matches[0]['id']
        if matches[0]['name']!=NAME:raise ValueError('Existing project has different identity')
    credentials=ROOT/'deploy/.acceptance-private/accounts.json'
    records=json.loads(credentials.read_bytes())
    if records['projectId']!=2:raise ValueError('Retain original account registry')
    def config(value):
        return {'semevosql':{'security':{'enabled':True,'accounts':{r['username']:{k:r[k]
            for k in ('passwordHash','administrator','projectIds')} for r in value['accounts']}}}}
    env=ROOT/'deploy/.env.acceptance.local'
    previous_env=env.read_text()
    key='SEMEVOSQL_LOCAL_SECURITY_JSON'
    old_line=key+"='"+json.dumps(config(records),separators=(',',':'))+"'"
    if [line for line in previous_env.splitlines() if line.startswith(key+'=')]!=[old_line]:
        raise ValueError('Account configuration diverged; refusing to replace it')
    before=json.loads(json.dumps(records))
    changed=[]
    for name in MEMBERS:
        member=next(r for r in records['accounts'] if r['username']==name)
        if project_id not in member['projectIds']:
            member['projectIds'].append(project_id);changed.append(name)
    if changed:
        suffix='-'+args.namespace if args.namespace else ''
        for path,content in ((credentials.with_name('accounts-before-quality'+suffix+'.json'),json.dumps(before,indent=2)),
                (credentials.with_name('env-before-quality'+suffix+'.local'),previous_env)):
            if not path.exists():
                with os.fdopen(os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as f:f.write(content)
        new_line=key+"='"+json.dumps(config(records),separators=(',',':'))+"'"
        for path,content in ((credentials,json.dumps(records,indent=2)+'\n'),(env,previous_env.replace(old_line,new_line))):
            tmp=path.with_name(path.name+'.quality-tmp')
            with os.fdopen(os.open(tmp,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as f:f.write(content)
            os.replace(tmp,path)
    versions=sql('semevosql_acceptance',f'SELECT id,status FROM qw_project_version WHERE project_id={int(project_id)} ORDER BY id')
    report={'status':'REGISTERED_DRAFT_REQUIRES_DEPLOYMENT_AND_NORMAL_INITIALIZATION','projectId':project_id,
        'projectCode':CODE,'datasourceId':source['id'],'database':DATABASE,'versions':versions,
        'changedTestMemberships':changed,'retainedOriginalProjectId':2,'credentialsPrinted':False,
        'boundary':'Normal datasource/project APIs; no semantic import, publication, approval or successful Run is manufactured.'}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':main()
