#!/usr/bin/env node
import {mkdir} from 'node:fs/promises';
import {homedir} from 'node:os';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs} from 'node:util';
import {render} from 'ink';
import {createElement} from 'react';
import {App} from './app.js';
import {errorText, modelFor} from './chat.js';
import {startCore} from './core.js';
import {terminalText} from './editor.js';

async function main() {
  const {values} = parseArgs({options: {
    help: {type: 'boolean', short: 'h'},
    db: {type: 'string'},
    core: {type: 'string'},
  }});
  if (values.help) {
    console.log(`Usage: messenger [--db PATH] [--core PATH]

  --db PATH     SQLite database (default: ~/.messenger/tui.db)
  --core PATH   messenger-core-server executable (default: local Cargo debug build)
  -h, --help    Show help

Open the command palette with Ctrl+P. Use /connect to add a provider.`);
    return;
  }
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    throw new Error('Messenger TUI needs an interactive terminal.');
  }

  const defaultCore = fileURLToPath(new URL('../../core/rust/target/debug/messenger-core-server', import.meta.url));
  const executable = values.core ? resolve(values.core) : defaultCore + (process.platform === 'win32' ? '.exe' : '');
  const database = resolve(values.db ?? join(homedir(), '.messenger', 'tui.db'));
  await mkdir(dirname(database), {recursive: true, mode: 0o700});
  const core = await startCore(executable, database);
  try {
    const [meta, agent] = await Promise.all([core.api.json('/meta'), core.api.json('/agents/default')]);
    const model = await modelFor(core.api, agent);
    const app = render(createElement(App, {
      api: core.api, version: meta.serverVersion, initialAgent: agent, initialModel: model,
    }), {alternateScreen: true, incrementalRendering: true, maxFps: 30});
    const unmount = () => app.unmount();
    process.once('SIGTERM', unmount);
    try {
      await Promise.race([
        app.waitUntilExit(),
        core.stopped.then((message) => { throw new Error(message); }),
      ]);
    } finally {
      process.removeListener('SIGTERM', unmount);
      app.unmount();
    }
  } finally {
    await core.stop();
  }
}

try {
  await main();
} catch (error) {
  console.error(`Messenger: ${terminalText(errorText(error))}`);
  process.exitCode = 1;
}
