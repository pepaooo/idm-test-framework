import { useEffect, useMemo, useRef, useState, useSyncExternalStore, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { currentLanguage, t, useLanguage } from '../i18n';
import { useWorkbench, type WorkbenchApi } from '../workbench/context';
import type { TreeNode } from '../workbench/types';
import type { RunScope, RunStore } from './runStore';
import './Run.css';

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
const RUN_KEYS = isMac ? '⌘↵' : 'Ctrl+↵';
const RUN_ALL_KEYS = isMac ? '⇧⌘↵' : 'Ctrl+Shift+↵';

export function isScenario(path: string | null): path is string {
  return path !== null && path.startsWith('scenarios/') && /\.ya?ml$/i.test(path);
}

/** The scenario files of the tree under `prefix` (a folder path, '' for all). */
function scenariosIn(tree: TreeNode | null, prefix = 'scenarios'): TreeNode[] {
  const out: TreeNode[] = [];
  const walk = (node: TreeNode) => {
    if (node.dir) {
      node.children?.forEach(walk);
    } else if (node.kind === 'scenario' && (node.path === prefix || node.path.startsWith(`${prefix}/`))) {
      out.push(node);
    }
  };
  if (tree) {
    walk(tree);
  }
  return out;
}

/** The folder Run folder runs: the active scenario's, when it is below scenarios/ itself. */
export function folderOf(path: string | null): string | null {
  if (!isScenario(path)) {
    return null;
  }
  const folder = path.slice(0, path.lastIndexOf('/'));
  return folder === 'scenarios' ? null : folder;
}

/**
 * Why Run cannot start now, or null: another tab holds the workspace, there is no profile, a run is
 * going on, or the lab misses something (spec §4.5: "Run is blocked with that message").
 */
function blockedReason(wb: WorkbenchApi, store: RunStore): string | null {
  if (wb.readOnly) {
    return t('ui.lock.banner');
  }
  if (wb.profile === null) {
    return t('ui.run.noProfile');
  }
  if (store.busy()) {
    return t('ui.run.running');
  }
  const lab = wb.labState();
  if (lab.state === 'missing') {
    return t('ui.run.blocked', { message: lab.message });
  }
  return null;
}

/**
 * Runs `scope`, saving the active file first when `save` (Save & Run): a save that fails or opens
 * the changed-on-disk choice means no run.
 */
async function runScope(wb: WorkbenchApi, store: RunStore, scope: RunScope, save: boolean) {
  if (blockedReason(wb, store) !== null || wb.profile === null) {
    return;
  }
  if (save) {
    try {
      await wb.saveActive();
    } catch {
      return; // the reason shows in the tab or the dialog
    }
  }
  await store.start(scope, wb.profile, currentLanguage());
}

/**
 * The Run split button in the top bar (spec §4.6, mockup run-all.html): ▶ Run (⌘↵ / Ctrl+Enter)
 * runs the active scenario — "Save & Run" when it has unsaved changes — and ▾ opens Run folder,
 * Run all scenarios (⇧⌘↵) and Run only the ones that found something.
 */
export function RunButton({ store }: { store: RunStore }) {
  useLanguage();
  const wb = useWorkbench();
  const run = useSyncExternalStore(store.subscribe, store.getState);
  const [open, setOpen] = useState(false);
  const arrow = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const active = isScenario(wb.activePath) ? wb.activePath : null;
  const dirty = active !== null && wb.isDirty(active);
  const blocked = blockedReason(wb, store);
  const folder = folderOf(active);

  const counts = useMemo(() => {
    const all = scenariosIn(wb.tree);
    return {
      all: all.length,
      folder: folder ? scenariosIn(wb.tree, folder).length : 0,
      findings: all.filter((node) => node.lastResult === 'finding' || node.lastResult === 'setup').length,
    };
  }, [wb.tree, folder]);

  // the latest values for the window's shortcut handler
  const latest = useRef({ wb, active, dirty, counts });
  latest.current = { wb, active, dirty, counts };

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!(event.metaKey || event.ctrlKey) || event.altKey || event.key !== 'Enter') {
        return;
      }
      // in the capture phase, before the editor turns ⌘↵ into a new line
      event.preventDefault();
      event.stopPropagation();
      const now = latest.current;
      if (event.shiftKey) {
        if (now.counts.all > 0) {
          void runScope(now.wb, store, { kind: 'all' }, false);
        }
      } else if (now.active) {
        void runScope(now.wb, store, { kind: 'scenario', path: now.active }, now.dirty);
      }
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [store]);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    menu.current?.querySelector<HTMLButtonElement>('[role=menuitem]:not(:disabled)')?.focus();
    const onDown = (event: MouseEvent) => {
      if (!menu.current?.contains(event.target as Node) && !arrow.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  const close = () => {
    setOpen(false);
    arrow.current?.focus();
  };

  const choose = (scope: RunScope, save = false) => {
    setOpen(false);
    void runScope(wb, store, scope, save);
  };

  const onMenuKey = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('[role=menuitem]:not(:disabled)') ?? []);
    const at = items.indexOf(document.activeElement as HTMLButtonElement);
    const move = (index: number) => items[(index + items.length) % items.length]?.focus();
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        move(at + 1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        move(at - 1);
        break;
      case 'Home':
        event.preventDefault();
        move(0);
        break;
      case 'End':
        event.preventDefault();
        move(items.length - 1);
        break;
      case 'Escape':
        event.preventDefault();
        close();
        break;
      case 'Tab':
        setOpen(false);
        break;
      default:
        break;
    }
  };

  const mainTitle = blocked ?? (active ? `${dirty ? t('ui.run.saveAndRun') : t('ui.run.run')} (${RUN_KEYS})` : t('ui.run.noScenario'));

  return (
    <div className="run-split">
      <button
        type="button"
        className="primary run-main"
        disabled={blocked !== null || active === null || run.phase === 'starting'}
        title={mainTitle}
        onClick={() => active && void runScope(wb, store, { kind: 'scenario', path: active }, dirty)}
      >
        <span aria-hidden="true">▶ </span>
        {dirty ? t('ui.run.saveAndRun') : t('ui.run.run')}
      </button>
      <button
        ref={arrow}
        type="button"
        className="primary run-arrow"
        aria-label={t('ui.run.more')}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? 'run-menu' : undefined}
        disabled={blocked !== null}
        title={blocked ?? t('ui.run.more')}
        onClick={() => setOpen((value) => !value)}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' && !open) {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        ▾
      </button>
      {open && (
        <div id="run-menu" ref={menu} className="run-menu" role="menu" aria-label={t('ui.run.more')} onKeyDown={onMenuKey}>
          <button type="button" role="menuitem" disabled={active === null} onClick={() => active && choose({ kind: 'scenario', path: active }, dirty)}>
            <span>
              <span aria-hidden="true">▶ </span>
              {dirty ? t('ui.run.saveAndRun') : t('ui.run.menu.this')}
            </span>
            <kbd>{RUN_KEYS}</kbd>
          </button>
          {folder && (
            <button type="button" role="menuitem" disabled={counts.folder === 0} onClick={() => choose({ kind: 'folder', folder })}>
              <span>{t('ui.run.menu.folder', { folder: `${folder.slice('scenarios/'.length)}/`, count: counts.folder })}</span>
              <kbd />
            </button>
          )}
          <button type="button" role="menuitem" disabled={counts.all === 0} onClick={() => choose({ kind: 'all' })}>
            <span>{t('ui.run.menu.all', { count: counts.all })}</span>
            <kbd>{RUN_ALL_KEYS}</kbd>
          </button>
          <button type="button" role="menuitem" disabled={counts.findings === 0} onClick={() => choose({ kind: 'findings' })}>
            <span>{t('ui.run.menu.findings', { count: counts.findings })}</span>
            <kbd />
          </button>
        </div>
      )}
    </div>
  );
}
