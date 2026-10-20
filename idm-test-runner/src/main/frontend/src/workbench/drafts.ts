/**
 * Unsaved edits kept in the browser (spec §4.4), so a refresh or a crash does not lose them:
 * IndexedDB database `idmtest`, store `drafts`, key = workspace path + NUL + workspace-relative file
 * path. Only file text the user typed goes here — never secrets.env (it cannot be opened), never a
 * file whose last validation flagged a plain-text password (the caller deletes those), never the
 * session token. When IndexedDB is missing or fails, drafts are simply not kept; nothing throws.
 */
export interface Draft {
  content: string;
  /** The version of the file on disk the edits started from. */
  baseVersion: string;
  /** When the draft was written (ms since the epoch). */
  savedAt: number;
}

const DB = 'idmtest';
const STORE = 'drafts';
const SEPARATOR = '\u0000';

export function draftKey(workspace: string, path: string): string {
  return workspace + SEPARATOR + path;
}

function openDb(): Promise<IDBDatabase | null> {
  return new Promise((resolve) => {
    let request: IDBOpenDBRequest;
    try {
      if (typeof indexedDB === 'undefined') {
        resolve(null);
        return;
      }
      request = indexedDB.open(DB, 1);
    } catch {
      resolve(null);
      return;
    }
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE)) {
        request.result.createObjectStore(STORE);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
    request.onblocked = () => resolve(null);
  });
}

/** Runs `work` in one transaction and resolves with its result once committed; null on any failure. */
async function inStore<T>(mode: IDBTransactionMode, work: (store: IDBObjectStore) => IDBRequest<T>): Promise<T | null> {
  const db = await openDb();
  if (!db) {
    return null;
  }
  try {
    return await new Promise<T | null>((resolve) => {
      let result: T | null = null;
      const tx = db.transaction(STORE, mode);
      const request = work(tx.objectStore(STORE));
      request.onsuccess = () => {
        result = request.result;
      };
      tx.oncomplete = () => resolve(result);
      tx.onerror = () => resolve(null);
      tx.onabort = () => resolve(null);
    });
  } catch {
    return null;
  } finally {
    db.close();
  }
}

function isDraft(value: unknown): value is Draft {
  const draft = value as Draft;
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof draft.content === 'string' &&
    typeof draft.baseVersion === 'string' &&
    typeof draft.savedAt === 'number'
  );
}

export async function readDraft(workspace: string, path: string): Promise<Draft | null> {
  const value = await inStore<unknown>('readonly', (store) => store.get(draftKey(workspace, path)));
  return isDraft(value) ? value : null;
}

export async function writeDraft(workspace: string, path: string, draft: Draft): Promise<void> {
  const value: Draft = { content: draft.content, baseVersion: draft.baseVersion, savedAt: draft.savedAt };
  await inStore('readwrite', (store) => store.put(value, draftKey(workspace, path)));
}

export async function deleteDraft(workspace: string, path: string): Promise<void> {
  await inStore('readwrite', (store) => store.delete(draftKey(workspace, path)));
}

/** The workspace-relative paths that have a draft in `workspace`, sorted. */
export async function listDrafts(workspace: string): Promise<string[]> {
  const prefix = workspace + SEPARATOR;
  let range: IDBKeyRange;
  try {
    range = IDBKeyRange.bound(prefix, prefix + '￿');
  } catch {
    return [];
  }
  const keys = await inStore<IDBValidKey[]>('readonly', (store) => store.getAllKeys(range));
  return (keys ?? [])
    .filter((key): key is string => typeof key === 'string' && key.startsWith(prefix))
    .map((key) => key.substring(prefix.length))
    .sort();
}
