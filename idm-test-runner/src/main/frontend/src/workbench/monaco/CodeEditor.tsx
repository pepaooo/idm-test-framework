import { useEffect, useRef, useState } from 'react';
import Editor from '@monaco-editor/react';
import { loadScenarioSchema, modelPath, monaco } from './setup';
import { replaceKeepingCursor } from './sync';
import { useMonacoTheme } from './theme';
import type { CodeEditorProps } from '../types';

type Ed = monaco.editor.IStandaloneCodeEditor;

/**
 * The Monaco editor of the active tab (one model per file, so each keeps its undo history and
 * cursor). The text is owned by the workbench: edits go out through `onChange`, and a different
 * `value` coming in (reload, restored draft, Load from disk) replaces the model's text keeping the
 * cursor. Problems become inline markers; `marks` highlights lines; ⌘S/Ctrl+S calls `onSave`.
 */
export default function CodeEditor(props: CodeEditorProps) {
  const { path, value, language, readOnly, label, problems, marks, focus, onChange, onSave } = props;
  const [editor, setEditor] = useState<Ed | null>(null);
  const theme = useMonacoTheme();
  const handlers = useRef({ onChange, onSave });
  handlers.current = { onChange, onSave };
  const decorations = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);

  useEffect(() => {
    void loadScenarioSchema();
  }, []);

  useEffect(() => {
    if (editor) {
      replaceKeepingCursor(editor, value);
    }
  }, [editor, path, value]);

  useEffect(() => {
    editor?.updateOptions({ readOnly, ariaLabel: label });
  }, [editor, readOnly, label]);

  useEffect(() => {
    const model = editor?.getModel();
    if (!model) {
      return;
    }
    monaco.editor.setModelMarkers(
      model,
      'idmtest',
      problems.map((p) => ({
        severity: p.severity === 'error' ? monaco.MarkerSeverity.Error : monaco.MarkerSeverity.Warning,
        message: p.message,
        startLineNumber: Math.max(1, p.line),
        startColumn: Math.max(1, p.column),
        endLineNumber: Math.max(1, p.line),
        endColumn: model.getLineMaxColumn(Math.min(Math.max(1, p.line), model.getLineCount())),
      })),
    );
  }, [editor, path, problems]);

  useEffect(() => {
    if (!editor) {
      return;
    }
    decorations.current?.clear();
    if (marks) {
      decorations.current = editor.createDecorationsCollection([
        {
          range: new monaco.Range(marks.from, 1, marks.to, 1),
          options: { isWholeLine: true, className: 'line-mark', linesDecorationsClassName: 'line-mark-gutter' },
        },
      ]);
      editor.revealLinesInCenterIfOutsideViewport(marks.from, marks.to);
    }
  }, [editor, path, marks]);

  useEffect(() => {
    if (editor && focus) {
      editor.revealLineInCenter(focus.line);
      editor.setPosition({ lineNumber: focus.line, column: Math.max(1, focus.column) });
      editor.focus();
    }
  }, [editor, focus]);

  return (
    <Editor
      path={modelPath(path)}
      defaultValue={value}
      language={language}
      theme={theme}
      options={{
        readOnly,
        ariaLabel: label,
        automaticLayout: true,
        minimap: { enabled: false },
        fontSize: 13,
        scrollBeyondLastLine: false,
        tabSize: 2,
        insertSpaces: true,
      }}
      onMount={(mounted) => {
        mounted.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => handlers.current.onSave());
        setEditor(mounted);
      }}
      onChange={(text) => handlers.current.onChange(text ?? '')}
    />
  );
}
