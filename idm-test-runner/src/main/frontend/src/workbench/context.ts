import { createContext, useContext } from 'react';
import type { OpenWorkspace } from '../api/client';
import type { LabState, LabView } from '../lab/types';
import type { TreeNode } from './types';

/**
 * What the workbench offers the panels inside it (the run panel of Task 14, the lab chip of
 * Task 13): read with `useWorkbench()` from any component rendered inside `<Workbench>`.
 */
export interface WorkbenchApi {
  workspace: OpenWorkspace;
  /** The profile chosen in the top bar (null when the workspace has none). */
  profile: string | null;
  /** The active tab's workspace-relative path, or null. */
  activePath: string | null;
  /** True while another browser tab holds this workspace (or before this tab knows). */
  readOnly: boolean;
  isDirty(path: string): boolean;
  /** Opens `path` in a tab and makes it active; resolves once its text is loaded (secrets.env: no-op). */
  openFile(path: string): Promise<void>;
  /**
   * Saves the active tab when it has unsaved changes. Resolves once it is saved (or there was
   * nothing to save); rejects with a `SaveError` when it was not saved: the file changed on disk
   * (the changed-on-disk choice is then open), the tab is read-only, or the server refused.
   */
  saveActive(): Promise<void>;
  /** Opens `path` and highlights lines `fromLine`–`toLine` (1-based, inclusive; order does not matter). */
  markLines(path: string, fromLine: number, toLine: number): Promise<void>;
  /** Reloads the file tree (e.g. after a run, for the last-result badges). */
  refreshTree(): Promise<void>;
  /** The file tree as last read (scenarios with their last result), or null before it is read. */
  tree: TreeNode | null;
  /** The text an open tab shows now (unsaved edits included), or undefined when it is not open. */
  textOf(path: string): string | undefined;
  /**
   * Marks lines of `path`, worked out from its text, without opening or activating it: at once
   * when it is open, else when it is opened later; null clears them.
   */
  markWhenOpen(path: string, mark: ((text: string) => { from: number; to: number } | null) | null): void;
  /** Moves the cursor of `path`'s tab to `line` (1-based) and makes it active. */
  focus(path: string, line: number): void;
  /**
   * The lab's readiness for the current profile (Task 13): `missing` (with `missingSystemId`) blocks
   * a run with `message`; `untested` and `ready` do not. `error` is set while the last reload of
   * the lab failed (the state is then the last good one). Kept current: read again on a profile
   * switch, after the lab panel saves or tests, when it opens, and when config/, scenarios/ or
   * secrets.env change on disk.
   */
  labState(): LabState;
  /** The lab as last read (systems with their fields and status, no secret values), or null. */
  lab: LabView | null;
  /** Opens the lab panel, on `systemId`'s tab when given (e.g. the missing system). */
  openLab(systemId?: string): void;
  /** Reads the lab again now. */
  refreshLab(): Promise<void>;
}

export const WorkbenchContext = createContext<WorkbenchApi | null>(null);

export function useWorkbench(): WorkbenchApi {
  const api = useContext(WorkbenchContext);
  if (!api) {
    throw new Error('useWorkbench() outside <Workbench>');
  }
  return api;
}
