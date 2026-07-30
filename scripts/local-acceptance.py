#!/usr/bin/env python3
"""Build tested local artifacts and start a loopback-only, separate acceptance stack.

All existing containers/data are retained. Credentials live in ignored .env.acceptance.local.
This command never changes model selection or credentials and never seeds success states.
"""
import argparse
import base64
import hashlib
import json
import re
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ENV = ROOT / 'deploy/.env.acceptance.local'


def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)


def compose(*args):
    return run(['docker', 'compose', '--env-file', str(ENV), '-p', 'semevosql-acceptance',
                '-f', 'deploy/semevosql/docker-compose.yml', '-f', 'deploy/acceptance/compose.override.yml',
                '--profile', 'app', *args])


def init():
    if ENV.exists():
        return
    values = {
        'SEMEVOSQL_COMPOSE_PROJECT_NAME': 'semevosql-acceptance',
        'SEMEVOSQL_METADATA_DATABASE': 'semevosql_acceptance',
        'SEMEVOSQL_METADATA_USER': 'acceptance',
        'SEMEVOSQL_METADATA_PASSWORD': secrets.token_hex(24),
        'SEMEVOSQL_EXECUTION_INTERNAL_TOKEN': secrets.token_hex(32),
        'SEMEVOSQL_SECRET_ENCRYPTION_KEY': base64.b64encode(secrets.token_bytes(32)).decode(),
        'SEMEVOSQL_METADATA_VOLUME': 'semevosql-acceptance-metadata',
        'SEMEVOSQL_UPLOADS_VOLUME': 'semevosql-acceptance-uploads',
        'SEMEVOSQL_NETWORK_NAME': 'semevosql-acceptance-net',
        'SEMEVOSQL_BACKEND_IMAGE': 'semevosql/backend:acceptance-tested',
        'SEMEVOSQL_FRONTEND_IMAGE': 'semevosql/frontend:acceptance-tested',
        'SEMEVOSQL_BIND_HOST': '127.0.0.1',
        'SEMEVOSQL_BACKEND_PORT': '18093',
        'SEMEVOSQL_FRONTEND_PORT': '3303',
        'SEMEVOSQL_SPRING_PROFILE': 'standalone',
        'SEMEVOSQL_RETENTION_ENABLED': 'false',
    }
    import os
    with os.fdopen(os.open(ENV, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as f:
        f.write(''.join(f'{key}={value}\n' for key, value in values.items()))


def build(backend_log, web_log, backend_only=False):
    if not backend_log or not web_log:
        raise ValueError('Both successful verification logs are required')
    if 'BUILD SUCCESS' not in backend_log.read_text() or 'Tests run:' not in backend_log.read_text():
        raise ValueError('Backend package must pass tests first')
    web = web_log.read_text()
    if 'built in' not in web or 'typecheck' not in web or not re.search(r'(?:#|ℹ)\s+fail 0(?:\n|$)', web):
        raise ValueError('Frontend verify, including actual tests, must pass first')
    jar = ROOT / 'backend/target/semevosql-backend.jar'
    dist = ROOT / 'frontend/dist'
    if jar.stat().st_mtime > backend_log.stat().st_mtime or any(
            p.stat().st_mtime > web_log.stat().st_mtime for p in dist.rglob('*') if p.is_file()):
        raise ValueError('Artifacts changed after the supplied verification; verify again')
    with tempfile.TemporaryDirectory(prefix='semevosql-tested-') as folder:
        path = Path(folder)
        shutil.copy2(jar, path / 'semevosql-backend.jar')
        (path / 'Dockerfile').write_text('FROM semevosql/backend:local\nCOPY --chown=10001:0 semevosql-backend.jar /app/semevosql-backend.jar\n')
        run(['docker', 'build', '-t', 'semevosql/backend:acceptance-tested', folder])
    if not backend_only:
        import runpy
        with tempfile.TemporaryDirectory(prefix='semevosql-web-proof-') as folder:
            # One frontend build path retains bounded old chunks in every deployment mode.
            runpy.run_path(str(ROOT / 'scripts/deploy-tested-web-acceptance.py'))['deploy'](
                web_log, Path(folder) / 'frontend.json')
    return {'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
            'backendTestLogSha256': hashlib.sha256(backend_log.read_bytes()).hexdigest(),
            'webTestLogSha256': hashlib.sha256(web_log.read_bytes()).hexdigest()}


def require_idle():
    found = run(['docker','ps','--filter','name=^semevosql-acceptance-backend-1$',
                 '--format','{{.Names}}'],capture_output=True,text=True).stdout.strip()
    if not found:
        return
    result=run(['docker','exec','semevosql-acceptance-metadata-db-1','psql','-X','-A','-t',
                '-v','ON_ERROR_STOP=1','-U','acceptance','-d','semevosql_acceptance','-c',
                "SELECT count(*) FROM qw_query_run WHERE status IN ('QUEUED','RUNNING','WAITING_HUMAN','CANCEL_REQUESTED')"],
                capture_output=True,text=True)
    if int(result.stdout.strip()):
        raise RuntimeError('Active or waiting Runs are retained; deployment refused until they are finished')


def verify_runtime(evidence):
    deadline=time.monotonic()+90
    while True:
        try:
            with urllib.request.urlopen('http://127.0.0.1:18093/actuator/health',timeout=5) as response:
                if json.load(response)['status']=='UP': break
        except (OSError,ValueError):
            pass
        if time.monotonic()>deadline: raise RuntimeError('Backend did not become healthy')
        time.sleep(2)
    raw=run(['docker','exec','semevosql-acceptance-backend-1','cat','/app/semevosql-backend.jar'],capture_output=True).stdout
    actual=hashlib.sha256(raw).hexdigest()
    if actual!=evidence['jarSha256']: raise RuntimeError('Running JAR differs from tested JAR')
    with tempfile.TemporaryDirectory(prefix='semevosql-web-verify-') as folder:
        target=Path(folder)/'html'
        run(['docker','cp','semevosql-acceptance-frontend-1:/usr/share/nginx/html',str(target)],capture_output=True)
        files=[p for p in (ROOT/'frontend/dist').rglob('*') if p.is_file()]
        if any(not (target/p.relative_to(ROOT/'frontend/dist')).is_file() or
               p.read_bytes()!=(target/p.relative_to(ROOT/'frontend/dist')).read_bytes() for p in files):
            raise RuntimeError('Running frontend differs from verified build')
    evidence.update(runningJarSha256=actual,matchingWebFiles=len(files),health='UP',
                    status='PASS_DEPLOYMENT_IDENTITY_BUSINESS_ACCEPTANCE_SEPARATE')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['init', 'up', 'status', 'stop'])
    parser.add_argument('--backend-test-log', type=Path)
    parser.add_argument('--web-test-log', type=Path)
    parser.add_argument('--backend-only', action='store_true', help='Keep the running verified frontend image and assets')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.output and args.output.exists():
        raise ValueError('Choose a new evidence output; earlier records are retained')
    init()
    if args.command == 'up':
        require_idle()
        evidence = build(args.backend_test_log, args.web_test_log, args.backend_only)
        require_idle()
        if args.backend_only:
            compose('up', '-d', '--no-build', '--no-deps', 'backend')
        else:
            compose('up', '-d', '--no-build')
        evidence['frontendRebuilt'] = not args.backend_only
        evidence.update(web='http://127.0.0.1:3303/semevosql/', backend='http://127.0.0.1:18093',
                        status='STARTED_HEALTH_AND_BUSINESS_VERIFICATION_REQUIRED')
        verify_runtime(evidence)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps(evidence))
    elif args.command == 'status':
        compose('ps', '-a')
    elif args.command == 'stop':
        compose('stop')


if __name__ == '__main__':
    main()
