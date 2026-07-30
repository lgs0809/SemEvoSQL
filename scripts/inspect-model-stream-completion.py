#!/usr/bin/env python3
"""One real configured-model SSE probe, separate from application/business acceptance.

Reads the active Luna binding without printing credentials. Retains protocol facts and
the synthetic request/response, never changes models, run states or retry budgets.
"""
import argparse
import datetime
import hashlib
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from importlib.machinery import SourceFileLoader
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sql = SourceFileLoader('model_probe_sql', str(ROOT / 'scripts/verify-offline-catalog.py')).load_module().sql


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--question-file', type=Path, required=True)
    parser.add_argument('--source-env', type=Path, required=True, help='Existing authorized provider credential file')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    paths = [args.output, args.output.with_suffix('.request.json'), args.output.with_suffix('.sse')]
    if any(p.exists() for p in paths):
        raise ValueError('Keep prior evidence; select a fresh output')
    configs = sql('semevosql_acceptance', "SELECT model_name,base_url,completions_path,temperature,max_tokens,request_timeout_seconds,proxy_enabled FROM model_config WHERE model_type='CHAT' AND is_active=TRUE AND is_deleted=0")
    if len(configs) != 1 or configs[0]['model_name'] != 'gpt-5.6-luna':
        raise ValueError('Probe requires the existing active Luna binding')
    config = configs[0]
    env=dict(line.split('=',1) for line in args.source_env.read_text().splitlines()
             if '=' in line and not line.lstrip().startswith('#'))
    provider_url=env['ORBISOPS_MODEL_BASE_URL'].strip().strip('"').strip("'")
    key=env['ORBISOPS_MODEL_API_KEY'].strip().strip('"').strip("'")
    if provider_url.rstrip('/') != config['base_url'].rstrip('/') or not key or key.startswith('enc:v1:'):
        raise ValueError('Existing provider binding must match; stored ciphertext cannot be used as an API key')
    if config['proxy_enabled']:
        raise ValueError('Proxy-enabled configuration needs its existing production adapter')
    base = urllib.parse.urlsplit(config['base_url'])
    parts = [p for p in base.path.split('/') if p]
    endpoint = [p for p in (config['completions_path'] or '/v1/chat/completions').split('/') if p]
    overlap = max([n for n in range(min(len(parts),len(endpoint))+1) if n == 0 or parts[-n:] == endpoint[:n]])
    url = urllib.parse.urlunsplit((base.scheme,base.netloc,'/'+ '/'.join(parts+endpoint[overlap:]),'',''))
    source = (ROOT / 'backend/src/main/java/cn/lgs/semevosql/workflow/node/QueryEnhanceNode.java').read_text()
    block = re.search(r'String prompt = """(.*?)""" \+ ',source,re.S)
    if not block:
        raise ValueError('Current request prompt source not found')
    lines = block.group(1).splitlines()
    prompt = '\n'.join(line[12:] if line.startswith(' '*12) else line for line in lines).strip()
    question = args.question_file.read_text().strip()
    if not question:
        raise ValueError('A synthetic diagnostic question is required')
    prompt += '\n当前日期: '+datetime.date.today().isoformat()+'\n本次冻结的会话上下文:\n(无)\n当前消息及本次确认:\n'+question
    body = {'model': config['model_name'], 'messages': [{'role':'user','content':prompt}],
            'stream':True, 'stream_options':{'include_usage':True},
            'temperature':config['temperature'], 'max_tokens':config['max_tokens']}
    encoded = json.dumps(body,ensure_ascii=False).encode()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.with_suffix('.request.json').write_bytes(encoded)
    report = {'status':'NOT_ASSESSED','requestedModel':config['model_name'], 'requestSha256':hashlib.sha256(encoded).hexdigest(),
              'actualModels':[], 'finishReasons':[], 'doneSeen':False, 'httpRequests':1,
              'boundary':'Direct host-side protocol diagnostic using an existing configured model. No graph, SQL execution or business success is inferred.'}
    raw = bytearray(); text=[]; started=time.monotonic()
    budget=min(60,config['request_timeout_seconds'] or 60)
    request = urllib.request.Request(url,data=encoded,headers={'Authorization':'Bearer '+key,
        'Content-Type':'application/json','Accept':'text/event-stream'},method='POST')
    try:
        with urllib.request.urlopen(request,timeout=budget) as response:
            report['httpStatus']=response.status
            while True:
                remaining=budget-(time.monotonic()-started)
                if remaining <= 0:
                    raise TimeoutError('Shared protocol deadline exceeded')
                response.fp.raw._sock.settimeout(remaining)
                line=response.readline()
                if not line: break
                raw.extend(line)
                if len(raw)>2*1024*1024: raise ValueError('Protocol response exceeded bounded diagnostic size')
                if not line.startswith(b'data:'): continue
                data=line[5:].strip()
                if data == b'[DONE]':
                    report['doneSeen']=True;break
                value=json.loads(data)
                if value.get('model') and value['model'] not in report['actualModels']:
                    report['actualModels'].append(value['model'])
                for choice in value.get('choices',[]):
                    if choice.get('index',0)!=0: continue
                    content=choice.get('delta',{}).get('content')
                    if content: text.append(content)
                    if choice.get('finish_reason'): report['finishReasons'].append(choice['finish_reason'])
        report['transportEnd']='DONE' if report['doneSeen'] else 'HTTP_BODY_EOF'
    except Exception as error:
        report['transportEnd']=type(error).__name__
        if isinstance(error,urllib.error.HTTPError):report['httpStatus']=error.code
    finally:
        report['elapsedMs']=round((time.monotonic()-started)*1000)
        output=''.join(text);report['response']=output
        report['responseSha256']=hashlib.sha256(output.encode()).hexdigest()
        report['responseChars']=len(output)
        try:
            parsed=json.loads(output)
            report['validRequestObject']=isinstance(parsed,dict) and set(parsed)=={'status','canonical_query','expanded_queries','context_turns','question','options'}
        except (ValueError,TypeError):report['validRequestObject']=False
        report['status']='COMPLETE_PROTOCOL_OBSERVED' if report['validRequestObject'] and report['doneSeen'] else 'INCOMPLETE_PROTOCOL_OBSERVED'
        args.output.with_suffix('.sse').write_bytes(raw)
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps({k:report[k] for k in ('status','actualModels','finishReasons','doneSeen','transportEnd','responseChars','elapsedMs')}))
    raise SystemExit(0 if report['status']=='COMPLETE_PROTOCOL_OBSERVED' else 1)


if __name__ == '__main__':
    main()
