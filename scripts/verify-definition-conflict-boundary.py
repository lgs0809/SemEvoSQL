#!/usr/bin/env python3
"""Read-only confirmation that a successful personal revision did not overwrite public or historical meanings."""
import argparse
import hashlib
import json
from importlib.machinery import SourceFileLoader
from pathlib import Path
import uuid

sql = SourceFileLoader('conflict_boundary_sql', str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline', required=True, type=Path)
    p.add_argument('--prior-use-proof', required=True, type=Path)
    p.add_argument('--numeric-proof', required=True, type=Path)
    p.add_argument('--review-proof', required=True, type=Path)
    p.add_argument('--preference-id', required=True, type=int)
    p.add_argument('--revision', required=True, type=int)
    p.add_argument('--account', required=True)
    p.add_argument('--output', required=True, type=Path)
    a = p.parse_args()
    if a.preference_id <= 0 or a.revision <= 1 or a.output.exists() or a.output.with_suffix('.sql').exists():
        raise ValueError('Exact revised identity and fresh output required')
    previous = json.loads(a.baseline.read_bytes())
    old_use = json.loads(a.prior_use_proof.read_bytes())
    numeric, reviewed = json.loads(a.numeric_proof.read_bytes()), json.loads(a.review_proof.read_bytes())
    run = str(uuid.UUID(numeric['runId']))
    # Reuse the actual pre-operation queries, not a baseline reconstructed after the operation.
    queries = [q.strip() for q in a.baseline.with_suffix('.sql').read_text().split(';') if q.strip()]
    if len(queries) != 3 or any(not q.startswith('SELECT ') for q in queries):
        raise ValueError('A retained three-query read-only baseline is required')
    facts = {k: sql('semevosql_acceptance', q) for k, q in zip(('project', 'personal', 'candidate'), queries)}
    additions = {
        'authorization': f"SELECT choice,source_id FROM qw_user_semantic_authorization WHERE preference_id={a.preference_id} AND definition_revision={a.revision} ORDER BY authorization_revision DESC LIMIT 1",
        'uses': f"SELECT * FROM qw_user_semantic_preference_usage WHERE preference_id={a.preference_id} ORDER BY definition_revision,run_id",
        'suggestions': f"SELECT c.id,c.lifecycle,c.blocked_reason,c.published_version_id,c.assessment_state,c.content_revision,c.evidence_revision FROM qw_project_definition_candidate c JOIN qw_project_definition_source s ON s.candidate_id=c.id WHERE c.project_id=2 AND s.preference_id={a.preference_id} AND s.definition_revision={a.revision} ORDER BY c.id",
    }
    facts.update({k: sql('semevosql_acceptance', q) for k, q in additions.items()})
    immutable = ('id', 'user_id', 'display_phrase', 'revision', 'definition_text', 'content_hash', 'source_id')
    original = previous['facts']['personal']
    new = [r for r in facts['personal'] if r['id'] == a.preference_id and r['revision'] == a.revision]
    uses = [u for u in facts['uses'] if u['run_id'] == run and u['definition_revision'] == a.revision]
    historical = [u for u in old_use['uses'] if u['preference_id'] == a.preference_id and u['definition_revision'] < a.revision]
    checks = {
        'actual_query_and_independent_numeric_oracle_passed': numeric['status'] == 'PASS' and all(numeric['checks'].values()),
        'same_run_has_actual_necessary_model_review': reviewed['status'] == 'PASS' and reviewed['runId'] == run and reviewed['checks'].get('real_model_review_evidence_preserved') is True,
        'public_version_and_catalog_hash_unchanged': facts['project'] == previous['facts']['project'],
        'all_original_personal_definition_bytes_and_identities_preserved': all(any(all(r[k] == old[k] for k in immutable) for r in facts['personal']) for old in original),
        'other_accounts_keep_their_personal_heads': all(any(r['id'] == old['id'] and r['revision'] == old['revision'] and r['current_revision'] == old['current_revision'] and r['archived'] == old['archived'] for r in facts['personal']) for old in original if old['id'] != a.preference_id),
        'only_the_confirmed_owner_gets_the_new_current_revision': len(new) == 1 and new[0]['user_id'] == a.account and new[0]['current_revision'] == a.revision and not new[0]['archived'],
        'exact_new_text_hash_and_revision_were_used': len(new) == 1 and any(d['id'] == a.preference_id and d['revision'] == a.revision and d['content_hash'] == new[0]['content_hash'] and d['definition_text'] == new[0]['definition_text'] for d in numeric['definitions']),
        'normal_confirmation_authorized_project_suggestion': len(facts['authorization']) == 1 and facts['authorization'][0]['choice'] == 'ALLOWED' and len(new) == 1 and facts['authorization'][0]['source_id'] == new[0]['source_id'],
        'one_real_new_query_counts_once': len(uses) == 1 and uses[0]['event_type'] == 'COUNTED' and uses[0]['valid'] is True,
        'future_only_change_does_not_retract_previous_valid_uses': old_use['status'] == 'PASS' and bool(historical) and all(any(u['id'] == old['id'] and u['run_id'] == old['run_id'] and u['definition_revision'] == old['definition_revision'] and u['valid'] is True and u['event_type'] == 'COUNTED' for u in facts['uses']) for old in historical),
        'conflicting_suggestion_waits_for_normal_administrator_decision': len(facts['suggestions']) == 1 and facts['suggestions'][0]['lifecycle'] == 'NEEDS_ADMIN_REVIEW' and facts['suggestions'][0]['blocked_reason'] == 'PUBLIC_CHANGE_REQUIRES_ADMIN' and facts['suggestions'][0]['published_version_id'] is None and facts['suggestions'][0]['assessment_state'] == 'DONE',
    }
    a.output.with_suffix('.sql').write_text('-- Read-only project 2 acceptance metadata; no approval or outcome writes.\n' + ';\n'.join(queries + list(additions.values())) + ';\n')
    report = {'status': 'PASS' if all(checks.values()) else 'FAIL', 'checks': checks, 'runId': run, 'facts': facts,
              'inputs': {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in (a.baseline, a.prior_use_proof, a.numeric_proof, a.review_proof)},
              'boundary': 'Normal browser authored the confirmation, approval and actual query. This audit confirms personal/public and historical-use separation; it is not a new public approval or held-out benchmark.'}
    a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'checks': len(checks), 'failed': [k for k, v in checks.items() if not v]}))
    raise SystemExit(0 if all(checks.values()) else 1)


if __name__ == '__main__':
    main()
