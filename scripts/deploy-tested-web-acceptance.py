#!/usr/bin/env python3
"""Deploy only verified frontend files/configuration to the isolated stack; retain all backend work."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import tempfile
import urllib.request
from web_asset_retention import retain

ROOT = Path(__file__).resolve().parents[1]


def deploy(log, output):
    if output.exists():
        raise ValueError('Preserve previous evidence; use a fresh output')
    verification = log.read_text()
    if 'built in' not in verification or 'typecheck' not in verification or not re.search(r'(?:#|ℹ)\s+fail 0(?:\n|$)', verification):
        raise ValueError('Require a passed npm run verify log')
    dist = ROOT / 'frontend/dist'
    files = [p for p in dist.rglob('*') if p.is_file()]
    if not files or any(p.stat().st_mtime > log.stat().st_mtime for p in files):
        raise ValueError('Build changed after verification')
    with tempfile.TemporaryDirectory(prefix='semevosql-web-') as folder:
        build = Path(folder)
        shutil.copytree(dist, build / 'dist')
        previous = build / 'previous'
        subprocess.run(['docker', 'cp', 'semevosql-acceptance-frontend-1:/usr/share/nginx/html', str(previous)], check=True)
        retention = retain(dist, previous, build / 'dist', 'assets')
        expected = {p.relative_to(build / 'dist').as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in (build / 'dist').rglob('*') if p.is_file()}
        shutil.rmtree(previous)
        shutil.copy2(ROOT / 'deploy/semevosql/nginx.conf', build / 'nginx.conf')
        (build / 'Dockerfile').write_text('FROM nginxinc/nginx-unprivileged:1.27-alpine\nUSER root\nRUN rm -rf /usr/share/nginx/html/*\nUSER 101\nCOPY nginx.conf /etc/nginx/conf.d/default.conf\nCOPY dist/ /usr/share/nginx/html/\n')
        subprocess.run(['docker', 'build', '--pull=false', '-t', 'semevosql/frontend:acceptance-tested', folder], check=True)
    compose = runpy.run_path(str(ROOT / 'scripts/local-acceptance.py'))['compose']
    compose('up', '-d', '--no-build', '--no-deps', 'frontend')
    subprocess.run(['docker', 'exec', 'semevosql-acceptance-frontend-1', 'nginx', '-t'], check=True)
    with tempfile.TemporaryDirectory(prefix='semevosql-web-check-') as folder:
        deployed = Path(folder) / 'html'
        subprocess.run(['docker', 'cp', 'semevosql-acceptance-frontend-1:/usr/share/nginx/html', str(deployed)], check=True)
        assert all(p.read_bytes() == (deployed / p.relative_to(dist)).read_bytes() for p in files)
        actual = {p.relative_to(deployed).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in deployed.rglob('*') if p.is_file()}
        assert actual == expected, 'All current and retained web files must match the image build'
    config = subprocess.check_output(['docker', 'exec', 'semevosql-acceptance-frontend-1', 'cat', '/etc/nginx/conf.d/default.conf'])
    assert config == (ROOT / 'deploy/semevosql/nginx.conf').read_bytes()
    with urllib.request.urlopen('http://127.0.0.1:3303/api/semevosql/auth/session', timeout=10) as response:
        assert response.status == 200
    result = {'status': 'PASS', 'matchingWebFiles': len(files), 'browserApiProxyStatus': 200,
              'nginxConfigSha256': hashlib.sha256(config).hexdigest(), 'backendRestarted': False,
              'testLogSha256': hashlib.sha256(log.read_bytes()).hexdigest(), 'assetRetention': retention}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--test-log', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    deploy(args.test_log, args.output)
