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
