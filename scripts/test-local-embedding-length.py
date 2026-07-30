#!/usr/bin/env python3
"""Use the installed Qwen tokenizer and actual local endpoint; never inject success states."""
import argparse
import json
from pathlib import Path
import time
import urllib.request
import urllib.error


def call(path, body):
    with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8110'+path,
        json.dumps(body,ensure_ascii=False).encode(), {'Content-Type':'application/json'}),timeout=90) as response:
        return json.load(response)


def main(output):
    if output.exists():raise ValueError('Choose a new evidence file')
    started=time.monotonic()
    small=call('/v1/embedding-token-count',{'input':['一月各地区已支付订单金额','客户地区和公司地区使用不同属性角色']})
    assert small['fits'] and not small['truncated'] and all(0<n<small['maxTokens'] for n in small['tokenCounts'])
    # Repeated text is only an input-length boundary, not an evaluation corpus or business case.
    long_text=' token'*(small['maxTokens']+100)
    too_long=call('/v1/embedding-token-count',{'input':[long_text]})
    assert not too_long['fits'] and too_long['tokenCounts'][0]>too_long['maxTokens']
    try:
        call('/v1/embeddings',{'input':[long_text],'dimensions':1024})
        raise AssertionError('Over-length model input accepted')
    except urllib.error.HTTPError as error:
        detail=json.load(error)
        assert error.code==413 and detail['detail']['code']=='MODEL_INPUT_TOO_LONG'
    result={'status':'PASS_ACTUAL_TOKENIZER_AND_HTTP_REJECTION','short':small,'oversize':too_long,
        'httpStatus':413,'seconds':round(time.monotonic()-started,3),
        'boundary':'Synthetic length input; actual tokenizer and service; no model quality claim'}
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True)
    main(p.parse_args().output)
