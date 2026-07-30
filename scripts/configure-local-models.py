#!/usr/bin/env python3
"""Configure the isolated acceptance app from existing authorized Luna/Terra credentials.

No secrets are printed. Each activation follows a real API connectivity test. Existing
configuration differences are rejected rather than silently replacing a model.
"""
import argparse
import json
from pathlib import Path
import time
import urllib.request
import urllib.error

ROOT=Path(__file__).resolve().parents[1]
BASE='http://127.0.0.1:18093'


def api(path,payload=None,method=None):
    body=None if payload is None else json.dumps(payload).encode()
    request=urllib.request.Request(BASE+path,body,{'Content-Type':'application/json'},method=method)
    with urllib.request.urlopen(request,timeout=190) as response:
        result=json.load(response)
    if isinstance(result,dict) and result.get('success') is False:
        raise RuntimeError('Application rejected operation')
    return result


def main(source,output):
    if output.exists(): raise ValueError('Use a fresh evidence filename')
    env=dict(line.split('=',1) for line in source.read_text().splitlines() if '=' in line and not line.lstrip().startswith('#'))
    url=env['ORBISOPS_MODEL_BASE_URL'].strip().strip('"').strip("'")
    key=env['ORBISOPS_MODEL_API_KEY'].strip().strip('"').strip("'")
    if not url.startswith('https://') or not key: raise ValueError('Existing authorized HTTPS provider required')
    configs=[dict(provider='openai',baseUrl=url,apiKey=key,modelName='gpt-5.6-'+role,modelType='CHAT',
        completionsPath='/v1/chat/completions',requestTimeoutSeconds=60,maxTokens=12000,temperature=0.0) for role in ['luna','terra']]
    configs += [dict(provider='openai-compatible',baseUrl='http://host.docker.internal:8110',apiKey='',
        modelName='Qwen/Qwen3-VL-Embedding-2B',modelType='EMBEDDING',embeddingsPath='/v1/embeddings',embeddingDimensions=1024,requestTimeoutSeconds=180),
        dict(provider='openai-compatible',baseUrl='http://host.docker.internal:8110',apiKey='',
        modelName='Qwen/Qwen3-VL-Reranker-2B',modelType='RERANK',rerankPath='/v1/rerank',requestTimeoutSeconds=60)]
    facts=[]
    for desired in configs:
        current=api('/api/model-config/list')['data']
        matches=[v for v in current if v['modelName']==desired['modelName'] and v['modelType']==desired['modelType']]
        if len(matches)>1: raise ValueError('Ambiguous model configuration retained')
        if not matches:
            api('/api/model-config/add',desired)
            matches=[v for v in api('/api/model-config/list')['data'] if v['modelName']==desired['modelName'] and v['modelType']==desired['modelType']]
        row=matches[0]
        for field in ['baseUrl','modelName','modelType','embeddingDimensions','requestTimeoutSeconds']:
            if field in desired and row.get(field)!=desired[field]:
                raise ValueError('Existing '+field+' differs; configuration retained')
        fact={'model':row['modelName'],'id':row['id'],'attempts':[],'status':'UNTESTED'}
        for attempt in range(1,6 if row['modelType']=='CHAT' else 2):
            start=time.monotonic()
            try:
                api('/api/model-config/test',row)
                fact['attempts'].append({'attempt':attempt,'status':'PASS','seconds':round(time.monotonic()-start,3)})
                fact['status']='PASS_CONNECTIVITY_ONLY'
                break
            except Exception as error:
                fact['attempts'].append({'attempt':attempt,'status':'FAIL','errorType':type(error).__name__,
                    'http':getattr(error,'code',None),'seconds':round(time.monotonic()-start,3)})
                fact['status']='FAIL_CONNECTIVITY'
                if attempt<5 and row['modelType']=='CHAT':time.sleep(min(2**(attempt-1),8))
        if fact['status']=='PASS_CONNECTIVITY_ONLY' and row['modelName']!='gpt-5.6-terra':
            active=[v for v in api('/api/model-config/list')['data'] if v['modelType']==row['modelType'] and v.get('isActive')]
            if active and active[0]['id']!=row['id']: raise ValueError('A different active model was retained')
            api('/api/model-config/activate/'+str(row['id']),{},'POST')
            fact['active']=True
        facts.append(fact)
        output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(json.dumps({'models':facts,'scope':'CONFIGURATION_AND_REAL_CONNECTIVITY_ONLY'},indent=2)+'\n')
        print(json.dumps(fact),flush=True)
    result={'models':facts,'readiness':api('/api/model-config/check-ready')['data'],'scope':'CONFIGURATION_AND_REAL_CONNECTIVITY_ONLY'}
    output.write_text(json.dumps(result,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source-env',required=True,type=Path);p.add_argument('--output',required=True,type=Path)
    a=p.parse_args();main(a.source_env,a.output)
