import {stripVTControlCharacters} from 'node:util';
import {useState} from 'react';
import wrapAnsi from 'wrap-ansi';

const segmenter = new Intl.Segmenter(undefined, {granularity: 'grapheme'});

function cleanText(value) {
  return stripVTControlCharacters(String(value ?? ''))
    .replace(/\r\n?/g, '\n')
    .replace(/[\x00-\x08\x0b-\x1f\x7f-\x9f]/g, '');
}

export function terminalText(value) {
  return cleanText(value).replace(/\t/g, '    ');
}

export function oneLine(value) {
  return terminalText(value).replace(/\n/g, ' ');
}

export function useEditor() {
  const [{value, cursor}, setEditor] = useState({value: '', cursor: 0});

  function replace(value = '') {
    setEditor({value, cursor: value.length});
  }

  function insert(input, multiline) {
    const text = multiline ? cleanText(input) : oneLine(input);
    if (!text) return;
    setEditor(({value, cursor}) => ({
      value: value.slice(0, cursor) + text + value.slice(cursor),
      cursor: cursor + text.length,
    }));
  }

  function edit(key) {
    if (['left', 'right', 'home', 'end', 'backspace', 'delete'].includes(key.name)) {
      setEditor(({value, cursor}) => {
        const segments = segmenter.segment(value);
        const left = segments.containing(cursor - 1)?.index ?? 0;
        const segment = segments.containing(cursor);
        const right = segment ? segment.index + segment.segment.length : value.length;
        if (key.name === 'home') return {value, cursor: 0};
        if (key.name === 'end') return {value, cursor: value.length};
        if (key.name === 'left') return {value, cursor: left};
        if (key.name === 'right') return {value, cursor: right};
        if (key.name === 'backspace') return {value: value.slice(0, left) + value.slice(cursor), cursor: left};
        return {value: value.slice(0, cursor) + value.slice(right), cursor};
      });
      return key.name === 'backspace' || key.name === 'delete';
    } else if (!key.ctrl && !key.meta && !key.option && !key.super && !key.hyper
        && /^[^\x00-\x1f\x7f-\x9f]+$/u.test(key.sequence)) {
      insert(key.sequence, false);
      return true;
    }
    return false;
  }

  function lines(width, maxRows, {mask = false, active = true} = {}) {
    const before = mask ? '*'.repeat(cursor) : terminalText(value.slice(0, cursor));
    const remaining = mask ? '*'.repeat(value.length - cursor) : terminalText(value.slice(cursor));
    if (!active) return wrapAnsi(before + remaining, width, {hard: true, trim: false}).split('\n').slice(-maxRows);
    const current = segmenter.segment(remaining).containing(0)?.segment ?? '';
    const underCursor = current === '\n' || current === '' ? ' ' : current;
    const after = current === '\n' ? remaining : remaining.slice(current.length);
    const caret = '\x1b[7m';
    const rows = wrapAnsi(before + caret + underCursor + '\x1b[27m' + after, width, {
      hard: true, trim: false, wordWrap: false,
    }).split('\n');
    const cursorRow = rows.findIndex((row) => row.includes(caret));
    const start = Math.max(0, Math.min(cursorRow, rows.length - maxRows));
    return rows.slice(start, start + maxRows);
  }

  return {value, replace, insert, edit, lines};
}
