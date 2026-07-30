#!/usr/bin/env python3
"""Export current metadata and build a clearly synthetic catalog for the existing business seed.

Only reads normal authenticated APIs and creates local files. Import, validation, publication and
activation are deliberately performed through their normal product UI. Existing files are retained.
"""
import argparse
import hashlib
import json
from pathlib import Path
from acceptance_http import LocalAcceptanceClient

ROOT=Path(__file__).resolve().parents[1]


def prepare(project,version,output,format_version='1.0'):
    output.mkdir(parents=True,exist_ok=True)
    destinations=[output/name for name in ('source-schema.json','semantic-catalog.json','initialization-notes.md')]
    if any(p.exists() for p in destinations):raise ValueError('Retain prior fixture files; choose an unused output directory')
    client=LocalAcceptanceClient()
    view=client.request(f'/api/semevosql/projects/{project}')
    if project!=1 or view['project']['projectCode']!='semevosql':
        raise ValueError('This fixture is restricted to the named isolated synthetic business project')
    source=client.request(f'/api/semevosql/projects/{project}/versions/{version}/offline-catalog/source-schema')
    available={table['table']:table for table in source['tables']}
    needed={'customers','customer_extensions','orders'}
    if not needed<=available.keys():raise ValueError('Expose the existing synthetic seed tables before export')
    fingerprint='sha256:'+hashlib.sha256(json.dumps(source,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()
    def table(alias,name):return dict(alias=alias,**{k:available[name][k] for k in ('datasource','schema','table')})
    def attr(code,name,alias,physical,description,unit=None,enums=None):
        column=next(c for c in available[{'o':'orders','c':'customers','x':'customer_extensions'}[alias]]['columns'] if c['name']==physical)
        row={'code':code,'name':name,'description':description,'mapping':{'source':alias,'column':physical},'dataType':column['dataType']}
        if unit:row['unit']=unit
        if enums:row['enumValues']=[{'value':value,'label':label} for value,label in enums]
        return row
    orders=[attr('order_id','Order identifier','o','order_id','One synthetic order'),
        attr('customer_id','Customer identifier','o','customer_id','Purchasing customer identity'),
        attr('channel_id','Channel identifier','o','channel_id','Acquisition channel of this order'),
        attr('ordered_at','Order time','o','ordered_at','Creation time of the order'),
        attr('paid_at','Payment time','o','paid_at','Successful payment time; unpaid orders have NULL'),
        attr('amount','Order amount','o','amount','Amount stored in yuan; refunds are separate rows','yuan'),
        attr('status','Order status','o','status','Synthetic seed state, not an inferred production enumeration',enums=[('PAID','Paid'),('CANCELLED','Cancelled'),('UNPAID','Unpaid')])]
    customer=[attr('customer_id','Customer identifier','c','customer_id','One synthetic customer'),
        attr('customer_name','Customer name','c','customer_name','Synthetic customer display name'),
        attr('registered_at','Registration date','c','created_at','Customer registration date'),
        attr('region','Customer region','x','region','Customer extension region; missing extension remains NULL/unknown'),
        attr('segment','Customer segment','x','segment','Segment from the unique customer extension')]
    entities=[{'code':'sales_orders','name':'Seed sales orders','description':'All synthetic orders; paid amounts require status PAID and payment time.',
        'grain':'One order per row','primaryKey':['order_id'],'source':{'tables':[table('o','orders')],'base':'o','joins':[]},'attributes':orders,'filters':[]},
        {'code':'customer_profile','name':'Seed customer profile','description':'Customer identity plus optional unique extension. Missing extension retains the customer.',
        'grain':'One customer per row','primaryKey':['customer_id'],'source':{'tables':[table('c','customers'),table('x','customer_extensions')],'base':'c',
        'joins':[{'type':'left','right':'x','on':[{'left':{'source':'c','column':'customer_id'},'right':{'source':'x','column':'customer_id'}}]}]},'attributes':customer,'filters':[]},
        {'code':'cancelled_orders','name':'Seed cancelled orders','description':'A distinct entity on the same physical orders table, restricted to CANCELLED.',
        'grain':'One cancelled order per row','primaryKey':['order_id'],'source':{'tables':[table('o','orders')],'base':'o','joins':[]},'attributes':orders,
        'filters':[{'attribute':'status','operator':'eq','value':'CANCELLED'}]}]
    metrics=[{'code':'paid_amount','name':'Paid amount','description':'Sum order amount in yuan for PAID orders, attributed to payment time; no refund deduction.',
        'entity':'sales_orders','expression':{'op':'sum','arg':{'attribute':'amount'}},'filters':[{'attribute':'status','operator':'eq','value':'PAID'}],'timeAttribute':'paid_at','unit':'yuan'},
        {'code':'cancelled_count','name':'Cancelled order count','description':'Count the cancelled-order entity, attributed to order creation time.',
        'entity':'cancelled_orders','expression':{'op':'count_rows'},'filters':[],'timeAttribute':'ordered_at','unit':'orders'},
        {'code':'customer_count','name':'Customer count','description':'Count synthetic customer identities; retain missing optional extensions.',
        'entity':'customer_profile','expression':{'op':'count_rows'},'filters':[],'timeAttribute':None,'unit':'customers'}]
    dimensions=[{'code':'customer_region','name':'Customer region','entity':'customer_profile','attribute':'region','description':'Region in unique customer extension; NULL is unknown.'},
        {'code':'order_customer','name':'Order customer identifier','entity':'sales_orders','attribute':'customer_id','description':'Customer identity recorded on the order.'}]
    relationships=[{'code':'order_customer_profile','name':'Orders connect to one customer identity','fromEntity':'sales_orders','toEntity':'customer_profile','cardinality':'many_to_one',
        'pairs':[{'from':'customer_id','to':'customer_id'}]}]
    catalog={'entities':entities,'metrics':metrics,'dimensions':dimensions,'relationships':relationships,'rules':[]}
    targets=[]
    for group,kind in [('entities','entity'),('metrics','metric'),('dimensions','dimension'),('relationships','relationship')]:
        for asset in catalog[group]:
            targets.append(f'{kind}:{asset["code"]}')
            if kind=='entity':targets.extend(f'entity:{asset["code"]}/attribute:{a["code"]}' for a in asset['attributes'])
    evidence=[{'target':target,'kind':'document','source':'deploy/acceptance/sql/business-seed.sql','location':'Synthetic acceptance fixture',
        'statement':'Synthetic development fixture defined by the retained seed and independent oracle; not a user-confirmed production definition.'} for target in targets]
    if format_version in ('1.1','1.2'):
        for entity in entities:
            entity['retrieval']={'queryExpressions':[{'sales_orders':'查询已付款订单销售金额','customer_profile':'按客户地区统计注册客户','cancelled_orders':'统计已经取消的订单数量'}[entity['code']]],
                'queryContexts':['明确标记的合成本地验收业务']}
        metrics[0]['retrieval']={'queryExpressions':['已付款订单一共收了多少钱'],'queryContexts':['按付款时间统计，以元显示，不扣退款']}
        metrics[1]['retrieval']={'queryExpressions':['一共有多少笔取消订单'],'queryContexts':['按订单创建时间统计，模型固定CANCELLED条件']}
        metrics[2]['retrieval']={'queryExpressions':['注册客户有多少人'],'queryContexts':['缺少客户扩展记录的客户仍计数']}
        metrics[2]['valueRange']={'minimum':0,'minimumInclusive':True}
        customer[3]['retrieval']={'queryExpressions':['客户所在地区'],'queryContexts':['扩展表缺失时保留NULL']}
        dimensions[0]['retrieval']={'queryExpressions':['各地区客户数量'],'queryContexts':['以客户地区分组']}
    if format_version == '1.2':
        catalog['definitions']=[
            {'code':'entity_order_amount','revision':1,'type':'METRIC','name':'实体范围内订单创建金额',
             'description':'按当前逻辑模型固定人群汇总订单金额，单位元，时间归属订单创建时间，不扣退款。',
             'aliases':['订单创建金额'],'retrieval':{'queryExpressions':['这个模型内订单总额是多少'],'queryContexts':['按逻辑实体明确的固定人群统计']},
             'specification':{'parameters':[{'code':'identity','dataType':'integer'},{'code':'amount','dataType':'decimal'},{'code':'time','dataType':'datetime'}],
                 'grainKeys':['identity'],'expression':{'op':'sum','arg':{'attribute':'amount'}},'filters':[],'timeAttribute':'time','unit':'yuan'}},
            {'code':'order_state_role','revision':1,'type':'DIMENSION','name':'订单状态',
             'description':'订单状态代码的含义由合成种子显式定义，每个模型内保留独立状态角色。','aliases':['订单业务状态'],
             'specification':{'parameters':[{'code':'state','dataType':'string'}],'attribute':'state'}},
            {'code':'customer_identity','revision':1,'type':'ATTRIBUTE','name':'客户标识概念','description':'客户标识整数代码；绑定区分购买者与注册主体，引用不增加JOIN。','aliases':[],
             'specification':{'parameters':[{'code':'identity','dataType':'integer'}],'attribute':'identity'}}]
        catalog['enumDictionaries']=[{'code':'order_state','revision':1,'name':'合成订单状态字典','description':'付款、取消和未付款三种合成种子状态，未推断生产代码。',
            'entries':[{'value':v,'label':label,'aliases':[alias]} for v,label,alias in [('PAID','Paid','已支付'),('CANCELLED','Cancelled','已取消'),('UNPAID','Unpaid','未支付')]]}]
        catalog['bindings']=[]
        for model,role in [('sales_orders','全部订单'),('cancelled_orders','已取消订单')]:
            catalog['bindings'].append({'model':model,'code':'amount_total','definition':'entity_order_amount','definitionRevision':1,'roleName':role+'创建金额',
                'aliases':[role+'总金额'],'attributeMappings':{'identity':'order_id','amount':'amount','time':'ordered_at'}})
            catalog['bindings'].append({'model':model,'code':'state','definition':'order_state_role','definitionRevision':1,'roleName':role+'业务状态',
                'aliases':[],'attributeMappings':{'state':'status'},'dictionary':{'code':'order_state','revision':1,'valueMappings':[]}})
        for model,role in [('sales_orders','订单购买客户标识'),('cancelled_orders','取消订单购买客户标识'),('customer_profile','注册客户主体标识')]:
            catalog['bindings'].append({'model':model,'code':'customer_identity','definition':'customer_identity','definitionRevision':1,'roleName':role,'aliases':[],
                'attributeMappings':{'identity':'customer_id'}})
        targets.extend('definition:'+d['code']+'@'+str(d['revision']) for d in catalog['definitions'])
        targets.extend('dictionary:'+d['code']+'@'+str(d['revision']) for d in catalog['enumDictionaries'])
        targets.extend('binding:'+b['model']+'/'+b['code'] for b in catalog['bindings'])
        evidence=[{'target':target,'kind':'document','source':'deploy/acceptance/sql/business-seed.sql','location':'Synthetic acceptance fixture',
            'statement':'Synthetic development fixture defined by the retained seed and independent oracle; not a user-confirmed production definition.'} for target in targets]
    pack={'formatVersion':format_version,'sourceSchemaFingerprint':fingerprint,'catalog':catalog,'evidence':evidence,'unresolvedIssues':[]}
    for path,value in zip(destinations[:2],[source,pack]):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
    destinations[2].write_text('Synthetic seed modeling fixture. Uses actual server-exported JDBC metadata.\n'
        'Three entities: multi-table customer profile, all orders, fixed-filter cancelled orders on the same table.\n'
        'No business data, credentials or publication state are changed by this script.\n'
        'Run the frozen Skill validator against these exact files, then preview/import through the project page.\n')
    print(json.dumps({'projectId':project,'versionId':version,'sourceFingerprint':fingerprint,'entities':len(entities),'metrics':len(metrics),'output':str(output),'status':'FILES_CREATED_REQUIRES_VALIDATION_AND_UI_IMPORT'}))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--project',type=int,default=1);p.add_argument('--version',type=int,default=1);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--format-version',choices=['1.0','1.1','1.2'],default='1.0')
    a=p.parse_args();prepare(a.project,a.version,a.output,a.format_version)
