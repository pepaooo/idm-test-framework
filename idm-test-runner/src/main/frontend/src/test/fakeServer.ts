import { vi } from 'vitest';

/** One request the page sent to the (fake) workbench server. */
export interface SentRequest {
  method: string;
  url: string;
  body: unknown;
  headers: Record<string, string>;
  credentials: RequestCredentials | undefined;
}

/** What the fake server answers: a status and a JSON body (none for 204). */
export interface Answer {
  status?: number;
  body?: unknown;
}

type Route = (request: SentRequest) => Answer | undefined | Promise<Answer | undefined>;

/**
 * Stands in for the workbench's HTTP API (the other side of `fetch`): routes are matched by
 * "METHOD /path" (the query string is part of `url` in the request, not of the key). Unknown
 * routes answer 404 like the server does.
 */
export function fakeServer(routes: Record<string, Answer | Route>) {
  const sent: SentRequest[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const method = (init?.method ?? 'GET').toUpperCase();
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((value, name) => {
      headers[name] = value;
    });
    const request: SentRequest = {
      method,
      url,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body instanceof FormData ? init.body : undefined,
      headers,
      credentials: init?.credentials,
    };
    sent.push(request);
    const route = routes[`${method} ${url.split('?')[0]}`];
    const answer: Answer =
      (typeof route === 'function' ? await route(request) : route) ?? {
        status: 404,
        body: { key: 'ui.request.notFound', message: 'Not found' },
      };
    const status = answer.status ?? 200;
    return new Response(status === 204 ? null : JSON.stringify(answer.body ?? null), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetchMock);
  return {
    sent,
    /** The requests sent as "METHOD url". */
    calls: () => sent.map((r) => `${r.method} ${r.url}`),
  };
}
