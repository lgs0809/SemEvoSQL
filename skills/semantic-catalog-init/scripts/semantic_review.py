"""Evidence-linked review inventory, not a business-quality score or an automatic confirmation.

The initialization model reviews these facts against supplied materials and actual requirements.
The program never guesses business meaning from names or sample values.
"""


def review_inventory(package, source):
    evidence = {}
    for item in package.get('evidence', []):
        evidence.setdefault(item['target'], []).append(item)
    rows = []
    def add(target, facts, aspects):
        rows.append(dict(target=target, facts=facts, evidence=evidence.get(target, []),
                         reviewAspects=aspects, businessDecision='requires_model_review'))
    catalog = package['catalog']
    for entity in catalog['entities']:
        target = 'entity:' + entity['code']
        add(target, {k: entity[k] for k in ('name','description','grain','primaryKey','source','filters')},
            ['业务对象与公开范围','身份与每行粒度','来源及固定人群','连接唯一性与缺失匹配','资料冲突及明确排除项'])
        for attribute in entity['attributes']:
            add(target+'/attribute:'+attribute['code'], attribute,
                ['公开属性和物理字段映射','业务含义与类型','单位与存储尺度是否适用','枚举代码与角色','空值及权限边界'])
    for kind in ('metrics','dimensions','relationships','rules'):
        prefix = dict(metrics='metric',dimensions='dimension',relationships='relationship',rules='rule')[kind]
        for asset in catalog[kind]:
            add(prefix+':'+asset['code'], asset,
                ['需求与权威口径是否一致','公式及依赖','时间归属','过滤人群','单位与输出尺度','粒度或基数','空值与异常边界'])
    for definition in catalog.get('definitions', []):
        add('definition:'+definition['code']+'@'+str(definition['revision']), definition,
            ['可复用含义及参数类型','公式单位时间过滤粒度','已确认别名与辅助检索描述的区别','修订身份与证据'])
    for binding in catalog.get('bindings', []):
        add('binding:'+binding['model']+'/'+binding['code'], binding,
            ['模型内业务角色','显式字段映射与转换','定义修订适用性','字典代码映射','不推导额外关联'])
    for dictionary in catalog.get('enumDictionaries', []):
        add('dictionary:'+dictionary['code']+'@'+str(dictionary['revision']), dictionary,
            ['代码体系及说明','已确认名称与别名','复用角色可不同且不意味着JOIN'])
    return dict(scope='initialization-model-review', businessQuality='not_assessed_by_program',
                sourceSchemaFingerprint=package['sourceSchemaFingerprint'], reviewTargets=rows,
                unresolvedIssues=package['unresolvedIssues'],
                nextStep='模型结合业务资料和具体需求逐项复核，只有会改变结果且证据不足的事实才问询；修改后重新验证。')
