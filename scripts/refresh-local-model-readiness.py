#!/usr/bin/env python3
"""Recheck existing active models through the normal API; never changes models or credentials."""
import argparse
import json
import time
from pathlib import Path
from acceptance_http import LocalAcceptanceClient


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--type', choices=('CHAT', 'EMBEDDING', 'RERANK'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Preserve previous evidence; choose a fresh output')
    client = LocalAcceptanceClient()
    before = client.request('/api/model-config/check-ready')['data']
    matches = [row for row in client.request('/api/model-config/list')['data']
               if row.get('isActive') and row['modelType'] == args.type]
    if len(matches) != 1:
        raise ValueError('Exactly one existing active model is required')
    row = matches[0]
    start = time.monotonic()
    failure = None
    try:
        result = client.request('/api/model-config/test', 'POST', row)
        if result.get('success') is not True:
            raise RuntimeError('Connection test was rejected')
    except Exception as error:
        failure = {'errorType': type(error).__name__, 'httpStatus': getattr(error, 'code', None)}
    after = client.request('/api/model-config/check-ready')['data']
    prefix = {'CHAT': 'chat', 'EMBEDDING': 'embedding', 'RERANK': 'rerank'}[args.type]
    proof = {'status': 'PASS' if failure is None and after.get(prefix + 'ModelReady') else 'FAIL',
             'scope': 'REAL_CONNECTIVITY_ONLY', 'model': row['modelName'], 'modelId': row['id'],
             'seconds': round(time.monotonic() - start, 3), 'before': before, 'after': after,
             'failure': failure, 'api': 'POST /api/model-config/test'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(proof, ensure_ascii=False))
    raise SystemExit(0 if proof['status'] == 'PASS' else 1)


if __name__ == '__main__':
    main()
