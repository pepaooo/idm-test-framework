import { currentLanguage, t } from '../i18n';

/**
 * A failed API call: the server's `{key, message}` (the message already in the page's language),
 * or `ui.network` when the workbench did not answer.
 */
export class ApiProblem extends Error {
  readonly status: number;
  readonly key: string;

  constructor(status: number, key: string, message: string) {
    super(message);
    this.name = 'ApiProblem';
    this.status = status;
    this.key = key;
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

/**
 * One same-origin call. The session cookie (HttpOnly, set by the server when the page was first
 * loaded with its token) goes with it; the page itself never sees or stores the token.
 */
async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'Accept-Language': currentLanguage(),
  };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
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
      throw new ApiProblem(response.status, data.key, data.message);
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
 * (sending Last-Event-ID). Returns the function that closes the stream.
 */
function events(path: string, onEvent: (name: string, data: unknown) => void, names: readonly string[] = []): () => void {
  const source = new EventSource(path);
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
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  del: <T = void>(path: string) => request<T>('DELETE', path),
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
