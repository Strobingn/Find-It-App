import test from 'node:test';
import assert from 'node:assert/strict';
import { createHandlers } from '../api/ideas.js';

const env = { FINDIT_IDEAS_TOKEN: 'a'.repeat(64), CURSOR_API_KEY: 'server-key', FINDIT_ALLOW_IMPLEMENT: 'true' };
const idea = { requestId: '6c6e74c2-47a2-4d7f-8454-31a905a150ae', title: 'Improve import progress',
  details: 'Show individual decode stages while importing a terrain tile.', mode: 'plan', appVersion: '2.0; Android 36' };
const runId = 'run-00000000-0000-4000-8000-000000000001';
function request(body = idea, token = env.FINDIT_IDEAS_TOKEN) {
  return new Request('https://backend.example/api/ideas', { method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
}
function ok(value, status = 200) { return Response.json(value, { status }); }

test('rejects missing/wrong credentials without contacting Cursor', async () => {
  let calls = 0;
  const handlers = createHandlers(env, async () => { calls++; });
  assert.equal((await handlers.POST(request(idea, 'wrong'))).status, 401);
  assert.equal((await createHandlers({}, async () => { calls++; }).POST(request())).status, 503);
  assert.equal(calls, 0);
});

test('rejects invalid ideas, unknown configuration fields and unapproved implementation', async () => {
  let calls = 0;
  const handlers = createHandlers(env, async () => { calls++; });
  for (const body of [null, [], { ...idea, title: '' }, { ...idea, details: 'short' },
    { ...idea, mode: 'anything' }, { ...idea, requestId: '../main' }, { ...idea, workOnCurrentBranch: true },
    { ...idea, repos: [{ url: 'https://github.com/other/repo' }] }]) {
    assert.equal((await handlers.POST(request(body))).status, 400);
  }
  assert.equal((await createHandlers({ ...env, FINDIT_ALLOW_IMPLEMENT: 'false' }, async () => { calls++; })
    .POST(request({ ...idea, mode: 'agent' }))).status, 403);
  assert.equal(calls, 0);
});

test('bounds chunked input before contacting Cursor', async () => {
  const handlers = createHandlers(env, async () => { throw Error('Must not contact Cursor'); });
  assert.equal((await handlers.POST(request({ ...idea, details: 'x'.repeat(40_000) }))).status, 413);
});

test('evaluation uses plan mode, pins repository and forbids working on the current branch', async () => {
  const calls = [];
  const handlers = createHandlers(env, async (url, init) => {
    const payload = JSON.parse(init.body);
    calls.push({ url, init, payload });
    return ok({ agent: { id: payload.agentId }, run: { id: runId, status: 'CREATING' } }, 201);
  });
  const response = await handlers.POST(request());
  const data = await response.json();
  assert.equal(response.status, 201);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(calls[0].url, 'https://api.cursor.com/v1/agents');
  assert.equal(calls[0].init.headers.Authorization, 'Bearer server-key');
  assert.equal(calls[0].payload.workOnCurrentBranch, false);
  assert.equal(calls[0].payload.autoCreatePR, false);
  assert.equal(calls[0].payload.mode, 'plan');
  assert.deepEqual(calls[0].payload.repos, [{ url: 'https://github.com/Strobingn/Find-It-App', startingRef: 'main' }]);
  assert.match(data.agentId, /^bc-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(data.agentUrl, `https://cursor.com/agents/${data.agentId}`);
  assert.ok(data.receipt);
  assert.equal(JSON.stringify(data).includes('server-key'), false);
});

test('implementation requests a PR on a new branch', async () => {
  const handlers = createHandlers({ ...env, FINDIT_BASE_REF: 'findit2' }, async (url, init) => {
    const payload = JSON.parse(init.body);
    assert.equal(payload.mode, 'agent');
    assert.equal(payload.workOnCurrentBranch, false);
    assert.equal(payload.autoCreatePR, true);
    assert.equal(payload.repos[0].startingRef, 'findit2');
    assert.match(payload.prompt.text, /Do not merge, release, deploy/);
    return ok({ agent: { id: payload.agentId }, run: { status: 'CREATING' } });
  });
  assert.equal((await handlers.POST(request({ ...idea, mode: 'agent' }))).status, 201);
});

test('lost-response retry reuses deterministic agent ID across handler restarts and recovers 409', async () => {
  let id;
  const first = createHandlers(env, async (url, init) => {
    id = JSON.parse(init.body).agentId;
    return ok({ agent: { id }, run: { status: 'CREATING' } });
  });
  await first.POST(request());
  const urls = [];
  const second = createHandlers(env, async (url, init) => {
    urls.push(url);
    if (init.method === 'POST') {
      assert.equal(JSON.parse(init.body).agentId, id);
      return ok({ error: 'agent_id_conflict' }, 409);
    }
    if (url.endsWith(`/runs/${runId}`)) return ok({ id: runId, status: 'COMPLETED' });
    return ok({ id, latestRunId: runId });
  });
  const response = await second.POST(request());
  assert.equal(response.status, 200);
  assert.equal((await response.json()).status, 'COMPLETED');
  assert.equal(urls.length, 3);
});

test('refresh uses run state instead of treating agent IDLE as success; rejects forged receipts', async () => {
  let id;
  const handlers = createHandlers(env, async (url, init) => {
    if (init.method === 'POST') {
      id = JSON.parse(init.body).agentId;
      return ok({ agent: { id }, run: { status: 'CREATING' } });
    }
    if (url.endsWith(`/runs/${runId}`)) return ok({ id: runId, status: 'FAILED' });
    return ok({ id, status: 'IDLE', latestRunId: runId });
  });
  const data = await (await handlers.POST(request())).json();
  const refresh = receipt => new Request(`https://backend.example/api/ideas?agentId=${id}&receipt=${receipt}`,
    { headers: { Authorization: `Bearer ${env.FINDIT_IDEAS_TOKEN}` } });
  assert.equal((await handlers.GET(refresh('forged'))).status, 403);
  const response = await handlers.GET(refresh(data.receipt));
  assert.equal((await response.json()).status, 'FAILED');
});

test('does not leak upstream error details or falsely report success', async () => {
  const handlers = createHandlers(env, async () => ok({ error: 'secret-account-info' }, 401));
  const response = await handlers.POST(request());
  assert.equal(response.status, 502);
  const body = await response.json();
  assert.equal(JSON.stringify(body).includes('secret-account-info'), false);
  assert.equal(body.notStarted, false);
});

test('only pre-launch rejection allows Android to discard its pending retry identity', async () => {
  const handlers = createHandlers(env, async () => { throw Error('Must not launch'); });
  const response = await handlers.POST(request({ ...idea, title: '' }));
  assert.equal((await response.json()).notStarted, true);
});
