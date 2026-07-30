#!/usr/bin/env python3
"""Real deployed authentication/authorization checks using normal APIs and durable conversation records."""
import argparse,json,subprocess,urllib.error,urllib.request
from pathlib import Path
from acceptance_http import LocalAcceptanceClient
ROOT=Path(__file__).resolve().parents[1]

def verify(output):
    if output.exists():raise ValueError('Retain earlier evidence')
    evidence={'status':'UNVERIFIED','checks':[]};output.parent.mkdir(parents=True,exist_ok=True)
    def save():output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    def check(name,fn,status=200):
        try:r=fn();actual=200
        except urllib.error.HTTPError as error:r=None;actual=error.code
        evidence['checks'].append({'name':name,'expected':status,'actual':actual,'passed':status==actual});save();assert actual==status,name
        return r
    a=LocalAcceptanceClient('sem_member_a');b=LocalAcceptanceClient('sem_member_b');admin=LocalAcceptanceClient();outsider=LocalAcceptanceClient('sem_outsider')
    check('anonymous cannot inspect project',lambda:urllib.request.urlopen('http://127.0.0.1:18093/api/semevosql/projects/2'),401)
    check('unassigned account cannot inspect project',lambda:outsider.request('/api/semevosql/projects/2'),403)
    assert check('unassigned project list is empty',lambda:outsider.request('/api/semevosql/projects'))==[]
    fixture=ROOT/'deploy/.acceptance-private/identity-fixture.json'
    if fixture.exists():ids=json.loads(fixture.read_text())
    else:
        ids={name:c.request('/api/semevosql/projects/2/conversations','POST',{'title':'账号隔离验收 '+name,'createdBy':'forged-admin'})['conversationId'] for name,c in [('a',a),('b',b)]}
        fixture.write_text(json.dumps(ids));fixture.chmod(0o600)
    check('owner can read own conversation',lambda:a.request('/api/semevosql/projects/2/conversations/'+ids['a']))
    check('another member cannot read owner conversation',lambda:b.request('/api/semevosql/projects/2/conversations/'+ids['a']),403)
    check('administrator cannot read another private conversation',lambda:admin.request('/api/semevosql/projects/2/conversations/'+ids['a']),403)
    check('spoofed identity header cannot read another conversation',lambda:b.request('/api/semevosql/projects/2/conversations/'+ids['a'],headers={'X-User-ID':'sem_member_a'}),403)
    own=check('member conversation list is scoped',lambda:a.request('/api/semevosql/projects/2/conversations'))
    assert ids['a'] in [v['conversationId'] for v in own] and ids['b'] not in [v['conversationId'] for v in own]
    assert all(v['createdBy']=='sem_member_a' for v in own)
    check('member cannot activate catalog with valid CSRF',lambda:a.request('/api/semevosql/projects/2/versions/3/activate','POST',{}),403)
    check('member cannot read administrative model configuration',lambda:a.request('/api/model-config'),403)
    check('member cannot read all project episodes',lambda:a.request('/api/semevosql/operations/projects/2/episodes'),403)
    sql="SELECT json_agg(json_build_object('conversationId',conversation_id,'projectId',project_id,'createdBy',created_by)) FROM qw_project_conversation WHERE conversation_id IN ('"+ids['a']+"','"+ids['b']+"');"
    output.with_suffix('.sql').write_text(sql+'\n')
    raw=subprocess.check_output(['docker','exec','semevosql-acceptance-metadata-db-1','psql','-X','-A','-t','-v','ON_ERROR_STOP=1','-U','acceptance','-d','semevosql_acceptance','-c',sql],text=True)
    evidence['databaseConversations']=json.loads(raw);evidence['fixtures']=ids
    assert {v['createdBy'] for v in evidence['databaseConversations']}=={'sem_member_a','sem_member_b'}
    evidence['status']='PASS';save();print(json.dumps({'status':'PASS','checks':len(evidence['checks']),'databaseOwnersVerified':2,'output':str(output)}))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);verify(p.parse_args().output)
