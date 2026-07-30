#!/usr/bin/env python3
"""Read-only proof of a single result containing an exact personal revision and a published metric."""
import argparse
import json
from decimal import Decimal
from importlib.machinery import SourceFileLoader
from pathlib import Path
from result_acceptance_evidence import final_query_receipt, final_result_artifact, final_receipt_matches_artifact, numeric_table_matches

sql = SourceFileLoader('mixed_query_proof', str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--personal-proof', type=Path, required=True)
    p.add_argument('--publication-proof', type=Path, required=True)
    p.add_argument('--oracle-sql', type=Path, required=True)
    p.add_argument('--personal-oracle-column', required=True)
    p.add_argument('--public-oracle-column', required=True)
    p.add_argument('--group-result-column')
    p.add_argument('--group-oracle-column')
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.output.exists() or a.output.with_suffix('.sql').exists():
        raise ValueError('Preserve earlier results; use a fresh output')
    e = json.loads(a.evidence.read_bytes())
    personal = json.loads(a.personal_proof.read_bytes())
    publication = json.loads(a.publication_proof.read_bytes())
    run = e['run'][0]
    if run['project_id'] not in (1, 2) or personal.get('status') != 'PASS' or publication.get('status') != 'PASS':
        raise ValueError('Actual isolated personal and publication proofs must both pass first')
    if personal.get('runId') != run['run_id'] or not personal.get('frozenPersonalReferences'):
        raise ValueError('Personal proof must belong to this exact Run')
    if len(personal['frozenPersonalReferences']) != 1:
        raise ValueError('This proof requires one personal measure and one public measure')
    private_code = personal['frozenPersonalReferences'][0].get('representationCode')
    if not private_code:
        raise ValueError('An exact structured personal metric is required')
    public = publication['publicMetric']
    public_code = public['metricCode']
    columns = {private_code, public_code}
    query = a.oracle_sql.read_text().strip().rstrip(';')
    oracle = sql('semevosql_acceptance_business', query)
    if not oracle or (not a.group_result_column and len(oracle) != 1):
        raise ValueError('Independent oracle must return expected non-empty shape')
    if bool(a.group_result_column) != bool(a.group_oracle_column):
        raise ValueError('Both group columns are required')
    expected = {
        private_code: Decimal(str(oracle[0][a.personal_oracle_column])),
        public_code: Decimal(str(oracle[0][a.public_oracle_column])),
    }

    if a.group_result_column:
        columns.add(a.group_result_column)

    def equal(rows):
        if a.group_result_column:
            return numeric_table_matches(rows,oracle,{private_code:a.personal_oracle_column,public_code:a.public_oracle_column,
                a.group_result_column:a.group_oracle_column},[a.group_oracle_column],exact_columns=True)
        try:
            return len(rows) == 1 and set(rows[0]) == columns and all(
                Decimal(str(rows[0][code])) == amount for code, amount in expected.items())
        except (KeyError, ValueError, TypeError, ArithmeticError):
            return False

    plans = [json.loads(x['payload']) for x in e['events'] if x['event_type'] == 'SEMANTIC_PLAN_SNAPSHOT']
    approved = [json.loads(x['payload']) for x in e['events'] if x['event_type'] == 'APPROVAL_PLAN_SNAPSHOT']
    plan = plans[-1] if plans else {}
    selected = next((m for m in plan.get('metrics', []) if m['metricCode'] == public_code), {})
    receipt, artifact = final_query_receipt(e), final_result_artifact(e)
    checks = {
        'run_succeeded': run['status'] == 'SUCCEEDED',
        'personal_provenance_and_query_proof_passed': all(personal['checks'].values()),
        'exact_public_version_frozen': run['project_version_id'] == publication['version']['id'] == plan.get('projectVersionId'),
        'exact_public_definition_binding_preserved': selected.get('definitionBinding') == public['definitionBinding'],
        'private_reference_preserved_alongside_public': plan.get('bindingDependencies') == personal['frozenPersonalReferences'],
        'both_requested_output_columns_preserved': set(plan.get('expectedResult', {}).get('columns', [])) == columns,
        'normal_approval_uses_exact_plan': bool(approved) and approved[-1] == plan,
        'actual_single_table_query_matches_both_independent_values': bool(receipt) and receipt['status'] == 'SUCCEEDED' and equal(receipt['result_json']['data']),
        'durable_final_table_matches_both_values': bool(artifact) and equal(artifact['data_json']),
        'final_sql_and_display_artifact_agree': final_receipt_matches_artifact(e),
        'compatible_confirmed_meanings_are_not_reasked': not e['questions'],
    }
    a.output.with_suffix('.sql').write_text('-- Read-only independent business oracle\n' + query + ';\n')
    report = {'status': 'PASS' if all(checks.values()) else 'FAIL', 'checks': checks,
              'runId': run['run_id'], 'oracle': oracle, 'publicMetric': selected,
              'frozenPersonalReferences': personal['frozenPersonalReferences'],
              'boundary': 'Uses independently passed exact personal provenance and publication proofs; no states or counts are written.'}
    a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'checks': len(checks), 'failed': [k for k, v in checks.items() if not v], 'output': str(a.output)}, ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)


if __name__ == '__main__':
    main()
