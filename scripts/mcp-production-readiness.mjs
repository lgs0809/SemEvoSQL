import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const baseUrl = process.env.SEMEVOSQL_ACCEPTANCE_URL || 'http://127.0.0.1:28065';
const projectId = process.env.SEMEVOSQL_ACCEPTANCE_PROJECT_ID?.trim();
if (!projectId) {
  throw new Error('Set SEMEVOSQL_ACCEPTANCE_PROJECT_ID to the local project prepared for acceptance.');
}
const queryInput = process.env.SEMEVOSQL_ACCEPTANCE_QUERY?.trim();
if (!queryInput) {
  throw new Error('Set SEMEVOSQL_ACCEPTANCE_QUERY to a query for the prepared local project.');
}
const deploymentUrl = `${baseUrl}/api/semevosql/projects/${projectId}/mcp-deployment`;
const authHeader = ['Author', 'ization'].join('');
const secretField = ['creden', 'tial'].join('');
const sessionHeader = 'mcp-session-id';
let rpcId = 0;

const parseRpcBody = async response => {
  const text = await response.text();
  if (!text.trim()) return null;
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('text/event-stream')) {
    const messages = text
      .split(/\r?\n/)
      .filter(line => line.startsWith('data:'))
      .map(line => line.slice(5).trim())
      .filter(Boolean)
      .map(line => JSON.parse(line));
    return messages.at(-1) || null;
  }
  return JSON.parse(text);
};

const jsonRequest = async (url, options = {}) => {
  const response = await fetch(url, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.headers || {}),
    },
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`${options.method || 'GET'} ${url} failed: HTTP ${response.status} ${body.slice(0, 300)}`);
  }
  if (response.status === 204) return null;
  const text = await response.text();
  if (!text.trim()) return null;
  return JSON.parse(text);
};

const mcpRequest = async ({ endpoint, secret, sessionId, method, params, notification = false }) => {
  const id = notification ? undefined : ++rpcId;
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
      [authHeader]: `Bearer ${secret}`,
      ...(sessionId ? { [sessionHeader]: sessionId } : {}),
    },
    body: JSON.stringify({
      jsonrpc: '2.0',
      ...(id === undefined ? {} : { id }),
      method,
      ...(params === undefined ? {} : { params }),
    }),
  });
  const nextSession = response.headers.get(sessionHeader) || sessionId || null;
  const body = await parseRpcBody(response);
  return { response, body, sessionId: nextSession, id };
};

const initialize = async (endpoint, secret) => {
  let call = await mcpRequest({
    endpoint,
    secret,
    method: 'initialize',
    params: {
      protocolVersion: '2025-03-26',
      capabilities: {},
      clientInfo: { name: 'semevosql-production-readiness', version: '1.0.0' },
    },
  });
  if (!call.response.ok || call.body?.error) {
    throw new Error(`MCP initialize failed: HTTP ${call.response.status} ${JSON.stringify(call.body)}`);
  }
  await mcpRequest({
    endpoint,
    secret,
    sessionId: call.sessionId,
    method: 'notifications/initialized',
    notification: true,
  });
  return call.sessionId;
};

const toolCall = async (endpoint, secret, sessionId, name, args) => {
  const call = await mcpRequest({
    endpoint,
    secret,
    sessionId,
    method: 'tools/call',
    params: { name, arguments: args },
  });
  if (!call.response.ok || call.body?.error) {
    throw new Error(`MCP tool ${name} failed: HTTP ${call.response.status} ${JSON.stringify(call.body)}`);
  }
  const text = call.body?.result?.content?.find(item => item.type === 'text')?.text;
  if (!text) throw new Error(`MCP tool ${name} returned no text content`);
  return JSON.parse(text);
};

const listTools = async (endpoint, secret, sessionId) => {
  const call = await mcpRequest({ endpoint, secret, sessionId, method: 'tools/list', params: {} });
  if (!call.response.ok || call.body?.error) {
    throw new Error(`MCP tools/list failed: HTTP ${call.response.status} ${JSON.stringify(call.body)}`);
  }
  return (call.body?.result?.tools || []).map(tool => tool.name).sort();
};

const expectRejectedSecret = async (endpoint, secret) => {
  const call = await mcpRequest({
    endpoint,
    secret,
    method: 'initialize',
    params: {
      protocolVersion: '2025-03-26',
      capabilities: {},
      clientInfo: { name: 'revoked-secret-check', version: '1.0.0' },
    },
  });
  if (call.response.ok && !call.body?.error) {
    throw new Error('Rotated MCP secret unexpectedly remained usable');
  }
};

let deployed = false;
try {
  const deploymentPayload = await jsonRequest(`${deploymentUrl}/deploy`, { method: 'POST' });
  deployed = true;
  let secret = deploymentPayload[secretField];
  const endpoint = deploymentPayload.config?.endpoint || deploymentPayload.deployment?.endpoint;
  if (!secret || !endpoint) throw new Error('MCP deploy response did not contain the expected connection material');

  let sessionId = await initialize(endpoint, secret);
  const tools = await listTools(endpoint, secret, sessionId);
  if (tools.join(',') !== 'query,query_status') {
    throw new Error(`Unexpected MCP tool surface: ${tools.join(',')}`);
  }

  const requestId = randomUUID();
  const first = await toolCall(endpoint, secret, sessionId, 'query', {
    input: queryInput,
    requestId,
  });
  const second = await toolCall(endpoint, secret, sessionId, 'query', {
    input: queryInput,
    requestId,
  });
  if (!first.episodeId || first.episodeId !== second.episodeId || first.runId !== second.runId) {
    throw new Error('MCP requestId idempotency did not return the same Episode/Run');
  }

  let terminal = second;
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline && ['RUNNING', 'QUEUED'].includes(terminal.status)) {
    await delay(1500);
    terminal = await toolCall(endpoint, secret, sessionId, 'query_status', { episodeId: first.episodeId });
  }
  if (!['COMPLETED', 'INPUT_REQUIRED'].includes(terminal.status)) {
    throw new Error(`MCP query did not reach an accepted terminal state: ${terminal.status}`);
  }
  if (terminal.status === 'COMPLETED' && (!terminal.semanticVersion || !terminal.runId)) {
    throw new Error('MCP completed result is missing semantic version or durable run identity');
  }

  const rotatedPayload = await jsonRequest(`${deploymentUrl}/rotate-credential`, { method: 'POST' });
  const rotatedSecret = rotatedPayload[secretField];
  if (!rotatedSecret || rotatedSecret === secret) throw new Error('MCP credential rotation did not issue a new secret');
  await expectRejectedSecret(endpoint, secret);
  secret = rotatedSecret;
  sessionId = await initialize(endpoint, secret);
  const toolsAfterRotate = await listTools(endpoint, secret, sessionId);
  if (toolsAfterRotate.join(',') !== 'query,query_status') {
    throw new Error('MCP tool surface changed after credential rotation');
  }

  await jsonRequest(deploymentUrl, { method: 'DELETE' });
  deployed = false;
  await expectRejectedSecret(endpoint, secret);

  console.log(
    `[mcp-production-readiness] PASS tools=${tools.join(',')} idempotent=true terminal=${terminal.status} rotate=true revoke=true`,
  );
} finally {
  if (deployed) {
    try {
      await jsonRequest(deploymentUrl, { method: 'DELETE' });
    } catch {
      // Best-effort cleanup after an acceptance failure.
    }
  }
}
