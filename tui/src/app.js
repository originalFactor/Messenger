import {randomUUID} from 'node:crypto';
import {decodePasteBytes, TextAttributes} from '@opentui/core';
import {useKeyboard, usePaste, useRenderer, useTerminalDimensions} from '@opentui/react';
import {createElement as h, useMemo, useState} from 'react';
import wrapAnsi from 'wrap-ansi';
import {useChat} from './chat.js';
import {oneLine, terminalText, useEditor} from './editor.js';

const accent = 'cyan';
const {BOLD, DIM, INVERSE} = TextAttributes;
const commands = [
  {id: 'new', label: 'New session', key: 'n'},
  {id: 'sessions', label: 'Switch session', key: 'l'},
  {id: 'models', label: 'Choose model', key: 'm'},
  {id: 'agents', label: 'Choose agent for a new session', key: 'a'},
  {id: 'connect', label: 'Add a provider and model'},
  {id: 'retry', label: 'Retry the last failed reply'},
  {id: 'regenerate', label: 'Regenerate the last reply'},
  {id: 'help', label: 'Keyboard shortcuts'},
  {id: 'quit', label: 'Exit Messenger', key: 'q'},
];

const fields = [
  {key: 'name', label: 'Provider name', example: 'OpenAI'},
  {key: 'base_url', label: 'Provider base URL', example: 'https://api.openai.com/v1'},
  {key: 'api_format', label: 'API format', example: 'chat', fallback: 'chat'},
  {key: 'api_key', label: 'API key', example: 'Enter to leave empty', fallback: '', mask: true},
  {key: 'model_id', label: 'Model ID', example: 'Use the exact ID from your provider'},
];

export function App({api, version, initialAgent, initialModel}) {
  const renderer = useRenderer();
  const {width: columns, height: rows} = useTerminalDimensions();
  const chat = useChat(api, initialAgent, initialModel);
  const prompt = useEditor();
  const panelInput = useEditor();
  const [panel, setPanel] = useState(null);
  const [selected, setSelected] = useState(0);
  const [leader, setLeader] = useState(false);
  const [scroll, setScroll] = useState(0);

  const width = Math.max(1, columns - 4);
  const query = panelInput.value.trim().toLowerCase();
  const options = (panel?.items ?? []).filter((item) =>
    `${item.label} ${item.hint ?? ''}`.toLowerCase().includes(query));
  const selection = Math.min(selected, Math.max(0, options.length - 1));
  const field = panel?.type === 'connect' ? fields[panel.step] : null;
  const inputLines = (panel ? panelInput : prompt).lines(Math.max(1, columns - 6), panel ? 2 : 4, {
    mask: field?.mask, active: !chat.busy && (!panel || Boolean(field) || Boolean(panel.items)),
  });
  const viewport = Math.max(1, rows - inputLines.length - 6);
  const transcript = useMemo(() => chat.messages.flatMap((message) => messageLines(
    message.role, message.content, message.error_message, width)), [chat.messages, width]);
  const lines = [
    ...transcript,
    ...messageLines('user', chat.live?.question, null, width),
    ...messageLines('assistant', chat.live?.content, chat.fault, width),
  ];
  const maxScroll = Math.max(0, lines.length - viewport);
  const end = lines.length - Math.min(scroll, maxScroll);

  function showPanel(next) {
    setPanel(next);
    setSelected(0);
    panelInput.replace();
  }

  function switchSession(task) {
    return chat.perform(async () => {
      await task();
      prompt.replace();
      setScroll(0);
    });
  }

  function openPicker(type, title, load, onSelect, empty) {
    return chat.perform(async () => {
      const items = await load();
      showPanel({type, title, items, onSelect, empty});
    });
  }

  function runCommand(id) {
    switch (id) {
      case 'new':
        return switchSession(() => chat.newSession());
      case 'sessions':
        return openPicker(id, 'Sessions', async () => (await api.list('/conversations')).map((item) => ({
          id: item.id, label: item.title || 'Untitled',
          hint: new Date(item.updated_at).toLocaleDateString(), value: item,
        })), (item) => switchSession(() => chat.openSession(item.value)), 'No saved sessions.');
      case 'models':
        return openPicker(id, 'Models', async () => {
          const [models, providers] = await Promise.all([api.list('/models'), api.list('/providers')]);
          const names = new Map(providers.map((provider) => [provider.id, provider.name]));
          return models.filter((model) => model.is_enabled).map((model) => ({
            id: model.id, label: model.display_name, value: model,
            hint: [names.get(model.provider_id), model.model_id].filter(Boolean).join(' / '),
          }));
        }, (item) => chat.perform(() => chat.chooseModel(item.value)), 'No enabled models. Use /connect to add one.');
      case 'agents':
        return openPicker(id, 'Agents · starts a new session', async () =>
          (await api.list('/agents')).filter((agent) => agent.role !== 'title').map((agent) => ({
            id: agent.id, label: agent.name, hint: agent.is_default ? 'Default' : '', value: agent,
          })), (item) => switchSession(() => chat.newSession(item.value)), 'No chat agents.');
      case 'connect':
        return showPanel({
          type: 'connect', step: 0, values: {}, providerId: randomUUID(), modelId: randomUUID(),
        });
      case 'retry':
      case 'regenerate': {
        const lastUser = chat.messages.findLastIndex((message) => message.role === 'user');
        const replies = chat.messages.slice(lastUser + 1).filter((message) => message.role === 'assistant');
        const target = id === 'retry' ? replies.at(-1) : replies[0];
        if (!target || (id === 'retry' && target.status !== 'error') || target.status === 'sending') {
          return chat.status(id === 'retry' ? 'No failed reply to retry.' : 'No reply to regenerate.', true);
        }
        setScroll(0);
        return chat.perform(() => chat.turn({operation: id, messageId: target.id}));
      }
      case 'help':
        return showPanel({type: 'help', title: 'Keyboard shortcuts'});
      case 'quit':
        return renderer.destroy();
    }
  }

  function submitProvider() {
    const value = panelInput.value.trim() || field.fallback;
    if (value === undefined) return chat.status(`${field.label} is required.`, true);
    if (field.key === 'base_url') {
      const url = URL.parse(value);
      if (!url || !['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
        return chat.status('Enter an HTTP(S) base URL without embedded credentials.', true);
      }
    }
    if (field.key === 'api_format' && !['chat', 'responses', 'messages', 'interactions'].includes(value)) {
      return chat.status('Choose chat, responses, messages or interactions.', true);
    }
    const values = {...panel.values, [field.key]: value};
    if (panel.step + 1 < fields.length) {
      setPanel({...panel, step: panel.step + 1, values});
      panelInput.replace();
      chat.status('Enter to continue · Esc to close');
      return;
    }
    return chat.perform(async () => {
      const now = Date.now();
      await api.json('/providers', {method: 'PUT', body: {
        id: panel.providerId, name: values.name, base_url: values.base_url,
        api_format: values.api_format, api_key: values.api_key, created_at: now, updated_at: now,
      }});
      const model = await api.json('/models', {method: 'PUT', body: {
        id: panel.modelId, provider_id: panel.providerId, model_id: values.model_id,
        display_name: values.model_id, is_enabled: true, created_at: now,
      }});
      await chat.chooseModel(model);
      showPanel(null);
    });
  }

  function submitPrompt() {
    const content = prompt.value.trim();
    if (!content) return;
    const command = commands.find((item) => content === `/${item.id}`);
    if (command) {
      prompt.replace();
      return runCommand(command.id);
    }
    if (content === '/') {
      prompt.replace();
      return showPalette();
    }
    setScroll(0);
    return chat.perform(() => chat.turn({content}, () => prompt.replace()));
  }

  function showPalette() {
    showPanel({
      type: 'commands', title: 'Commands', onSelect: (item) => runCommand(item.id),
      items: commands.map((item) => ({...item, hint: `/${item.id}`})),
    });
  }

  usePaste(({bytes}) => {
    if (chat.busy || panel?.type === 'help') return;
    (panel ? panelInput : prompt).insert(decodePasteBytes(bytes), !panel);
    setSelected(0);
  });

  useKeyboard((key) => {
    const {name} = key;
    if (name === 'escape') {
      setLeader(false);
      if (chat.busy) void chat.cancel();
      else if (panel) showPanel(null);
      return;
    }
    if (!panel && (name === 'pageup' || name === 'pagedown')) {
      const step = Math.max(1, viewport - 1);
      if (name === 'pageup' && scroll >= maxScroll && chat.hasOlder) {
        void chat.perform(async () => {
          await chat.olderMessages();
          setScroll(maxScroll + step);
        });
        return;
      }
      setScroll(Math.min(maxScroll, Math.max(0, scroll + (name === 'pageup' ? step : -step))));
      return;
    }
    if (chat.busy) return;
    if (key.ctrl && name === 'p') return showPalette();
    if (!panel && key.ctrl && name === 'x') return setLeader(true);
    if (leader) {
      setLeader(false);
      const action = commands.find((item) => item.key === name);
      if (action) return runCommand(action.id);
    }
    if (field) {
      if (name === 'return') return submitProvider();
      return panelInput.edit(key);
    }
    if (panel?.items) {
      if (name === 'up') return setSelected(Math.max(0, selection - 1));
      if (name === 'down') return setSelected(Math.max(0, Math.min(options.length - 1, selection + 1)));
      if (name === 'return' && options[selection]) {
        const item = options[selection];
        showPanel(null);
        return panel.onSelect(item);
      }
      if (name !== 'return') {
        panelInput.edit(key);
        setSelected(0);
      }
      return;
    }
    if (panel) return;
    if ((name === 'return' && key.shift) || (key.ctrl && name === 'o')) return prompt.insert('\n', true);
    if (name === 'return') return submitPrompt();
    prompt.edit(key);
  });

  if (columns < 30 || rows < 12) {
    return h(Line, {}, 'Resize terminal');
  }

  const notice = chat.notice?.text ?? (panel ? 'Esc close' : chat.busy
    ? 'Working… · Esc cancel' : chat.hasOlder ? 'PgUp for older messages' : '');
  const statusColor = chat.notice?.error ? 'red' : undefined;
  const title = chat.session ? chat.session.title || 'Untitled' : 'New session';

  return h('box', {flexDirection: 'column', paddingX: 1, height: rows},
    h(Line, {fg: accent, attributes: BOLD},
      `MESSENGER  ${version}   /   ${oneLine(title)}`),
    h('box', {height: viewport, flexDirection: 'column', overflow: 'hidden', paddingX: 1},
      panel
        ? h(Panel, {panel, field, options, selection, height: viewport})
        : lines.length
          ? lines.slice(Math.max(0, end - viewport), end).map(({text, color, bold}, i) =>
            h(Line, {key: i, fg: color, attributes: bold ? BOLD : undefined}, text || ' '))
          : h('box', {flexDirection: 'column', justifyContent: 'center', flexGrow: 1},
            h(Line, {attributes: BOLD, fg: accent}, 'Start a conversation'),
            h(Line, {attributes: DIM}, chat.model
              ? 'Write a message below, or open the command palette.'
              : 'Use /connect to add a provider and model, or /models to choose one.'))),
    h('box', {flexDirection: 'column', border: true, borderStyle: 'rounded', borderColor: accent, paddingX: 1},
      panel?.type === 'help'
        ? h(Line, {attributes: DIM}, 'Esc closes this panel')
        : (panel ? panelInput.value : prompt.value)
          ? inputLines.map((line, i) => h(Line, {key: i}, inputLine(line)))
          : h(Line, {attributes: DIM},
            field?.example ?? (panel ? 'Filter…' : 'Write a message or type /'))),
    h(Line, {fg: statusColor, attributes: statusColor ? undefined : DIM}, oneLine(notice) || ' '),
    h(Line, {fg: accent},
      oneLine(`${chat.agent.name}  ·  ${chat.model?.display_name ?? 'No model'}`)),
    h(Line, {attributes: DIM}, leader
      ? 'Ctrl+X  n new  l sessions  m models  a agents  q quit'
      : 'Ctrl+P commands  ·  Ctrl+O newline  ·  PgUp/PgDn history'));
}

function Line({fg, attributes, children}) {
  return h('text', {fg, attributes, height: 1, flexShrink: 0, wrapMode: 'none', truncate: true}, children);
}

function inputLine(line) {
  // The editor uses a private SGR marker to locate the caret across wrapped
  // lines. OpenTUI paints it as a styled span, keeping control bytes out of cells.
  const [before, caret, after] = line.split(/\x1b\[(?:7|27)m/);
  if (caret === undefined) return before || ' ';
  return [before, h('span', {key: 'caret', attributes: INVERSE}, caret), after];
}

function Panel({panel, field, options, selection, height}) {
  if (field) {
    return [
      h(Line, {key: 'title', attributes: BOLD, fg: accent}, `Connect · ${panel.step + 1}/${fields.length}`),
      h(Line, {key: 'field', attributes: BOLD}, field.label),
      h(Line, {key: 'hint', attributes: DIM}, field.key === 'api_format'
        ? 'chat / responses / messages / interactions'
        : 'Enter to continue · Esc to close'),
    ];
  }
  if (panel.type === 'help') {
    return [
      'Enter sends · Ctrl+O inserts a line break',
      'Ctrl+P opens commands · / lists commands',
      'Ctrl+X then N, L, M, A, Q opens new, sessions, models, agents, quit',
      'Page Up / Page Down scrolls history',
      'Esc cancels a turn · Ctrl+C exits',
    ].slice(0, height).map((label) => h(Line, {key: label}, label));
  }
  const count = Math.max(1, height - 1);
  const start = Math.max(0, Math.min(selection - Math.floor(count / 2), options.length - count));
  return [
    h(Line, {key: 'title', attributes: BOLD, fg: accent}, panel.title),
    ...(options.length
      ? options.slice(start, start + count).map((item, i) => h(Line, {
        key: item.id, fg: start + i === selection ? accent : undefined,
        attributes: start + i === selection ? BOLD : undefined,
      }, `${start + i === selection ? '›' : ' '} ${oneLine(item.label)}`,
      item.hint && h('span', {attributes: DIM}, `  ${oneLine(item.hint)}`)))
      : [h(Line, {key: 'empty', attributes: DIM},
        panel.items.length ? 'No matches.' : panel.empty)]),
  ];
}

function messageLines(role, content, error, width) {
  if (!content && !error) return [];
  const user = role === 'user';
  const label = {user: 'You', assistant: 'Assistant', tool: 'Tool', system: 'System'}[role] ?? 'Message';
  const wrap = (value, color) => wrapAnsi(terminalText(value), width, {hard: true, trim: false})
    .split('\n').map((text) => ({text, color}));
  return [
    {text: label, color: user ? accent : undefined, bold: true},
    ...(content ? wrap(content) : []),
    ...(error ? wrap(error, 'red') : []),
    {text: ''},
  ];
}
