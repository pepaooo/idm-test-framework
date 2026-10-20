/** One entry of `POST /api/validate`: 1-based line and column. */
export interface Problem {
  severity: 'error' | 'warning';
  line: number;
  column: number;
  message: string;
  key: string;
}

/** The validator's key for a password written in plain text (spec §4.4). */
export const PLAIN_SECRET = 'ui.lint.plainSecret';

/** A `GET /api/files/tree` entry. */
export interface TreeNode {
  name: string;
  path: string;
  dir: boolean;
  kind: 'scenario' | 'config' | 'secrets' | 'other' | null;
  lastResult: 'passed' | 'finding' | 'setup' | null;
  children: TreeNode[] | null;
}

/** Lines to highlight in a file (Task 14: the step of a finding), 1-based and inclusive. */
export interface LineMark {
  from: number;
  to: number;
}

/** Where to put the cursor; `nonce` makes a second click on the same line move it again. */
export interface EditorFocus {
  line: number;
  column: number;
  nonce: number;
}

/** What the editor component gets: Monaco in the app, a textarea in the unit tests. */
export interface CodeEditorProps {
  path: string;
  value: string;
  language: string;
  readOnly: boolean;
  label: string;
  problems: readonly Problem[];
  marks: LineMark | null;
  focus: EditorFocus | null;
  onChange(value: string): void;
  onSave(): void;
}

/** Mine against disk, read-only (the Compare of the changed-on-disk dialog). */
export interface DiffViewProps {
  path: string;
  language: string;
  disk: string;
  mine: string;
}

export function languageOf(path: string): string {
  return /\.ya?ml$/i.test(path) ? 'yaml' : 'plaintext';
}

/** Whether `POST /api/validate` accepts the file: scenarios/**.y*ml and config/*.y*ml. */
export function isValidatable(path: string): boolean {
  if (!/\.ya?ml$/i.test(path)) {
    return false;
  }
  const parts = path.split('/');
  return (parts[0] === 'scenarios' && parts.length >= 2) || (parts[0] === 'config' && parts.length === 2);
}

export function isSecretsFile(path: string): boolean {
  return (path.split('/').pop() ?? '').toLowerCase() === 'secrets.env';
}
