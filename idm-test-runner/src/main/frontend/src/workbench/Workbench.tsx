import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { api, ApiProblem, type OpenWorkspace, type ServerMessage } from '../api/client';
import { Badge } from '../components/Badge';
import { t } from '../i18n';
import { ChangedOnDiskDialog } from './ChangedOnDiskDialog';
import { EditorPane } from './EditorPane';
import { FileTree } from './FileTree';
import { WorkbenchContext, type WorkbenchApi } from './context';
import { isDirty, WorkbenchStore } from './store';
import { openDrafts } from './drafts';
import { createTabLock, type LockState, type TabLock } from './tabLock';
import { TopBar } from './TopBar';
import { LabChip } from '../lab/LabChip';
import { deriveLabState } from '../lab/labState';
import { LabStore } from '../lab/labStore';
import { RunButton } from '../run/RunButton';
import { RunPanel } from '../run/RunPanel';
import { readScenarioFile, RunStore } from '../run/runStore';
import type { TreeNode } from './types';
import './Workbench.css';

interface Props {
  workspace: OpenWorkspace;
  /** A warning from opening the workspace (e.g. about git). */
  warning?: ServerMessage;
  /** How long a new tab listens for another tab holding the workspace (ms). */
  lockWait?: number;
  /** The right-hand run panel; the live run panel when not given. */
  runPanel?: ReactNode;
  /** The lab chip in the top bar; the lab chip and panel when not given. */
  lab?: ReactNode;
  /** The Run split button in the top bar; Run and its menu when not given. */
  run?: ReactNode;
}

interface FileEvent {
  type: 'changed' | 'created' | 'deleted';
  path: string;
}

/** What the run panel says before any run. */
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
  const [takingOver, setTakingOver] = useState(false);
  const [tree, setTree] = useState<TreeNode | null>(null);
  const [treeError, setTreeError] = useState<string | null>(null);
  const [profile, setProfile] = useState<string | null>(workspace.profiles[0] ?? null);
  const [showWarning, setShowWarning] = useState(Boolean(warning));
  const labStore = useMemo(() => new LabStore(), [workspace.path]);
  const labView = useSyncExternalStore(labStore.subscribe, labStore.getState);

  useEffect(() => {
    void labStore.setProfile(profile);
  }, [labStore, profile]);

  useEffect(() => () => labStore.dispose(), [labStore]);

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

  // the run of this workspace: Run in the top bar and the run panel share it
  const refreshTreeRef = useRef(refreshTree);
  refreshTreeRef.current = refreshTree;
  const runStore = useMemo(
    () => new RunStore({ refreshTree: () => void refreshTreeRef.current(), readScenario: readScenarioFile }),
    [workspace.path],
  );
  const runState = useSyncExternalStore(runStore.subscribe, runStore.getState);
  useEffect(() => {
    runStore.connect(); // the current or last run
    return () => runStore.dispose();
  }, [runStore]);
  const runningPath = runState.scenarios.find((s) => s.state === 'running')?.name;

  // leaving the workspace: keep pending drafts, then stop the timers
  useEffect(() => () => void store.flushNow().finally(() => store.dispose()), [store]);

  useEffect(() => {
    void openDrafts(); // the drafts connection is open before any pagehide needs it
    const created = createTabLock(workspace.path, {
      wait: lockWait,
      // turn read-only here (drafts written, kept tabs reset) and say `yielded` only once the
      // writes are committed, so the tab taking over reads them
      onYield: async () => {
        store.setReadOnly(true);
        await store.lastFlush;
      },
      // asked by a tab about to take over: drafts are written first, so the answer includes failed writes
      hasUnkeptEdits: () => store.checkUnkeptEdits(),
    });
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
      labStore.fileEvent(event.path);
    });
  }, [store, refreshTree, labStore]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && !event.altKey && !event.shiftKey && event.key.toLowerCase() === 's') {
        event.preventDefault();
        void store.save().catch(() => undefined); // the reason shows in the tab or the dialog
      }
    };
    // the lock flushes as it lets go (store.setReadOnly); this covers a page without BroadcastChannel
    const onPageHide = () => void store.flushNow();
    // a file dropped anywhere but the tree must not make the browser open it and leave the page
    const onDrag = (event: DragEvent) => {
      // the tree accepts files itself (it has already called preventDefault): leave those alone
      if (!event.defaultPrevented && Array.from(event.dataTransfer?.types ?? []).includes('Files')) {
        event.preventDefault();
        if (event.type === 'dragover' && event.dataTransfer) {
          event.dataTransfer.dropEffect = 'none';
        }
      }
    };
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      // Write the drafts here, not only in pagehide: Chromium drops IndexedDB transactions still
      // running when a reloading document unloads (a put issued in pagehide is lost), while a put
      // issued in beforeunload, over the already-open connection, commits. pagehide still flushes
      // for the cases without beforeunload (back/forward cache, mobile tab discards).
      // not final: the user may cancel leaving, and a draft still due must stay due
      void store.flushNow(false);
      // edits that cannot be kept as a draft (a plain-text password, unchecked text) would be lost
      if (store.hasUnkeptEdits()) {
        event.preventDefault();
      }
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('pagehide', onPageHide);
    window.addEventListener('beforeunload', onBeforeUnload);
    window.addEventListener('dragover', onDrag);
    window.addEventListener('drop', onDrag);
    return () => {
      window.removeEventListener('dragover', onDrag);
      window.removeEventListener('drop', onDrag);
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
      tree,
      textOf: (path) => store.doc(path)?.content,
      focus: (path, line) => store.focus(path, line),
      labState: () => deriveLabState(labView.view, labView.error, profile !== null),
      lab: labView.view,
      openLab: (systemId) => labStore.openPanel(systemId),
      refreshLab: () => labStore.reload(),
    }),
    [workspace, profile, state.active, state.readOnly, state.tabs, store, refreshTree, tree, labStore, labView],
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
          onProfile={(next) => {
            // the lab panel's typing belongs to the profile it was typed for
            if (labStore.hasUnsaved() && !window.confirm(t('ui.lab.confirmProfile'))) {
              return;
            }
            setProfile(next);
          }}
          unsaved={dirty.size}
          canSave={!state.readOnly && active !== null && isDirty(active)}
          saving={active?.saving ?? false}
          onSave={() => void store.save().catch(() => undefined)}
          lab={lab === undefined ? <LabChip store={labStore} /> : lab}
          run={run === undefined ? <RunButton store={runStore} /> : run}
        />
        {lockState === 'readOnly' && (
          <div className="lock-banner" role="status">
            <Badge status="warn" />
            <strong>{t('ui.lock.banner')}</strong>
            <span className="muted">{t('ui.lock.hint')}</span>
            <button
              type="button"
              className="secondary"
              disabled={takingOver}
              onClick={() => {
                if (!lock || takingOver) {
                  return;
                }
                setTakingOver(true);
                void lock
                  .takeOver((certain) => window.confirm(t(certain ? 'ui.lock.confirmUnsaved' : 'ui.lock.confirmMaybe')))
                  .finally(() => setTakingOver(false));
              }}
            >
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
              running={runningPath ? `scenarios/${runningPath}` : null}
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
            {runPanel === undefined ? <RunPanel store={runStore} /> : runPanel}
          </aside>
        </div>
      </div>
      {conflicted?.conflict && !state.readOnly && (
        <ChangedOnDiskDialog
          key={`${conflicted.path}:${conflicted.conflict.diskVersion}`}
          path={conflicted.path}
          mine={conflicted.content}
          disk={conflicted.conflict.diskContent}
          error={conflicted.error}
          busy={conflicted.saving}
          onRetry={() => void store.retryDisk(conflicted.path)}
          onKeepMine={() => void store.keepMine(conflicted.path)}
          onLoadFromDisk={() => void store.loadFromDisk(conflicted.path)}
        />
      )}
    </WorkbenchContext.Provider>
  );
}
