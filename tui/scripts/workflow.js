import {execFileSync, spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {
  chmodSync, copyFileSync, cpSync, mkdirSync, mkdtempSync, readFileSync, readdirSync,
  renameSync, rmSync, writeFileSync,
} from 'node:fs';
import {tmpdir} from 'node:os';
import {basename, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs} from 'node:util';

const tui = fileURLToPath(new URL('../', import.meta.url));
const root = resolve(tui, '..');
const core = 'messenger-core-server';
const cargoArgs = ['--locked', '--manifest-path', 'core/rust/Cargo.toml', '-p', 'messenger-http'];
const manifestPath = join(tui, 'package.json');
const pnpm = process.env.npm_execpath;

function run(program, args, options = {}) {
  const result = spawnSync(program, args, {cwd: root, stdio: 'inherit', ...options});
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${program} failed (${result.signal ?? result.status}).`);
  }
}

function capture(program, args) {
  return execFileSync(program, args, {
    cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'], maxBuffer: 16 * 1024 * 1024,
  }).trim();
}

function installDependencies(directory, production = false) {
  const args = ['install', '--frozen-lockfile', ...(production ? ['--prod'] : [])];
  // pnpm 12 is a native executable, so invoke the pinned runner directly.
  run(pnpm, args, {cwd: directory});
}

function hostTarget() {
  const host = capture('rustc', ['-vV']).match(/^host: ([\w.-]+)$/m)?.[1];
  if (!host) throw new Error('Could not determine the Rust host target.');
  return host;
}

function build(target, release = false) {
  const messages = capture('cargo', [
    'build', ...cargoArgs, '--target', target, '--bin', core,
    '--message-format=json-render-diagnostics', ...(release ? ['--release'] : []),
  ]);
  for (const line of messages.split('\n')) {
    if (!line.startsWith('{')) continue;
    const message = JSON.parse(line);
    if (message.reason === 'compiler-artifact' && message.target.name === core && message.executable) {
      return message.executable;
    }
  }
  throw new Error('Cargo did not report the Core executable.');
}

function checkSources() {
  for (const directory of ['src', 'scripts', 'test']) {
    for (const file of readdirSync(join(tui, directory)).filter((name) => name.endsWith('.js'))) {
      run(process.execPath, ['--check', join(tui, directory, file)]);
    }
  }
}

function test(target, executable) {
  run('cargo', ['test', ...cargoArgs, '--target', target]);
  run(process.execPath, ['--test'], {
    cwd: tui, env: {...process.env, MESSENGER_TUI_TEST_CORE: executable},
  });
}

function stageBundle(bundle, executable) {
  cpSync(join(tui, 'src'), join(bundle, 'src'), {recursive: true});
  const {scripts, ...manifest} = JSON.parse(readFileSync(manifestPath, 'utf8'));
  writeFileSync(join(bundle, 'package.json'), JSON.stringify(manifest, null, 2) + '\n');
  for (const file of ['pnpm-lock.yaml', 'pnpm-workspace.yaml']) {
    copyFileSync(join(tui, file), join(bundle, file));
  }
  copyFileSync(join(root, 'LICENSE'), join(bundle, 'LICENSE'));
  mkdirSync(join(bundle, 'bin'));
  const bundledCore = join(bundle, 'bin', basename(executable));
  copyFileSync(executable, bundledCore);
  chmodSync(bundledCore, 0o755);

  if (process.platform === 'win32') {
    writeFileSync(join(bundle, 'messenger.cmd'), '@echo off\r\nnode "%~dp0src\\main.js" %*\r\n');
  } else {
    writeFileSync(join(bundle, 'messenger'), [
      '#!/bin/sh',
      'here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1',
      'exec node "$here/src/main.js" "$@"',
      '',
    ].join('\n'), {mode: 0o755});
  }
  installDependencies(bundle, true);
}

function release(target, executable, output) {
  const version = capture(executable, ['--version']);
  if (version === '0.0.0' || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)) {
    throw new Error(`Core reported an invalid release version "${version}". Check VERSION.`);
  }
  const name = `messenger-tui-${version}-${target}`;
  const filename = `${name}.tar.gz`;
  mkdirSync(output, {recursive: true});
  const stage = mkdtempSync(join(tmpdir(), 'messenger-tui-release-'));
  let archiveStage;
  try {
    const bundle = join(stage, name);
    stageBundle(bundle, executable);
    const reportedVersion = capture(process.execPath, [join(bundle, 'src', 'main.js'), '--version']);
    if (reportedVersion !== `Messenger ${version}`) {
      throw new Error(`Bundled TUI reported "${reportedVersion}".`);
    }

    // Build the archive on the destination filesystem so a failed pack leaves
    // any previous release intact and the completed archive appears at once.
    archiveStage = mkdtempSync(join(output, '.messenger-release-'));
    const archive = join(archiveStage, filename);
    run('tar', ['-czf', archive, '-C', stage, name]);
    const sha256 = createHash('sha256').update(readFileSync(archive)).digest('hex');
    const destination = join(output, filename);
    renameSync(archive, destination);
    console.log(`Release: ${destination}\nSHA-256: ${sha256}`);
  } finally {
    if (archiveStage) rmSync(archiveStage, {recursive: true, force: true});
    rmSync(stage, {recursive: true, force: true});
  }
}

function main(command, args) {
  if (!pnpm || !process.env.npm_config_user_agent?.startsWith('pnpm/12.')) {
    throw new Error('Run the TUI workflow with pnpm 12, for example pnpm --prefix tui run dev.');
  }
  if (!['dev', 'build', 'test', 'release'].includes(command)) throw new Error('Unknown TUI workflow.');
  let output = join(tui, 'dist');
  if (command === 'release') {
    const {values} = parseArgs({args, options: {out: {type: 'string'}}});
    if (values.out) output = resolve(process.env.INIT_CWD ?? root, values.out);
  } else if (command !== 'dev' && args.length) {
    throw new Error(`${command} does not accept arguments.`);
  }

  const target = hostTarget();
  if (command !== 'release') installDependencies(tui);
  if (command !== 'dev') checkSources();
  const executable = build(target, command === 'release');
  const app = join(tui, 'src', 'main.js');
  switch (command) {
    case 'dev':
      run(process.execPath, [app, '--core', executable, ...args], {cwd: process.env.INIT_CWD ?? root});
      break;
    case 'build':
      run(process.execPath, [app, '--core', executable, '--version']);
      console.log(`Core: ${executable}`);
      break;
    case 'test':
      run(process.execPath, [app, '--core', executable, '--version']);
      test(target, executable);
      break;
    case 'release':
      test(target, executable);
      release(target, executable, output);
      break;
  }
}

try {
  const [, , command, ...args] = process.argv;
  // Accept a separator before arguments forwarded by pnpm.
  main(command, args[0] === '--' ? args.slice(1) : args);
} catch (error) {
  console.error(`TUI workflow: ${error.message}`);
  process.exitCode = 1;
}
