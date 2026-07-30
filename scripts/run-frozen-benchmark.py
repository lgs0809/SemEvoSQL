#!/usr/bin/env python3
"""Execute a frozen synthetic manifest through real conversations, approval and SQL.

The model sees only the question and (when it asks) the frozen natural-language
clarification response. Reference SQL/results remain evaluator-only. Every attempt
gets a fresh conversation, normal approval, durable DB evidence and an outcome;
failures, cancellation, timeout and false blocking stay in the denominator.
"""
import argparse
from collections import Counter, deque
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import importlib.util
import json
from pathlib import Path
import threading
import time
import urllib.error
import uuid

from acceptance_http import LocalAcceptanceClient
from result_acceptance_evidence import final_query_receipt, final_result_artifact, final_receipt_matches_artifact, query_result_contract_matches_answers

spec = importlib.util.spec_from_file_location('oracle', Path(__file__).with_name('verify-offline-catalog.py'))
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)

TERMINAL_RUN_STATUSES = frozenset(('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'))


def run_is_terminal(status):
    return status in TERMINAL_RUN_STATUSES


def admitted_jobs(pool, jobs, submit, workers, pause_file=None, pause_changed=lambda paused: None):
    """Pause only new admissions; active HTTP, normal approvals and evidence collection continue."""
    pending = deque(jobs)
    active, was_paused = {}, False
    while active or pending:
        paused = bool(pause_file and pause_file.exists())
        if paused != was_paused:
            pause_changed(paused)
            was_paused = paused
        while not paused and pending and len(active) < workers:
            job = pending.popleft()
            active[submit(pool, job)] = job
        if not active:
            if not pending: break
            time.sleep(0.25)
            continue
        completed, _ = wait(active, timeout=0.25, return_when=FIRST_COMPLETED)
        for future in completed:
            yield future, active.pop(future)
    if was_paused:
        pause_changed(False)


def run_events(client, run_id):
    events, after = [], 0
    while True:
        page = client.request('/api/semevosql/runs/' + run_id + '/events?limit=500&afterSequence=' + str(after))
        if not page: return events
        if any(event['sequence'] <= after for event in page):
            raise ValueError('Run event cursor did not advance')
        events.extend(page)
        after = page[-1]['sequence']
        if len(page) < 500: return events


def learning_snapshot(project):
    return oracle.sql('semevosql_acceptance', f"""SELECT
        (SELECT count(*) FROM qw_query_example WHERE project_id={project}) AS cases,
        (SELECT count(*) FROM qw_query_pattern WHERE project_id={project}) AS patterns,
        (SELECT count(*) FROM qw_query_pattern_template WHERE project_id={project}) AS templates,
        (SELECT count(*) FROM qw_semantic_sql_pattern WHERE project_id={project}) AS sql_patterns,
        (SELECT count(*) FROM qw_query_case_question_index i JOIN qw_query_example q ON q.id=i.query_example_id WHERE q.project_id={project}) AS case_documents,
        (SELECT count(*) FROM qw_query_case_question_index i JOIN qw_query_example q ON q.id=i.query_example_id WHERE q.project_id={project} AND i.embedding IS NOT NULL) AS case_vectors,
        (SELECT count(*) FROM qw_query_case_embedding i JOIN qw_query_example q ON q.id=i.query_example_id WHERE q.project_id={project}) AS legacy_case_vectors,
        (SELECT count(*) FROM qw_user_semantic_preference WHERE project_id={project}) AS personal_definitions,
        (SELECT count(*) FROM qw_personal_definition_document WHERE project_id={project}) AS personal_documents,
        (SELECT count(*) FROM qw_personal_definition_document WHERE project_id={project} AND embedding IS NOT NULL) AS personal_vectors,
        (SELECT count(*) FROM qw_project_definition_candidate WHERE project_id={project}) AS definition_candidates,
        (SELECT count(*) FROM qw_project_definition_candidate WHERE project_id={project} AND embedding IS NOT NULL) AS candidate_vectors,
        (SELECT count(*) FROM qw_semantic_retrieval_document WHERE project_id={project}) AS documents,
        (SELECT count(*) FROM qw_semantic_retrieval_embedding e JOIN qw_semantic_retrieval_document d ON d.id=e.document_id WHERE d.project_id={project}) AS vectors""")[0]


def inspect(run_id, path):
    q = "'" + str(uuid.UUID(run_id)) + "'"
    queries = {
        'run': f'SELECT * FROM qw_query_run WHERE run_id={q}',
        'events': f'SELECT * FROM qw_run_event WHERE run_id={q} ORDER BY sequence',
        'checkpoints': f'SELECT c.checkpoint_id,c.node_id,c.next_node_id,c.saved_at FROM graphcheckpoint c JOIN graphthread t ON t.thread_id=c.thread_id JOIN qw_native_graph_binding b ON b.graph_thread_id::text=t.thread_name WHERE b.run_id={q} ORDER BY c.saved_at',
        'questions': f'SELECT * FROM qw_runtime_clarification WHERE run_id={q}',
        'answers': f'SELECT * FROM qw_runtime_clarification_answer WHERE clarification_id IN(SELECT clarification_id FROM qw_runtime_clarification WHERE run_id={q})',
        'sqlExecutionAttempts': f'SELECT * FROM qw_sql_execution_attempt WHERE run_id={q} ORDER BY create_time,sql_attempt_id',
        'sourceSubRuns': f'SELECT * FROM qw_source_sub_run WHERE run_id={q} ORDER BY create_time',
        'resultArtifacts': f'SELECT * FROM qw_result_artifact WHERE run_id={q} ORDER BY create_time',
        'merges': f'SELECT * FROM qw_merge_execution WHERE run_id={q} ORDER BY create_time',
        'queryCases': f'SELECT id,status FROM qw_query_example WHERE run_id={q}',
        'caseHistorySnapshots': f'SELECT snapshot_id,snapshot_json FROM qw_query_case_recall_snapshot WHERE run_id={q}',
    }
    # A clarification has its own text column named evidence. Qualify the whole
    # record so PostgreSQL cannot aggregate that column instead of the row.
    sql = 'SELECT jsonb_build_object(' + ','.join("'" + key + "',(SELECT COALESCE(jsonb_agg(evidence.*),'[]'::jsonb) FROM (" + value + ') evidence)' for key, value in queries.items()) + ') AS evidence'
    result = oracle.sql('semevosql_acceptance', sql, preserve_decimals=True)[0]['evidence']
    path.with_suffix('.sql').write_text(sql + ';\n')
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    return result


def table_matches(actual, expected, mapping, dates=(), ordered=False):
    def canonical(rows, columns):
        result = []
        for row in rows:
            if set(row) != set(columns): raise ValueError('Unexpected output columns')
            values = []
            for column, logical in columns.items():
                value = row[column]
                if value is None: values.append(('null', None)); continue
                if logical in dates:
                    values.append(('date', datetime.fromisoformat(str(value).replace('Z','+00:00')).date().isoformat())); continue
                try:
                    number = Decimal(str(value))
                    if not number.is_finite(): raise ValueError('Non-finite output')
                    values.append(('number', number))
                except InvalidOperation: values.append(('text', str(value)))
            result.append(tuple(values))
        return result
    try:
        left = canonical(actual, mapping)
        right = canonical(expected, {x:x for x in mapping.values()})
        return left == right if ordered else Counter(left) == Counter(right)
    except (KeyError, ValueError, TypeError, ArithmeticError): return False


def version_is_query_ready(readiness, version_id):
    # SemanticVersionRef is the readiness API contract, distinct from a version-list row.
    return readiness.get('queryReady') is True and readiness.get('activeVersion', {}).get('semanticVersionId') == version_id


def run_case(suite, case, repeat, directory, persist, baseline):
    ident = case['id'] + '-r' + str(repeat)
    folder = directory / ident
    folder.mkdir()
    start = time.monotonic()
    record = {'attemptId':ident,'caseId':case['id'],'family':case['family'],'split':case['split'],
        'repeat':repeat,'status':'RUNNING','startedAt':datetime.now(timezone.utc).isoformat()}
    persist(record)
    client = LocalAcceptanceClient(suite['account'])
    base = f"/api/semevosql/projects/{suite['projectId']}/conversations"
    cid = client.request(base, 'POST', {'title':'冻结合成评测 · '+ident})['conversationId']
    key = 'frozen-benchmark-' + str(uuid.uuid4())
    record.update(conversationId=cid, requestId=key)
    persist(record)
    submitted = client.request(base + '/' + cid + '/messages', 'POST', {
        'content':case['question'],'idempotencyKey':key,'requestId':key,'approvalMode':'REQUIRE_APPROVAL'})
    run_id = submitted['run']['runId']
    record.update(runId=run_id, conversationId=cid, requestId=key)
    persist(record)
    approved_plans, answered, human_seconds = set(), False, 0.0
    deadline = start + 720
    while True:
        run = client.request('/api/semevosql/runs/' + run_id)
        if run_is_terminal(run['status']): break
        if run['status'] == 'WAITING_HUMAN':
            begin_wait = time.monotonic()
            events = run_events(client, run_id)
            plans = [row for row in events if row['eventType']=='APPROVAL_PLAN_SNAPSHOT']
            latest = plans[-1] if plans else None
            if latest and latest['sequence'] not in approved_plans:
                plan = json.loads(latest['payload'])
                if plan.get('projectVersionId') != suite['projectVersionId'] or plan.get('bindingDependencies'):
                    record['reason'] = 'UNEXPECTED_VERSION_OR_PERSONAL_DEPENDENCY'
                    break
                client.request(base+'/'+cid+'/runs/'+run_id+'/human-review','POST',{
                    'approved':True,'feedback':'','idempotencyKey':key+':approve:'+str(latest['sequence'])})
                approved_plans.add(latest['sequence'])
            else:
                try: question = client.request('/api/semevosql/runs/'+run_id+'/clarification')
                except urllib.error.HTTPError as error:
                    if error.code != 404: raise
                    question = None
                if not question or not case['mustClarify'] or answered or '净收入' not in question.get('rawExpression',''):
                    record['reason'] = 'UNEXPECTED_CLARIFICATION_OR_WAIT'
                    break
                client.request('/api/semevosql/runs/'+run_id+'/clarification/'+question['clarificationId']+'/answer','POST',{
                    'revision':question['revision'],'idempotencyKey':key+':answer','selectedOption':None,
                    'customAnswer':case['clarificationAnswer'],'scope':'QUERY'})
                answered = True
            human_seconds += time.monotonic()-begin_wait
            persist(record)
        if time.monotonic() >= deadline:
            record['reason'] = 'CLIENT_WAIT_LIMIT'
            break
        time.sleep(2)
    if not run_is_terminal(run['status']):
        client.request('/api/semevosql/runs/'+run_id+'/cancel','POST',{'idempotencyKey':key+':cancel'})
        for _ in range(30):
            run = client.request('/api/semevosql/runs/'+run_id)
            if run_is_terminal(run['status']): break
            time.sleep(1)
    evidence = inspect(run_id, folder/'run.json')
    receipt, artifact = final_query_receipt(evidence), final_result_artifact(evidence)
    plans = [json.loads(row['payload']) for row in evidence['events'] if row['event_type']=='APPROVAL_PLAN_SNAPSHOT']
    mapping = dict(case['columns'])
    temporary_contract = True
    if '$queryMeasure' in mapping:
        measures = plans[-1].get('resultContract',{}).get('queryMeasures',[]) if plans else []
        temporary_contract = len(measures)==1 and query_result_contract_matches_answers(plans[-1], evidence, suite['account'])
        if len(measures)==1: mapping[measures[0]['outputCode']] = mapping.pop('$queryMeasure')
    checks = {
        'run_succeeded':evidence['run'][0]['status']=='SUCCEEDED',
        'normal_frozen_plan_approval':bool(approved_plans) and bool(plans) and all(p['projectVersionId']==suite['projectVersionId'] for p in plans),
        'published_version_frozen':evidence['run'][0]['project_version_id']==suite['projectVersionId'],
        'only_authorized_source':{x['datasource_id'] for x in evidence['sqlExecutionAttempts'] if x['phase']=='QUERY'}=={suite['datasourceId']},
        'clarification_decision_correct':bool(evidence['questions'])==case['mustClarify'] and answered==case['mustClarify'],
        'temporary_contract_exact':temporary_contract,
        'receipt_matches_gold':bool(receipt) and receipt['status']=='SUCCEEDED' and table_matches(receipt['result_json']['data'],case['expectedRows'],mapping,case['dateColumns'],case['ordered']),
        'artifact_matches_gold':bool(artifact) and table_matches(artifact['data_json'],case['expectedRows'],mapping,case['dateColumns'],case['ordered']),
        'receipt_artifact_consistent':final_receipt_matches_artifact(evidence),
        'native_checkpoint_saved':len(evidence['checkpoints'])>=10,
        'actual_model_planning':any(json.loads(e['payload']).get('planningTrace',{}).get('modelCallCount',0)>0
            for e in evidence['events'] if e['event_type']=='PLANNING_TRACE'),
        'heldout_not_captured':not evidence['queryCases'],
        'learning_assets_frozen':learning_snapshot(suite['projectId'])==baseline,
    }
    elapsed = time.monotonic()-start
    record.update(status='PASS' if all(checks.values()) else 'FAIL',checks=checks,
        runStatus=evidence['run'][0]['status'],errorCode=evidence['run'][0]['error_code'],
        wallSeconds=elapsed,automatedHumanResponseSeconds=human_seconds,machineSeconds=elapsed-human_seconds,
        finishedAt=datetime.now(timezone.utc).isoformat(),evidence=str(folder/'run.json'))
    record['planningModelCalls'] = sum(json.loads(e['payload']).get('planningTrace',{}).get('modelCallCount',0)
        for e in evidence['events'] if e['event_type']=='PLANNING_TRACE')
    persist(record)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite',required=True,type=Path)
    parser.add_argument('--expected-suite-sha256',required=True)
    parser.add_argument('--output-directory',required=True,type=Path)
    parser.add_argument('--workers',type=int,choices=(1,2),default=1)
    parser.add_argument('--case',action='append')
    parser.add_argument('--repeats',type=int,choices=(1,5),default=5)
    parser.add_argument('--pause-file',type=Path,help='Existing local file pauses the next case; active Runs continue normally')
    args = parser.parse_args()
    if args.output_directory.exists(): raise ValueError('Retain actual attempts; choose a new directory')
    if hashlib.sha256(args.suite.read_bytes()).hexdigest()!=args.expected_suite_sha256:
        raise ValueError('The reviewed frozen manifest bytes changed')
    suite = json.loads(args.suite.read_bytes())
    if (suite['scope']!='FROZEN_SYNTHETIC_300_NOT_EXTERNAL_PRODUCTION_BENCHMARK' or suite['projectId']!=5
        or suite['projectVersionId']!=13 or suite['datasourceId']!=4 or suite['database']!='semevosql_quality_benchmark_20261002'):
        raise ValueError('Only the named isolated frozen synthetic evaluation is supported')
    dev = {x['family'] for x in suite['cases'] if x['split']=='development'}
    held = {x['family'] for x in suite['cases'] if x['split']=='held_out'}
    if len(suite['cases'])!=300 or dev & held: raise ValueError('Frozen case/family count mismatch')
    selected = [x for x in suite['cases'] if not args.case or x['id'] in args.case]
    if not selected or args.case and set(args.case) != {x['id'] for x in selected}:
        raise ValueError('Select existing frozen cases')
    for case in selected:
        if oracle.sql(suite['database'],case['oracleSql'],preserve_decimals=True)!=case['expectedRows']:
            raise ValueError('Database truth changed after freeze: '+case['id'])
    baseline = learning_snapshot(suite['projectId'])
    if any(value for key,value in baseline.items() if key not in ('documents','vectors')) or baseline['documents']!=6 or baseline['vectors']!=6:
        raise ValueError('Evaluation project must have exactly six ready models and no reusable learned assets')
    version = LocalAcceptanceClient(suite['account']).request(f"/api/semevosql/projects/{suite['projectId']}/versions")
    if not any(v['id']==suite['projectVersionId'] and v['status']=='PUBLISHED' for v in version): raise ValueError('Frozen version is not published')
    readiness = LocalAcceptanceClient(suite['account']).request(f"/api/semevosql/projects/{suite['projectId']}/semantic-readiness")
    if not version_is_query_ready(readiness, suite['projectVersionId']):
        raise ValueError('Frozen version must be active and query-ready')
    jobs = [(c,r) for c in selected for r in range(1,(args.repeats if c['split']=='held_out' else 1)+1)]
    args.output_directory.mkdir(parents=True)
    results, lock = {}, threading.Lock()
    summary = {'scope':suite['scope'],'suiteSha256':hashlib.sha256(args.suite.read_bytes()).hexdigest(),
        'runnerSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'plannedRuns':len(jobs),'workers':args.workers,'learningBaseline':baseline,'results':results,
        'operatorAdmissionPauses':[],'startedAt':datetime.now(timezone.utc).isoformat()}
    def save_summary():
        tmp = args.output_directory/'summary.tmp'
        tmp.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
        tmp.replace(args.output_directory/'summary.json')
    def persist(record):
        with lock:
            results[record['attemptId']] = record
            save_summary()
    pause_started = None
    def pause_changed(paused):
        nonlocal pause_started
        with lock:
            now = datetime.now(timezone.utc).isoformat()
            if paused:
                pause_started = time.monotonic()
                summary['operatorAdmissionPauses'].append({'startedAt':now,'reason':'LOCAL_ADMISSION_PAUSE_FILE'})
            else:
                summary['operatorAdmissionPauses'][-1].update(finishedAt=now,seconds=time.monotonic()-pause_started)
                pause_started = None
            save_summary()
        print(json.dumps({'newAdmissionsPaused':paused,'at':now,'activeRunsInterrupted':False}),flush=True)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        def submit(pool, job):
            case, repeat = job
            return pool.submit(run_case,suite,case,repeat,args.output_directory,persist,baseline)
        for future, (case, repeat) in admitted_jobs(pool,jobs,submit,args.workers,args.pause_file,pause_changed):
            try: result = future.result()
            except Exception as error:
                result = results.get(case['id']+'-r'+str(repeat), {'attemptId':case['id']+'-r'+str(repeat),'caseId':case['id'],'family':case['family'],'split':case['split'],'repeat':repeat})
                result.update(status='ERROR',reason=type(error).__name__,message=str(error)[:300])
                if not result.get('runId') and result.get('requestId'):
                    # A lost HTTP response must not lose the identity of a committed request.
                    recovered = oracle.sql('semevosql_acceptance', "SELECT run_id FROM qw_query_run WHERE request_id='"+
                        result['requestId']+"' AND project_id=5")
                    if len(recovered)==1: result['runId'] = recovered[0]['run_id']
                if result.get('runId'):
                    try:
                        cleanup = LocalAcceptanceClient(suite['account'])
                        state = cleanup.request('/api/semevosql/runs/'+result['runId'])
                        if not run_is_terminal(state['status']):
                            cleanup.request('/api/semevosql/runs/'+result['runId']+'/cancel',
                                'POST',{'idempotencyKey':result['requestId']+':error-cancel'})
                        for _ in range(30):
                            state = cleanup.request('/api/semevosql/runs/'+result['runId'])
                            result['runStatus'] = state['status']
                            if run_is_terminal(state['status']): break
                            time.sleep(1)
                        proof = args.output_directory/result['attemptId']/'error-run.json'
                        inspect(result['runId'], proof)
                        result['errorEvidence'] = str(proof)
                        if not run_is_terminal(result['runStatus']):
                            result['cancellationError'] = 'RUN_NOT_TERMINAL_REQUIRES_FOLLOWUP'
                    except Exception as cancellation:
                        result['cancellationError'] = type(cancellation).__name__
                persist(result)
            print(json.dumps({key:result.get(key) for key in ('attemptId','status','runStatus','reason','errorCode')},ensure_ascii=False),flush=True)
    summary['finishedAt'] = datetime.now(timezone.utc).isoformat()
    summary['counts'] = dict(Counter(x['status'] for x in results.values()))
    summary['learningFinal'] = learning_snapshot(suite['projectId'])
    persist(next(iter(results.values())))
    raise SystemExit(0 if all(x['status']=='PASS' for x in results.values()) else 1)


if __name__=='__main__': main()
