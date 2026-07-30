#!/usr/bin/env python3
"""Read-only deployed publication DTO, database identity and permission proof."""
import argparse
from datetime import datetime, timezone
import hashlib
from importlib.machinery import SourceFileLoader
import json
from pathlib import Path
import subprocess
from urllib.error import HTTPError

from acceptance_http import LocalAcceptanceClient
from acceptance_fixture_scope import business_database

sql = SourceFileLoader('publication_status_proof', str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql
FIELDS = {'id', 'state', 'attempts', 'nextAttemptAt', 'lastError', 'preparedVersionId', 'finishedAt'}


def canonical(value):
    result = dict(value)
    for key in ('nextAttemptAt', 'finishedAt'):
        if result.get(key):
            result[key] = datetime.fromisoformat(result[key].replace('Z', '+00:00')).astimezone(timezone.utc).isoformat()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=int, required=True)
    parser.add_argument('--expected-jar-sha256', required=True)
    parser.add_argument('--account', default='semevosql-acceptance-owner')
    parser.add_argument('--namespace')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.candidate <= 0 or args.output.exists() or args.output.with_suffix('.sql').exists():
        raise ValueError('Positive candidate and fresh proof paths required')
    if len(args.expected_jar_sha256) != 64:
        raise ValueError('An immutable tested JAR SHA256 is required')
    queries = {'candidate': f'SELECT id,project_id,content_revision,representation_hash,assessment_state,lifecycle FROM qw_project_definition_candidate WHERE id={args.candidate}'}
    candidates = sql('semevosql_acceptance', queries['candidate'])
    if len(candidates) != 1:
        raise ValueError('Exactly one isolated acceptance candidate required')
    candidate = candidates[0]
    project = candidate['project_id']
    business_database(project, sql, args.namespace)
    queries['currentPublication'] = f"""SELECT json_build_object('id',j.id,'state',j.state,'attempts',j.attempt_count,
      'nextAttemptAt',j.next_attempt_at,'lastError',j.last_error,
      'preparedVersionId',j.prepared_version_id,'finishedAt',j.finish_time) AS publication
      FROM qw_project_definition_publication j JOIN qw_project_definition_candidate c ON c.id=j.candidate_id
      WHERE c.id={args.candidate} AND j.project_id=c.project_id AND j.content_revision=c.content_revision
        AND j.source_representation_hash=c.representation_hash ORDER BY j.id DESC LIMIT 1"""
    owner = LocalAcceptanceClient(args.account)
    path = f'/api/semevosql/projects/{project}/definition-candidates'
    # Both native reads bracket the GET. A concurrent worker transition is preserved,
    # not scored as an API mismatch or silently replaced with a guessed state.
    before = sql('semevosql_acceptance', queries['currentPublication'])
    rows = owner.request(path)
    after = sql('semevosql_acceptance', queries['currentPublication'])
    selected = [row for row in rows if row['id'] == args.candidate]
    api = selected[0].get('publication') if len(selected) == 1 else None
    expected = after[0]['publication'] if len(after) == 1 else None
    actual_jar = hashlib.sha256(subprocess.check_output(['docker', 'exec', 'semevosql-acceptance-backend-1', 'cat', '/app/semevosql-backend.jar'])).hexdigest()

    def denied(account, request_path):
        try:
            LocalAcceptanceClient(account).request(request_path)
        except HTTPError as error:
            return error.code == 403
        return False

    checks = {
        'tested_running_jar_matches': actual_jar == args.expected_jar_sha256,
        'actual_candidate_returned_once': len(selected) == 1,
        'candidate_revision_and_representation_are_exact': len(selected) == 1 and all(selected[0][key] == candidate[key] for key in ('content_revision', 'representation_hash')),
        'native_publication_stable_across_get': before == after,
        'publication_exactly_matches_current_native_identity_and_state': canonical(api) == canonical(expected) if api and expected else api is None and expected is None,
        'publication_field_whitelist_only': set(api) == FIELDS if api else True,
        'assessment_is_a_separate_field': len(selected) == 1 and selected[0]['assessment_state'] == candidate['assessment_state'],
        'ordinary_member_cannot_read_admin_publication_list': denied('sem_member_a', path),
        'outsider_cannot_read_admin_publication_list': denied('sem_outsider', path),
        'scoped_admin_cannot_read_other_project_list': denied(args.account, '/api/semevosql/projects/1/definition-candidates'),
    }
    report = {'at': datetime.now(timezone.utc).isoformat(), 'status': 'PASS' if all(checks.values()) else 'FAIL',
              'checks': checks, 'candidate': candidate, 'apiPublication': api,
              'nativePublicationBefore': before, 'nativePublicationAfter': after,
              'runningJarSha256': actual_jar,
              'boundary': 'Only normal authenticated GETs and read-only native SQL. No approval, model call, publication write, fabricated DONE or client-timeout kernel cancellation. Browser rendering and full publication completion are separate evidence.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.with_suffix('.sql').write_text('-- Read-only current publication identity proof\n' + ';\n\n'.join(queries.values()) + ';\n')
    with args.output.open('x') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps({'status': report['status'], 'checks': len(checks), 'failed': [key for key, value in checks.items() if not value], 'output': str(args.output)}))
    raise SystemExit(0 if all(checks.values()) else 1)


if __name__ == '__main__':
    main()
