import { useEffect, useId, useRef, useState, type DragEvent } from 'react';
import { api, ApiProblem, withQuery } from '../api/client';
import { Badge, type BadgeStatus } from '../components/Badge';
import { t } from '../i18n';
import { NewScenarioDialog } from './NewScenarioDialog';
import { scenarioTemplate } from './scenarioTemplate';
import type { TreeNode } from './types';

const RESULT_BADGE: Record<'passed' | 'finding' | 'setup', BadgeStatus> = {
  passed: 'ok',
  finding: 'finding',
  setup: 'error',
};

interface Props {
  tree: TreeNode | null;
  active: string | null;
  dirty: ReadonlySet<string>;
  readOnly: boolean;
  /** The folder selected for Run folder (workspace-relative), marked as the selected row. */
  selectedFolder?: string | null;
  /** The scenarios of the current run still to finish, by workspace-relative path. */
  runStates?: ReadonlyMap<string, 'running' | 'waiting'>;
  onOpen(path: string): void;
  /** A folder row was clicked (it also folds or unfolds). */
  onSelectFolder?(path: string): void;
  onAdded(added: string[]): void;
  /** Where New scenario creates the file (workspace-relative, scenarios/ or below it). */
  newFolder?: string;
  /** The text the editor shows for an open file (unsaved edits included); undefined when it is not open. */
  textOf?(path: string): string | undefined;
  /** New scenario or Duplicate created this file. */
  onCreated?(path: string): void;
}

/** What the name dialog is for: a new scenario, or a copy of `source`. */
type Creating = { kind: 'new' } | { kind: 'duplicate'; source: string };

const folderOf = (path: string) => path.slice(0, path.lastIndexOf('/'));
const nameOf = (path: string) => path.slice(path.lastIndexOf('/') + 1);
/** `a.yaml` → `a-copy.yaml`, keeping the extension. */
const copyName = (name: string) => name.replace(/(\.ya?ml)?$/, (ext) => `-copy${ext}`);

const NO_RUN: ReadonlyMap<string, 'running' | 'waiting'> = new Map();

const isYaml = (name: string) => /\.ya?ml$/i.test(name);

/** Folders under scenarios/ start unfolded; the others (config/, reports/…) folded. */
const startsOpen = (path: string) => path === 'scenarios' || path.startsWith('scenarios/');

/**
 * The workspace's files (spec §4.3): scenario badges for the last run, ● for unsaved files,
 * secrets.env locked and never opened. "Add files" and dropping .yaml/.yml files on the tree copy
 * them into scenarios/ through `POST /api/files/add`. "New scenario" (in `newFolder`, from the
 * template) and Duplicate on a scenario row (in its folder, copying what the editor shows) create a
 * file through `POST /api/files/new`, which never overwrites one.
 */
export function FileTree({
  tree,
  active,
  dirty,
  readOnly,
  selectedFolder = null,
  runStates = NO_RUN,
  onOpen,
  onSelectFolder,
  onAdded,
  newFolder = 'scenarios',
  textOf = () => undefined,
  onCreated = () => undefined,
}: Props) {
  const [toggled, setToggled] = useState<ReadonlySet<string>>(new Set());
  const [messages, setMessages] = useState<{ text: string; problem: boolean }[]>([]);
  const [dropping, setDropping] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const [creating, setCreating] = useState<Creating | null>(null);
  // the button that opened the name dialog: it gets the focus back when the dialog closes
  const opener = useRef<HTMLButtonElement | null>(null);
  // the selected folder's description: it is the folder Run folder runs (the active file is current too)
  const runFolderNote = useId();

  const isOpen = (path: string) => startsOpen(path) !== toggled.has(path);
  const toggle = (path: string) =>
    setToggled((current) => {
      const next = new Set(current);
      if (!next.delete(path)) {
        next.add(path);
      }
      return next;
    });

  const add = async (files: File[]) => {
    const yaml = files.filter((file) => isYaml(file.name));
    const left = files.filter((file) => !isYaml(file.name)).map((file) => file.name);
    const leftOut = left.length ? [{ text: t('ui.tree.onlyYaml', { names: left.join(', ') }), problem: true }] : [];
    if (!yaml.length) {
      setMessages(leftOut);
      return;
    }
    const form = new FormData();
    yaml.forEach((file) => form.append('files', file, file.name));
    try {
      const answer = await api.upload<{ added: string[] }>('/api/files/add', form);
      setMessages([{ text: t('ui.tree.added', { count: answer.added.length }), problem: false }, ...leftOut]);
      onAdded(answer.added);
    } catch (e) {
      setMessages([{ text: e instanceof ApiProblem ? e.message : String(e), problem: true }, ...leftOut]);
    }
  };

  const startCreating = (what: Creating, button: HTMLButtonElement) => {
    opener.current = button;
    setCreating(what);
  };

  // a tab that turns read-only (another tab took over) creates nothing: the dialog closes
  useEffect(() => {
    if (readOnly) {
      setCreating(null);
    }
  }, [readOnly]);

  const stopCreating = () => {
    setCreating(null);
    opener.current?.focus();
  };

  /** Creates `folder/fileName`: null when done, else the reason it was refused. */
  const create = async (what: Creating, folder: string, fileName: string): Promise<string | null> => {
    const path = `${folder}/${fileName}`;
    try {
      let content: string;
      if (what.kind === 'new') {
        content = scenarioTemplate(fileName.replace(/\.ya?ml$/, ''));
      } else {
        // what the editor shows (unsaved edits too); the original is neither saved nor changed
        content = textOf(what.source) ?? (await api.get<{ content: string }>(withQuery('/api/files', { path: what.source }))).content;
      }
      const created = await api.post<{ path: string; version: string }>('/api/files/new', { path, content });
      // the new file shows in the tree: its folders unfold (selecting a folder also folds it)
      setToggled((current) => {
        const next = new Set(current);
        const parts = created.path.split('/');
        for (let index = 1; index < parts.length; index += 1) {
          const folder = parts.slice(0, index).join('/');
          if (startsOpen(folder)) {
            next.delete(folder);
          } else {
            next.add(folder);
          }
        }
        return next;
      });
      stopCreating();
      onCreated(created.path);
      return null;
    } catch (e) {
      return e instanceof ApiProblem ? e.message : String(e);
    }
  };

  const onDragOver = (event: DragEvent) => {
    if (readOnly || !Array.from(event.dataTransfer?.types ?? []).includes('Files')) {
      return;
    }
    event.preventDefault();
    setDropping(true);
  };

  const onDrop = (event: DragEvent) => {
    setDropping(false);
    if (readOnly) {
      return;
    }
    event.preventDefault();
    void add(Array.from(event.dataTransfer?.files ?? []));
  };

  const renderNode = (node: TreeNode, depth: number) => {
    const indent = { paddingLeft: 10 + depth * 12 };
    if (node.dir) {
      const open = isOpen(node.path);
      const selected = node.path === selectedFolder;
      return (
        <li key={node.path}>
          <button
            type="button"
            className={`tree-row tree-dir${selected ? ' tree-selected' : ''}`}
            style={indent}
            aria-expanded={open}
            aria-current={selected ? 'true' : undefined}
            aria-describedby={selected ? runFolderNote : undefined}
            onClick={() => {
              toggle(node.path);
              onSelectFolder?.(node.path);
            }}
          >
            <span aria-hidden="true" className="tree-caret">{open ? '▾' : '▸'} </span>
            {node.name}
          </button>
          {open && node.children && node.children.length > 0 && (
            <ul role="group">{node.children.map((child) => renderNode(child, depth + 1))}</ul>
          )}
        </li>
      );
    }
    if (node.kind === 'secrets') {
      return (
        <li key={node.path}>
          <button type="button" className="tree-row tree-locked" style={indent} aria-disabled="true" title={t('ui.tree.secrets')}>
            {node.name}{' '}
            <span role="img" aria-label={t('ui.tree.locked')}>
              🔒
            </span>
          </button>
        </li>
      );
    }
    const result = node.kind === 'scenario' && node.lastResult ? node.lastResult : null;
    const runState = runStates.get(node.path);
    return (
      <li key={node.path} className={node.kind === 'scenario' ? 'tree-file' : undefined}>
        <button
          type="button"
          className={`tree-row${node.path === active ? ' tree-active' : ''}`}
          style={indent}
          aria-current={node.path === active ? 'true' : undefined}
          onClick={() => onOpen(node.path)}
        >
          {node.name}
          {dirty.has(node.path) && (
            <span className="unsaved-dot" role="img" aria-label={t('ui.tree.unsaved')}>
              {' ●'}
            </span>
          )}
          {runState === 'running' && (
            <span className="tree-badge run-spin" role="img" aria-label={t('ui.run.state.running')}>
              ◌
            </span>
          )}
          {runState === 'waiting' && (
            <span className="tree-badge tree-waiting" role="img" aria-label={t('ui.run.state.waiting')}>
              ○
            </span>
          )}
          {result && runState === undefined && (
            <span className="tree-badge">
              <Badge status={RESULT_BADGE[result]} label={t(`ui.tree.lastResult.${result}`)} />
            </span>
          )}
        </button>
        {node.kind === 'scenario' && (
          <button
            type="button"
            className="tree-action"
            disabled={readOnly}
            aria-label={`${t('ui.tree.duplicate')} ${node.name}`}
            title={t('ui.tree.duplicate')}
            onClick={(event) => startCreating({ kind: 'duplicate', source: node.path }, event.currentTarget)}
          >
            ⧉
          </button>
        )}
      </li>
    );
  };

  const targetFolder = creating?.kind === 'duplicate' ? folderOf(creating.source) : newFolder;

  return (
    <>
      <nav
        className={`tree${dropping ? ' tree-dropping' : ''}`}
        aria-label={t('ui.tree.label')}
        onDragOver={onDragOver}
        onDragLeave={() => setDropping(false)}
        onDrop={onDrop}
      >
        <div className="pane-head">
          <span>{t('ui.tree.title')}</span>
          <span className="tree-head-actions">
            <button
              type="button"
              className="pill"
              disabled={readOnly}
              onClick={(event) => startCreating({ kind: 'new' }, event.currentTarget)}
            >
              {t('ui.tree.new')}
            </button>
            <button type="button" className="pill" disabled={readOnly} onClick={() => input.current?.click()} title={t('ui.tree.dropHint')}>
              {t('ui.tree.add')}
            </button>
          </span>
          <input
            ref={input}
            type="file"
            multiple
            accept=".yaml,.yml"
            hidden
            aria-label={t('ui.tree.add')}
            disabled={readOnly}
            onChange={(event) => {
              const files = Array.from(event.target.files ?? []);
              event.target.value = '';
              void add(files);
            }}
          />
        </div>
        <span id={runFolderNote} hidden>
          {t('ui.tree.runFolder')}
        </span>
        {tree?.children && <ul className="tree-list">{tree.children.map((child) => renderNode(child, 0))}</ul>}
        {messages.map((message) => (
          <p
            key={message.text}
            className={`tree-message${message.problem ? '' : ' muted'}`}
            role={message.problem ? 'alert' : 'status'}
          >
            {message.problem && <Badge status="warn" />} <span>{message.text}</span>
          </p>
        ))}
      </nav>
      {creating && (
        <NewScenarioDialog
          title={t(creating.kind === 'new' ? 'ui.tree.new' : 'ui.tree.duplicate')}
          label={t(creating.kind === 'new' ? 'ui.tree.newName' : 'ui.tree.duplicateName')}
          folder={targetFolder}
          suggested={creating.kind === 'new' ? '' : copyName(nameOf(creating.source))}
          onCreate={(fileName) => create(creating, targetFolder, fileName)}
          onCancel={stopCreating}
        />
      )}
    </>
  );
}
