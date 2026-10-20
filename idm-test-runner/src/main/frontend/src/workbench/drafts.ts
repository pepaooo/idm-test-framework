/**
 * Unsaved edits kept in the browser (spec §4.4), so a refresh or a crash does not lose them:
 * IndexedDB database `idmtest`, store `drafts`, key = workspace path + NUL + workspace-relative file
 * path. Only file text the user typed goes here — never secrets.env (it cannot be opened), never a
 * file whose last validation flagged a plain-text password (the caller deletes those), never the
 * session token. When IndexedDB is missing or fails, drafts are simply not kept; nothing throws.
 * writeDraft/deleteDraft issue their request synchronously when the connection is open (see
 * openDrafts), which is what lets the page keep drafts inside `pagehide`.
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

/*
 * One connection, kept open: opened by openDrafts() when a workspace opens, so that a write asked
 * for inside `pagehide` issues its put synchronously (opening a connection there may never finish
 * before the page is gone). Reopened when it closes or when the IndexedDB factory changes (tests).
 */
let connection: IDBDatabase | null = null;
let connectionFactory: IDBFactory | null = null;
let connecting: Promise<IDBDatabase | null> | null = null;

function currentFactory(): IDBFactory | null {
  try {
    return typeof indexedDB === 'undefined' ? null : indexedDB;
  } catch {
    return null;
  }
}

function forget(db: IDBDatabase) {
  if (connection === db) {
    connection = null;
    connecting = null;
  }
}

/** The open connection if there is one right now (no waiting). */
function openNow(): IDBDatabase | null {
  if (connection && connectionFactory === currentFactory()) {
    return connection;
  }
  return null;
}

function connect(): Promise<IDBDatabase | null> {
  const factory = currentFactory();
  if (connection && connectionFactory === factory) {
    return Promise.resolve(connection);
  }
  if (connection) {
    connection.close();
    connection = null;
    connecting = null;
  }
  if (!connecting || connectionFactory !== factory) {
    connectionFactory = factory;
    connecting = openDb().then((db) => {
      if (db && connectionFactory === factory) {
        connection = db;
        db.onclose = () => forget(db);
        db.onversionchange = () => {
          db.close();
          forget(db);
        };
      }
      if (!db) {
        connecting = null;
      }
      return db;
    });
  }
  return connecting;
}

/** Opens the drafts connection ahead of time (the workbench calls this when a workspace opens). */
export function openDrafts(): Promise<void> {
  return connect().then(() => undefined);
}

function run<T>(db: IDBDatabase, mode: IDBTransactionMode, work: (store: IDBObjectStore) => IDBRequest<T>): Promise<T | null> {
  try {
    const tx = db.transaction(STORE, mode);
    const request = work(tx.objectStore(STORE));
    return new Promise<T | null>((resolve) => {
      let result: T | null = null;
      request.onsuccess = () => {
        result = request.result;
      };
      tx.oncomplete = () => resolve(result);
      tx.onerror = () => resolve(null);
      tx.onabort = () => resolve(null);
    });
  } catch {
    forget(db);
    return Promise.resolve(null);
  }
}

/**
 * Runs `work` in one transaction and resolves with its result once committed; null on any failure.
 * With the connection already open the request is issued before this returns.
 */
function inStore<T>(mode: IDBTransactionMode, work: (store: IDBObjectStore) => IDBRequest<T>): Promise<T | null> {
  const db = openNow();
  if (db) {
    return run(db, mode, work);
  }
  return connect().then((opened) => (opened ? run(opened, mode, work) : null));
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
