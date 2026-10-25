import { useEffect, useMemo, useRef, useState } from 'react';
import { t } from '../i18n';
import type { TreeNode } from './types';

interface Props {
  tree: TreeNode | null;
  active: string | null;
  dirty: ReadonlySet<string>;
  onOpen(path: string): void;
}

function filesOf(node: TreeNode | null): TreeNode[] {
  if (!node) return [];
  if (!node.dir) return [node];
  return (node.children ?? []).flatMap(filesOf);
}

export function WorkspaceFilePicker({ tree, active, dirty, onOpen }: Props) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [highlight, setHighlight] = useState(0);
  const container = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const files = useMemo(() => filesOf(tree), [tree]);
  const matches = useMemo(() => files.filter((file) => file.path.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())), [files, query]);

  useEffect(() => {
    if (open) search.current?.focus();
  }, [open]);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (!container.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open]);

  const close = (restoreFocus: boolean) => {
    setOpen(false);
    if (restoreFocus) trigger.current?.focus();
  };
  useEffect(() => {
    if (!open) return;
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); close(true); }
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, [open]);
  const choose = (file: TreeNode) => {
    if (file.kind === 'secrets') return;
    setOpen(false);
    onOpen(file.path);
    window.setTimeout(() => document.querySelector<HTMLButtonElement>('.tab-on [role="tab"]')?.focus(), 0);
  };
  const move = (direction: number) => {
    if (!matches.length) return;
    let index = highlight;
    for (let attempts = 0; attempts < matches.length; attempts += 1) {
      index = (index + direction + matches.length) % matches.length;
      if (matches[index].kind !== 'secrets') {
        setHighlight(index);
        break;
      }
    }
  };

  return (
    <div className="workspace-file-picker" ref={container}>
      <button
        ref={trigger}
        type="button"
        className="file-picker-trigger pill"
        aria-label={t('ui.editor.openWorkspaceFile')}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => {
          if (open) close(false);
          else { setQuery(''); setHighlight(0); setOpen(true); }
        }}
      >{t('ui.editor.openWorkspaceFile')} ▾</button>
      {open && <div className="file-picker-popover">
        <input
          ref={search}
          type="search"
          className="file-picker-search"
          aria-label={t('ui.editor.findWorkspaceFile')}
          placeholder={t('ui.editor.findWorkspaceFile')}
          value={query}
          onChange={(event) => { setQuery(event.target.value); setHighlight(0); }}
          onKeyDown={(event) => {
            if (event.key === 'Escape') { event.preventDefault(); close(true); }
            else if (event.key === 'ArrowDown') { event.preventDefault(); move(1); }
            else if (event.key === 'ArrowUp') { event.preventDefault(); move(-1); }
            else if (event.key === 'Enter') {
              event.preventDefault();
              const selected = matches[highlight]?.kind === 'secrets' ? matches.find((file) => file.kind !== 'secrets') : matches[highlight];
              if (selected) choose(selected);
            }
          }}
        />
        <div className="file-picker-list" role="listbox" aria-label={t('ui.tree.label')}>
          {matches.length === 0 && <p className="muted file-picker-empty">{t('ui.editor.noWorkspaceFiles')}</p>}
          {matches.map((file, index) => file.kind === 'secrets' ? (
            <div key={file.path} role="option" aria-label={`${file.path} ${t('ui.tree.locked')}`} aria-disabled="true" aria-selected="false" className="file-picker-option file-picker-locked">
              <span>{file.path}</span><span aria-hidden="true">🔒</span>
            </div>
          ) : (
            <button key={file.path} type="button" role="option" aria-label={file.path} aria-selected={file.path === active} className={`file-picker-option${index === highlight ? ' file-picker-highlight' : ''}`} onMouseEnter={() => setHighlight(index)} onClick={() => choose(file)}>
              <span>{file.path}</span>
              {dirty.has(file.path) && <span className="unsaved-dot" role="img" aria-label={t('ui.tree.unsaved')}>●</span>}
            </button>
          ))}
        </div>
      </div>}
    </div>
  );
}
