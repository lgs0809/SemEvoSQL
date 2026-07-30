#!/usr/bin/env python3
"""Explicitly share one existing synthetic acceptance definition through its normal owner API.

This is an authorized acceptance action, not a seed that fabricates usage or public publication.
The second identical request checks that the normal API does not create duplicate candidates.
"""
import argparse,json
from pathlib import Path
from importlib.machinery import SourceFileLoader
from acceptance_http import LocalAcceptanceClient
from acceptance_fixture_scope import business_database
sql=SourceFileLoader('share_definition_proof',str(Path(__file__).with_name('verify-offline-catalog.py'))).load_module().sql

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--preference',type=int,required=True)
    parser.add_argument('--expected-revision',type=int,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--namespace',help='Explicit ordinary synthetic quality fixture namespace')
    parser.add_argument('--account',default='semevosql-acceptance-owner')
    args=parser.parse_args()
    if args.preference<=0 or args.output.exists() or args.output.with_suffix('.sql').exists():raise ValueError('Positive preference and new evidence required')
    query=f"SELECT p.id,p.project_id,p.user_id,p.current_revision,p.archived,d.definition_text,d.content_hash FROM qw_user_semantic_preference p JOIN qw_user_semantic_definition_revision d ON d.preference_id=p.id AND d.revision=p.current_revision WHERE p.id={args.preference}"
    records=sql('semevosql_acceptance',query)
    if len(records)!=1 or (not args.namespace and records[0]['project_id']!=2) or records[0]['user_id']!=args.account or records[0]['archived'] or records[0]['current_revision']!=args.expected_revision:
        raise ValueError('Only the authenticated owner current definition in the identified synthetic project may be shared')
    business_database(records[0]['project_id'],sql,args.namespace)
    client=LocalAcceptanceClient(args.account)
    path=f'/api/semevosql/semantic-preferences/{args.preference}/promote-project'
    confirmation={'definitionRevision':args.expected_revision,'sourceContentHash':records[0]['content_hash']}
    receipts=[client.request(path,'POST',confirmation),client.request(path,'POST',confirmation)]
    source=f"SELECT s.*,c.project_id,c.lifecycle,c.definition_text,c.published_version_id FROM qw_project_definition_source s JOIN qw_project_definition_candidate c ON c.id=s.candidate_id WHERE s.preference_id={args.preference} AND s.definition_revision={args.expected_revision}"
    auth=f"SELECT choice,authorization_revision FROM qw_user_semantic_authorization WHERE preference_id={args.preference} AND definition_revision={args.expected_revision} ORDER BY authorization_revision DESC"
    args.output.with_suffix('.sql').write_text('-- Read-only metadata inspection; sharing is performed by the owner API.\n'+query+';\n'+source+';\n'+auth+';\n')
    after=sql('semevosql_acceptance',query);sources=sql('semevosql_acceptance',source);authorizations=sql('semevosql_acceptance',auth)
    checks={'same_current_complete_definition':records==after,
        'explicit_allowed_choice':bool(authorizations) and authorizations[0]['choice']=='ALLOWED',
        'one_candidate_for_two_normal_requests':len(sources)==1 and receipts[0]['candidateId']==receipts[1]['candidateId']==str(sources[0]['candidate_id']),
        'candidate_preserves_full_text':len(sources)==1 and sources[0]['definition_text']==records[0]['definition_text'],
        'no_publication_manufactured':len(sources)==1 and sources[0]['lifecycle']!='PUBLISHED' and sources[0]['published_version_id'] is None}
    report={'status':'PASS' if all(checks.values()) else 'FAIL','checks':checks,'receipts':receipts,'before':records,'after':after,'sources':sources,'authorization':authorizations,
        'boundary':'Normal authenticated sharing API. No database business writes, query uses, approvals or public version states are manufactured.'}
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':report['status'],'checks':len(checks),'candidateId':receipts[0]['candidateId'],'output':str(args.output)}))
    raise SystemExit(0 if all(checks.values()) else 1)

if __name__=='__main__':main()
