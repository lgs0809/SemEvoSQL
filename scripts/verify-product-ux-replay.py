#!/usr/bin/env python3
"""Cross-check durable replay execution rows against an independent read-only business oracle."""
import argparse
import json
from decimal import Decimal
from pathlib import Path
import subprocess
import uuid
from acceptance_http import LocalAcceptanceClient


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--job-id', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    job_id = str(uuid.UUID(args.job_id))
    if args.output.exists() or args.output.with_suffix('.sql').exists():
        raise ValueError('Keep prior evidence; choose fresh output')
    query = f"SELECT j.id,j.run_id,j.project_id,j.project_version_id,j.status,j.result_json,r.status AS run_status FROM qw_evaluation_job j JOIN qw_query_run r ON r.run_id=j.run_id WHERE j.id='{job_id}' AND j.project_id=2 AND j.job_type='REPLAY'"
    def rows(db, sql):
        raw = subprocess.check_output(['docker','exec','semevosql-acceptance-metadata-db-1','psql','-X','-A','-t','-v','ON_ERROR_STOP=1','-U','acceptance','-d',db,'-c',
                                      'SELECT row_to_json(proof.*) FROM ('+sql+') proof'], text=True)
        return [json.loads(line) for line in raw.splitlines() if line]
    stored = rows('semevosql_acceptance', query)
    if len(stored) != 1:
        raise ValueError('Expected one isolated replay job')
    job = stored[0]
    api = LocalAcceptanceClient().request('/api/semevosql/operations/jobs/'+job_id)
    api_result = api['result_json']
    if isinstance(api_result, dict) and api_result.get('type') == 'jsonb':
        api_result = json.loads(api_result['value'])
    result = job['result_json']
    proof = result.get('proofs', [{}])[0]
    actual = [e for e in proof.get('executionProof', []) if e.get('artifactType') == 'SOURCE_RESULT']
    oracle_sql = "SELECT count(*) AS order_count,sum(amount) AS amount FROM orders WHERE ordered_at>=TIMESTAMP '2026-01-01' AND ordered_at<TIMESTAMP '2026-02-01'"
    oracle = rows('semevosql_acceptance_business', oracle_sql)
    value = actual[0]['rows'][0]['ordered_amount'] if len(actual) == 1 and len(actual[0].get('rows', [])) == 1 else None
    checks = {
        'job_and_run_succeeded': job['status'] == 'SUCCEEDED' and job['run_status'] == 'SUCCEEDED',
        'api_and_database_results_agree': api_result == result,
        'real_planning_and_execution_recorded': proof.get('modelCallCount', 0) > 0 and proof.get('sqlPresent') is True and proof.get('sourceCount') == 1,
        'enabled_case_and_safety_passed': result.get('total') == 1 and result.get('passed') == 1 and result.get('failed') == 0 and result.get('safetyPassed') is True,
        'execution_row_matches_independent_oracle': value is not None and Decimal(str(value)) == Decimal(str(oracle[0]['amount'])),
        'expected_synthetic_business_baseline_preserved': oracle[0]['order_count'] == 5 and Decimal(str(oracle[0]['amount'])) == Decimal('420.00'),
        'bound_parameters_preserve_half_open_month': proof.get('sourceQueries', [{}])[0].get('parameters') == ['2026-01-01T00:00:00', '2026-02-01T00:00:00'],
    }
    report = {'status': 'PASS' if all(checks.values()) else 'FAIL', 'checks': checks, 'job': job, 'oracle': oracle,
              'boundary': 'Actual LIVE replay plus separate exact synthetic-data oracle; not the 300-question benchmark.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.with_suffix('.sql').write_text('-- Metadata DB: semevosql_acceptance\n'+query+';\n-- Business DB: semevosql_acceptance_business\n'+oracle_sql+';\n')
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({'status': report['status'], 'checks': checks}, ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)


if __name__ == '__main__':
    main()
