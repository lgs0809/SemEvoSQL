#!/usr/bin/env python3
"""Restart only the idle isolated backend and prove encoder identity/vectors survive unchanged."""
import argparse,json,subprocess,time,urllib.request
from pathlib import Path
from acceptance_http import LocalAcceptanceClient


def verify(output):
    if output.exists():raise ValueError('Keep previous evidence; choose a fresh output')
    sql="""SELECT json_build_object(
      'activeRuns',(SELECT count(*) FROM qw_query_run WHERE status IN ('QUEUED','RUNNING','WAITING_HUMAN','CANCEL_REQUESTED')),
      'activeVersion',(SELECT active_version_id FROM qw_project WHERE id=2),
      'registry',(SELECT row_to_json(r) FROM qw_embedding_index_registry r WHERE index_scope='SEMANTIC_CATALOG'),
      'semanticFingerprint',(SELECT md5(string_agg(document_id||embedding_version||embedding::text||update_time::text,'' ORDER BY document_id,embedding_version)) FROM qw_semantic_retrieval_embedding),
      'caseFingerprint',(SELECT md5(string_agg(query_example_id||question_type||COALESCE(embedding_version,'')||COALESCE(embedding::text,'')||update_time::text,'' ORDER BY query_example_id,question_type)) FROM qw_query_case_question_index),
      'activities',(SELECT json_agg(row_to_json(a)) FROM (SELECT project_version_id,activity_type,operator_name,create_time FROM qw_project_version_activity WHERE project_id=2 AND project_version_id=3 ORDER BY id) a));"""
    def database():
        return json.loads(subprocess.check_output(['docker','exec','semevosql-acceptance-metadata-db-1','psql',
          '-X','-A','-t','-v','ON_ERROR_STOP=1','-U','acceptance','-d','semevosql_acceptance','-c',sql],text=True))
    before=database();assert before['activeRuns']==0 and before['activeVersion']==3
    output.parent.mkdir(parents=True,exist_ok=True);output.with_suffix('.sql').write_text(sql+'\n')
    output.write_text(json.dumps({'status':'RESTARTING','before':before},ensure_ascii=False,indent=2)+'\n')
    start=time.monotonic();subprocess.run(['docker','restart','semevosql-acceptance-backend-1'],check=True,stdout=subprocess.DEVNULL)
    for n in range(90):
        try:
            with urllib.request.urlopen('http://127.0.0.1:18093/actuator/health',timeout=2) as r:
                if json.load(r)['status']=='UP':break
        except (OSError,ValueError):pass
        time.sleep(1)
    else:raise RuntimeError('Restart did not recover health')
    client=LocalAcceptanceClient();index=client.request('/api/semevosql/operations/projects/2/semantic-index')
    cases=client.request('/api/semevosql/operations/projects/2/query-case-index')
    after=database()
    assert before['semanticFingerprint']==after['semanticFingerprint'] and before['caseFingerprint']==after['caseFingerprint']
    assert before['registry']['embedding_version']==after['registry']['embedding_version']
    assert after['activeVersion']==3 and index['status']=='INDEX_READY' and cases['status']=='INDEX_READY'
    assert {'PUBLISHED','ACTIVATED'}<={a['activity_type'] for a in after['activities']}
    evidence={'status':'PASS_RESTART_STABLE_IDENTITY','recoverySeconds':round(time.monotonic()-start,3),
        'before':before,'after':after,'semanticReadiness':index,'caseReadiness':cases,
        'restartCommand':'docker restart semevosql-acceptance-backend-1'}
    output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':evidence['status'],'recoverySeconds':evidence['recoverySeconds'],
        'activeVersion':after['activeVersion'],'semanticIndex':index['status'],'caseIndex':cases['status']}))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();verify(args.output)
