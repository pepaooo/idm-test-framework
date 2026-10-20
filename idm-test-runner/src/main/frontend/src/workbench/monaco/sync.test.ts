import { describe, expect, it } from 'vitest';
import { replaceKeepingCursor, type SyncEditor, type SyncModel } from './sync';

function fakeEditor(initial: string, position: { lineNumber: number; column: number }) {
  let value = initial;
  let cursor: { lineNumber: number; column: number } | null = position;
  let scroll = 120;
  const edits: string[] = [];
  const model: SyncModel = {
    getValue: () => value,
    getFullModelRange: () => ({ startLineNumber: 1, startColumn: 1, endLineNumber: 99, endColumn: 1 }),
    pushEditOperations: (_before, list) => {
      value = list[0].text ?? '';
      edits.push(value);
      cursor = { lineNumber: value.split('\n').length, column: 1 }; // Monaco moves it to the end
      scroll = 0;
      return null;
    },
    validatePosition: (p) => {
      const lines = value.split('\n');
      const lineNumber = Math.min(p.lineNumber, lines.length);
      return { lineNumber, column: Math.min(p.column, lines[lineNumber - 1].length + 1) };
    },
  };
  const editor: SyncEditor = {
    getModel: () => model,
    getPosition: () => cursor,
    setPosition: (p) => {
      cursor = p;
    },
    getScrollTop: () => scroll,
    setScrollTop: (top) => {
      scroll = top;
    },
  };
  return { editor, edits, value: () => value, cursor: () => cursor, scroll: () => scroll };
}

describe('replaceKeepingCursor', () => {
  it('replaces the text and keeps the cursor and scroll where they were', () => {
    const fake = fakeEditor('a: 1\nb: 2\nc: 3\n', { lineNumber: 2, column: 3 });
    expect(replaceKeepingCursor(fake.editor, 'a: 1\nb: 20\nc: 3\n')).toBe(true);
    expect(fake.value()).toBe('a: 1\nb: 20\nc: 3\n');
    expect(fake.cursor()).toEqual({ lineNumber: 2, column: 3 });
    expect(fake.scroll()).toBe(120);
  });

  it('moves the cursor inside the text when the text got shorter', () => {
    const fake = fakeEditor('a\nb\nc\nd\n', { lineNumber: 4, column: 2 });
    replaceKeepingCursor(fake.editor, 'a\n');
    expect(fake.cursor()).toEqual({ lineNumber: 2, column: 1 });
  });

  it('does nothing when the text is the same', () => {
    const fake = fakeEditor('same', { lineNumber: 1, column: 2 });
    expect(replaceKeepingCursor(fake.editor, 'same')).toBe(false);
    expect(fake.edits).toEqual([]);
  });
});
