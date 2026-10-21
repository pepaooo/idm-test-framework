import { DiffEditor } from '@monaco-editor/react';
import './setup';
import { useMonacoTheme } from './theme';
import type { DiffViewProps } from '../types';

/** Disk (left) against mine (right), both read-only; its models are separate from the editor's. */
export default function DiffView({ path, language, disk, mine }: DiffViewProps) {
  const theme = useMonacoTheme();
  return (
    <DiffEditor
      original={disk}
      modified={mine}
      language={language}
      theme={theme}
      originalModelPath={`idmtest-disk:///${path}`}
      modifiedModelPath={`idmtest-mine:///${path}`}
      options={{ readOnly: true, originalEditable: false, renderSideBySide: true, minimap: { enabled: false }, automaticLayout: true }}
    />
  );
}
