import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore, type CSSProperties, type ReactNode } from 'react';
import { api, ApiProblem, setPageWorkspace, workspaceSwitch, type OpenWorkspace, type ServerMessage } from '../api/client';
import { Badge } from '../components/Badge';
import { t } from '../i18n';
import { ChangedOnDiskDialog } from './ChangedOnDiskDialog';
import { EditorPane } from './EditorPane';
import { FileTree } from './FileTree';
import { WorkspaceIcon } from './WorkspaceIcon';
import { WorkbenchContext, type WorkbenchApi } from './context';
import { isDirty, WorkbenchStore } from './store';
import { openDrafts } from './drafts';
import { createTabLock, type LockState, type TabLock } from './tabLock';
import { TopBar } from './TopBar';
import { LabChip } from '../lab/LabChip';
import { deriveLabState } from '../lab/labState';
import { LabStore } from '../lab/labStore';
import { isRunFolder, RunButton } from '../run/RunButton';
import { RunPanel } from '../run/RunPanel';
import { readScenarioFile, RunStore } from '../run/runStore';
import type { TreeNode } from './types';
import { clampPanelLayout, usePanelLayout } from './panelLayout';
import { PanelSplitter } from './PanelSplitter';
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
  /** `overflow`: events may have been missed (path ''): read the tree and the open files again. */
  type: 'changed' | 'created' | 'deleted' | 'overflow';
  path: string;
}

/**
 * Where New scenario creates the file: the folder selected in the tree, else the open file's
 * folder, else scenarios/ — always scenarios/ or a folder below it.
 */
export function newScenarioFolder(selected: string | null, active: string | null): string {
  const underScenarios = (folder: string | null): folder is string =>
    folder !== null && (folder === 'scenarios' || folder.startsWith('scenarios/'));
  if (underScenarios(selected)) {
    return selected;
  }
  const activeFolder = active?.includes('/') ? active.slice(0, active.lastIndexOf('/')) : null;
  return underScenarios(activeFolder) ? activeFolder : 'scenarios';
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
  // before any request of this screen (its children's effects run before its own): they are for this workspace
  useMemo(() => setPageWorkspace(workspace.path), [workspace.path]);
  const switched = useSyncExternalStore(workspaceSwitch.subscribe, workspaceSwitch.get);
  const store = useMemo(() => new WorkbenchStore(workspace.path), [workspace.path]);
  const state = useSyncExternalStore(store.subscribe, store.getState);
  const [lock, setLock] = useState<TabLock | null>(null);
  const [lockState, setLockState] = useState<LockState>('probing');
  const [takingOver, setTakingOver] = useState(false);
  const [tree, setTree] = useState<TreeNode | null>(null);
  const [treeError, setTreeError] = useState<string | null>(null);
  const [revealFolder, setRevealFolder] = useState<'config' | 'scenarios' | null>(null);
  const [profile, setProfile] = useState<string | null>(workspace.profiles[0] ?? null);
  const [showWarning, setShowWarning] = useState(Boolean(warning));
  const labStore = useMemo(() => new LabStore(), [workspace.path]);
  const labView = useSyncExternalStore(labStore.subscribe, labStore.getState);
  const { layout, update: updateLayout, reset: resetLayout } = usePanelLayout(workspace.path);
  const [viewport, setViewport] = useState(() => ({ width: window.innerWidth, height: window.innerHeight }));
  useEffect(() => {
    const measure = () => setViewport({ width: window.innerWidth, height: window.innerHeight });
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, []);
  const shownLayout = clampPanelLayout(layout, viewport.width, viewport.height);

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
    () =>
      new RunStore({
        refreshTree: () => void refreshTreeRef.current(),
        readScenario: readScenarioFile,
        // a new run of a scenario: the last run's failed step is no longer marked
        scenarioStarted: (path) => store.markWhenOpen(path, null),
      }),
    [workspace.path, store],
  );
  const runState = useSyncExternalStore(runStore.subscribe, runStore.getState);
  useEffect(() => {
    runStore.connect(); // the current or last run
    return () => runStore.dispose();
  }, [runStore]);
  // while a run goes on, the tree marks each of its scenarios still to finish
  const runStates = useMemo(() => {
    const marks = new Map<string, 'running' | 'waiting'>();
    if (runState.phase === 'starting' || runState.phase === 'running') {
      for (const row of runState.scenarios) {
        if (row.state === 'running' || row.state === 'waiting') {
          marks.set(`scenarios/${row.name}`, row.state);
        }
      }
    }
    return marks;
  }, [runState.phase, runState.scenarios]);
  const runProgress = useMemo(() => {
    if (runState.phase === 'starting') return '…';
    if (runState.phase !== 'running' || runState.scenarios.length === 0) return null;
    const count = runState.scenarios.filter((row) => row.state !== 'waiting' && row.state !== 'notRun').length;
    return `${count}/${runState.scenarios.length}`;
  }, [runState.phase, runState.scenarios]);
  // the folder Run folder runs: the last folder clicked in the tree, until a file is opened from it
  const [runFolder, setRunFolder] = useState<string | null>(null);

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
    return api.events(
      '/api/files/events',
      (_name, data) => {
        const event = data as FileEvent | null;
        if (!event || typeof event.path !== 'string') {
          return;
        }
        if (event.type === 'overflow') {
          void refreshTree();
          void store.recheckOpen();
          void labStore.reload();
          return;
        }
        if (event.type === 'created' || event.type === 'deleted') {
          void refreshTree();
        }
        if (event.type === 'changed' || event.type === 'created') {
          void store.fileChanged(event.path);
        }
        if (event.type === 'deleted') {
          void store.fileDeleted(event.path);
        }
        labStore.fileEvent(event.path);
      },
      [],
      (closed) => {
        // the server ended the stream for good (e.g. another tab opened another workspace: 409);
        // reading the tree says why, and shows the banner when that is it
        if (closed) {
          void refreshTree();
        }
      },
    );
  }, [store, refreshTree, labStore]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && !event.altKey && !event.shiftKey && event.key.toLowerCase() === 's') {
        event.preventDefault();
        if (workspaceSwitch.get() !== null) {
          return; // another workspace is open: nothing is saved from here
        }
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
      markWhenOpen: (path, mark) => store.markWhenOpen(path, mark),
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
          onResetLayout={resetLayout}
          lab={lab === undefined ? <LabChip store={labStore} /> : lab}
          run={run === undefined ? <RunButton store={runStore} runFolder={runFolder} /> : run}
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
        <div className="wb-grid" style={{
          '--workspace-width': `${shownLayout.workspaceWidth}px`,
          '--run-width': `${shownLayout.runWidth}px`,
          '--workspace-handle': layout.workspaceCollapsed ? '0px' : '6px',
          '--run-handle': layout.runCollapsed ? '0px' : '6px',
        } as CSSProperties}>
          <div className={`wb-col wb-tree${layout.workspaceCollapsed ? ' wb-rail' : ''}`}>
            {layout.workspaceCollapsed ? (
              <div className="workspace-rail" role="toolbar" aria-label={t('ui.tree.title')}>
                <button type="button" className="workspace-rail-button workspace-rail-expand" aria-label={t('ui.layout.expandWorkspace')} title={t('ui.layout.expandWorkspace')} onClick={() => { setRevealFolder(null); updateLayout({ workspaceCollapsed: false }); }}>
                  <WorkspaceIcon name="expand" />
                </button>
                {tree?.children?.some((node) => node.dir && node.path === 'config') && (
                  <button type="button" className="workspace-rail-button" aria-label={t('ui.tree.showConfigFolder')} title={t('ui.tree.showConfigFolder')} onClick={() => { setRevealFolder('config'); updateLayout({ workspaceCollapsed: false }); }}>
                    <WorkspaceIcon name="config" />
                  </button>
                )}
                {tree?.children?.some((node) => node.dir && node.path === 'scenarios') && (
                  <button type="button" className="workspace-rail-button" aria-label={t('ui.tree.showScenariosFolder')} title={t('ui.tree.showScenariosFolder')} onClick={() => { setRevealFolder('scenarios'); updateLayout({ workspaceCollapsed: false }); }}>
                    <WorkspaceIcon name="scenarios" />
                  </button>
                )}
              </div>
            ) : <>
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
              onCollapse={() => updateLayout({ workspaceCollapsed: true })}
              revealFolder={revealFolder}
              // a folder Run folder cannot run (scenarios/, config/…) is not shown as selected
              selectedFolder={isRunFolder(runFolder) ? runFolder : null}
              runStates={runStates}
              onOpen={(path) => {
                setRunFolder(null);
                void store.open(path);
              }}
              onSelectFolder={setRunFolder}
              onAdded={() => void refreshTree()}
              newFolder={newScenarioFolder(runFolder, state.active)}
              textOf={(path) => {
                const doc = store.doc(path);
                return doc?.loaded ? doc.content : undefined;
              }}
              onCreated={(path) => {
                setRunFolder(null);
                void refreshTree();
                void store.open(path);
              }}
              prepareDelete={(path) => store.prepareDelete(path)}
              onDelete={async (path, version) => {
                try {
                  await store.deleteFile(path, version);
                } catch (e) {
                  return e instanceof ApiProblem ? e.message : String(e);
                }
                void refreshTree();
                return null;
              }}
            />
            </>}
          </div>
          {!layout.workspaceCollapsed && <div className="wb-divider wb-divider-tree"><PanelSplitter orientation="vertical" label={t('ui.layout.resizeWorkspace')} value={shownLayout.workspaceWidth} min={170} max={Math.max(170, viewport.width - shownLayout.runWidth - 332)} onChange={(workspaceWidth) => updateLayout({ workspaceWidth })} /></div>}
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
              tree={tree}
              dirty={dirty}
              onOpenFile={(path) => { setRunFolder(null); void store.open(path); }}
              problemsHeight={shownLayout.problemsHeight}
              problemsCollapsed={layout.problemsCollapsed}
              onProblemsHeight={(problemsHeight) => updateLayout({ problemsHeight })}
              onProblemsCollapsed={(problemsCollapsed) => updateLayout({ problemsCollapsed })}
            />
          </div>
          {!layout.runCollapsed && <div className="wb-divider wb-divider-run"><PanelSplitter orientation="vertical" reverse label={t('ui.layout.resizeRun')} value={shownLayout.runWidth} min={220} max={Math.max(220, viewport.width - shownLayout.workspaceWidth - 332)} onChange={(runWidth) => updateLayout({ runWidth })} /></div>}
          <aside className={`wb-col wb-run${layout.runCollapsed ? ' wb-rail' : ''}`} aria-labelledby="run-title">
            {layout.runCollapsed ? (
              <button type="button" className="rail-button" aria-label={t('ui.layout.expandRun')} onClick={() => updateLayout({ runCollapsed: false })}>
                <span aria-hidden="true">◂</span><span className="rail-name" id="run-title">{t('ui.run.title')}</span>
                {runProgress && <span className="rail-progress" aria-label={t('ui.layout.runProgress')}>{runProgress}</span>}
              </button>
            ) : <>
            <div className="pane-head">
              <span id="run-title">{t('ui.run.title')}</span>
              <button type="button" className="pane-icon" aria-label={t('ui.layout.collapseRun')} onClick={() => updateLayout({ runCollapsed: true })}>▸</button>
            </div>
            {runPanel === undefined ? <RunPanel store={runStore} /> : runPanel}
            </>}
          </aside>
        </div>
      </div>
      {switched !== null && (
        <div className="switched-overlay">
          <div className="switched-banner" role="alertdialog" aria-modal="true" aria-labelledby="switched-title" aria-describedby="switched-hint">
            <p id="switched-title">
              <Badge status="error" /> <strong>{switched}</strong>
            </p>
            <p id="switched-hint" className="muted">
              {t('ui.workspace.switchedHint')}
            </p>
            <button type="button" className="primary" autoFocus onClick={() => window.location.reload()}>
              {t('ui.workspace.reload')}
            </button>
          </div>
        </div>
      )}
      {conflicted?.conflict && !state.readOnly && switched === null && (
        <ChangedOnDiskDialog
          key={`${conflicted.path}:${conflicted.conflict.deleted ? 'deleted' : conflicted.conflict.diskVersion}`}
          path={conflicted.path}
          mine={conflicted.content}
          disk={conflicted.conflict.diskContent}
          error={conflicted.error}
          busy={conflicted.saving}
          deleted={conflicted.conflict.deleted ?? false}
          onDiscard={() => store.discardDeleted(conflicted.path)}
          onRetry={() => void store.retryDisk(conflicted.path)}
          onKeepMine={() => void store.keepMine(conflicted.path)}
          onLoadFromDisk={() => void store.loadFromDisk(conflicted.path)}
        />
      )}
    </WorkbenchContext.Provider>
  );
}
