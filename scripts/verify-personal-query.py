#!/usr/bin/env python3
"""Read-only personal-definition provenance, actual SQL receipts and an independent business oracle."""
import argparse,json,uuid
from decimal import Decimal
from pathlib import Path
from result_acceptance_evidence import final_query_receipt, final_result_artifact, final_receipt_matches_artifact, numeric_table_matches
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
from acceptance_fixture_scope import business_database
sql=SourceFileLoader('personal_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence',type=Path,required=True);p.add_argument('--oracle-sql',type=Path,required=True)
    p.add_argument('--result-column',required=True);p.add_argument('--oracle-column',required=True)
    p.add_argument('--expected-value',type=Decimal,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--group-result-column');p.add_argument('--group-oracle-column')
    p.add_argument('--account',default='semevosql-acceptance-owner',help='Existing isolated test account owning this Run')
    p.add_argument('--namespace',help='Explicit ordinary synthetic quality fixture namespace')
    mode=p.add_mutually_exclusive_group()
    mode.add_argument('--require-structured',action='store_true',help='Require exact immutable personal AST consumed by deterministic compilation')
    mode.add_argument('--require-text',action='store_true',help='Require the executed plan to use an exact confirmed text revision before its AST is ready')
    mode.add_argument('--require-frozen-asset',action='store_true',help='Require an old confirmed metric asset snapshot projected as an isolated private metric')
    a=p.parse_args()
    if a.output.exists() or a.output.with_suffix('.sql').exists():raise ValueError('Keep prior results; choose a fresh output')
    evidence=json.loads(a.evidence.read_bytes());run=evidence['run'][0];run_id=str(uuid.UUID(run['run_id']))
    database=business_database(run['project_id'],sql,a.namespace)
    plans=[json.loads(e['payload']) for e in evidence['events'] if e['event_type']=='SEMANTIC_PLAN_SNAPSHOT']
    approved=[json.loads(e['payload']) for e in evidence['events'] if e['event_type']=='APPROVAL_PLAN_SNAPSHOT']
    bindings=plans[-1].get('bindingDependencies',[]) if plans else []
    refs=[b for b in bindings if b.get('source')=='USER']
    personal_sql="""SELECT p.id,p.project_id,p.user_id,p.display_phrase,p.current_revision,p.archived,
        d.revision,d.definition_text,d.definition_snapshot,d.asset_type,d.asset_key,d.content_hash,d.dependency_fingerprint,d.source_id,d.source_kind,
        r.representation_state,r.task_state,r.attempt_count,r.last_error,r.structured_json,
        (SELECT a.choice FROM qw_user_semantic_authorization a WHERE a.preference_id=p.id AND a.definition_revision=d.revision
          ORDER BY a.authorization_revision DESC LIMIT 1) AS sharing
        FROM qw_user_semantic_preference p JOIN qw_user_semantic_definition_revision d ON d.preference_id=p.id
        JOIN qw_user_semantic_representation r ON r.preference_id=p.id AND r.source_revision=d.revision
        WHERE d.source_id IN (SELECT clarification_id FROM qw_runtime_clarification WHERE run_id='%s')
          OR (p.id,d.revision) IN (SELECT preference_id,definition_revision FROM qw_user_semantic_preference_usage WHERE run_id='%s')
        ORDER BY p.id,d.revision"""%(run_id,run_id)
    usage_sql="SELECT * FROM qw_user_semantic_preference_usage WHERE run_id='%s' ORDER BY preference_id,definition_revision"%run_id
    definitions=sql('semevosql_acceptance',personal_sql);uses=sql('semevosql_acceptance',usage_sql)
    oracle_query=a.oracle_sql.read_text().strip().rstrip(';');oracle=sql(database,oracle_query)
    a.output.with_suffix('.sql').write_text('-- Metadata database: semevosql_acceptance\n'+personal_sql+';\n'+usage_sql+';\n-- Business database: '+database+'\n'+oracle_query+';\n')
    if bool(a.group_result_column)!=bool(a.group_oracle_column):raise ValueError('Both group columns are required')
    expected=sum((Decimal(str(row[a.oracle_column])) for row in oracle),Decimal(0)) if a.group_result_column else Decimal(str(oracle[0][a.oracle_column])) if len(oracle)==1 else None
    def equal(rows):
        if a.group_result_column:
            return numeric_table_matches(rows,oracle,{a.result_column:a.oracle_column,a.group_result_column:a.group_oracle_column},[a.group_oracle_column])
        try:return len(rows)==1 and Decimal(str(rows[0][a.result_column]))==expected
        except (KeyError,TypeError,ValueError,ArithmeticError):return False
    receipt=final_query_receipt(evidence);artifact=final_result_artifact(evidence)
    def provenance(b):
        return any(d['id']==b.get('sourceRecordId') and d['revision']==b.get('sourceRevision')
            and d['user_id']==b.get('principalId') and d['project_id']==run['project_id']
            and d['content_hash']==b.get('sourceContentHash') and d['definition_text']==b.get('definitionText')
            and d['dependency_fingerprint']==b.get('dependencyFingerprint') for d in definitions)
    checks={
        'actual_run_succeeded':run['status']=='SUCCEEDED',
        'owner_can_read_run':LocalAcceptanceClient(a.account).request('/api/semevosql/runs/'+run_id)['runId']==run_id,
        'full_personal_definition_is_preserved':bool(definitions) and all(d['definition_text'] for d in definitions),
        'exact_private_revision_frozen_in_plan':bool(refs) and all(provenance(b) for b in refs),
        'normal_browser_plan_approval_recorded':any(e['event_type'] in ('REQUEST_APPROVED','HUMAN_FEEDBACK_ANSWERED','HUMAN_FEEDBACK_APPLIED') for e in evidence['events']),
        'approval_uses_frozen_plan':bool(plans) and bool(approved) and plans[-1]==approved[-1],
        'independent_oracle_matches_expected':expected==a.expected_value,
        'actual_query_receipt_matches_oracle':bool(receipt) and receipt['status']=='SUCCEEDED' and equal(receipt['result_json']['data']),
        'durable_final_result_matches_oracle':bool(artifact) and equal(artifact['data_json']),
        'final_sql_and_display_artifact_agree':final_receipt_matches_artifact(evidence),
        'native_checkpoints_exist':len(evidence['checkpoints'])>=10,
        'use_is_unique_per_definition_and_run':len({(u['preference_id'],u['definition_revision'],u['run_id']) for u in uses})==len(uses),
        'saving_without_actual_query_does_not_count':any(s['phase']=='QUERY' for s in evidence['sqlExecutionAttempts']) or not any(u['event_type']=='COUNTED' for u in uses),
        'real_model_planning_recorded':any(json.loads(e['payload']).get('planningTrace',{}).get('modelCallCount',0)>0 for e in evidence['events'] if e['event_type']=='PLANNING_TRACE')}
    structures=[]
    if a.require_frozen_asset:
        calculation=('modelCode','expression','filterExpression','aggregation','timeColumn','unit')
        def frozen_asset(b):
            definition=next((d for d in definitions if d['id']==b.get('sourceRecordId') and d['revision']==b.get('sourceRevision')),None)
            if not definition or definition['asset_type']!='METRIC' or definition['source_kind']!='ASSET_CONFIRMATION':return False
            snapshot=definition['definition_snapshot'];code=f"p_{definition['id']}_{definition['revision']}"
            target=snapshot.get('target',{});selected=[m for m in plans[-1]['metrics'] if m['metricCode']==code]
            return snapshot.get('completeDefinitionRecorded') is True and target.get('metricCode')==definition['asset_key'] \
                and b.get('representationCode')==code and b.get('representationHash')==definition['content_hash'] \
                and len(selected)==1 and selected[0].get('definitionBinding') is None \
                and all(selected[0].get(k)==target.get(k) for k in calculation)
        checks.update({
            'exact_immutable_metric_asset_snapshot_consumed':bool(refs) and all(frozen_asset(b) for b in refs),
            'private_asset_is_selected_without_public_asset_identity':bool(refs) and all(not any(m['metricCode']==b['assetKey'] for m in plans[-1]['metrics']) for b in refs),
            'deterministic_frozen_asset_compilation_used':bool(plans) and plans[-1].get('compilerMode')=='DETERMINISTIC',
            'saved_asset_source_remains_confirmed_and_private':bool(definitions) and all(d['source_kind']=='ASSET_CONFIRMATION' and d['sharing']=='PRIVATE' for d in definitions)})
    if a.require_text:
        plan=plans[-1] if plans else {};measures=plan.get('resultContract',{}).get('personalMeasures',[])
        checks.update({
            'actual_plan_froze_text_without_background_ast':bool(refs) and all(b.get('representationCode') is None and b.get('representationHash') is None for b in refs),
            'constrained_generation_consumed_confirmed_text':plan.get('compilerMode')=='CONSTRAINED_GENERATION',
            'requested_outputs_retain_exact_text_identity':bool(measures) and all(any(m['definitionId']==b.get('sourceRecordId') and m['definitionRevision']==b.get('sourceRevision') and m['sourceContentHash']==b.get('sourceContentHash') and m['businessName']==b.get('phrase') for b in refs) for m in measures),
            'actual_query_returns_only_frozen_requested_columns':bool(receipt) and receipt['status']=='SUCCEEDED' and set(receipt['result_json'].get('column',[]))==set(plan.get('expectedResult',{}).get('columns',[]))})
    if a.require_structured:
        structure_sql="SELECT preference_id,source_revision,representation_hash,structured_json FROM qw_user_semantic_structure_revision WHERE (preference_id,source_revision) IN (SELECT preference_id,definition_revision FROM qw_user_semantic_preference_usage WHERE run_id='%s') ORDER BY preference_id,source_revision"%run_id
        structures=sql('semevosql_acceptance',structure_sql)
        with a.output.with_suffix('.sql').open('a') as f:f.write('-- Exact immutable representations in metadata database\n'+structure_sql+';\n')
        def frozen_structure(b):
            return any(row['preference_id']==b.get('sourceRecordId') and row['source_revision']==b.get('sourceRevision')
                and row['representation_hash']==b.get('representationHash')
                and row['structured_json'].get('sourceContentHash')==b.get('sourceContentHash')
                and row['structured_json']['metric']['code']==b.get('representationCode') for row in structures)
        checks.update({
            'exact_immutable_representation_frozen':bool(refs) and all(frozen_structure(b) for b in refs),
            'private_metric_is_selected':bool(refs) and all(any(m['metricCode']==b.get('representationCode') for m in plans[-1]['metrics']) for b in refs),
            'deterministic_private_compilation_used':bool(plans) and plans[-1].get('compilerMode')=='DETERMINISTIC',
            'text_source_remains_preserved_after_structuring':bool(definitions) and all(d['definition_text'] and d['representation_state']=='STRUCTURED_ACTIVE' for d in definitions)})
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'runId':run_id,
        'oracle':oracle,'definitions':definitions,'uses':uses,'frozenPersonalReferences':refs,'immutableStructures':structures,
        'boundary':'Read-only real records and independent business SQL; failures remain failures. Background structure and public promotion are separately assessed.'}
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':report['status'],'checks':len(checks),'failed':[k for k,v in checks.items() if not v],'output':str(a.output)},ensure_ascii=False))
    raise SystemExit(0 if all(checks.values()) else 1)
if __name__=='__main__':main()
