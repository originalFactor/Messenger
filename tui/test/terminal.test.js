import assert from 'node:assert/strict';
import {test} from 'node:test';
import {testRender} from '@opentui/react/test-utils';
import {act, createElement} from 'react';
import {App} from '../src/app.js';

test('OpenTUI displays the chat and handles palette, paste, masking and resize', async () => {
  const terminal = await testRender(createElement(App, {
    api: {}, version: '1.1.0', initialAgent: {name: 'Default'}, initialModel: null,
  }), {width: 84, height: 24});
  const {mockInput} = terminal;

  async function expectFrame(pattern) {
    await act(() => terminal.renderOnce());
    assert.match(terminal.captureCharFrame(), pattern);
  }

  try {
    await expectFrame(/MESSENGER  1\.1\.0/);
    await expectFrame(/Default  ·  No model/);
    await act(() => mockInput.pasteBracketedText('第一行\nsecond line'));
    await expectFrame(/第一行/);
    await expectFrame(/second line/);

    await act(async () => mockInput.pressKey('p', {ctrl: true}));
    await expectFrame(/Commands/);
    await act(() => mockInput.typeText('connect'));
    await act(async () => mockInput.pressEnter());
    await expectFrame(/Provider name/);

    await act(() => mockInput.typeText('Local provider'));
    await act(async () => mockInput.pressEnter());
    await act(() => mockInput.pasteBracketedText('http://localhost:1234/v1'));
    await act(async () => mockInput.pressEnter());
    await act(async () => mockInput.pressEnter());
    await expectFrame(/API key/);
    await act(() => mockInput.typeText('fake-api-key'));
    await expectFrame(/\*{12}/);
    assert.doesNotMatch(terminal.captureCharFrame(), /fake-api-key/);

    await act(async () => terminal.resize(24, 10));
    await expectFrame(/Resize terminal/);
  } finally {
    await act(() => terminal.dispose());
  }
});

test('slash completion filters, navigates and fills commands without submitting chat', async () => {
  const requested = [];
  const terminal = await testRender(createElement(App, {
    api: {list: async (path) => { requested.push(path); return []; }},
    version: '1.1.0',
    initialAgent: {name: 'Default'},
    initialModel: null,
  }), {width: 84, height: 24});
  const {mockInput} = terminal;

  async function frame() {
    await act(() => terminal.renderOnce());
    return terminal.captureCharFrame();
  }

  async function escape() {
    await act(async () => {
      mockInput.pressEscape();
      await new Promise((resolve) => setTimeout(resolve, 40));
    });
  }

  try {
    await act(() => mockInput.typeText('/'));
    assert.match(await frame(), /Tab complete/);
    assert.match(await frame(), /\/sessions/);
    await act(async () => mockInput.pressArrow('down'));
    await act(async () => mockInput.pressArrow('down'));
    assert.match(await frame(), /› \/sessions/);
    await act(async () => mockInput.pressTab());
    assert.match(await frame(), /│ \/sessions/);
    assert.doesNotMatch(await frame(), /Tab complete/);
    assert.deepEqual(requested, []);
    await act(async () => mockInput.pressEnter());
    assert.match(await frame(), /No saved sessions/);
    assert.deepEqual(requested, ['/conversations']);
    await escape();

    await act(() => mockInput.typeText('/re'));
    const filtered = await frame();
    assert.match(filtered, /\/retry/);
    assert.match(filtered, /\/regenerate/);
    assert.doesNotMatch(filtered, /\/sessions/);
    await escape();
    assert.match(await frame(), /│ \/re/);
    assert.doesNotMatch(await frame(), /Tab complete/);

    await act(() => mockInput.typeText('t'));
    assert.match(await frame(), /› \/retry/);
    assert.doesNotMatch(await frame(), /\/regenerate/);
    await act(async () => mockInput.pressKey('o', {ctrl: true}));
    assert.doesNotMatch(await frame(), /Tab complete/);

    // Clear the two-line draft and execute a highlighted partial command.
    await act(async () => {
      for (let i = 0; i < '/ret\n'.length; i++) mockInput.pressBackspace();
    });
    await act(() => mockInput.typeText('/co'));
    assert.match(await frame(), /› \/connect/);
    await act(async () => mockInput.pressEnter());
    assert.match(await frame(), /Provider name/);
    assert.deepEqual(requested, ['/conversations']);
  } finally {
    await act(() => terminal.dispose());
  }
});
