import { IDBFactory, IDBKeyRange } from 'fake-indexeddb';

/**
 * Browser APIs jsdom lacks, for the tests: an in-memory BroadcastChannel (peers in one test talk to
 * each other, asynchronously as in a browser, and never across tests), a fake EventSource the test
 * drives, and a fresh fake IndexedDB per test.
 */

type Listener = (event: MessageEvent) => void;

const channels = new Set<FakeBroadcastChannel>();

export class FakeBroadcastChannel {
  readonly name: string;
  onmessage: Listener | null = null;
  private listeners = new Set<Listener>();
  private closed = false;

  constructor(name: string) {
    this.name = name;
    channels.add(this);
  }

  postMessage(data: unknown): void {
    if (this.closed) {
      throw new Error('BroadcastChannel is closed');
    }
    const copy = structuredClone(data);
    for (const peer of channels) {
      if (peer !== this && peer.name === this.name) {
        setTimeout(() => peer.deliver(copy), 0);
      }
    }
  }

  private deliver(data: unknown): void {
    if (this.closed) {
      return;
    }
    const event = new MessageEvent('message', { data });
    this.onmessage?.(event);
    this.listeners.forEach((listener) => listener(event));
  }

  addEventListener(type: string, listener: Listener): void {
    if (type === 'message') {
      this.listeners.add(listener);
    }
  }

  removeEventListener(type: string, listener: Listener): void {
    if (type === 'message') {
      this.listeners.delete(listener);
    }
  }

  close(): void {
    this.closed = true;
    channels.delete(this);
  }
}

const sources = new Set<FakeEventSource>();

export class FakeEventSource {
  readonly url: string;
  private listeners = new Map<string, Set<(event: MessageEvent<string>) => void>>();
  closed = false;
  /** 0 connecting, 1 open, 2 closed (as EventSource). */
  readyState = 1;

  constructor(url: string) {
    this.url = url;
    sources.add(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set());
    }
    this.listeners.get(type)!.add(listener);
  }

  removeEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    this.listeners.get(type)?.delete(listener);
  }

  close(): void {
    this.closed = true;
    this.readyState = 2;
    sources.delete(this);
  }

  /** A connection error; `final` as when the server answered 204 (the browser does not reconnect). */
  fail(final: boolean): void {
    if (final) {
      this.readyState = 2;
    }
    this.listeners.get('error')?.forEach((listener) => listener(new MessageEvent<string>('error')));
  }

  emit(type: string, data: unknown): void {
    const event = new MessageEvent<string>(type, { data: typeof data === 'string' ? data : JSON.stringify(data) });
    this.listeners.get(type)?.forEach((listener) => listener(event));
  }
}

/** Sends `data` as an unnamed SSE event to every open stream at `url`. */
export function emitServerEvent(url: string, data: unknown): void {
  for (const source of sources) {
    if (source.url.split('?')[0] === url) {
      source.emit('message', data);
    }
  }
}

/** Sends a named SSE event to every open stream at `url`. */
export function emitNamedEvent(url: string, name: string, data: unknown): void {
  for (const source of [...sources]) {
    if (source.url.split('?')[0] === url) {
      source.emit(name, data);
    }
  }
}

/** The open streams at `url`. */
export function eventStreams(url: string): FakeEventSource[] {
  return [...sources].filter((source) => source.url.split('?')[0] === url);
}

export function openEventStreams(): string[] {
  return [...sources].map((source) => source.url.split('?')[0]);
}

export function installBrowserFakes(): void {
  globalThis.BroadcastChannel = FakeBroadcastChannel as unknown as typeof BroadcastChannel;
  globalThis.EventSource = FakeEventSource as unknown as typeof EventSource;
  globalThis.indexedDB = new IDBFactory();
  globalThis.IDBKeyRange = IDBKeyRange;
}

export function resetBrowserFakes(): void {
  [...channels].forEach((channel) => channel.close());
  [...sources].forEach((source) => source.close());
  globalThis.indexedDB = new IDBFactory();
}
