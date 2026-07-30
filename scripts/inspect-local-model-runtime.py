#!/usr/bin/env python3
"""Bind the existing shared Qwen deployment to source and sanitized runtime facts.

Read-only: no preload, model inference, rebuild, restart, environment dump or weight
download. A passed identity check is not a long-input, rerank or latency result.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

FILES = ('embedding-contract.Dockerfile', 'contract_app.py', 'orbisops_retrieval_contract.py',
         'patch_embedding_dimensions.py', 'qwen_embedding_length_guard.py',
         'qwen_encoding_identity.py',
         'qwen_inference_admission.py', 'qwen_cpu_runtime.py', 'qwen_model_residency.py',
         'qwen_cpu_attention.py')


def inspect(output, source, expected_attention):
    if output.exists():
        raise ValueError('Keep previous evidence; choose a fresh output')
    files = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in FILES}
    digest = hashlib.sha256(b''.join((source / name).read_bytes() for name in FILES)).hexdigest()
    container = json.loads(subprocess.check_output(['docker', 'inspect', 'embedding-model']))[0]
    process = subprocess.run(['docker', 'exec', 'embedding-model', 'sha256sum',
                              *['/app/' + name for name in FILES if name.endswith('.py')]],
                             capture_output=True, text=True)
    deployed = {Path(line.split()[1]).name: line.split()[0]
                for line in process.stdout.splitlines() if len(line.split()) == 2}
    try:
        with urllib.request.urlopen('http://127.0.0.1:8110/ready', timeout=5) as response:
            ready = json.load(response)
    except urllib.error.HTTPError as error:
        ready = json.load(error)
    residency = ready.get('residency', {})
    states = ready.get('modelStates', {})
    # A legitimate load transition is part of the same single-resident contract.
    # Record its loading/busy state; do not equate it to model readiness or failure.
    state_consistency = len(states) == 2 and sum(bool(s.get('resident')) for s in states.values()) <= 1
    for kind, state in states.items():
        status = state.get('status')
        state_consistency = state_consistency and (
            (status == 'loaded' and state.get('resident') is True and residency.get('residentKind') == kind)
            or (status == 'evicted' and state.get('resident') is False)
            or (status == 'loading' and state.get('resident') is False and residency.get('loadingKind') == kind)
        )
    checks = {
        'source_label_matches_ten_files': (container['Config'].get('Labels') or {}).get('orbisops.retrieval.source') == digest,
        'deployed_python_bytes_match_nine_modules': process.returncode == 0 and all(deployed.get(name) == files[name] for name in FILES if name.endswith('.py')),
        'single_existing_cache_volume': container['HostConfig']['Binds'] == ['embedding-model-cache:/models'],
        'loopback_bridge_retained': container['HostConfig']['NetworkMode'] == 'bridge' and container['HostConfig']['PortBindings'] == {'8110/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '8110'}]},
        'same_named_models': ready.get('embeddingModel') == 'Qwen/Qwen3-VL-Embedding-2B' and ready.get('rerankModel') == 'Qwen/Qwen3-VL-Reranker-2B',
        'capacity_one_model_states_are_consistent_including_loading': residency.get('capacity') == 1 and state_consistency,
        'both_weight_kinds_verified': set(ready.get('residency', {}).get('verifiedKinds', [])) == {'embedding', 'rerank'},
        'cpu_threads_eight_and_one': ready.get('cpuRuntime', {}).get('intraOpThreads') == 8 and ready.get('cpuRuntime', {}).get('interOpThreads') == 1,
        'resource_deadline_180': ready.get('inference', {}).get('resourceDeadlineSeconds') == 180.0,
        'attention_policy_explicit_and_mask_unchanged': ready.get('cpuAttention', {}).get('policy') == expected_attention and ready.get('cpuAttention', {}).get('registration') == 'Transformers.AttentionInterface' and ready.get('cpuAttention', {}).get('maskFormatterChanged') is False,
        'weights_and_dtype_unchanged_policy': ready.get('cpuAttention', {}).get('weightsChanged') is False and ready.get('cpuAttention', {}).get('dtypeChanged') is False,
        'actual_model_dtype_and_attention_reported': all(ready.get('modelStates', {}).get(kind, {}).get('parameterDtype') == 'torch.bfloat16' and ready.get('modelStates', {}).get(kind, {}).get('attentionImplementation') == 'sdpa' for kind in ('embedding', 'rerank')),
    }
    receipt = {
        'at': datetime.now(timezone.utc).isoformat(),
        'status': 'PASS_IDENTITY_ONLY_QUALITY_AND_LATENCY_SEPARATE' if all(checks.values()) else 'IDENTITY_CHECK_FAILED',
        'checks': checks, 'sourceDigest': digest, 'sourceFiles': files, 'deployedPythonFiles': deployed,
        'containerId': container['Id'], 'imageId': container['Image'], 'restartCount': container['RestartCount'],
        'processState': {key: container['State'][key] for key in ('Status', 'Running', 'Pid', 'StartedAt', 'OOMKilled')},
        'readiness': ready,
        'boundary': 'Read-only deployment/source/runtime binding. No request admission, model loading, inference, success-state SQL write or performance claim. Busy kernels are recorded, never cancelled or treated as failed weights. Actual complete-input and publication results are independent evidence.'
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x') as stream:
        json.dump(receipt, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps({'status': receipt['status'], 'passed': sum(checks.values()), 'checks': len(checks), 'sourceDigest': digest, 'inference': ready.get('inference')}))
    return 0 if all(checks.values()) else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True, help='Directory of the authorized local model runtime scripts')
    parser.add_argument('--expected-attention', choices=('native', 'expanded-kv', 'expanded-kv-fp32'), required=True)
    args = parser.parse_args()
    raise SystemExit(inspect(args.output, args.source, args.expected_attention))
