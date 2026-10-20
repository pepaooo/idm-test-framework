import { api, ApiProblem, withQuery } from '../api/client';
import { t } from '../i18n';
import { deleteDraft, listDrafts, readDraft, writeDraft, type Draft } from './drafts';
import { isSecretsFile, isValidatable, PLAIN_SECRET, type EditorFocus, type LineMark, type Problem } from './types';

/**
 * The file on disk when it differs from what the tab was loaded from. `diskContent` is null when
 * the file could not be read back (after a 409): then only Keep mine, or a retry, is offered.
 */
export interface Conflict {
  diskContent: string | null;
  diskVersion: string;
}

/** An open file (a tab). Unsaved = `content` differs from `diskContent`. */
export interface Doc {
  path: string;
  loaded: boolean;
  content: string;
  /** The text last read from or saved to disk, with its `version`. */
  diskContent: string;
  version: string;
  problems: readonly Problem[];
  plainSecret: boolean;
  /** The text the current `problems` are for (null: not validated yet). */
  validated: string | null;
  conflict: Conflict | null;
  marks: LineMark | null;
  focus: EditorFocus | null;
  error: string | null;
  saving: boolean;
}

export interface State {
  tabs: readonly Doc[];
  active: string | null;
  readOnly: boolean;
}

export function isDirty(doc: Doc): boolean {
  return doc.loaded && doc.content !== doc.diskContent;
}

/** A save that did not happen: the file changed on disk, the tab is read-only, or the server said no. */
export class SaveError extends Error {
  readonly reason: 'conflict' | 'readOnly' | 'failed';

  constructor(reason: SaveError['reason'], message: string) {
    super(message);
    this.name = 'SaveError';
    this.reason = reason;
  }
}

interface FileContent {
  content: string;
  version: string;
}

export interface StoreOptions {
  validateDelay?: number;
  draftDelay?: number;
  /** How soon a validation that got no answer is tried again (ms). */
  retryDelay?: number;
}

const filePath = (path: string) => withQuery('/api/files', { path });

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/**
 * The workbench's open files and every rule that protects the user's edits (spec §4.4):
 * saves only on request and only over the version last read or saved (409 → the changed-on-disk
 * choice); a file changed on disk reloads silently when it has no unsaved edits, and otherwise
 * offers the choice; drafts in IndexedDB, written ~1 s after an edit once the text has been
 * validated, never for a file whose latest validation found a plain-text password; Problems 400 ms
 * after typing stops, older answers ignored. Plain TypeScript: React reads it through
 * `subscribe`/`getState`.
 */
export class WorkbenchStore {
  readonly workspace: string;
  private state: State = { tabs: [], active: null, readOnly: true };
  private readonly listeners = new Set<() => void>();
  private readonly validateDelay: number;
  private readonly draftDelay: number;
  private readonly retryDelay: number;
  private readonly validateTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly draftTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly draftWanted = new Set<string>();
  private readonly validationSeq = new Map<string, number>();
  private readonly saves = new Map<string, Promise<void>>();
  private readonly opening = new Map<string, Promise<void>>();
  private nonce = 0;

  constructor(workspace: string, options: StoreOptions = {}) {
    this.workspace = workspace;
    this.validateDelay = options.validateDelay ?? 400;
    this.draftDelay = options.draftDelay ?? 1000;
    this.retryDelay = options.retryDelay ?? 3000;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getState = (): State => this.state;

  doc(path: string): Doc | undefined {
    return this.state.tabs.find((tab) => tab.path === path);
  }

  private set(next: Partial<State>) {
    this.state = { ...this.state, ...next };
    this.listeners.forEach((listener) => listener());
  }

  private update(path: string, change: (doc: Doc) => Partial<Doc>) {
    let changed = false;
    const tabs = this.state.tabs.map((tab) => {
      if (tab.path !== path) {
        return tab;
      }
      changed = true;
      return { ...tab, ...change(tab) };
    });
    if (changed) {
      this.set({ tabs });
    }
  }

  /**
   * Read-only (another tab holds the workspace, or this page is going away): first keeps every
   * tab's edits as drafts — the writes start synchronously, so this works inside `pagehide` — then
   * puts the tabs back to the disk text, so in-memory edits never outlive the lock (the holder works
   * from the drafts). Editable again: the stored drafts win over what the tabs show.
   */
  setReadOnly(readOnly: boolean): void {
    const was = this.state.readOnly;
    if (was === readOnly) {
      return;
    }
    if (readOnly) {
      this.lastFlush = this.flushNow();
      [...this.state.tabs.map((tab) => tab.path)].forEach((path) => this.clearTimers(path));
      const tabs = this.state.tabs.map((tab) => {
        if (!tab.loaded || (!isDirty(tab) && !tab.conflict)) {
          return tab;
        }
        const disk = tab.conflict?.diskContent ?? null;
        return {
          ...tab,
          content: disk ?? tab.diskContent,
          diskContent: disk ?? tab.diskContent,
          version: disk !== null && tab.conflict ? tab.conflict.diskVersion : tab.version,
          conflict: null,
          validated: null,
          problems: [],
          plainSecret: false,
        };
      });
      const reset = tabs.filter((tab, i) => tab !== this.state.tabs[i]).map((tab) => tab.path);
      this.set({ tabs, readOnly });
      reset.forEach((path) => void this.validate(path));
      return;
    }
    this.set({ readOnly });
    void this.restoreDrafts();
  }

  /** The draft writes started when this tab last became read-only. */
  lastFlush: Promise<void> = Promise.resolve();

  activate(path: string): void {
    if (this.doc(path)) {
      this.set({ active: path });
    }
  }

  /** Opens `path` in a tab (or switches to it), restoring its draft; secrets.env is never opened. */
  open(path: string, activate = true): Promise<void> {
    if (isSecretsFile(path)) {
      return Promise.resolve();
    }
    if (this.doc(path)) {
      if (activate) {
        this.activate(path);
      }
      return this.opening.get(path) ?? Promise.resolve();
    }
    const placeholder: Doc = {
      path,
      loaded: false,
      content: '',
      diskContent: '',
      version: '',
      problems: [],
      plainSecret: false,
      validated: null,
      conflict: null,
      marks: null,
      focus: null,
      error: null,
      saving: false,
    };
    this.set({ tabs: [...this.state.tabs, placeholder], active: activate || !this.state.active ? path : this.state.active });
    const loading = this.load(path).finally(() => this.opening.delete(path));
    this.opening.set(path, loading);
    return loading;
  }

  private async load(path: string): Promise<void> {
    let disk: FileContent;
    try {
      disk = await api.get<FileContent>(filePath(path));
    } catch (e) {
      this.update(path, () => ({ error: messageOf(e) }));
      return;
    }
    const draft = this.state.readOnly ? null : await readDraft(this.workspace, path);
    if (!this.doc(path)) {
      return;
    }
    this.update(path, () => ({
      loaded: true,
      content: disk.content,
      diskContent: disk.content,
      version: disk.version,
      error: null,
    }));
    if (draft) {
      await this.applyDraft(path, draft);
    }
    void this.validate(path);
  }

  /**
   * A draft over the loaded file — it wins over what the tab shows: same base → the edits, marked
   * unsaved; another base → the choice (the tab keeps the draft's base version, so a save is refused
   * until the user chooses).
   */
  private async applyDraft(path: string, draft: Draft): Promise<void> {
    const doc = this.doc(path);
    if (!doc || !doc.loaded) {
      return;
    }
    if (draft.content === doc.diskContent) {
      this.update(path, (current) => ({ content: current.diskContent, conflict: null, validated: null }));
      await deleteDraft(this.workspace, path);
      return;
    }
    this.update(path, (current) =>
      draft.baseVersion === current.version
        ? { content: draft.content, conflict: null, validated: null }
        : {
            content: draft.content,
            version: draft.baseVersion,
            conflict: { diskContent: current.diskContent, diskVersion: current.version },
            validated: null,
          },
    );
  }

  /** Opens every file of this workspace with a draft (after a reload, or after taking over). */
  async restoreDrafts(): Promise<void> {
    const paths = await listDrafts(this.workspace);
    for (const path of paths) {
      if (this.state.readOnly) {
        return;
      }
      await this.opening.get(path); // a tab still loading (perhaps read while read-only)
      const doc = this.doc(path);
      if (!doc) {
        await this.open(path, !this.state.active);
      } else if (doc.loaded) {
        const draft = await readDraft(this.workspace, path);
        if (draft) {
          await this.applyDraft(path, draft);
          void this.validate(path);
        }
      }
    }
  }

  /** Closes a tab; `discard` also forgets its draft (the user chose to drop the edits). */
  close(path: string, discard = false): void {
    const index = this.state.tabs.findIndex((tab) => tab.path === path);
    if (index < 0) {
      return;
    }
    this.clearTimers(path);
    if (discard) {
      void deleteDraft(this.workspace, path);
    }
    const tabs = this.state.tabs.filter((tab) => tab.path !== path);
    const active =
      this.state.active === path ? (tabs[Math.min(index, tabs.length - 1)]?.path ?? null) : this.state.active;
    this.set({ tabs, active });
  }

  edit(path: string, content: string): void {
    const doc = this.doc(path);
    if (!doc || !doc.loaded || this.state.readOnly || doc.content === content) {
      return;
    }
    this.update(path, () => ({ content }));
    this.scheduleValidate(path);
    this.scheduleDraft(path);
  }

  private scheduleValidate(path: string, delay = this.validateDelay) {
    clearTimeout(this.validateTimers.get(path));
    this.validateTimers.set(
      path,
      setTimeout(() => {
        this.validateTimers.delete(path);
        void this.validate(path);
      }, delay),
    );
  }

  /** Validates the tab's current text; an answer older than the latest request is ignored. */
  async validate(path: string): Promise<void> {
    const doc = this.doc(path);
    if (!doc || !doc.loaded) {
      return;
    }
    const content = doc.content;
    if (!isValidatable(path)) {
      this.update(path, () => ({ validated: content }));
      await this.afterValidation(path);
      return;
    }
    const seq = (this.validationSeq.get(path) ?? 0) + 1;
    this.validationSeq.set(path, seq);
    let problems: Problem[] | null = null;
    try {
      problems = (await api.post<{ problems: Problem[] }>('/api/validate', { path, content })).problems;
    } catch {
      problems = null;
    }
    if (this.validationSeq.get(path) !== seq || !this.doc(path)) {
      return;
    }
    if (!problems) {
      // the check did not answer: whether this text holds a plain password is unknown, so it is
      // not kept as a draft until a check says it is clean; try again shortly
      if (this.doc(path)?.content === content) {
        this.scheduleValidate(path, this.retryDelay);
      }
      return;
    }
    const plainSecret = problems.some((problem) => problem.key === PLAIN_SECRET);
    this.update(path, () => ({ problems, plainSecret, validated: content }));
    await this.afterValidation(path);
  }

  private async afterValidation(path: string) {
    const doc = this.doc(path);
    if (!doc) {
      return;
    }
    if (doc.plainSecret) {
      clearTimeout(this.draftTimers.get(path));
      this.draftTimers.delete(path);
      this.draftWanted.delete(path);
      await deleteDraft(this.workspace, path);
    } else if (this.draftWanted.has(path)) {
      await this.writeDraftNow(path);
    }
  }

  private scheduleDraft(path: string) {
    clearTimeout(this.draftTimers.get(path));
    this.draftTimers.set(
      path,
      setTimeout(() => {
        this.draftTimers.delete(path);
        void this.writeDraftNow(path);
      }, this.draftDelay),
    );
  }

  /** Keeps the tab's edits as a draft — once validated, and not when they hold a plain-text password. */
  private async writeDraftNow(path: string): Promise<void> {
    const doc = this.doc(path);
    if (!doc || !doc.loaded || this.state.readOnly) {
      return;
    }
    if (!isDirty(doc)) {
      this.draftWanted.delete(path);
      await deleteDraft(this.workspace, path);
      return;
    }
    if (doc.validated !== doc.content) {
      this.draftWanted.add(path); // written when the validation of this text answers
      return;
    }
    this.draftWanted.delete(path);
    if (doc.plainSecret) {
      await deleteDraft(this.workspace, path);
      return;
    }
    await writeDraft(this.workspace, path, { content: doc.content, baseVersion: doc.version, savedAt: Date.now() });
  }

  /**
   * Keeps every tab's edits as drafts now (the page is going away, or another tab takes over). All
   * writes start before this returns — `pagehide` gives no time to await — and the promise says
   * when they are done. A tab's newest text that a check found clean is kept: its current text when
   * that was checked, else the last checked one (text not yet checked may hold a plain password).
   * Read-only tabs have nothing to keep.
   */
  flushNow(): Promise<void> {
    if (this.state.readOnly) {
      return Promise.resolve();
    }
    const writes: Promise<void>[] = [];
    for (const doc of this.state.tabs) {
      const pending = this.draftTimers.has(doc.path) || this.draftWanted.has(doc.path);
      clearTimeout(this.draftTimers.get(doc.path));
      this.draftTimers.delete(doc.path);
      this.draftWanted.delete(doc.path);
      if (!doc.loaded || !pending) {
        continue;
      }
      if (!isDirty(doc)) {
        writes.push(deleteDraft(this.workspace, doc.path));
        continue;
      }
      const safe = isValidatable(doc.path) ? doc.validated : doc.content;
      if (doc.plainSecret && isValidatable(doc.path)) {
        writes.push(deleteDraft(this.workspace, doc.path));
      } else if (safe !== null && safe !== doc.diskContent) {
        writes.push(writeDraft(this.workspace, doc.path, { content: safe, baseVersion: doc.version, savedAt: Date.now() }));
      }
    }
    return Promise.all(writes).then(() => undefined);
  }

  /** Saves `path` (the active tab by default) over the version last read or saved. */
  save(path: string | null = this.state.active): Promise<void> {
    if (!path) {
      return Promise.resolve();
    }
    const running = this.saves.get(path);
    if (running) {
      return running.then(() => this.save(path));
    }
    const doc = this.doc(path);
    if (!doc || !doc.loaded) {
      return Promise.resolve();
    }
    if (this.state.readOnly) {
      return Promise.reject(new SaveError('readOnly', t('ui.lock.banner')));
    }
    if (doc.conflict) {
      return Promise.reject(new SaveError('conflict', t('ui.disk.title', { name: path })));
    }
    if (!isDirty(doc)) {
      return Promise.resolve();
    }
    const saving = this.put(path, doc.content, doc.version).finally(() => this.saves.delete(path));
    this.saves.set(path, saving);
    return saving;
  }

  private async put(path: string, content: string, version: string): Promise<void> {
    this.update(path, () => ({ saving: true, error: null }));
    try {
      const saved = await api.put<{ version: string }>(filePath(path), { content, version });
      this.update(path, () => ({ saving: false, diskContent: content, version: saved.version, conflict: null }));
      const doc = this.doc(path);
      if (doc && !isDirty(doc)) {
        clearTimeout(this.draftTimers.get(path));
        this.draftTimers.delete(path);
        this.draftWanted.delete(path);
        await deleteDraft(this.workspace, path);
      }
    } catch (e) {
      if (e instanceof ApiProblem && e.status === 409) {
        const disk = await this.readDisk(path, e);
        this.update(path, () => ({ saving: false, conflict: disk }));
        throw new SaveError('conflict', e.message);
      }
      this.update(path, () => ({ saving: false, error: messageOf(e) }));
      throw new SaveError('failed', messageOf(e));
    }
  }

  /**
   * What is on disk now. When it cannot be read (after a 409), the content is unknown (null) and
   * the version is the 409's currentVersion: Keep mine still works, Load and Compare wait for
   * {@link retryDisk}.
   */
  private async readDisk(path: string, conflict?: ApiProblem): Promise<Conflict> {
    try {
      const disk = await api.get<FileContent>(filePath(path));
      return { diskContent: disk.content, diskVersion: disk.version };
    } catch {
      const current = (conflict?.data as { currentVersion?: string } | undefined)?.currentVersion ?? '';
      return { diskContent: null, diskVersion: current };
    }
  }

  /** Reads the disk side of a conflict again (it could not be read before). */
  async retryDisk(path: string): Promise<void> {
    const doc = this.doc(path);
    if (!doc?.conflict) {
      return;
    }
    const current = doc.conflict.diskVersion;
    try {
      const disk = await api.get<FileContent>(filePath(path));
      this.update(path, () => ({ conflict: { diskContent: disk.content, diskVersion: disk.version }, error: null }));
    } catch (e) {
      this.update(path, () => ({ conflict: { diskContent: null, diskVersion: current }, error: messageOf(e) }));
    }
  }

  /** Keep mine (confirmed by the user): overwrites the file on disk over its current version. */
  async keepMine(path: string): Promise<void> {
    const doc = this.doc(path);
    if (!doc?.conflict || this.state.readOnly) {
      return;
    }
    await this.put(path, doc.content, doc.conflict.diskVersion).catch((e) => {
      if (!(e instanceof SaveError)) {
        throw e;
      }
    });
  }

  /** Load from disk: drops the edits (and their draft) for the file as it is on disk. */
  async loadFromDisk(path: string): Promise<void> {
    const doc = this.doc(path);
    if (!doc?.conflict || doc.conflict.diskContent === null) {
      return;
    }
    const { diskContent, diskVersion } = doc.conflict;
    this.clearTimers(path);
    this.update(path, () => ({
      content: diskContent,
      diskContent,
      version: diskVersion,
      conflict: null,
      error: null,
      validated: null,
    }));
    await deleteDraft(this.workspace, path);
    void this.validate(path);
  }

  /** A file event from the server for `path` (created and changed alike; deleted is the tree's). */
  async fileChanged(path: string): Promise<void> {
    await this.saves.get(path)?.catch(() => undefined);
    await this.opening.get(path);
    const doc = this.doc(path);
    if (!doc || !doc.loaded) {
      return;
    }
    let disk: FileContent;
    try {
      disk = await api.get<FileContent>(filePath(path));
    } catch {
      return;
    }
    const now = this.doc(path);
    if (!now || disk.version === now.version) {
      return; // our own save, or nothing new
    }
    if (now.conflict && !this.state.readOnly) {
      this.update(path, () => ({ conflict: { diskContent: disk.content, diskVersion: disk.version } }));
    } else if (disk.content === now.diskContent && !now.conflict) {
      this.update(path, () => ({ version: disk.version }));
    } else if (!isDirty(now) || this.state.readOnly) {
      // no edits here (a read-only tab never has any: they are the holder's drafts): reload quietly
      this.update(path, () => ({
        content: disk.content,
        diskContent: disk.content,
        version: disk.version,
        conflict: null,
        validated: null,
      }));
      void this.validate(path);
    } else {
      this.update(path, () => ({ conflict: { diskContent: disk.content, diskVersion: disk.version } }));
    }
  }

  /** Opens `path` and highlights lines `from`–`to` (1-based, inclusive). */
  async markLines(path: string, from: number, to: number): Promise<void> {
    await this.open(path);
    const first = Math.max(1, Math.min(from, to));
    this.update(path, () => ({ marks: { from: first, to: Math.max(first, from, to) } }));
  }

  /** Moves the cursor of `path`'s tab to a line (a Problems entry was clicked). */
  focus(path: string, line: number, column = 1): void {
    this.activate(path);
    this.nonce += 1;
    const nonce = this.nonce;
    this.update(path, () => ({ focus: { line, column, nonce } }));
  }

  private clearTimers(path: string) {
    clearTimeout(this.validateTimers.get(path));
    clearTimeout(this.draftTimers.get(path));
    this.validateTimers.delete(path);
    this.draftTimers.delete(path);
    this.draftWanted.delete(path);
  }

  /** Stops pending timers (the page is leaving the workspace). Listeners stay, for React's re-mounts. */
  dispose(): void {
    this.validateTimers.forEach((timer) => clearTimeout(timer));
    this.draftTimers.forEach((timer) => clearTimeout(timer));
    this.validateTimers.clear();
    this.draftTimers.clear();
  }
}
