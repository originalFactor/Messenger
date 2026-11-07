import assert from 'node:assert/strict';
import {mkdtemp, rm} from 'node:fs/promises';
import {createServer} from 'node:http';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {test} from 'node:test';
import {startCore} from '../src/core.js';

test('terminal client can configure, stream, retry, cancel and reopen a chat', {timeout: 40000}, async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'messenger-tui-test-'));
  const database = join(directory, 'chat.db');
  const requests = [];
  const upstream = createServer(async (request, response) => {
    try {
      let raw = '';
      for await (const chunk of request) raw += chunk;
      const body = JSON.parse(raw);
      const prompt = body.messages.at(-1).content;
      requests.push({prompt, auth: request.headers.authorization});
      response.writeHead(200, {'Content-Type': 'text/event-stream'});
      const delta = (content, finish_reason = null) =>
        `data: ${JSON.stringify({choices: [{delta: {content}, finish_reason}]})}\n\n`;
      if (prompt === 'cancel this turn') {
        response.write(delta('Partial reply'));
        return;
      }
      if (prompt === 'fail once' && requests.filter((item) => item.prompt === prompt).length === 1) {
        response.end(delta('Partial error'));
        return;
      }
      response.end(delta('你好，') + delta('Messenger!', 'stop') + 'data: [DONE]\n\n');
    } catch (error) {
      response.destroy(error);
    }
  });
  let core;
  t.after(async () => {
    await core?.stop();
    const closed = new Promise((resolve) => upstream.close(resolve));
    upstream.closeAllConnections();
    await closed;
    await rm(directory, {recursive: true, force: true});
  });
  await new Promise((resolve, reject) => {
    upstream.once('error', reject);
    upstream.listen(0, '127.0.0.1', resolve);
  });

  const executable = process.env.MESSENGER_TUI_TEST_CORE;
  assert.ok(executable, 'Run npm test to build the Core executable before testing.');
  core = await startCore(executable, database);
  const {api} = core;
  assert.equal((await fetch(`${api.url}/meta`)).status, 401);
  const meta = await api.json('/meta');
  assert.match(meta.serverVersion, /^\d+\.\d+\.\d+$/);
  const agent = await api.json('/agents/default');
  assert.equal(agent.is_default, true);
  assert.equal(agent.default_model_id, null);

  const put = (path, body) => api.json(path, {method: 'PUT', body});
  const now = Date.now();
  await put('/providers', {
    id: 'test-provider', name: 'Fake provider', api_key: 'test-key',
    base_url: `http://127.0.0.1:${upstream.address().port}/v1`, created_at: now, updated_at: now,
  });
  await put('/models', {
    id: 'test-model', provider_id: 'test-provider', model_id: 'test-model',
    display_name: 'Fake model', is_enabled: true, created_at: now,
  });
  const path = '/conversations/test-chat';
  await put('/conversations', {
    id: 'test-chat', title: 'Integration test', provider_id: 'test-provider',
    agent_id: agent.id, created_at: now, updated_at: now,
  });

  const history = async () => (await api.json(`${path}/messages`)).items;
  const send = (content) => Array.fromAsync(api.turn(path, {content}));
  await assert.rejects(send('no model yet'), {code: 'model_required'});
  assert.deepEqual(await history(), []);
  assert.deepEqual(requests, []);

  await put(`/agents/${agent.id}`, {...agent, default_model_id: 'test-model'});
  const prompt = '第一行\n\tsecond line';
  const events = await send(prompt);
  assert.equal(events.at(-1).type, 'Finished');
  assert.equal(events.filter(({type}) => type === 'TextDelta').map(({text}) => text).join(''), '你好，Messenger!');
  assert.deepEqual(requests, [{prompt, auth: 'Bearer test-key'}]);

  const failedEvents = await send('fail once');
  assert.equal(failedEvents.at(-1).type, 'Error');
  assert.equal(requests.filter(({prompt}) => prompt === 'fail once').length, 1);
  const failed = (await history()).at(-1);
  assert.equal(failed.status, 'error');
  const retried = await Array.fromAsync(api.turn(path, {operation: 'retry', messageId: failed.id}));
  assert.equal(retried.at(-1).type, 'Finished');
  assert.equal(requests.filter(({prompt}) => prompt === 'fail once').length, 2);

  let finalEvent;
  for await (const event of api.turn(path, {content: 'cancel this turn'})) {
    finalEvent = event;
    if (event.type === 'TextDelta') await api.json(`${path}/cancel`, {method: 'POST'});
  }
  assert.equal(finalEvent.type, 'Cancelled');

  await core.stop();
  core = await startCore(executable, database);
  assert.equal((await core.api.json('/agents/default')).id, agent.id);
  assert.equal((await core.api.json('/agents/default')).default_model_id, 'test-model');
  const persisted = (await core.api.json(`${path}/messages`)).items;
  assert.equal(persisted[0].content, prompt);
  assert.equal(persisted[1].content, '你好，Messenger!');
  assert.ok(persisted.every(({id}) => id !== failed.id));
});
