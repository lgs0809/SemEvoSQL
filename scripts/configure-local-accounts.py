#!/usr/bin/env python3
"""Configure independent synthetic local accounts; retain credentials and existing business owners.

Passwords are generated once in an ignored 0600 file. Only PBKDF2 hashes and explicit project scopes
enter server configuration. Repeated runs retain all account credentials; no business data is touched.
"""
import argparse,hashlib,json,os,secrets
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def configure(project):
    directory=ROOT/'deploy/.acceptance-private';directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    credentials=directory/'accounts.json'
    if credentials.exists():
        records=json.loads(credentials.read_text())
        if records['projectId']!=project:raise ValueError('Existing account scope retained; use its original project')
    else:
        records={'projectId':project,'accounts':[]}
        for name in ['semevosql-acceptance-owner','sem_member_a','sem_member_b','sem_member_c','sem_outsider']:
            password=secrets.token_urlsafe(24);salt=secrets.token_bytes(16)
            digest=hashlib.pbkdf2_hmac('sha256',password.encode(),salt,310000,32)
            records['accounts'].append({'username':name,'password':password,'passwordHash':'{pbkdf2@SpringSecurity_v5_8}'+(salt+digest).hex(),
                'administrator':name=='semevosql-acceptance-owner','projectIds':[] if name=='sem_outsider' else [project]})
        with os.fdopen(os.open(credentials,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as f:json.dump(records,f,indent=2)
    config={'semevosql':{'security':{'enabled':True,'accounts':{r['username']:{k:r[k] for k in ['passwordHash','administrator','projectIds']} for r in records['accounts']}}}}
    env=ROOT/'deploy/.env.acceptance.local';lines=env.read_text().splitlines()
    key='SEMEVOSQL_LOCAL_SECURITY_JSON';value=json.dumps(config,separators=(',',':'))
    previous=[line for line in lines if line.startswith(key+'=')]
    if previous and previous[0]!=key+"='"+value+"'":raise ValueError('Different existing account configuration retained')
    if not previous:
        with env.open('a') as f:f.write(key+"='"+value+"'\n")
    os.chmod(env,0o600);os.chmod(credentials,0o600)
    print(json.dumps({'status':'CONFIGURED_REQUIRES_DEPLOYMENT','accountCount':len(records['accounts']),'projectId':project,'credentialFile':str(credentials),'credentialsPrinted':False}))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--project',type=int,required=True);configure(p.parse_args().project)
