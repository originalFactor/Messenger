#!/usr/bin/env node
import {execFileSync} from 'node:child_process';
import {existsSync} from 'node:fs';
import {mkdir} from 'node:fs/promises';
import {homedir} from 'node:os';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs} from 'node:util';
import {createElement} from 'react';
import {errorText, modelFor} from './chat.js';
import {startCore} from './core.js';
import {terminalText} from './editor.js';

async function main() {
  const {values} = parseArgs({options: {
    help: {type: 'boolean', short: 'h'},
    version: {type: 'boolean', short: 'V'},
    db: {type: 'string'},
    core: {type: 'string'},
  }});
  if (values.help) {
    console.log(`Usage: messenger [--db PATH] [--core PATH]

  --db PATH     SQLite database (default: ~/.messenger/tui.db)
  --core PATH   messenger-core-server executable (default: bundled Core or Cargo debug build)
  -V, --version Show the Messenger version
  -h, --help    Show help

Open the command palette with Ctrl+P. Use /connect to add a provider.`);
    return;
  }
  const binary = process.platform === 'win32' ? 'messenger-core-server.exe' : 'messenger-core-server';
  const bundledCore = fileURLToPath(new URL(`../bin/${binary}`, import.meta.url));
  const debugCore = fileURLToPath(new URL(`../../core/rust/target/debug/${binary}`, import.meta.url));
  const defaultCore = existsSync(bundledCore) ? bundledCore : debugCore;
  const executable = values.core ? resolve(values.core) : defaultCore;
  if (values.version) {
    const version = execFileSync(executable, ['--version'], {encoding: 'utf8', windowsHide: true});
    console.log(`Messenger ${version.trim()}`);
    return;
  }
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    throw new Error('Messenger TUI needs an interactive terminal.');
  }
  if (!process.getBuiltinModule?.('node:ffi')) {
    throw new Error('OpenTUI requires Node.js 26.4+ with --experimental-ffi.');
  }
  const [{createCliRenderer}, {createRoot}, {App}] = await Promise.all([
    import('@opentui/core'), import('@opentui/react'), import('./app.js'),
  ]);

  const database = resolve(values.db ?? join(homedir(), '.messenger', 'tui.db'));
  await mkdir(dirname(database), {recursive: true, mode: 0o700});
  const core = await startCore(executable, database);
  try {
    const [meta, agent] = await Promise.all([core.api.json('/meta'), core.api.json('/agents/default')]);
    const model = await modelFor(core.api, agent);
    const renderer = await createCliRenderer({
      screenMode: 'alternate-screen',
      exitOnCtrlC: true,
      useMouse: false,
      consoleMode: 'disabled',
      openConsoleOnError: false,
      maxFps: 30,
    });
    try {
      createRoot(renderer).render(createElement(App, {
        api: core.api, version: meta.serverVersion, initialAgent: agent, initialModel: model,
      }));
      await Promise.race([
        renderer.closed,
        core.stopped.then((message) => { throw new Error(message); }),
      ]);
    } finally {
      renderer.destroy();
      await renderer.closed;
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
