#!/usr/bin/env python3
"""Read-only exact personal-definition/background evidence in the two isolated acceptance projects."""
import argparse,json,subprocess
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_fixture_scope import business_database

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--preference-id',type=int,required=True);p.add_argument('--namespace',help='Explicit ordinary synthetic fixture namespace');p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.preference_id<=0 or a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Positive identity and fresh evidence path required')
    sql=SourceFileLoader('personal_snapshot_sql',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql
    head=sql('semevosql_acceptance',f'SELECT project_id FROM qw_user_semantic_preference WHERE id={a.preference_id}')
    if len(head)!=1:raise ValueError('Exactly one existing personal identity required')
    project=int(head[0]['project_id']);business_database(project,sql,a.namespace)
    queries={
      'definitions':f"SELECT p.project_id,p.user_id,p.display_phrase,p.current_revision,p.archived,d.*,r.representation_state,r.task_state,r.attempt_count,r.last_error,r.next_attempt_at,r.structured_json FROM qw_user_semantic_preference p JOIN qw_user_semantic_definition_revision d ON d.preference_id=p.id JOIN qw_user_semantic_representation r ON r.preference_id=p.id AND r.source_revision=d.revision WHERE p.id={a.preference_id} AND p.project_id={project} ORDER BY d.revision",
      'structures':f"SELECT s.* FROM qw_user_semantic_structure_revision s JOIN qw_user_semantic_preference p ON p.id=s.preference_id WHERE p.id={a.preference_id} AND p.project_id={project} ORDER BY s.source_revision,s.representation_hash",
      'authorizations':f"SELECT a.* FROM qw_user_semantic_authorization a JOIN qw_user_semantic_preference p ON p.id=a.preference_id WHERE p.id={a.preference_id} AND p.project_id={project} ORDER BY a.definition_revision,a.authorization_revision",
      'uses':f"SELECT u.* FROM qw_user_semantic_preference_usage u JOIN qw_user_semantic_preference p ON p.id=u.preference_id WHERE p.id={a.preference_id} AND p.project_id={project} ORDER BY u.create_time,u.id",
    }
    result={}
    for key,query in queries.items():
      command=['docker','exec','semevosql-acceptance-metadata-db-1','psql','-X','-A','-t','-v','ON_ERROR_STOP=1','-U','acceptance','-d','semevosql_acceptance','-c','SELECT row_to_json(e.*) FROM ('+query+') e']
      result[key]=[json.loads(line) for line in subprocess.run(command,capture_output=True,text=True,check=True).stdout.splitlines() if line]
    result['boundary']='Actual immutable revisions and background records; structure completion is separate from actual query execution and public promotion.'
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');a.output.with_suffix('.sql').write_text(';\n'.join(queries.values())+';\n')
    latest=result['definitions'][-1] if result['definitions'] else {}
    print(json.dumps({'preferenceId':a.preference_id,'representation':latest.get('representation_state'),'task':latest.get('task_state'),'immutableStructures':len(result['structures']),'uses':len(result['uses'])},ensure_ascii=False))
if __name__=='__main__':main()
