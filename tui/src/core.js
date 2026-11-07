import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {setTimeout as delay} from 'node:timers/promises';

export async function startCore(executable, database) {
  const child = spawn(executable, [database], {
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true,
  });
  child.stdin.on('error', () => {});
  let stderr = '';
  child.stderr.setEncoding('utf8').on('data', (chunk) => {
    stderr = (stderr + chunk).slice(-4096);
  });

  const stopped = new Promise((resolve) => {
    child.on('error', (error) => resolve(error.code === 'ENOENT'
      ? 'Core executable missing. Build messenger-core-server first or pass --core PATH.'
      : error.message));
    child.once('close', (code, signal) => {
      resolve(stderr.trim() || `Core exited (${signal ?? code}).`);
    });
  });

  async function stop() {
    child.stdin.end();
    const timeout = new AbortController();
    try {
      await Promise.race([
        stopped,
        delay(6000, undefined, {signal: timeout.signal}).then(() => child.kill('SIGKILL')),
      ]);
    } finally {
      timeout.abort();
    }
    await stopped;
  }

  const lines = createInterface({input: child.stdout});
  const timeout = new AbortController();
  try {
    const line = await Promise.race([
      new Promise((resolve) => lines.once('line', resolve)),
      stopped.then((message) => { throw new Error(message); }),
      delay(30000, undefined, {signal: timeout.signal}).then(() => {
        throw new Error('Timed out starting the local Core.');
      }),
    ]);
    const {url, token} = JSON.parse(line);
    if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(url) || typeof token !== 'string' || !token) {
      throw new Error('Invalid loopback Core handshake.');
    }
    return {api: new CoreApi(url, token), stop, stopped};
  } catch (error) {
    await stop();
    throw error;
  } finally {
    timeout.abort();
    lines.close();
  }
}

export class ApiError extends Error {
  constructor(code, message, detail) {
    super(detail && detail !== message ? `${message} (${detail})` : message);
    this.code = code;
  }
}

class CoreApi {
  constructor(url, token) {
    this.url = `${url}/api/v1`;
    this.token = token;
  }

  async request(path, {method = 'GET', body, signal} = {}) {
    const headers = {authorization: `Bearer ${this.token}`};
    if (body !== undefined) headers['content-type'] = 'application/json';
    const response = await fetch(this.url + path, {
      method, headers, body: JSON.stringify(body), redirect: 'error', signal,
    });
    if (!response.ok) {
      const data = await response.text();
      let error;
      try {
        error = JSON.parse(data);
      } catch {
        error = {message: data};
      }
      throw new ApiError(error?.code ?? `http_${response.status}`,
        error?.message || response.statusText, error?.detail);
    }
    return response;
  }

  async json(path, options = {}) {
    const response = await this.request(path, {
      signal: AbortSignal.timeout(15000),
      ...options,
    });
    return response.status === 204 || response.status === 202 ? undefined : response.json();
  }

  async list(path) {
    const items = [];
    let offset = 0;
    do {
      const page = await this.json(`${path}?limit=100&offset=${offset}`);
      items.push(...page.items);
      offset = page.nextOffset;
    } while (offset != null);
    return items;
  }

  async *turn(path, body, signal) {
    const response = await this.request(`${path}/messages`, {method: 'POST', body, signal});
    if (!response.headers.get('content-type')?.startsWith('text/event-stream') || !response.body) {
      await response.body?.cancel();
      throw new ApiError('invalid_stream', 'Core did not return a chat event stream.');
    }

    let hasFinished = false;
    try {
      for await (const event of chatEvents(response.body)) {
        if (['Finished', 'Cancelled', 'Error'].includes(event.type)) hasFinished = true;
        yield event;
      }
    } catch (error) {
      if (signal?.aborted || hasFinished || error instanceof ApiError) throw error;
      throw new ApiError('stream_ended', 'Chat stream ended before the turn finished.', error.message);
    }
    if (!hasFinished) throw new ApiError('stream_ended', 'Chat stream ended before the turn finished.');
  }
}

// SSE records can span network chunks, including a split CRLF or UTF-8 character.
async function* chatEvents(body) {
  let pending = '';
  let data = [];
  for await (const chunk of body.pipeThrough(new TextDecoderStream())) {
    pending += chunk;
    let newline;
    while ((newline = pending.indexOf('\n')) !== -1) {
      const line = pending.slice(0, newline).replace(/\r$/, '');
      pending = pending.slice(newline + 1);
      if (line === '') {
        if (data.length) {
          let event;
          try {
            event = JSON.parse(data.join('\n'));
          } catch {
            throw new ApiError('invalid_stream', 'Core returned an invalid chat event.');
          }
          if (typeof event?.type !== 'string') {
            throw new ApiError('invalid_stream', 'Core returned a chat event without a type.');
          }
          yield event;
        }
        data = [];
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).replace(/^ /, ''));
      }
    }
  }
}
