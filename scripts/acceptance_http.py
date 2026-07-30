"""Cookie/CSRF client for the isolated local account registry; never exposes credentials in evidence."""
import http.cookiejar,json,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

class LocalAcceptanceClient:
    def __init__(self, account='semevosql-acceptance-owner', base='http://127.0.0.1:18093'):
        if base not in ('http://127.0.0.1:18093','http://127.0.0.1:3303'):raise ValueError('Local acceptance addresses only')
        self.base=base;self.cookies=http.cookiejar.CookieJar()
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(self.cookies))
        self.session={};self.account=account
        self.refresh()
        if self.session.get('enabled'):
            from urllib.parse import urlencode
            rows=json.loads((ROOT/'deploy/.acceptance-private/accounts.json').read_text())['accounts']
            record=next(r for r in rows if r['username']==account)
            self.request('/api/semevosql/auth/login','POST',urlencode({'username':account,'password':record['password']}).encode(),{'Content-Type':'application/x-www-form-urlencoded'})
            self.refresh()
            if self.session.get('username')!=account:raise RuntimeError('Local login did not establish requested identity')
    def refresh(self):
        self.session=self.request('/api/semevosql/auth/session')
    def request(self,path,method='GET',body=None,headers=None):
        if not path.startswith('/api/'):raise ValueError('Expected local API path')
        h=dict(headers or {})
        if isinstance(body,(dict,list)):
            body=json.dumps(body,ensure_ascii=False).encode();h.setdefault('Content-Type','application/json')
        if method not in ('GET','HEAD','OPTIONS') and self.session.get('csrfToken'):
            h[self.session['csrfHeader']]=self.session['csrfToken']
        with self.opener.open(urllib.request.Request(self.base+path,method=method,data=body,headers=h),timeout=90) as response:
            raw=response.read();return json.loads(raw) if raw else None
