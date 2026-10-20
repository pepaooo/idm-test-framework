/** The parts of a Monaco editor and model that {@link replaceKeepingCursor} uses (tested without Monaco). */
export interface SyncRange {
  startLineNumber: number;
  startColumn: number;
  endLineNumber: number;
  endColumn: number;
}

export interface SyncModel {
  getValue(): string;
  getFullModelRange(): SyncRange;
  pushEditOperations(before: null | unknown[], edits: { range: SyncRange; text: string | null }[], cursor: () => null): unknown;
  validatePosition(position: { lineNumber: number; column: number }): { lineNumber: number; column: number };
}

export interface SyncEditor {
  getModel(): SyncModel | null;
  getPosition(): { lineNumber: number; column: number } | null;
  setPosition(position: { lineNumber: number; column: number }): void;
  getScrollTop(): number;
  setScrollTop(top: number): void;
}

/**
 * Puts `value` in the editor's model when it differs (a file reloaded from disk, a restored draft,
 * Load from disk) as one undoable edit, keeping the cursor where it was (moved inside the text when
 * the text got shorter) and the scroll position. Returns whether anything changed.
 */
export function replaceKeepingCursor(editor: SyncEditor, value: string): boolean {
  const model = editor.getModel();
  if (!model || model.getValue() === value) {
    return false;
  }
  const position = editor.getPosition();
  const scroll = editor.getScrollTop();
  model.pushEditOperations([], [{ range: model.getFullModelRange(), text: value }], () => null);
  if (position) {
    editor.setPosition(model.validatePosition(position));
  }
  editor.setScrollTop(scroll);
  return true;
}
