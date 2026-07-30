#!/usr/bin/env python3
"""Read-only dual-question index evidence and EXPLAIN from the isolated acceptance DB.

Copies the application's FTS SQL into the evidence .sql for manual reruns. The vector
EXPLAIN deliberately uses an existing index vector, so it measures the database path,
not natural-language embedding quality. Real browser runs are recorded separately.
"""
import argparse, json, re, subprocess, textwrap, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def rows(sql):
    raw=subprocess.run(['docker','exec','semevosql-acceptance-metadata-db-1','psql','-X','-A','-t',
        '-v','ON_ERROR_STOP=1','-U','acceptance','-d','semevosql_acceptance','-c',sql],capture_output=True,text=True,check=True)
    return raw.stdout.strip()
def quote(value):
    return 'NULL' if value is None else "'"+str(value).replace("'","''")+"'"
def select(sql):
    return [json.loads(line) for line in rows('SELECT row_to_json(e.*) FROM ('+sql+') e').splitlines() if line]

def main(args):
    if args.output.exists() or args.output.with_suffix('.sql').exists(): raise ValueError('Use fresh evidence filenames')
    scope=select(f"SELECT project_version_id,catalog_hash FROM qw_query_example WHERE project_id={args.project_id} AND status='APPROVED' GROUP BY project_version_id,catalog_hash")
    if len(scope)!=1: raise ValueError('Project must have exactly one approved version/hash for this focused inspector')
    version=scope[0]['project_version_id']; catalog=scope[0]['catalog_hash']
    java=(ROOT/'backend/src/main/java/cn/lgs/semevosql/learning/QueryCaseQuestionIndexRepository.java').read_text()
    def constant(name): return textwrap.dedent(re.search(r'String '+name+r' = """(.*?)"""',java,re.S).group(1)).strip()
    terms=args.terms.split()
    if not terms or any(not t.isalnum() for t in terms): raise ValueError('Provide whitespace-separated letter/digit terms')
    values={'project':str(args.project_id),'version':str(version),'catalog':quote(catalog),'context':'NULL','principal':quote(args.principal),
        'tokenizer':quote('cjk-unigram-bigram-ascii-v1'),'query':quote(' | '.join(terms)),
        'terms':quote('{'+','.join(terms)+'}'),'termCount':str(float(len(terms))),'limit':'10'}
    sql=constant('LEXICAL_SQL').replace('%s',constant('ELIGIBLE'))
    sql=re.sub(r'(?<!:):([a-zA-Z]+)',lambda m:values[m.group(1)],sql)
    distribution=f"""SELECT q.status,d.question_type,d.embedding_dimension,COUNT(*) AS records,
        COUNT(d.embedding) AS vectors,COUNT(*) FILTER (WHERE d.source_hash=qw_query_case_source_hash(q)) AS current_records,
        MAX(d.retry_count) AS max_retries,SUM(octet_length(d.question_text)) AS question_bytes
        FROM qw_query_case_question_index d JOIN qw_query_example q ON q.id=d.query_example_id
        WHERE q.project_id={args.project_id} GROUP BY q.status,d.question_type,d.embedding_dimension ORDER BY 1,2,3"""
    started=time.monotonic(); hits=select(sql); elapsed=time.monotonic()-started
    result={'scope':scope[0], 'distribution':select(distribution),'ftsHits':hits,'ftsResponseBytes':len(json.dumps(hits,ensure_ascii=False).encode()),
        'ftsClientSeconds':round(elapsed,4),'ftsExplain':json.loads(rows('EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) '+sql)),
        'boundaries':['Read-only SQL; no case/result status changed.','Vector EXPLAIN reuses an indexed vector, not a new model output.','LIMIT bounds returned references, not necessarily scanned rows.']}
    probe=select(f"SELECT d.embedding::text AS embedding,d.embedding_model,d.embedding_version,d.embedding_dimension FROM qw_query_case_question_index d JOIN qw_query_example q ON q.id=d.query_example_id WHERE q.project_id={args.project_id} AND d.embedding IS NOT NULL ORDER BY d.generation DESC LIMIT 1")
    vector_sql=''
    if probe:
        v=probe[0]; distance='(d.embedding <=> '+quote(v['embedding'])+'::vector)'
        filters=re.sub(r'(?<!:):([a-zA-Z]+)',lambda m:values[m.group(1)],constant('ELIGIBLE'))
        vector_sql=f"""WITH matches AS (SELECT q.id AS case_id,d.question_type,1-{distance} AS score,
            row_number() OVER (PARTITION BY q.id ORDER BY {distance},d.question_type) AS text_rank
            FROM qw_query_case_question_index d JOIN qw_query_example q ON q.id=d.query_example_id
            WHERE {filters} AND d.embedding IS NOT NULL AND d.embedding_model={quote(v['embedding_model'])}
                AND d.embedding_version={quote(v['embedding_version'])} AND d.embedding_dimension={v['embedding_dimension']})
            SELECT case_id,question_type,score FROM matches WHERE text_rank=1 AND score>0 ORDER BY score DESC,case_id LIMIT 10"""
        result['vectorProbeHits']=select(vector_sql)
        result['vectorExplain']=json.loads(rows('EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) '+vector_sql))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    args.output.with_suffix('.sql').write_text('-- Read-only; isolated synthetic acceptance database.\n'+distribution+';\n'+sql+';\nEXPLAIN (ANALYZE,BUFFERS) '+sql+';\n'+(vector_sql+';\nEXPLAIN (ANALYZE,BUFFERS) '+vector_sql+';\n' if vector_sql else ''))
    print(json.dumps({'ftsHits':len(hits),'ftsResponseBytes':result['ftsResponseBytes'],'distribution':result['distribution']},ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-id',type=int,required=True);parser.add_argument('--principal',default='local-acceptance')
    parser.add_argument('--terms',default='支付 金额');parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.project_id<=0: parser.error('Positive project id required')
    main(args)
