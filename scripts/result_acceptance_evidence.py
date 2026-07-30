"""Select the actual final receipt/artifact, preserving abandoned results as history."""
import json
from decimal import Decimal, InvalidOperation


def final_query_receipt(evidence):
    queries = sorted((r for r in evidence['sqlExecutionAttempts'] if r['phase'] == 'QUERY'),
                     key=lambda r: (r['create_time'], r['sql_attempt_id']))
    return queries[-1] if queries else None


def final_result_artifact(evidence):
    artifacts = {r['artifact_id']: r for r in evidence['resultArtifacts']
                 if r['artifact_type'] in ('DIRECT_RESULT', 'MERGED_RESULT') and r['status'] == 'READY'}
    accepted = sorted((r for r in evidence['events']
                       if r['event_type'] in ('RESULT_ARTIFACT_ACCEPTED', 'RESULT_ARTIFACT_READY')),
                      key=lambda r: r['sequence'])
    if accepted:
        return artifacts.get(json.loads(accepted[-1]['payload']).get('artifactId'))
    # Older deployed runs predate explicit acceptance receipts. Preserve their actual merge pointer.
    merges = sorted((r for r in evidence.get('merges', [])
                     if r['status'] == 'COMPLETED' and r.get('output_artifact_id')),
                    key=lambda r: (r['update_time'], r['merge_id']))
    if merges:
        return artifacts.get(merges[-1]['output_artifact_id'])
    direct = sorted((r for r in artifacts.values() if r['artifact_type'] == 'DIRECT_RESULT'),
                    key=lambda r: (r['update_time'], r['artifact_id']))
    return direct[-1] if direct else None


def final_receipt_matches_artifact(evidence):
    receipt, artifact = final_query_receipt(evidence), final_result_artifact(evidence)
    if not receipt or receipt['status'] != 'SUCCEEDED' or not artifact:
        return False
    result = receipt.get('result_json') or {}
    # These scalar acceptance scripts cover a single data source. Multi-source merged outputs require a merge oracle.
    if len({r['datasource_id'] for r in evidence['sqlExecutionAttempts'] if r['phase'] == 'QUERY'}) != 1:
        return False
    def cell(value):
        try:
            return Decimal(str(value))
        except InvalidOperation:
            return value
    def rows(values):
        return [{k: cell(v) for k, v in row.items()} for row in values]
    return (set(result.get('column', [])) == set(artifact['schema_json'])
            and rows(result.get('data', [])) == rows(artifact['data_json']))


def numeric_table_matches(actual, oracle, column_map, date_columns=(), exact_columns=False):
    """Compare independent tabular values without depending on row order or decimal display precision."""
    from collections import Counter
    from datetime import datetime
    def values(rows, mapping):
        result = []
        for row in rows:
            if exact_columns and set(row) != set(mapping):
                raise ValueError('Unexpected output columns')
            cells = []
            for column, reference in mapping.items():
                value = row[column]
                if reference in date_columns:
                    value = datetime.fromisoformat(str(value).replace('Z', '+00:00')).date().isoformat()
                else:
                    value = Decimal(str(value))
                    if not value.is_finite():
                        raise ValueError('Non-finite value')
                cells.append(value)
            result.append(tuple(cells))
        return Counter(result)
    try:
        return values(actual, column_map) == values(oracle, {v: v for v in column_map.values()})
    except (KeyError, ValueError, TypeError, ArithmeticError):
        return False


def query_result_contract_matches_answers(plan, evidence, principal):
    """A temporary result must refer to an exact submitted answer in this Run, not a private/public asset."""
    import hashlib
    import uuid
    try:
        measures = plan['resultContract']['queryMeasures']
        if not measures:
            return False
        questions = {q['clarification_id']: q for q in evidence['questions']}
        answers = evidence['answers']
        seen = set()
        for measure in measures:
            identity = measure['clarificationId']
            q = questions[identity]
            output = 'q_' + str(uuid.UUID(identity)).replace('-', '') + '_' + str(q['revision'])
            if (measure['outputCode'] in seen or measure['outputCode'] != output
                    or measure['businessName'] != q['raw_expression']
                    or measure['sourceRevision'] != q['revision']
                    or measure['definitionText'] != q['resolved_value']
                    or measure['sourceContentHash'] != hashlib.sha256(q['resolved_value'].encode()).hexdigest()
                    or q['run_id'] != evidence['run'][0]['run_id']
                    or q['status'] != 'ANSWERED' or q['selected_scope'] != 'QUERY'
                    or q['answered_by'] != principal
                    or not any(a['clarification_id'] == identity and a['selected_scope'] == 'QUERY'
                               and a['answered_by'] == principal and a['custom_answer'] == q['resolved_value']
                               for a in answers)):
                return False
            seen.add(measure['outputCode'])
        return True
    except (KeyError, TypeError, ValueError, AttributeError):
        return False
