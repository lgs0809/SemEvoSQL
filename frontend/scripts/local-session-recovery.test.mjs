import assert from 'node:assert/strict';
import { test } from 'node:test';
import axios, { AxiosError } from 'axios';
import { currentSession, localSession, sessionExpired, sessionNotice } from '../src/services/localSession.ts';

const forbidden = (config, data) => new AxiosError('HTTP 403', 'ERR_BAD_REQUEST', config, undefined,
  { config, data, status: 403, statusText: 'Forbidden', headers: {} });

function adapter(t, handle) {
  const original = axios.defaults.adapter;
  currentSession.value = {enabled: true, username: 'owner', csrfHeader:'X-CSRF-TOKEN', csrfToken:'old-test-token'};
  sessionExpired.value = false;
  t.after(() => { axios.defaults.adapter = original; currentSession.value = undefined; sessionNotice.value = ''; sessionExpired.value = false; });
  axios.defaults.adapter = handle;
}

test('a rejected stale CSRF request refreshes once and preserves its exact body and decision key', async t => {
  const writes = [];
  let sessions = 0;
  adapter(t, async config => {
    if (config.url.endsWith('/auth/session')) {
      sessions++;
      return {config, status:200, data:{enabled:true, username:'owner', csrfHeader:'X-CSRF-TOKEN', csrfToken:'new-test-token'}};
    }
    writes.push({body:config.data, key:config.headers.get('Idempotency-Key'), csrf:config.headers.get('X-CSRF-TOKEN')});
    if (writes.length === 1) throw forbidden(config, 'Invalid CSRF Token');
    return {config, status:200, data:{id:42}};
  });
  const result = await axios.post('/api/semevosql/projects/2/definition-candidates/9/decisions',
    {action:'ASSOCIATE',reason:'same meaning'}, {headers:{'Idempotency-Key':'original-decision-key'}});
  assert.equal(result.data.id,42);
  assert.equal(sessions,1);
  assert.equal(writes.length,2);
  assert.equal(writes[0].body,writes[1].body);
  assert.equal(writes[0].key,writes[1].key);
  assert.deepEqual(writes.map(w=>w.csrf),['old-test-token','new-test-token']);
});

test('switching the principal in another tab never replays the former user action', async t => {
  let writes = 0;
  adapter(t, async config => {
    if (config.url.endsWith('/auth/session')) return {config,status:200,
      data:{enabled:true,username:'member',csrfHeader:'X-CSRF-TOKEN',csrfToken:'member-test-token'}};
    writes++;
    throw forbidden(config,'Invalid CSRF Token');
  });
  await assert.rejects(axios.post('/api/semevosql/projects/2/action',{privateIntent:'owner intent'}),/登录账号已变化/);
  assert.equal(writes,1);
  assert.match(sessionNotice.value,/旧操作未提交/);
});

test('a background 401 does not discard the last authenticated operator needed for safe recovery', async t => {
  let authenticated = true;
  let writes = 0;
  adapter(t, async config => {
    if (config.url.endsWith('/auth/session')) return {config,status:200,
      data:{enabled:true,username:'owner',csrfHeader:'X-CSRF-TOKEN',csrfToken:'fresh-test-token'}};
    if (config.method === 'get' && !authenticated) throw new AxiosError('HTTP 401','ERR_BAD_REQUEST',config,undefined,
      {config,status:401,data:'Unauthorized',headers:{}});
    writes++;
    if (writes === 1) throw forbidden(config,'Invalid CSRF Token');
    return {config,status:200,data:{id:42}};
  });
  await localSession.load(true);
  authenticated = false;
  await assert.rejects(axios.get('/api/semevosql/model-capabilities'));
  assert.equal(currentSession.value,undefined);
  assert.equal(sessionExpired.value,true);
  authenticated = true;
  const result = await axios.post('/api/semevosql/projects/2/action',{reason:'owner intent'});
  assert.equal(result.data.id,42); assert.equal(writes,2);
  assert.equal(sessionExpired.value,false);
});

test('expired authentication is surfaced once without replaying the interrupted write', async t => {
  let writes = 0;
  adapter(t, async config => {
    writes++;
    throw new AxiosError('HTTP 401','ERR_BAD_REQUEST',config,undefined,
      {config,status:401,data:'Unauthorized',headers:{}});
  });
  await assert.rejects(axios.post('/api/semevosql/projects/2/action',{reason:'original intent'}),/登录已失效/);
  assert.equal(writes,1); assert.equal(sessionExpired.value,true);
  assert.equal(currentSession.value,undefined);
});

test('incorrect login credentials do not mark an existing session as expired', async t => {
  adapter(t, async config => {
    throw new AxiosError('HTTP 401','ERR_BAD_REQUEST',config,undefined,
      {config,status:401,data:'Unauthorized',headers:{}});
  });
  await assert.rejects(axios.post('/api/semevosql/auth/login',{}));
  assert.equal(sessionExpired.value,false);
  assert.equal(currentSession.value.username,'owner');
});

test('permission denials are never retried and repeated CSRF rejection is bounded', async t => {
  let attempts = 0;
  let sessions = 0;
  let errorBody = {message:'Forbidden'};
  adapter(t, async config => {
    if (config.url.endsWith('/auth/session')) {
      sessions++;
      return {config,status:200,data:{enabled:true,username:'owner',csrfHeader:'X-CSRF-TOKEN',csrfToken:'new-test-token'}};
    }
    attempts++;
    throw forbidden(config,errorBody);
  });
  await assert.rejects(axios.post('/api/semevosql/projects/2/action',{}),/当前账号无权/);
  assert.equal(attempts,1); assert.equal(sessions,0);
  errorBody = 'Invalid CSRF Token'; attempts = 0;
  await assert.rejects(axios.post('/api/semevosql/projects/2/action',{}),/登录验证已失效/);
  assert.equal(attempts,2); assert.equal(sessions,1);
});
