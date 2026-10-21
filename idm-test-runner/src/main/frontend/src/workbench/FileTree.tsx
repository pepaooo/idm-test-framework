import { useRef, useState, type DragEvent } from 'react';
import { api, ApiProblem } from '../api/client';
import { Badge, type BadgeStatus } from '../components/Badge';
import { t } from '../i18n';
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
  /** The scenario the current run is running, marked as running. */
  running?: string | null;
  onOpen(path: string): void;
  onAdded(added: string[]): void;
}

const isYaml = (name: string) => /\.ya?ml$/i.test(name);

/** Folders under scenarios/ start unfolded; the others (config/, reports/…) folded. */
const startsOpen = (path: string) => path === 'scenarios' || path.startsWith('scenarios/');

/**
 * The workspace's files (spec §4.3): scenario badges for the last run, ● for unsaved files,
 * secrets.env locked and never opened. "Add files" and dropping .yaml/.yml files on the tree copy
 * them into scenarios/ through `POST /api/files/add`.
 */
export function FileTree({ tree, active, dirty, readOnly, running = null, onOpen, onAdded }: Props) {
  const [toggled, setToggled] = useState<ReadonlySet<string>>(new Set());
  const [messages, setMessages] = useState<{ text: string; problem: boolean }[]>([]);
  const [dropping, setDropping] = useState(false);
  const input = useRef<HTMLInputElement>(null);

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
      return (
        <li key={node.path}>
          <button type="button" className="tree-row tree-dir" style={indent} aria-expanded={open} onClick={() => toggle(node.path)}>
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
    return (
      <li key={node.path}>
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
          {node.path === running && (
            <span className="tree-badge run-spin" role="img" aria-label={t('ui.run.state.running')}>
              ◌
            </span>
          )}
          {result && node.path !== running && (
            <span className="tree-badge">
              <Badge status={RESULT_BADGE[result]} label={t(`ui.tree.lastResult.${result}`)} />
            </span>
          )}
        </button>
      </li>
    );
  };

  return (
    <nav
      className={`tree${dropping ? ' tree-dropping' : ''}`}
      aria-label={t('ui.tree.label')}
      onDragOver={onDragOver}
      onDragLeave={() => setDropping(false)}
      onDrop={onDrop}
    >
      <div className="pane-head">
        <span>{t('ui.tree.title')}</span>
        <button type="button" className="pill" disabled={readOnly} onClick={() => input.current?.click()} title={t('ui.tree.dropHint')}>
          {t('ui.tree.add')}
        </button>
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
  );
}
