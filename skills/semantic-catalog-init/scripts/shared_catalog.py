"""Version 1.2 shared definitions and explicit model roles; read-only deterministic expansion."""
import copy,hashlib,json


def require(condition, code):
    if not condition:raise ValueError(code)


def asset_code(model, binding):
    encoded=json.dumps([model,binding],ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
    return 'b_'+hashlib.sha256(encoded).hexdigest()[:32]


def indexed(rows, revisioned=False):
    found={}
    for row in rows:
        key=row['code']+('@'+str(row['revision']) if revisioned else '')
        require(key not in found,'DUPLICATE_SHARED_ID');found[key]=row
    return found


def expand_shared(original):
    pack=copy.deepcopy(original);catalog=pack['catalog'];definitions=indexed(catalog['definitions'],True)
    dictionaries=indexed(catalog['enumDictionaries'],True);entities=indexed(catalog['entities']);bindings=set()
    targets={*('definition:'+k for k in definitions),*('dictionary:'+k for k in dictionaries)}
    evidence={}
    for row in original['evidence']:evidence.setdefault(row['target'],[]).append(row)
    for target in targets:require(target in evidence,'MISSING_SHARED_EVIDENCE')

    def mapped(mapping, key):
        require(key in mapping,'UNKNOWN_DEFINITION_PARAMETER');return mapping[key]

    def rewrite(value,mapping):
        if isinstance(value,dict):
            require('metric' not in value,'SHARED_METRIC_REFERENCE_REQUIRES_EXPLICIT_BINDING')
            return {k:(mapped(mapping,v) if k in ('attribute','timeAttribute') and v is not None else rewrite(v,mapping)) for k,v in value.items()}
        if isinstance(value,list):return [rewrite(v,mapping) for v in value]
        return value

    def compatible(value,datatype):
        if datatype=='integer':return isinstance(value,int) and not isinstance(value,bool)
        if datatype=='decimal':return isinstance(value,(int,float)) and not isinstance(value,bool)
        if datatype=='boolean':return isinstance(value,bool)
        return isinstance(value,str)

    def literal(value):return json.dumps(value,ensure_ascii=False,separators=(',',':'))

    def attach_dictionary(attribute,dictionary,use):
        entries={}
        for entry in dictionary['entries']:
            key=literal(entry['value']);require(key not in entries,'DUPLICATE_DICTIONARY_CODE');entries[key]=entry
        values=[];seen=set()
        for mapping in use['valueMappings'] or [{'source':e['value'],'target':e['value']} for e in entries.values()]:
            key=literal(mapping['source']);require(key not in seen,'DUPLICATE_DICTIONARY_MAPPING');seen.add(key)
            entry=entries.get(literal(mapping['target']));require(entry is not None,'UNKNOWN_DICTIONARY_CODE')
            require(compatible(mapping['source'],attribute['dataType']),'DICTIONARY_SOURCE_TYPE')
            values.append({'value':mapping['source'],'label':entry['label']})
        labels={literal(v['value']):v['label'] for v in values}
        for old in attribute.get('enumValues',[]):require(labels.get(literal(old['value']))==old['label'],'DICTIONARY_ENUM_CONFLICT')
        attribute['enumValues']=values

    bound_targets=set()
    for binding in catalog['bindings']:
        model,code=binding['model'],binding['code'];key=model+'/'+code;require(key not in bindings,'DUPLICATE_MODEL_BINDING');bindings.add(key)
        target='binding:'+key;targets.add(target);require(target in evidence,'MISSING_SHARED_EVIDENCE')
        entity=entities.get(model);require(entity is not None,'UNKNOWN_BINDING_MODEL')
        definition=definitions.get(binding['definition']+'@'+str(binding['definitionRevision']));require(definition is not None,'UNKNOWN_DEFINITION_REVISION')
        spec=definition['specification'];kind=definition['type'];mapping=binding['attributeMappings'];attrs=indexed(entity['attributes']);parameters=indexed(spec['parameters'])
        require(set(mapping)==set(parameters),'BINDING_PARAMETER_SET')
        for name,parameter in parameters.items():
            actual=attrs.get(mapping[name]);require(actual is not None,'UNKNOWN_BINDING_ATTRIBUTE')
            require(actual['dataType']==parameter['dataType'],'BINDING_PARAMETER_TYPE')
        asset=asset_code(model,code);attribute=mapped(mapping,spec['attribute']) if 'attribute' in spec else None
        if kind=='METRIC':
            require('dictionary' not in binding,'METRIC_DICTIONARY_UNSUPPORTED')
            require({mapped(mapping,k) for k in spec['grainKeys']}==set(entity['primaryKey']),'SHARED_METRIC_GRAIN_MISMATCH')
            row=rewrite(spec,mapping);row.pop('parameters');row.pop('grainKeys');row.update(code=asset,entity=model,name=binding['roleName'],description=definition['description'])
            if 'retrieval' in definition:row['retrieval']=definition['retrieval']
            catalog['metrics'].append(row)
        elif kind=='DIMENSION':
            row=dict(code=asset,entity=model,attribute=attribute,name=binding['roleName'],description=definition['description'])
            if 'retrieval' in definition:row['retrieval']=definition['retrieval']
            catalog['dimensions'].append(row)
        else:
            row=attrs[attribute];row.update(name=binding['roleName'],description=definition['description']);asset=attribute
            if 'unit' in spec:row['unit']=spec['unit']
            if 'retrieval' in definition:row['retrieval']=definition['retrieval']
        binding_target=kind+':'+model+':'+asset;require(binding_target not in bound_targets,'DUPLICATE_BINDING_TARGET');bound_targets.add(binding_target)
        if 'dictionary' in binding:
            use=binding['dictionary'];dictionary=dictionaries.get(use['code']+'@'+str(use['revision']));require(dictionary is not None,'UNKNOWN_DICTIONARY_REVISION')
            attach_dictionary(attrs[attribute],dictionary,use)
        legacy=('entity:'+model+'/attribute:'+attribute) if kind=='ATTRIBUTE' else kind.lower()+':'+asset
        pack['evidence'].extend(dict(row,target=legacy) for row in evidence[target])
    pack['evidence']=[e for e in pack['evidence'] if e['target'] not in targets]
    for issue in pack['unresolvedIssues']:
        if issue['target'] in targets:
            require(not issue['blocking'],'UNRESOLVED_SHARED_DEFINITION');issue['target']='catalog'
    for key in ('definitions','bindings','enumDictionaries'):catalog.pop(key)
    pack['formatVersion']='1.1';return pack
