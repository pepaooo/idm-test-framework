import { currentLanguage, t } from '../i18n';

/**
 * A failed API call: the server's `{key, message}` (the message already in the page's language),
 * or `ui.network` when the workbench did not answer.
 */
export class ApiProblem extends Error {
  readonly status: number;
  readonly key: string;
  /** The whole error body (e.g. a 409's `currentVersion`). */
  readonly data: unknown;

  constructor(status: number, key: string, message: string, data?: unknown) {
    super(message);
    this.name = 'ApiProblem';
    this.status = status;
    this.key = key;
    this.data = data;
  }
}

/** A server warning or error as JSON. */
export interface ServerMessage {
  key: string;
  message: string;
}

function isServerMessage(value: unknown): value is ServerMessage {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as ServerMessage).key === 'string' &&
    typeof (value as ServerMessage).message === 'string'
  );
}

/** The server's answer when another tab opened another workspace in this workbench. */
export const WORKSPACE_SWITCHED = 'ui.workspace.switched';
export const WORKSPACE_HEADER = 'X-Idmtest-Workspace';

/*
 * The workspace this page shows. The session's workspace is shared by every tab, so each call for
 * the files, the lab, runs and reports says which one it is for; the server refuses it (409
 * ui.workspace.switched) when another tab has opened another one since.
 */
let pageWorkspace: string | null = null;
let switched: string | null = null;
const switchListeners = new Set<() => void>();

/** The workspace the page shows from now on (null: the start page); forgets an earlier switch. */
export function setPageWorkspace(path: string | null): void {
  pageWorkspace = path;
  if (switched !== null) {
    switched = null;
    switchListeners.forEach((listener) => listener());
  }
}

/** Whether another tab opened another workspace: the server's message, or null. For useSyncExternalStore. */
export const workspaceSwitch = {
  subscribe(listener: () => void): () => void {
    switchListeners.add(listener);
    return () => {
      switchListeners.delete(listener);
    };
  },
  get(): string | null {
    return switched;
  },
};

function noteSwitched(message: string) {
  if (switched === null) {
    switched = message;
    switchListeners.forEach((listener) => listener());
  }
}

/** `url` (same-origin, e.g. a report) with `?workspace=` of the page's workspace, for links and streams. */
export function withWorkspace(url: string): string {
  if (pageWorkspace === null) {
    return url;
  }
  return `${url}${url.includes('?') ? '&' : '?'}workspace=${encodeURIComponent(pageWorkspace)}`;
}

/** Per-call options. `workspace`: the workspace the call is for, instead of the page's. */
export interface RequestOptions {
  workspace?: string;
}

/**
 * One same-origin call. The session cookie (HttpOnly, set by the server when the page was first
 * loaded with its token) goes with it; the page itself never sees or stores the token.
 */
async function request<T>(method: string, path: string, body?: unknown, options?: RequestOptions): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'Accept-Language': currentLanguage(),
  };
  const workspace = options?.workspace ?? pageWorkspace;
  if (workspace !== null) {
    headers[WORKSPACE_HEADER] = encodeURIComponent(workspace);
  }
  const multipart = typeof FormData !== 'undefined' && body instanceof FormData;
  if (body !== undefined && !multipart) {
    headers['Content-Type'] = 'application/json';
  }
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers,
      body: body === undefined ? undefined : multipart ? (body as FormData) : JSON.stringify(body),
    });
  } catch {
    throw new ApiProblem(0, 'ui.network', t('ui.error.network'));
  }
  const text = response.status === 204 ? '' : await response.text();
  let data: unknown;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    data = undefined;
  }
  if (!response.ok) {
    if (isServerMessage(data)) {
      // A call for another workspace than the page's (options.workspace) says nothing about this page.
      if (response.status === 409 && data.key === WORKSPACE_SWITCHED && options?.workspace === undefined) {
        noteSwitched(data.message);
      }
      throw new ApiProblem(response.status, data.key, data.message, data);
    }
    throw new ApiProblem(response.status, 'ui.unexpected', t('ui.error.status', { status: response.status }));
  }
  return data as T;
}

/** `path` with its query parameters, each encoded. */
export function withQuery(path: string, params: Record<string, string | undefined>): string {
  const query = Object.entries(params)
    .filter((entry): entry is [string, string] => entry[1] !== undefined)
    .map(([name, value]) => `${encodeURIComponent(name)}=${encodeURIComponent(value)}`)
    .join('&');
  return query ? `${path}?${query}` : path;
}

/**
 * Server-Sent Events from `path`: `onEvent(name, data)` for each event of `names` (and unnamed
 * ones as "message"), with `data` parsed as JSON when it is JSON. The browser reconnects by itself
 * (sending Last-Event-ID); `onError(closed)` hears each connection error, `closed` true when the
 * browser gave up (e.g. the server answered 204: nothing to send, or 409: another workspace is open).
 * The stream is for the page's workspace (`?workspace=`). Returns the function that closes the stream.
 */
function events(
  path: string,
  onEvent: (name: string, data: unknown) => void,
  names: readonly string[] = [],
  onError?: (closed: boolean) => void,
): () => void {
  const source = new EventSource(withWorkspace(path));
  if (onError) {
    source.addEventListener('error', () => onError(source.readyState === 2));
  }
  const handle = (name: string) => (event: MessageEvent<string>) => {
    let data: unknown = event.data;
    try {
      data = JSON.parse(event.data);
    } catch {
      // plain text
    }
    onEvent(name, data);
  };
  for (const name of ['message', ...names]) {
    source.addEventListener(name, handle(name) as EventListener);
  }
  return () => source.close();
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  /** `options.workspace`: send the call for that workspace (e.g. stopping a run from the start page). */
  post: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('POST', path, body ?? {}, options),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  del: <T = void>(path: string) => request<T>('DELETE', path),
  /** A multipart POST (the browser sets the boundary). */
  upload: <T>(path: string, form: FormData) => request<T>('POST', path, form),
  events,
};

/** What `GET /api/health` answers. */
export interface Health {
  version: string;
  git: boolean;
  locale: string;
  /** The workspace given with `idmtest ui --workspace`, or the one open now; null for the start page. */
  workspace: string | null;
}

export interface LastRun {
  runId: string;
  at: string;
  profile: string | null;
  passed: number;
  findings: number;
  setupProblems: number;
  /** The scenarios a stop interrupted; not in `setupProblems`. */
  stopped: number;
}

export interface RecentWorkspace {
  name: string;
  path: string;
  exists: boolean;
  lastRun: LastRun | null;
}

export interface FolderListing {
  path: string;
  parent: string | null;
  entries: { name: string; path: string; isWorkspace: boolean }[];
}

/** A workspace the page opened. */
export interface OpenWorkspace {
  path: string;
  name: string;
  profiles: string[];
}
