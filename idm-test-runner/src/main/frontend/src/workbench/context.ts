import { createContext, useContext } from 'react';
import type { OpenWorkspace } from '../api/client';

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
}

export const WorkbenchContext = createContext<WorkbenchApi | null>(null);

export function useWorkbench(): WorkbenchApi {
  const api = useContext(WorkbenchContext);
  if (!api) {
    throw new Error('useWorkbench() outside <Workbench>');
  }
  return api;
}
