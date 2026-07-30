#!/usr/bin/env python3
"""Hold a temporary lock in the isolated acceptance business DB, release after a real failed SQL trace.
Run while the selected Run waits for normal browser approval, then approve through the UI.
No application or business rows are changed. A bounded transaction lifetime is a second cleanup guard.
"""
import argparse, json, re, select, subprocess, time, uuid
from pathlib import Path

CONTAINER = 'semevosql-acceptance-metadata-db-1'
META = 'semevosql_acceptance'
BUSINESS = 'semevosql_acceptance_business'

def query(sql, database=META):
    result = subprocess.run(['docker','exec','-i',CONTAINER,'psql','-X','-A','-t','-v','ON_ERROR_STOP=1',
        '-U','acceptance','-d',database], input=sql, text=True, capture_output=True, check=True, timeout=10)
    return result.stdout.strip()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id',required=True)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--max-seconds',type=int,default=120)
    parser.add_argument('--namespace',help='Named synthetic quality fixture created by seed-quality-business.py')
    parser.add_argument('--crash-backend',action='store_true',help='Kill only the local acceptance backend after its actual SQL is observed waiting on this lock; restart it after rollback')
    args=parser.parse_args()
    rid=str(uuid.UUID(args.run_id))
    if args.output.exists(): raise ValueError('Keep earlier evidence; choose a new output')
    if not 10 <= args.max_seconds <= 120: raise ValueError('Use a bounded 10..120 second local lock')
    if query(f"SELECT status FROM qw_query_run WHERE run_id='{rid}'") != 'WAITING_HUMAN':
        raise ValueError('The target must be waiting for normal browser approval')
    business=BUSINESS
    if args.namespace:
        if not re.fullmatch(r'[a-z][a-z0-9_]{0,23}',args.namespace):raise ValueError('Invalid synthetic namespace')
        business='semevosql_quality_'+args.namespace
        if query('SELECT fixture FROM public.fixture_identity WHERE id=1',business)!='SEMEVOSQL_QUALITY_V1':
            raise ValueError('Refusing to lock an unidentified business fixture')
        bound=query(f"""SELECT count(*) FROM qw_query_run r
            JOIN qw_project p ON p.id=r.project_id
            JOIN qw_project_datasource_binding b ON b.project_id=r.project_id AND b.project_version_id=r.project_version_id
            JOIN datasource d ON d.id=b.datasource_id
            WHERE r.run_id='{rid}' AND p.project_code='semevosql-quality-{args.namespace}'
            AND d.database_name='{business}' AND d.username='semevosql_reader_{args.namespace}'
            AND d.host='metadata-db' AND d.port=5432""")
        if bound!='1':raise ValueError('Run must use the exact named isolated quality datasource')
    if query(f"SELECT count(*) FROM qw_query_run WHERE status='RUNNING' AND run_id<>'{rid}'") != '0':
        raise ValueError('Do not interfere with another running acceptance query')
    trace_sql=f"""SELECT coalesce(jsonb_agg(s ORDER BY s.create_time),'[]'::jsonb) FROM qw_sql_trace s
        JOIN qw_attempt a ON a.id=s.attempt_id WHERE a.episode_id=(SELECT episode_id FROM qw_query_run WHERE run_id='{rid}') AND s.status='FAILED';"""
    lock_sql="""BEGIN;
SET LOCAL idle_in_transaction_session_timeout='150s';
SET LOCAL lock_timeout='3s';
LOCK TABLE public.orders IN ACCESS EXCLUSIVE MODE;
SELECT 'LOCK_HELD:' || pg_backend_pid();
"""
    args.output.with_suffix('.sql').write_text('-- Isolated business DB '+business+' only; run in an interactive session.\n'+lock_sql+'-- Release with ROLLBACK;\nROLLBACK;\n\n-- Metadata verification\n'+trace_sql+'\n')
    started=time.monotonic(); observations=[]; reason='MAX_HOLD_REACHED'; held=False; killed=False
    child=subprocess.Popen(['docker','exec','-i',CONTAINER,'psql','-X','-A','-t','-v','ON_ERROR_STOP=1','-U','acceptance','-d',business],
        stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
    try:
        child.stdin.write(lock_sql); child.stdin.flush()
        while time.monotonic()-started<8:
            readable,_,_=select.select([child.stdout],[],[],1)
            if readable:
                line=child.stdout.readline().strip()
                if line.startswith('LOCK_HELD:'):
                    held=True; print(line,flush=True); break
            if child.poll() is not None: raise RuntimeError('Lock session stopped before readiness')
        if not held: raise RuntimeError('Lock acquisition was not confirmed')
        while time.monotonic()-started<args.max_seconds:
            traces=json.loads(query(trace_sql))
            active=json.loads(query("SELECT coalesce(jsonb_agg(t),'[]'::jsonb) FROM (SELECT pid,state,wait_event_type,wait_event,left(query,300) AS query FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND state='active') t",business))
            observations.append({'elapsedSeconds':round(time.monotonic()-started,3),'failedTraces':traces,'activeQueries':active})
            if args.crash_backend:
                attempts=json.loads(query(f"SELECT coalesce(jsonb_agg(t),'[]'::jsonb) FROM qw_sql_execution_attempt t WHERE run_id='{rid}'"))
                observations[-1]['sqlExecutionAttempts']=attempts
                if any(a['status']=='RUNNING' for a in attempts) and any(a.get('wait_event_type')=='Lock' and 'orders' in a.get('query','') for a in active):
                    subprocess.run(['docker','kill','--signal','KILL','semevosql-acceptance-backend-1'],check=True,capture_output=True,text=True,timeout=15)
                    killed=True;reason='BACKEND_KILLED_DURING_REAL_SQL';break
            if traces:
                reason='REAL_SQL_FAILURE_OBSERVED';break
            time.sleep(.5)
    finally:
        try:
            child.stdin.write('ROLLBACK;\n\\q\n');child.stdin.flush()
            stdout,stderr=child.communicate(timeout=10)
            clean=child.returncode==0
        except (BrokenPipeError,subprocess.TimeoutExpired):
            child.terminate();stdout,stderr=child.communicate(timeout=10);clean=False
        restarted=False
        if killed:
            subprocess.run(['docker','start','semevosql-acceptance-backend-1'],check=True,capture_output=True,text=True,timeout=15)
            restarted=True
        result={'backendKilled':killed,'backendRestarted':restarted,'runId':rid,'database':business,'namespace':args.namespace,'lockConfirmed':held,'releaseReason':reason,'rollbackProcessSucceeded':clean,
            'elapsedSeconds':round(time.monotonic()-started,3),'observations':observations,'stderr':stderr}
        args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps({k:v for k,v in result.items() if k!='observations'},ensure_ascii=False))
    if not clean or reason!=('BACKEND_KILLED_DURING_REAL_SQL' if args.crash_backend else 'REAL_SQL_FAILURE_OBSERVED'): raise SystemExit(1)

if __name__=='__main__':main()
