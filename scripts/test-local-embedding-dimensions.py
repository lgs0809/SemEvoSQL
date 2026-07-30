#!/usr/bin/env python3
"""Exercise the existing local Qwen service and preserve partial results on failure."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import time
import urllib.request
import urllib.error


def request(body, path='/v1/embeddings', headers=None, attempts=None):
    start = time.monotonic()
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8110' + path,
                    json.dumps(body).encode(), {'Content-Type': 'application/json', **(headers or {})}), timeout=90) as response:
                data = json.load(response)
            if attempts is not None:
                attempts.append({'path': path, 'attempt': attempt + 1, 'status': 200})
            return data, round(time.monotonic() - start, 3)
        except urllib.error.HTTPError as error:
            if attempts is not None:
                attempts.append({'path': path, 'attempt': attempt + 1, 'status': error.code,
                                 'body': error.read(2048).decode(errors='replace')})
            if error.code not in (429, 502, 503, 504) or attempt == 4:
                raise
            time.sleep(2 ** attempt)


def main(output, orbis_credential):
    if output.exists():
        raise ValueError('Use a new evidence filename')
    result = {'status': 'RUNNING', 'checks': [], 'attempts': [], 'invalidDimensionsRejected': 0,
              'scope': 'REAL_EXISTING_QWEN_EMBEDDING_ONLY_RERANK_NOT_RETESTED'}
    def save():
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    save()
    try:
        for dimensions in [1024, 2048]:
            data, seconds = request({'model': 'Qwen/Qwen3-VL-Embedding-2B', 'input': ['一月各地区已支付订单金额'],
                                     'dimensions': dimensions}, attempts=result['attempts'])
            vector = data['data'][0]['embedding']
            norm = math.sqrt(sum(v * v for v in vector))
            assert len(vector) == dimensions and math.isfinite(norm) and norm > 0
            if dimensions == 1024:
                assert abs(norm - 1) < 1e-4
            result['checks'].append({'route': 'standard', 'dimensions': len(vector), 'norm': norm,
                                     'seconds': seconds, 'sha256': hashlib.sha256(json.dumps(vector).encode()).hexdigest(), 'status': 'PASS'})
            save()
        for invalid in [0, 63, 2049, True, '1024']:
            try:
                request({'input': ['invalid boundary'], 'dimensions': invalid}, attempts=result['attempts'])
                raise AssertionError('Invalid dimension accepted')
            except urllib.error.HTTPError as error:
                assert error.code == 400
                result['invalidDimensionsRejected'] += 1
                save()
        key = json.loads(orbis_credential.read_text())['apiKey']
        data, seconds = request({'model': 'Qwen/Qwen3-VL-Embedding-2B',
            'revision': '9f2f7e710d6d81056aa5c0a4f04764fec6bb7bda',
            'preprocessing': 'skill-route-text-v1:nfc:instruction:mrl1024:l2', 'kind': 'query',
            'dimensions': 1024, 'text': '检查订单服务异常'}, '/orbisops/embed',
            {'Authorization': 'Bearer ' + key}, result['attempts'])
        assert len(data['embedding']) == 1024
        result['checks'].append({'route': 'orbisops', 'dimensions': 1024, 'model': data['model'],
                                 'revision': data['revision'], 'seconds': seconds, 'status': 'PASS'})
        result['status'] = 'PASS'
    except Exception as error:
        result['status'] = 'FAIL'
        result['errorType'] = type(error).__name__
        raise
    finally:
        save()
        print(json.dumps({'status': result['status'], 'completedChecks': len(result['checks']), 'output': str(output)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--orbis-credential', type=Path, required=True)
    args = parser.parse_args()
    main(args.output, args.orbis_credential)
