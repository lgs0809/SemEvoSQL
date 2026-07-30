"""Read-only evaluator identity for unique, governed direct ATTRIBUTE outputs.

Gold, model outputs and original scores are immutable. JOIN/CTE/UNION or
ambiguous identities remain unsupported. No question IDs or answer values are read.
"""
import hashlib
import json
import re
from frozen_output_identity import FROM, approved_output_mapping

SCORER_VERSION = 'approved-governed-direct-attribute-identity-v3'
PROJECTION_FIELDS = frozenset(('model_code', 'column_name', 'business_name', 'data_type', 'role',
    'expression', 'synonyms', 'description', 'unit', 'nullable_flag', 'sensitivity_level',
    'masking_policy', 'allow_aggregation', 'allow_filter', 'allow_projection', 'allow_export',
    'allow_send_to_llm', 'status', 'retrieval_json'))
DIRECT = re.compile(r'(?:(?P<qualifier>[a-z_][a-z0-9_]*)\.)?(?P<field>[a-z_][a-z0-9_]*)(?:\s+(?:as\s+)?(?P<alias>[a-z_][a-z0-9_]*))?', re.I)


def verify_governed_snapshot(snapshot_bytes, expected_hash, catalog_binding):
    if hashlib.sha256(snapshot_bytes).hexdigest() != expected_hash:
        raise ValueError('Reviewed governed snapshot bytes changed')
    snapshot = json.loads(snapshot_bytes)
    if snapshot.get('catalogBinding') != catalog_binding:
        raise ValueError('Governed snapshot belongs to another frozen import/version')
    version = snapshot.get('version', {})
    if (version.get('project_id'), version.get('version_id'), version.get('catalog_hash'),
        version.get('status')) != (catalog_binding['projectId'], catalog_binding['projectVersionId'],
        catalog_binding['publishedCatalogHash'], 'PUBLISHED'):
        raise ValueError('Governed snapshot is not this published version')
    identities = set()
    for row in snapshot.get('attributes', []):
        identity = (row['model_code'], row['binding_code'])
        if identity in identities:
            raise ValueError('Duplicate model/binding identity')
        identities.add(identity)
        binding, definition = row['binding_json'], row['definition_json']
        projection = definition.get('specification', {}).get('legacyProjection', {})
        if (row['project_id'], row['project_version_id'], row['asset_type'], row['definition_format']) != (
            catalog_binding['projectId'], catalog_binding['projectVersionId'], 'ATTRIBUTE', 'LEGACY_PROJECTION'):
            raise ValueError('Attribute provenance differs from frozen version membership')
        if set(projection) != PROJECTION_FIELDS or projection != row['column_projection']:
            raise ValueError('Immutable projection and frozen column differ')
        if row['definition_content_hash'] != row['actual_definition_content_hash']:
            raise ValueError('Immutable definition content hash differs')
        if (binding.get('model'), binding.get('code'), binding.get('definition'), binding.get('definitionRevision')) != (
            row['model_code'], row['binding_code'], row['definition_code'], row['definition_revision']):
            raise ValueError('Binding identity differs from versioned definition')
        if (definition.get('code'), definition.get('revision'), definition.get('type'), definition.get('name')) != (
            row['definition_code'], row['definition_revision'], 'ATTRIBUTE', binding.get('roleName')):
            raise ValueError('Definition revision/type/role differs')
        if binding.get('roleName') != projection.get('business_name'):
            raise ValueError('Original column business role differs')
        if (binding.get('legacyAssetKey'), row['asset_key'], projection.get('column_name'), projection.get('model_code')) != (
            row['asset_key'], row['asset_key'], row['asset_key'], row['model_code']):
            raise ValueError('Binding does not describe this exact model field')
        if (definition.get('aliases'), binding.get('aliases'), binding.get('attributeMappings')) != ([], [], {}):
            raise ValueError('Alias or computed mapping cannot define a direct field')
        if row.get('dictionary_code') is not None or row.get('dictionary_revision') is not None:
            raise ValueError('Dictionary binding is not a direct attribute')
    if not identities:
        raise ValueError('No frozen governed attributes')
    return snapshot


def select_items(oracle, source):
    match = re.match(r'\s*select\s+', oracle, re.I)
    if not match:
        raise ValueError('Unsupported Oracle select')
    fragment = oracle[match.end():source.start()]
    items, start, depth, quoted = [], 0, 0, False
    for index, char in enumerate(fragment):
        if char == "'": quoted = not quoted
        if quoted: continue
        if char == '(': depth += 1
        if char == ')': depth -= 1
        if depth < 0: raise ValueError('Unbalanced Oracle expression')
        if char == ',' and depth == 0:
            items.append(fragment[start:index].strip()); start = index + 1
    if quoted or depth != 0: raise ValueError('Unbalanced Oracle expression')
    return items + [fragment[start:].strip()]


def approved_governed_output_mapping(case, plan, catalog, datasource_id, snapshot):
    binding = snapshot.get('catalogBinding', {})
    if (plan.get('projectId'), plan.get('projectVersionId')) != (
        binding.get('projectId'), binding.get('projectVersionId')):
        raise ValueError('Approved plan belongs to another governed version')
    mapping = dict(case['columns'])
    expected = plan.get('expectedResult', {}).get('columns', [])
    if len(expected) != len(set(expected)):
        raise ValueError('Duplicate approved result identities')
    missing = [code for code in mapping if code not in expected]
    if not missing:
        if set(mapping) != set(expected):
            raise ValueError('Approved result has unexpected or missing outputs')
        return mapping, []
    oracle = case['oracleSql']
    if re.search(r'\b(join|union|with)\b', oracle, re.I):
        raise ValueError('Unsupported ambiguous Oracle source')
    sources = list(FROM.finditer(oracle))
    if len(sources) != 1:
        raise ValueError('Oracle source must be unique')
    source = sources[0]
    schema, table = source.group(1) or 'public', source.group(2)
    source_alias = source.group(3)
    if source_alias and source_alias.lower() in ('where', 'group', 'order', 'limit', 'offset'):
        source_alias = None
    items = [DIRECT.fullmatch(item) for item in select_items(oracle, source)]
    proofs = []
    for original in missing:
        if any(d.get('code') == original for d in catalog.get('dimensions', [])):
            continue  # Existing v2 time-bucket contract, after direct attributes are bound.
        direct = [item for item in items if item and (item.group('alias') or item.group('field')) == mapping[original]]
        if len(direct) != 1:
            raise ValueError('Gold output is not one direct Oracle field')
        item = direct[0]
        field = item.group('field')
        if item.group('qualifier') and item.group('qualifier').lower() not in (table.lower(), str(source_alias).lower()):
            raise ValueError('Oracle field belongs to another source alias')
        candidates = []
        for model in catalog.get('entities', []):
            for attr in model.get('attributes', []):
                if attr.get('code') != original or attr.get('mapping', {}).get('column') != field:
                    continue
                tables = [t for t in model.get('source', {}).get('tables', [])
                    if t.get('alias') == attr.get('mapping', {}).get('source')]
                if len(tables) != 1:
                    continue
                physical = tables[0].get('schema', 'public') + '.' + tables[0]['table']
                if physical.lower() != (schema + '.' + table).lower():
                    continue
                approved = [m for m in plan.get('models', []) if m.get('modelCode') == model['code']
                    and m.get('physicalTable') == physical and m.get('datasourceId') == datasource_id]
                if len(approved) == 1:
                    candidates.append((model, attr, physical))
        if len(candidates) != 1:
            raise ValueError('Frozen model/attribute/physical source is ambiguous')
        model, attr, physical = candidates[0]
        dimensions = [d for d in plan.get('dimensions', []) if d.get('modelCode') == model['code']
            and d.get('columnName') == field and d.get('dimensionType') == 'ATTRIBUTE'
            and d.get('expression') in (None, field)]
        if len(dimensions) != 1:
            raise ValueError('Approved governed dimension is missing or ambiguous')
        dimension = dimensions[0]
        ref = dimension.get('definitionBinding', {})
        rows = [row for row in snapshot['attributes'] if row['model_code'] == model['code']
            and row['binding_code'] == ref.get('bindingCode') and row['definition_code'] == ref.get('definitionCode')
            and row['definition_revision'] == ref.get('definitionRevision') and row['asset_key'] == field]
        if len(rows) != 1 or ref.get('modelCode') != model['code']:
            raise ValueError('Approved ATTRIBUTE revision is not one frozen binding')
        row = rows[0]
        if row['physical_table'] != physical or row['datasource_id'] != datasource_id:
            raise ValueError('Governed binding belongs to another physical source')
        column = row['column_projection']
        if (ref.get('roleName'), dimension.get('businessName')) != (row['binding_json']['roleName'], row['definition_json']['name']):
            raise ValueError('Approved attribute role/name drifted')
        if not (column['allow_projection'] is True and column['allow_send_to_llm'] is True
            and column['status'] == 'ENABLED' and row['model_status'] == 'ENABLED'
            and column['expression'] in (None, '', field)):
            raise ValueError('Governed direct field is unavailable or computed')
        if ref.get('confirmedAliases', []) != [] or ref.get('dictionaryCode') is not None or ref.get('dictionaryRevision') is not None:
            raise ValueError('Approved aliases/dictionary changed the attribute identity')
        alias = dimension['dimensionCode']
        groups = [g for g in plan.get('groupBy', []) if g.get('alias') == alias
            and g.get('modelCode') == model['code'] and g.get('columnName') == field
            and g.get('expression') == field and g.get('timeBucketGranularity') is None]
        projections = [p for p in plan.get('projections', []) if p.get('alias') == alias]
        if len(groups) != 1 or len(projections) != 1:
            raise ValueError('Approved group/projection identity is ambiguous')
        projection = projections[0]
        if projection.get('projectionType') != 'DIMENSION' or any(projection.get(k) != groups[0].get(k)
            for k in ('modelCode', 'columnName', 'expression', 'timeBucketGranularity')):
            raise ValueError('Approved direct projection differs from grouping')
        if alias not in expected or alias in mapping:
            raise ValueError('Output identity collision')
        mapping[alias] = mapping.pop(original)
        proofs.append({'originalOutput': original, 'approvedOutput': alias, 'modelCode': model['code'],
            'datasourceId': datasource_id, 'physicalTable': physical, 'columnName': field,
            'definitionBinding': ref, 'definitionContentHash': row['definition_content_hash'],
            'projectionSha256': hashlib.sha256(json.dumps(column, sort_keys=True, ensure_ascii=False,
                separators=(',', ':')).encode()).hexdigest()})
    remaining_case = dict(case, columns=mapping)
    final, buckets = approved_output_mapping(remaining_case, plan, catalog, datasource_id)
    if set(final) != set(expected):
        raise ValueError('Approved result has unexpected or missing outputs')
    return final, proofs + buckets
