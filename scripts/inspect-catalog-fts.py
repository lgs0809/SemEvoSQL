#!/usr/bin/env python3
"""Read-only FTS SQL, EXPLAIN, row and byte evidence from the isolated acceptance DB.

Copies the repository's query to the retained SQL file. It measures PostgreSQL
lexical retrieval; model/vector/rerank quality must be checked separately.
"""
import argparse
import json
import re
import subprocess
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def raw(sql):
    return subprocess.run(['docker', 'exec', 'semevosql-acceptance-metadata-db-1',
        'psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'acceptance',
        '-d', 'semevosql_acceptance', '-c', sql], capture_output=True, text=True, check=True).stdout.strip()


def rows(sql):
    return [json.loads(line) for line in raw('SELECT row_to_json(e.*) FROM (' + sql + ') e').splitlines() if line]


def quote(value):
    return str(value) if isinstance(value, int) else "'" + str(value).replace("'", "''") + "'"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-id', type=int, required=True)
    parser.add_argument('--version-id', type=int, required=True)
    parser.add_argument('--query', default='一月已支付订单支付金额 paid_amount')
    parser.add_argument('--limit', type=int, default=48)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.project_id <= 0 or args.version_id <= 0 or not 1 <= args.limit <= 500:
        parser.error('Positive project/version and LIMIT 1..500 required')
    if args.output.exists() or args.output.with_suffix('.sql').exists():
        raise ValueError('Retain old evidence; choose a new filename')
    scope = rows(f'SELECT id,project_id,catalog_hash FROM qw_project_version WHERE id={args.version_id} AND project_id={args.project_id}')
    if len(scope) != 1 or not scope[0]['catalog_hash']:
        raise ValueError('No matching version with a catalog hash')
    source = (ROOT/'backend/src/main/java/cn/lgs/semevosql/semantic/retrieval/SemanticRetrievalDocumentRepository.java').read_text()
    template = re.search(r'String sql = """(.*?)"""\.formatted\(where\)', source, re.S)
    if not template:
        raise ValueError('Repository query changed; inspect it before adapting this evidence script')
    sql = textwrap.dedent(template.group(1)).strip().replace('%s', 'd.project_id=? AND d.project_version_id=? AND d.catalog_hash=?')
    values = iter([args.query, args.project_id, args.version_id, scope[0]['catalog_hash'], args.limit])
    sql = re.sub(r'\?', lambda match: quote(next(values)), sql)
    hits = rows(sql)
    catalog_scope = f'project_id={args.project_id} AND project_version_id={args.version_id} AND catalog_hash={quote(scope[0]["catalog_hash"])}'
    stats_sql = f'SELECT count(*) AS documents,sum(octet_length(lexical_text)+octet_length(semantic_text)) AS text_bytes FROM qw_semantic_retrieval_document WHERE {catalog_scope}'
    catalog_stats = rows(stats_sql)[0]
    selected_sql = ''
    selected = []
    if hits:
        ids = ','.join(quote(hit['id']) for hit in hits)
        selected_sql = f'SELECT id,document_type,asset_key,model_code,lexical_tokenizer_version,octet_length(lexical_text)+octet_length(semantic_text) AS text_bytes FROM qw_semantic_retrieval_document WHERE {catalog_scope} AND id IN ({ids}) ORDER BY id'
        selected = rows(selected_sql)
    result = {'scope': scope[0], 'query': args.query, 'limit': args.limit, 'catalog': catalog_stats,
        'hits': hits, 'returnedRows': len(hits), 'returnedScoreBytes': len(json.dumps(hits).encode()),
        'loadedDocuments': selected, 'loadedTextBytes': sum(row['text_bytes'] for row in selected),
        'explain': json.loads(raw('EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) ' + sql)),
        'boundary': 'Read-only actual FTS; LIMIT bounds returned rows, not necessarily scanned rows. Not full model-level retrieval acceptance.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    args.output.with_suffix('.sql').write_text('-- Read-only. Metadata acceptance database; not the business database.\n' + stats_sql + ';\n' + sql + ';\nEXPLAIN (ANALYZE,BUFFERS) ' + sql + ';\n' + (selected_sql + ';\n' if selected_sql else ''))
    print(json.dumps({key: result[key] for key in ('catalog', 'returnedRows', 'returnedScoreBytes', 'loadedTextBytes')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
