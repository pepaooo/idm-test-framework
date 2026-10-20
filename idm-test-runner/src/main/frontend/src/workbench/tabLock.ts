/**
 * One editing tab per workspace (spec §4.4: a second tab "opens read-only until the user takes
 * over"), over the BroadcastChannel `idmtest-workspace`, every message tagged with the workspace
 * path so tabs on other workspaces ignore it.
 *
 * - A new tab says `hello` and is `probing` (read-only, no banner yet). The holder answers `held`,
 *   which makes the newcomer `readOnly`. Hearing no holder within `wait` (~300 ms) the tab takes the
 *   lock; tabs probing at the same time tie-break on their ids (the smallest wins).
 * - `takeOver()` asks the holder to yield: it keeps its drafts (`onYield`), says `yielded` and turns
 *   read-only; the taker then holds the lock (also when no answer comes within `yieldTimeout`).
 * - The holder says `released` on `pagehide` and on `close()`; read-only tabs then probe again, so
 *   one of them takes the lock.
 * Without BroadcastChannel the tab simply holds the lock.
 */
export type LockState = 'probing' | 'holder' | 'readOnly';

export interface TabLock {
  state(): LockState;
  subscribe(listener: () => void): () => void;
  takeOver(): Promise<void>;
  close(): void;
}

export interface TabLockOptions {
  wait?: number;
  yieldTimeout?: number;
  id?: string;
  /** Runs in the holder before it yields to a tab taking over (e.g. writes pending drafts). */
  onYield?: () => Promise<void>;
}

type Kind = 'hello' | 'held' | 'takeover' | 'yielded' | 'released';

interface LockMessage {
  ws: string;
  from: string;
  type: Kind;
  to?: string;
}

export const CHANNEL = 'idmtest-workspace';

function randomId(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  }
}

export function createTabLock(workspace: string, options: TabLockOptions = {}): TabLock {
  const wait = options.wait ?? 300;
  const yieldTimeout = options.yieldTimeout ?? 1000;
  const id = options.id ?? randomId();
  const listeners = new Set<() => void>();
  let current: LockState = 'probing';
  let channel: BroadcastChannel | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let rivals = new Set<string>();
  let yielded: (() => void) | null = null;
  let closed = false;

  const set = (next: LockState) => {
    if (next !== current) {
      current = next;
      listeners.forEach((listener) => listener());
    }
  };

  const post = (type: Kind, to?: string) => {
    try {
      channel?.postMessage({ ws: workspace, from: id, type, to } satisfies LockMessage);
    } catch {
      // closed channel: nothing to tell
    }
  };

  const stopTimer = () => {
    if (timer !== undefined) {
      clearTimeout(timer);
      timer = undefined;
    }
  };

  const probe = () => {
    stopTimer();
    set('probing');
    rivals = new Set();
    post('hello');
    timer = setTimeout(decide, wait);
  };

  const decide = () => {
    timer = undefined;
    if (current !== 'probing') {
      return;
    }
    if ([...rivals].every((rival) => rival > id)) {
      set('holder');
      post('held');
    } else {
      // a rival with a smaller id should claim it; if it does not (it closed), probe again
      timer = setTimeout(probe, wait);
    }
  };

  const onMessage = async (event: MessageEvent) => {
    const message = event.data as LockMessage;
    if (closed || !message || message.ws !== workspace || message.from === id) {
      return;
    }
    switch (message.type) {
      case 'hello':
        if (current === 'holder') {
          post('held');
        } else if (current === 'probing') {
          rivals.add(message.from);
        }
        break;
      case 'held':
        if (current === 'holder') {
          if (message.from < id) {
            set('readOnly');
          } else {
            post('held');
          }
        } else {
          stopTimer();
          set('readOnly');
        }
        break;
      case 'takeover':
        stopTimer();
        if (current === 'holder') {
          try {
            await options.onYield?.(); // still the holder: its drafts can be written
          } catch {
            // yield anyway: the other tab is taking over
          }
          set('readOnly');
          post('yielded', message.from);
        } else {
          set('readOnly');
        }
        break;
      case 'yielded':
        if (message.to === id) {
          yielded?.();
        }
        break;
      case 'released':
        if (current === 'readOnly') {
          probe();
        }
        break;
    }
  };

  const onPageHide = () => {
    if (current === 'holder') {
      post('released');
      set('probing');
    }
  };

  const onPageShow = (event: PageTransitionEvent) => {
    if (event.persisted && current !== 'holder') {
      probe();
    }
  };

  if (typeof BroadcastChannel === 'undefined') {
    current = 'holder';
  } else {
    try {
      channel = new BroadcastChannel(CHANNEL);
      channel.addEventListener('message', onMessage);
    } catch {
      channel = null;
    }
    if (channel) {
      window.addEventListener('pagehide', onPageHide);
      window.addEventListener('pageshow', onPageShow);
      probe();
    } else {
      current = 'holder';
    }
  }

  return {
    state: () => current,
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    takeOver() {
      if (current === 'holder' || !channel) {
        return Promise.resolve();
      }
      stopTimer();
      return new Promise<void>((resolve) => {
        const done = () => {
          clearTimeout(fallback);
          yielded = null;
          if (!closed) {
            set('holder');
            post('held');
          }
          resolve();
        };
        const fallback = setTimeout(done, yieldTimeout);
        yielded = done;
        post('takeover');
      });
    },
    close() {
      if (closed) {
        return;
      }
      stopTimer();
      if (current === 'holder') {
        post('released');
      }
      closed = true;
      window.removeEventListener('pagehide', onPageHide);
      window.removeEventListener('pageshow', onPageShow);
      channel?.removeEventListener('message', onMessage);
      channel?.close();
      channel = null;
      listeners.clear();
    },
  };
}
