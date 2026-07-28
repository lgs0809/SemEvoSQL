import test from "node:test";
import assert from "node:assert/strict";
import axios from "axios";
import { semEvoSQLService as api } from "../src/services/semevosql.ts";

function capture(t, handler = () => ({ ok: true })) {
  const requests = [];
  const original = axios.defaults.adapter;
  t.after(() => {
    axios.defaults.adapter = original;
  });
  axios.defaults.adapter = async (config) => {
    requests.push(config);
    return { config, status: 200, data: await handler(config), headers: {} };
  };
  return requests;
}

test("domain facade keeps ordinary reads scoped and carries their abort signal", async (t) => {
  const requests = capture(t);
  const signal = new AbortController().signal;
  await api.projectHealth(42, signal);
  await api.projectDocuments(42, 9);
  await api.semanticCatalog(42, 9);
  assert.deepEqual(
    requests.map((r) => r.url),
    [
      "/api/semevosql/projects/42/health",
      "/api/semevosql/projects/42/versions/9/documents",
      "/api/semevosql/projects/42/versions/9/semantic-catalog",
    ],
  );
  assert.equal(requests[0].signal, signal);
  assert.ok(requests.every((r) => r.method === "get"));
});

test("public decision and publication preparation retain their exact revision and decision key", async (t) => {
  const requests = capture(t);
  const decision = {
    action: "RENAME",
    reason: "distinct meaning",
    contentRevision: 7,
    evidenceRevision: 8,
    baseVersion: 9,
    catalogHash: "catalog",
    contributionFingerprint: "contribution",
    representationHash: "representation",
    publicName: "name",
  };
  await api.decideProjectDefinition(42, 11, decision, "original-decision-key");
  await api.checkProjectDefinitionPublication(42, 11, {
    publicationId: 12,
    contentRevision: 7,
    representationHash: "representation",
  });
  assert.deepEqual(JSON.parse(requests[0].data), decision);
  assert.equal(
    requests[0].headers.get("Idempotency-Key"),
    "original-decision-key",
  );
  assert.equal(
    requests[1].url,
    "/api/semevosql/projects/42/definition-candidates/11/publication-check",
  );
  assert.deepEqual(JSON.parse(requests[1].data), {
    publicationId: 12,
    contentRevision: 7,
    representationHash: "representation",
  });
});

test("natural language is forwarded unchanged with manual approval and two distinct request identities", async (t) => {
  const requests = capture(t);
  const content = "只修改我的使用范围，完整定义保持不变。";
  await api.sendProjectMessage(42, "conversation", content);
  const body = JSON.parse(requests[0].data);
  assert.equal(body.content, content);
  assert.equal(body.approvalMode, "REQUIRE_APPROVAL");
  assert.match(body.idempotencyKey, /^[0-9a-f-]{36}$/);
  assert.notEqual(body.idempotencyKey, body.requestId);
  assert.equal(
    requests[0].url,
    "/api/semevosql/projects/42/conversations/conversation/messages",
  );
});

test("clarification keeps the original question snapshot across asynchronous operator lookup", async (t) => {
  const question = { clarificationId: "original", revision: 3 };
  const requests = capture(t, (config) => {
    if (config.url.endsWith("/operator-context")) {
      question.clarificationId = "changed";
      question.revision = 4;
      return { operator: "member" };
    }
    return { ok: true };
  });
  await api.answerClarification(
    "run",
    question,
    "CUSTOM",
    " 按完整业务定义 ",
    "USER",
  );
  assert.equal(
    requests[1].url,
    "/api/semevosql/runs/run/clarification/original/answer",
  );
  const body = JSON.parse(requests[1].data);
  assert.equal(body.revision, 3);
  assert.equal(body.scope, "USER");
  assert.equal(body.customAnswer, "按完整业务定义");
  assert.match(body.idempotencyKey, /^clarification:[a-f0-9]{64}$/);
});

test("governed index action retains both mutation headers without changing its scope", async (t) => {
  const requests = capture(t);
  await api.reindexQueryCaseIndex(42);
  assert.equal(requests[0].params.projectId, 42);
  const id = requests[0].headers.get("X-Request-ID");
  assert.match(id, /^[0-9a-f-]{36}$/);
  assert.equal(
    requests[0].headers.get("Idempotency-Key"),
    `query-case-index-reindex:42:${id}`,
  );
});
