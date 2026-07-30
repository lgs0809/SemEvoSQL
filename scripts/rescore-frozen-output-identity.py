#!/usr/bin/env python3
"""Read-only, separate rescoring of frozen Runs; never overwrites original results."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
from frozen_output_identity import approved_output_mapping, SCORER_VERSION, verify_frozen_catalog, verified_approval

spec = importlib.util.spec_from_file_location('frozen_runner', Path(__file__).with_name('run-frozen-benchmark.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', required=True, type=Path)
    parser.add_argument('--expected-suite-sha256', required=True)
    parser.add_argument('--catalog', required=True, type=Path)
    parser.add_argument('--expected-catalog-sha256', required=True)
    parser.add_argument('--import-proof', required=True, type=Path)
    parser.add_argument('--published-proof', required=True, type=Path)
    parser.add_argument('--original-summary', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists(): raise ValueError('Use a new evidence path')
    suite_bytes, catalog_bytes, original_bytes = args.suite.read_bytes(), args.catalog.read_bytes(), args.original_summary.read_bytes()
    suite_hash = hashlib.sha256(suite_bytes).hexdigest()
    if suite_hash != args.expected_suite_sha256: raise ValueError('Reviewed Gold bytes changed')
    suite, original = json.loads(suite_bytes), json.loads(original_bytes)
    package, catalog_binding = verify_frozen_catalog(suite, catalog_bytes, args.expected_catalog_sha256,
        json.loads(args.import_proof.read_bytes()), json.loads(args.published_proof.read_bytes()))
    if runner.oracle.sql(suite['database'], 'SELECT current_schema() AS schema')[0]['schema'] != 'public':
        raise ValueError('Implicit Oracle source schema is not the frozen public schema')
    if original['suiteSha256'] != suite_hash: raise ValueError('Original scoring uses another suite')
    cases = {c['id']: c for c in suite['cases']}
    scores = {}
    for attempt_id, raw in original['results'].items():
        score = {'attemptId': attempt_id, 'runId': raw.get('runId'), 'originalStatus': raw['status'], 'rescoredStatus': raw['status']}
        scores[attempt_id] = score
        if raw['status'] not in ('PASS', 'FAIL') or not raw.get('evidence'): continue
        evidence_bytes = Path(raw['evidence']).read_bytes()
        evidence = json.loads(evidence_bytes)
        score['evidenceSha256'] = hashlib.sha256(evidence_bytes).hexdigest()
        events = evidence['events']
        plans = [e for e in events if e['event_type'] == 'APPROVAL_PLAN_SNAPSHOT']
        if not plans: continue
        event = plans[-1]
        try: plan, approval_proof = verified_approval(evidence, event, raw['requestId'], catalog_binding)
        except ValueError as error:
            score['approvalRejected'] = str(error); continue
        case = cases[raw['caseId']]
        try: mapping, proof = approved_output_mapping(case, plan, package['catalog'], suite['datasourceId'])
        except ValueError as error:
            score['mappingRejected'] = str(error); continue
        if not proof: continue
        receipt, artifact = runner.final_query_receipt(evidence), runner.final_result_artifact(evidence)
        checks = dict(raw['checks'])
        checks['receipt_matches_gold'] = bool(receipt) and receipt['status'] == 'SUCCEEDED' and runner.table_matches(
            receipt['result_json']['data'], case['expectedRows'], mapping, case['dateColumns'], case['ordered'])
        checks['artifact_matches_gold'] = bool(artifact) and runner.table_matches(
            artifact['data_json'], case['expectedRows'], mapping, case['dateColumns'], case['ordered'])
        score.update(rescoredStatus='PASS' if all(checks.values()) else 'FAIL', mapping=mapping, bindingProof=proof,
            approvalBinding=approval_proof, checks=checks)
    output = {'scope': suite['scope'], 'scorerVersion': SCORER_VERSION,
        'scorerSha256': hashlib.sha256(Path(__file__).with_name('frozen_output_identity.py').read_bytes()).hexdigest(),
        'rescoreSourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'suiteSha256': suite_hash, 'catalogSha256': hashlib.sha256(catalog_bytes).hexdigest(),
        'originalSummarySha256': hashlib.sha256(original_bytes).hexdigest(), 'catalogBinding': catalog_binding,
        'importProofSha256': hashlib.sha256(args.import_proof.read_bytes()).hexdigest(),
        'publishedProofSha256': hashlib.sha256(args.published_proof.read_bytes()).hexdigest(), 'oracleDefaultSchema': 'public',
        'createdAt': datetime.now(timezone.utc).isoformat(), 'newModelExecutions': 0,
        'originalCounts': dict(Counter(r['status'] for r in original['results'].values())),
        'rescoredCounts': dict(Counter(r['rescoredStatus'] for r in scores.values())), 'results': scores}
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: v for k,v in output.items() if k != 'results'}, ensure_ascii=False))


if __name__ == '__main__': main()
