"""Strict evaluator-only time-bucket output identity from frozen source metadata.

Does not inspect answer values or question identifiers. Unsupported SQL/forms and
ambiguous mappings fail closed; the original Gold and Run are never changed.
"""
import re
import hashlib
import json

SCORER_VERSION = 'approved-time-bucket-identity-v2'
IDENTIFIER = r'[a-z_][a-z0-9_]*'
BUCKET = re.compile(r"date_trunc\s*\(\s*'(day|week|month|quarter|year)'\s*,\s*(?:([a-z_][a-z0-9_]*)\.)?([a-z_][a-z0-9_]*)\s*\)\s*::\s*date\s+as\s+([a-z_][a-z0-9_]*)", re.I)
FROM = re.compile(r'\bfrom\s+(?:([a-z_][a-z0-9_]*)\.)?([a-z_][a-z0-9_]*)(?:\s+(?:as\s+)?([a-z_][a-z0-9_]*))?', re.I)


def approved_output_mapping(case, plan, catalog, datasource_id):
    original = dict(case['columns'])
    mapping = dict(original)
    expected = plan.get('expectedResult', {}).get('columns', [])
    if len(expected) != len(set(expected)):
        raise ValueError('Duplicate approved result identities')
    missing = [code for code in mapping if code not in expected]
    if not missing:
        return mapping, []
    dimensions = catalog.get('dimensions', [])
    entities = catalog.get('entities', [])
    oracle = case['oracleSql']
    # For unqualified source columns we only support one physical SQL table.
    if re.search(r'\b(join|union|with)\b', oracle, re.I):
        raise ValueError('Unsupported ambiguous oracle source')
    sources = list(FROM.finditer(oracle))
    if len(sources) != 1:
        raise ValueError('Oracle source must be unique')
    source = sources[0]
    source_schema = source.group(1) or 'public'
    source_alias = source.group(3)
    if source_alias and source_alias.lower() in ('where','group','order','limit','offset'):
        source_alias = None
    proofs = []
    for code in missing:
        dims = [d for d in dimensions if d.get('code') == code]
        if len(dims) != 1 or mapping[code] not in case['dateColumns']:
            raise ValueError('Missing output is not one frozen date dimension')
        dim = dims[0]
        models = [e for e in entities if e.get('code') == dim.get('entity')]
        if len(models) != 1:
            raise ValueError('Frozen model identity is ambiguous')
        model = models[0]
        attrs = [a for a in model.get('attributes', []) if a.get('code') == dim.get('attribute')]
        if len(attrs) != 1 or attrs[0].get('dataType') not in ('datetime','date','timestamp'):
            raise ValueError('Frozen dimension is not a time field')
        attr = attrs[0]
        tables = [t for t in model.get('source', {}).get('tables', []) if t.get('alias') == attr.get('mapping', {}).get('source')]
        if len(tables) != 1:
            raise ValueError('Frozen physical source is ambiguous')
        table = tables[0]
        field = attr['mapping']['column']
        physical = table.get('schema','public') + '.' + table['table']
        if source.group(2).lower() != table['table'].lower() or source_schema.lower() != table.get('schema','public').lower():
            raise ValueError('Oracle and frozen physical sources differ')
        buckets = [b for b in BUCKET.finditer(oracle) if b.group(3).lower() == field.lower()
            and (not b.group(2) or b.group(2).lower() in (table['table'].lower(), str(source_alias).lower()))]
        if len(buckets) != 1:
            raise ValueError('Frozen time expression is missing or ambiguous')
        grain = buckets[0].group(1).upper()
        approved_models = [m for m in plan.get('models', []) if m.get('modelCode') == dim['entity']]
        if len(approved_models) != 1 or approved_models[0].get('physicalTable') != physical or approved_models[0].get('datasourceId') != datasource_id:
            raise ValueError('Approved model/source differs from frozen identity')
        groups = [g for g in plan.get('groupBy', []) if g.get('modelCode') == dim['entity'] and g.get('columnName') == field
            and g.get('timeBucketGranularity') == grain
            and re.fullmatch(r"DATE_TRUNC\('" + grain.lower() + r"',\s*" + re.escape(field) + r"\)", g.get('expression',''), re.I)]
        if len(groups) != 1:
            raise ValueError('Approved time bucket is missing or ambiguous')
        group = groups[0]
        aliases = [p for p in plan.get('projections', []) if p.get('alias') == group.get('alias')]
        if len(aliases) != 1:
            raise ValueError('Approved output projection is not unique')
        projection = aliases[0]
        if any(projection.get(k) != group.get(k) for k in ('modelCode','columnName','expression','timeBucketGranularity')) or projection.get('projectionType') != 'TIME_BUCKET':
            raise ValueError('Approved projection differs from approved grouping')
        alias = group['alias']
        if alias not in expected or alias in mapping:
            raise ValueError('Output identity collision')
        mapping[alias] = mapping.pop(code)
        proofs.append({'originalOutput':code,'approvedOutput':alias,'modelCode':dim['entity'],'datasourceId':datasource_id,
            'physicalTable':physical,'columnName':field,'granularity':grain,'expression':group['expression']})
    if set(mapping) != set(expected):
        raise ValueError('Approved result has unexpected or missing outputs')
    return mapping, proofs


def verify_frozen_catalog(suite, package_bytes, expected_catalog_hash, import_proof, published_proof):
    if hashlib.sha256(package_bytes).hexdigest() != expected_catalog_hash:
        raise ValueError('Reviewed initialization package bytes changed')
    package = json.loads(package_bytes)
    canonical = 'sha256:' + hashlib.sha256(json.dumps(package, ensure_ascii=False, sort_keys=True,
        separators=(',', ':'), allow_nan=False).encode()).hexdigest()
    imports = [r for r in import_proof.get('imports', []) if r.get('project_id') == suite['projectId']
        and r.get('project_version_id') == suite['projectVersionId'] and r.get('status') == 'COMMITTED'
        and r.get('input_hash') == canonical and r.get('source_fingerprint') == package.get('sourceSchemaFingerprint')]
    published = [r for r in published_proof.get('database', []) if r.get('id') == suite['projectId']
        and r.get('version_id') == suite['projectVersionId'] and r.get('status') == 'PUBLISHED'
        and r.get('active_version_id') == suite['projectVersionId']]
    if len(imports) != 1 or len(published) != 1 or not published[0].get('catalog_hash'):
        raise ValueError('Package does not bind one actual committed and published frozen version')
    return package, {'projectId': suite['projectId'], 'projectVersionId': suite['projectVersionId'],
        'importId': imports[0]['import_id'], 'inputHash': canonical,
        'sourceFingerprint': package['sourceSchemaFingerprint'], 'publishedCatalogHash': published[0]['catalog_hash']}


def verified_approval(evidence, plan_event, request_id, catalog_binding):
    run = evidence['run'][0]
    plan = json.loads(plan_event['payload'])
    events = evidence['events']
    sequence = plan_event['sequence']
    required = [e for e in events if e['event_type'] == 'HUMAN_FEEDBACK_REQUIRED' and e['sequence'] > sequence]
    answers = [e for e in events if e['event_type'] == 'HUMAN_FEEDBACK_ANSWERED' and e['sequence'] > sequence]
    if len(required) != 1 or len(answers) != 1 or required[0]['sequence'] >= answers[0]['sequence']:
        raise ValueError('Latest approval request and answer are not unique or ordered')
    answer = answers[0]
    envelope = json.loads(answer['payload'])
    if json.loads(envelope['answerPayload']).get('approved') is not True:
        raise ValueError('Plan was not approved')
    recovery_bytes = run['recovery_payload'].encode()
    if hashlib.sha256(recovery_bytes).hexdigest() != envelope.get('recoveryHash'):
        raise ValueError('Approval recovery command hash mismatch')
    recovery = json.loads(recovery_bytes)
    expected_key = 'human-feedback:' + request_id + ':approve:' + str(sequence)
    if answer.get('idempotency_key') != expected_key or recovery.get('runId') != run['run_id'] or recovery.get('requestId') != request_id:
        raise ValueError('Approval receipt belongs to another request or plan sequence')
    if recovery.get('recoveredSemanticPlan') != plan:
        raise ValueError('Approved recovery command does not contain this exact plan')
    project = json.loads(run['execution_snapshot'])['payload']['project']
    if any(project.get(k) != catalog_binding.get(k) for k in ('projectId','projectVersionId')) or project.get('catalogHash') != catalog_binding['publishedCatalogHash']:
        raise ValueError('Run snapshot is bound to another published catalog')
    if plan.get('projectId') != catalog_binding['projectId'] or plan.get('projectVersionId') != catalog_binding['projectVersionId']:
        raise ValueError('Plan belongs to another published version')
    applied = [e for e in events if e['event_type'] == 'HUMAN_FEEDBACK_APPLIED' and e['sequence'] > answer['sequence']
        and e.get('payload') and json.loads(e['payload']).get('approved') is True]
    if len(applied) != 1:
        raise ValueError('Approval application is not unique')
    return plan, {'planSequence': sequence, 'requiredSequence': required[0]['sequence'], 'answerSequence': answer['sequence'],
        'appliedSequence': applied[0]['sequence'], 'recoveryHash': envelope['recoveryHash'],
        'planSha256': hashlib.sha256(plan_event['payload'].encode()).hexdigest(), 'receiptKey': expected_key}
