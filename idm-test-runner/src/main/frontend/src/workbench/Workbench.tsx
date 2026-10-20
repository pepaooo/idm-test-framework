import { useCallback, useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react';
import { api, ApiProblem, type OpenWorkspace, type ServerMessage } from '../api/client';
import { Badge } from '../components/Badge';
import { t } from '../i18n';
import { ChangedOnDiskDialog } from './ChangedOnDiskDialog';
import { EditorPane } from './EditorPane';
import { FileTree } from './FileTree';
import { WorkbenchContext, type WorkbenchApi } from './context';
import { isDirty, WorkbenchStore } from './store';
import { createTabLock, type LockState, type TabLock } from './tabLock';
import { TopBar } from './TopBar';
import type { TreeNode } from './types';
import './Workbench.css';

interface Props {
  workspace: OpenWorkspace;
  /** A warning from opening the workspace (e.g. about git). */
  warning?: ServerMessage;
  /** How long a new tab listens for another tab holding the workspace (ms). */
  lockWait?: number;
  /** The right-hand run panel (Task 14); a placeholder until then. */
  runPanel?: ReactNode;
  /** The lab chip in the top bar (Task 13). */
  lab?: ReactNode;
  /** The Run split button in the top bar (Task 14). */
  run?: ReactNode;
}

interface FileEvent {
  type: 'changed' | 'created' | 'deleted';
  path: string;
}

/** Stands in for the run panel until Task 14. */
export function RunPanelSlot() {
  return <p className="muted run-placeholder">{t('ui.run.placeholder')}</p>;
}

/**
 * The main screen (spec §4.3, mockup B): top bar · file tree · editor + Problems · run panel.
 * Owns the open files (a {@link WorkbenchStore}), the tab lock, file events and ⌘S / Ctrl+S, and
 * gives the panels inside it the {@link WorkbenchApi} through `useWorkbench()`.
 */
export function Workbench({ workspace, warning, lockWait = 300, runPanel, lab, run }: Props) {
  const store = useMemo(() => new WorkbenchStore(workspace.path), [workspace.path]);
  const state = useSyncExternalStore(store.subscribe, store.getState);
  const [lock, setLock] = useState<TabLock | null>(null);
  const [lockState, setLockState] = useState<LockState>('probing');
  const [tree, setTree] = useState<TreeNode | null>(null);
  const [treeError, setTreeError] = useState<string | null>(null);
  const [profile, setProfile] = useState<string | null>(workspace.profiles[0] ?? null);
  const [showWarning, setShowWarning] = useState(Boolean(warning));

  const refreshTree = useCallback(async () => {
    try {
      setTree(await api.get<TreeNode>('/api/files/tree'));
      setTreeError(null);
    } catch (e) {
      setTreeError(e instanceof ApiProblem ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    void refreshTree();
  }, [refreshTree]);

  // leaving the workspace: keep pending drafts, then stop the timers
  useEffect(() => () => void store.flushDrafts().finally(() => store.dispose()), [store]);

  useEffect(() => {
    const created = createTabLock(workspace.path, { wait: lockWait, onYield: () => store.flushDrafts() });
    const sync = () => {
      setLockState(created.state());
      store.setReadOnly(created.state() !== 'holder');
    };
    const off = created.subscribe(sync);
    sync();
    setLock(created);
    return () => {
      off();
      created.close();
    };
  }, [store, workspace.path, lockWait]);

  useEffect(() => {
    if (typeof EventSource === 'undefined') {
      return undefined;
    }
    return api.events('/api/files/events', (_name, data) => {
      const event = data as FileEvent | null;
      if (!event || typeof event.path !== 'string') {
        return;
      }
      if (event.type === 'created' || event.type === 'deleted') {
        void refreshTree();
      }
      if (event.type === 'changed' || event.type === 'created') {
        void store.fileChanged(event.path);
      }
    });
  }, [store, refreshTree]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && !event.altKey && !event.shiftKey && event.key.toLowerCase() === 's') {
        event.preventDefault();
        void store.save().catch(() => undefined); // the reason shows in the tab or the dialog
      }
    };
    const onPageHide = () => void store.flushDrafts();
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      // edits that cannot be kept as a draft (a plain-text password) would be lost: ask first
      if (store.getState().tabs.some((doc) => isDirty(doc) && doc.plainSecret)) {
        event.preventDefault();
      }
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('pagehide', onPageHide);
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('pagehide', onPageHide);
      window.removeEventListener('beforeunload', onBeforeUnload);
    };
  }, [store]);

  const dirty = useMemo(() => new Set(state.tabs.filter(isDirty).map((doc) => doc.path)), [state.tabs]);
  const active = state.tabs.find((doc) => doc.path === state.active) ?? null;
  const conflicted =
    (active?.conflict ? active : null) ?? state.tabs.find((doc) => doc.conflict !== null) ?? null;

  const workbenchApi = useMemo<WorkbenchApi>(
    () => ({
      workspace,
      profile,
      activePath: state.active,
      readOnly: state.readOnly,
      isDirty: (path) => {
        const doc = store.doc(path);
        return doc ? isDirty(doc) : false;
      },
      openFile: (path) => store.open(path),
      saveActive: () => store.save(),
      markLines: (path, fromLine, toLine) => store.markLines(path, fromLine, toLine),
      refreshTree,
    }),
    [workspace, profile, state.active, state.readOnly, store, refreshTree],
  );

  const closeTab = (path: string) => {
    const doc = store.doc(path);
    if (doc && isDirty(doc)) {
      if (window.confirm(t('ui.editor.closeUnsaved', { name: path.split('/').pop() ?? path }))) {
        store.close(path, true);
      }
    } else {
      store.close(path);
    }
  };

  return (
    <WorkbenchContext.Provider value={workbenchApi}>
      <div className="workbench">
        <TopBar
          name={workspace.name}
          path={workspace.path}
          profiles={workspace.profiles}
          profile={profile}
          onProfile={setProfile}
          unsaved={dirty.size}
          canSave={!state.readOnly && active !== null && isDirty(active)}
          saving={active?.saving ?? false}
          onSave={() => void store.save().catch(() => undefined)}
          lab={lab}
          run={run}
        />
        {lockState === 'readOnly' && (
          <div className="lock-banner" role="status">
            <Badge status="warn" />
            <strong>{t('ui.lock.banner')}</strong>
            <span className="muted">{t('ui.lock.hint')}</span>
            <button type="button" className="secondary" onClick={() => void lock?.takeOver()}>
              {t('ui.lock.takeOver')}
            </button>
          </div>
        )}
        {showWarning && warning && (
          <p role="status" className="notice wb-warning">
            <Badge status="warn" /> {warning.message}{' '}
            <button type="button" className="link" onClick={() => setShowWarning(false)}>
              ×
            </button>
          </p>
        )}
        <div className="wb-grid">
          <div className="wb-col wb-tree">
            {treeError && (
              <p role="alert" className="tree-message">
                <Badge status="error" /> {treeError}
              </p>
            )}
            <FileTree
              tree={tree}
              active={state.active}
              dirty={dirty}
              readOnly={state.readOnly}
              onOpen={(path) => void store.open(path)}
              onAdded={() => void refreshTree()}
            />
          </div>
          <div className="wb-col wb-editor">
            <EditorPane
              tabs={state.tabs}
              active={state.active}
              readOnly={state.readOnly}
              onActivate={(path) => store.activate(path)}
              onClose={closeTab}
              onChange={(path, content) => store.edit(path, content)}
              onSave={() => void store.save().catch(() => undefined)}
              onGoTo={(path, line, column) => store.focus(path, line, column)}
            />
          </div>
          <aside className="wb-col wb-run" aria-labelledby="run-title">
            <div className="pane-head">
              <span id="run-title">{t('ui.run.title')}</span>
            </div>
            {runPanel ?? <RunPanelSlot />}
          </aside>
        </div>
      </div>
      {conflicted?.conflict && (
        <ChangedOnDiskDialog
          key={`${conflicted.path}:${conflicted.conflict.diskVersion}`}
          path={conflicted.path}
          mine={conflicted.content}
          disk={conflicted.conflict.diskContent}
          error={conflicted.error}
          busy={conflicted.saving || state.readOnly}
          onKeepMine={() => void store.keepMine(conflicted.path)}
          onLoadFromDisk={() => void store.loadFromDisk(conflicted.path)}
        />
      )}
    </WorkbenchContext.Provider>
  );
}
