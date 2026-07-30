#!/usr/bin/env python3
"""Prepare a separate legacy-format catalog for native recovery acceptance through ordinary APIs.
This does NOT claim offline v1 import compatibility and never publishes/activates or writes task outcomes.
"""
import argparse,json,urllib.request,urllib.error
from pathlib import Path

BASE='http://127.0.0.1:18093'

def api(path,body=None,method=None):
    payload=None if body is None else json.dumps(body,ensure_ascii=False).encode()
    req=urllib.request.Request(BASE+path,payload,{'Content-Type':'application/json'},method=method or ('POST' if body is not None else 'GET'))
    try:
        with urllib.request.urlopen(req,timeout=125) as response:return json.load(response)
    except urllib.error.HTTPError as error:
        detail=error.read().decode(errors='replace')
        raise RuntimeError(f'HTTP {error.code}: {detail[:1800]}') from None

def prepare(output):
    if output.exists():raise ValueError('Use a new evidence filename')
    evidence={'scope':'Existing single-table legacy catalog only; offline-v1 compatibility and publication are separate.'}
    output.parent.mkdir(parents=True,exist_ok=True)
    try:
        projects=api('/api/semevosql/projects')
        matches=[p for p in projects if p['projectCode']=='native-recovery-acceptance']
        if len(matches)>1:raise ValueError('Ambiguous fixture retained')
        if matches:project=matches[0]
        else:
            request=dict(projectCode='native-recovery-acceptance',name='SemEvoSQL 恢复机制验收',businessDomain='合成订单',
                description='独立的单表兼容目录，用于真实模型澄清、审批和重启恢复；不代表离线 v1 导入验收。',
                firstVersionNumber='1.0.0',source='LOCAL_ACCEPTANCE_LEGACY_CATALOG',datasourceBindings=[dict(datasourceId=1,
                domainCode='synthetic-orders',domainName='合成订单',responsibility='只读查询合成订单',priority=100,exposedTables=['orders'])])
            evidence['createRequest']=request
            created=api('/api/semevosql/projects',request)
            project=created['project']
        project_id=project['id'];versions=api(f'/api/semevosql/projects/{project_id}/versions')
        if len(versions)!=1:raise ValueError('Existing multiple versions retained')
        version=versions[0];version_id=version['id'];prefix=f'/api/semevosql/projects/{project_id}/versions/{version_id}'
        evidence.update(projectId=project_id,versionId=version_id)
        current=api(prefix+'/semantic-catalog')
        if current.get('models'):
            evidence['status']='EXISTING_CATALOG_RETAINED';return
        if version['status']!='DRAFT':raise ValueError('Non-draft version retained')
        api(prefix+'/analysis/start',{})
        note='合成业务夹具：deploy/acceptance/sql/business-seed.sql 与 business-oracle.sql；不是用户真实业务定义。'
        def asset(**kw):return dict(status='ENABLED',evidence=note,**kw)
        columns=[]
        for code,name,typ,role,nullable in [
            ('order_id','订单编号','BIGINT','IDENTIFIER',False),('customer_id','客户编号','BIGINT','IDENTIFIER',False),
            ('channel_id','渠道编号','BIGINT','IDENTIFIER',False),('ordered_at','下单时间','TIMESTAMP','TIME',False),
            ('paid_at','支付时间','TIMESTAMP','TIME',True),('amount','订单金额','DECIMAL','MEASURE',False),
            ('status','订单状态','TEXT','DIMENSION',False)]:
            columns.append(asset(modelCode='orders',columnName=code,businessName=name,dataType=typ,role=role,nullable=nullable,
                description=name+'。'+note,expression=code))
        catalog=dict(models=[asset(datasourceId=1,modelCode='orders',physicalTable='orders',businessName='合成订单',
            modelType='FACT',description='每笔订单一条。amount 单位为元；包含 PAID、CANCELLED、UNPAID。支付金额只统计 PAID，退款另记。')],
            columns=columns,metrics=[
                asset(modelCode='orders',metricCode='paid_amount',businessName='支付金额',expression='SUM(amount)',aggregation='SUM',
                    unit='元',timeColumn='paid_at',filterExpression="status = 'PAID'",additiveType='ADDITIVE',description='按支付时间统计已支付订单金额，不扣退款。'),
                asset(modelCode='orders',metricCode='ordered_amount',businessName='下单金额',expression='SUM(amount)',aggregation='SUM',
                    unit='元',timeColumn='ordered_at',filterExpression='',additiveType='ADDITIVE',description='按下单时间统计全部订单金额，包括未支付和取消订单。')],
            dimensions=[asset(modelCode='orders',dimensionCode='order_status',businessName='订单状态',columnName='status',
                expression='status',dimensionType='CATEGORICAL',description='PAID 已支付，CANCELLED 已取消，UNPAID 未支付。')],
            relationships=[],grains=[asset(modelCode='orders',grainCode='order',keyColumns='order_id',timeColumn='paid_at',
                uniquenessRule='order_id UNIQUE NOT NULL',description='每笔订单一行，order_id 是主键。')],
            enumValues=[asset(modelCode='orders',columnName='status',valueCode=code,businessName=name,aliases=name,
                description='合成订单状态 '+name,sortOrder=i) for i,(code,name) in enumerate([('PAID','已支付'),('CANCELLED','已取消'),('UNPAID','未支付')])],rules=[])
        evidence['legacyCatalog']=catalog
        saved=api(prefix+'/semantic-catalog',catalog,'PUT');evidence['savedCounts']={k:len(saved[k]) for k in ['models','columns','metrics','dimensions','enumValues']}
        evidence['readiness']=api(prefix+'/semantic-catalog/readiness')
        completed=api(prefix+'/analysis/complete',{});evidence['version']=completed['version']
        evidence['status']='DRAFT_READY_FOR_BROWSER_VALIDATION_AND_PUBLICATION'
    finally:output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:evidence[k] for k in ['status','projectId','versionId','savedCounts']},ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();prepare(args.output)
