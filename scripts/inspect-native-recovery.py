#!/usr/bin/env python3
"""Read-only Run/checkpoint/answer evidence from the isolated SemEvoSQL acceptance database."""
import argparse,json,subprocess,uuid
from pathlib import Path

def inspect(run_id, output):
    run_id=str(uuid.UUID(run_id))
    if output.exists() or output.with_suffix('.sql').exists():raise ValueError('Retain earlier evidence; choose a new output')
    q="'"+run_id+"'"
    queries={
        'run':f"SELECT run_id,episode_id,status,attempt_id,owner_instance,lease_expire_time,revision,current_node,error_code,error_message,deadline_epoch_millis,human_wait_started_ms,human_wait_deadline_ms,paused_execution_remaining_ms,task_budget_unit_ms,task_budget_count,create_time,start_time,finish_time,project_id,project_version_id,thread_id FROM qw_query_run WHERE run_id={q}",
        'binding':f"SELECT * FROM qw_native_graph_binding WHERE run_id={q}",
        'checkpoints':f"SELECT c.checkpoint_id,c.parent_checkpoint_id,c.node_id,c.next_node_id,c.saved_at,c.state_content_type,md5(c.state_data::text) AS state_hash,octet_length(c.state_data::text) AS state_bytes FROM graphcheckpoint c JOIN graphthread t ON t.thread_id=c.thread_id JOIN qw_native_graph_binding b ON b.graph_thread_id::text=t.thread_name WHERE b.run_id={q} ORDER BY c.saved_at,c.checkpoint_id",
        'questions':f"SELECT * FROM qw_runtime_clarification WHERE run_id={q} ORDER BY create_time",
        'answers':f"SELECT * FROM qw_runtime_clarification_answer WHERE clarification_id IN (SELECT clarification_id FROM qw_runtime_clarification WHERE run_id={q})",
        'events':f"SELECT * FROM qw_run_event WHERE run_id={q} ORDER BY sequence",
        'caseHistorySnapshots':f"SELECT snapshot_id,run_id,recall_key,project_id,project_version_id,catalog_hash,principal_id,create_time,md5(snapshot_json::text) AS content_hash,octet_length(snapshot_json::text) AS content_bytes,snapshot_json FROM qw_query_case_recall_snapshot WHERE run_id={q} ORDER BY create_time,snapshot_id",
        'sourceSubRuns':f"SELECT * FROM qw_source_sub_run WHERE run_id={q} ORDER BY create_time",
        'queryTasks':f"SELECT * FROM qw_query_task WHERE run_id={q} ORDER BY ordinal_no",
        'sqlExecutionAttempts':f"SELECT * FROM qw_sql_execution_attempt WHERE run_id={q} ORDER BY create_time,sql_attempt_id",
        'sqlTraces':f"SELECT s.* FROM qw_sql_trace s JOIN qw_attempt a ON a.id=s.attempt_id WHERE a.episode_id=(SELECT episode_id FROM qw_query_run WHERE run_id={q}) ORDER BY a.attempt_no,s.create_time,s.id",
        'resultArtifacts':f"SELECT * FROM qw_result_artifact WHERE run_id={q} ORDER BY create_time",
        'nodeEffects':f"SELECT * FROM qw_run_node_effect WHERE run_id={q} ORDER BY create_time,node_key",
        'merges':f"SELECT * FROM qw_merge_execution WHERE run_id={q} ORDER BY create_time",
        'queryCases':f"SELECT id,run_id,status,intent_type,original_question,normalized_question,quality_proof_json,quarantine_reason,update_time FROM qw_query_example WHERE run_id={q} ORDER BY create_time,id",
        'caseAssetReferences':f"SELECT r.asset_type,r.asset_key,r.asset_fingerprint,r.catalog_hash FROM qw_query_example_asset_ref r JOIN qw_query_example e ON e.id=r.query_example_id WHERE e.run_id={q} ORDER BY r.asset_type,r.asset_key",
        'feedback':f"SELECT * FROM qw_feedback WHERE episode_id=(SELECT episode_id FROM qw_query_run WHERE run_id={q}) ORDER BY create_time",
        'conversationTurns':f"SELECT run_id,thread_id,turn_sequence,user_question,planner_output,canonical_query,context_summary_json,result_artifact_id,status,revision,create_time,update_time FROM qw_conversation_turn WHERE thread_id=(SELECT thread_id FROM qw_query_run WHERE run_id={q}) ORDER BY turn_sequence",
    }
    result={}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.with_suffix('.sql').write_text(';\n'.join(queries.values())+';\n')
    for name,sql in queries.items():
        command=['docker','exec','semevosql-acceptance-metadata-db-1','psql','-X','-A','-t','-v','ON_ERROR_STOP=1',
            '-U','acceptance','-d','semevosql_acceptance','-c','SELECT row_to_json(native_evidence_row.*) FROM ('+sql+') native_evidence_row']
        raw=subprocess.run(command,capture_output=True,text=True)
        if raw.returncode:raise RuntimeError(name+': '+raw.stderr)
        result[name]=[json.loads(line) for line in raw.stdout.splitlines() if line]
    result['boundary']='Read-only actual records; no successful state is manufactured.'
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'run':run_id,'status':result['run'][0]['status'],'checkpoints':len(result['checkpoints']),
        'questions':len(result['questions']),'answers':len(result['answers'])},ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run-id',required=True);parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();inspect(args.run_id,args.output)
