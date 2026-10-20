/**
 * One editing tab per workspace (spec §4.4: a second tab "opens read-only until the user takes
 * over"), over the BroadcastChannel `idmtest-workspace`, every message tagged with the workspace
 * path so tabs on other workspaces ignore it.
 *
 * - A new tab says `hello` and is `probing` (read-only, no banner yet). The holder answers `held`,
 *   which makes the newcomer `readOnly`. Hearing no holder within `wait` (~300 ms) the tab takes the
 *   lock; tabs probing at the same time tie-break on their ids (the smallest wins).
 * - `takeOver()` asks the holder to yield. The holder answers `yielding` at once and, from then on,
 *   never claims the lock again (no `held` to newcomers or rivals); it keeps its drafts (`onYield`),
 *   turns read-only and says `yielded`. The taker holds the lock only after `yielded` — so it reads
 *   the drafts after they are written — waiting up to `yieldWait` once the holder has answered, or
 *   `yieldTimeout` when nobody answers (the holder is gone).
 * - The holder turns read-only (its listeners keep their edits then) and says `released` on
 *   `pagehide` and on `close()`; read-only tabs then probe again, so one of them takes the lock.
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
  /** How long to wait for any answer to a take-over before assuming the holder is gone (ms). */
  yieldTimeout?: number;
  /** How long to wait for `yielded` once the holder has answered `yielding` (ms). */
  yieldWait?: number;
  id?: string;
  /** Runs in the holder before it yields to a tab taking over (e.g. writes pending drafts). */
  onYield?: () => Promise<void>;
}

type Kind = 'hello' | 'held' | 'takeover' | 'yielding' | 'yielded' | 'released';

interface LockMessage {
  ws: string;
  from: string;
  type: Kind;
  to?: string;
  /** With `held`: when the sender took the lock (the longer-standing holder wins a split). */
  since?: number;
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
  const yieldWait = options.yieldWait ?? 30000;
  const id = options.id ?? randomId();
  const listeners = new Set<() => void>();
  let current: LockState = 'probing';
  let channel: BroadcastChannel | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let rivals = new Set<string>();
  let yielded: (() => void) | null = null;
  let answered: (() => void) | null = null;
  let yielding = false;
  let taking = false;
  let since = 0;
  let closed = false;

  const set = (next: LockState) => {
    if (next === 'holder' && current !== 'holder') {
      since = Date.now();
    }
    if (next !== current) {
      current = next;
      listeners.forEach((listener) => listener());
    }
  };

  const post = (type: Kind, to?: string) => {
    try {
      channel?.postMessage({ ws: workspace, from: id, type, to, since: type === 'held' ? since : undefined } satisfies LockMessage);
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
        if ((current === 'holder' && !yielding) || taking) {
          post('held'); // a tab taking over answers too, so a newcomer does not claim meanwhile
        } else if (current === 'probing') {
          rivals.add(message.from);
        }
        break;
      case 'held':
        if (yielding || taking) {
          break; // a hand-over is under way: the taker claims the lock when it ends
        }
        if (current === 'holder') {
          // two holders (a newcomer decided before the answer arrived): the one holding longer keeps it
          const theirs = message.since ?? 0;
          if (theirs < since || (theirs === since && message.from < id)) {
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
        if (yielding) {
          break;
        }
        if (current === 'holder') {
          yielding = true;
          post('yielding', message.from); // the taker now waits for `yielded`, however long this takes
          try {
            await options.onYield?.(); // still the holder: its drafts can be written
          } catch {
            // yield anyway: the other tab is taking over
          }
          yielding = false;
          set('readOnly');
          post('yielded', message.from);
        } else {
          set('readOnly');
        }
        break;
      case 'yielding':
        if (message.to === id) {
          answered?.();
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
    if (current === 'holder' && !yielding) {
      set('probing'); // first: the listeners keep their edits while this is still the page's turn
      post('released');
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
        let fallback: ReturnType<typeof setTimeout>;
        const done = () => {
          clearTimeout(fallback);
          yielded = null;
          answered = null;
          taking = false;
          if (!closed) {
            set('holder');
            post('held');
          }
          resolve();
        };
        // nobody answers within yieldTimeout: the holder is gone; once it answers, wait for it
        fallback = setTimeout(done, yieldTimeout);
        answered = () => {
          clearTimeout(fallback);
          fallback = setTimeout(done, yieldWait);
        };
        yielded = done;
        taking = true;
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
