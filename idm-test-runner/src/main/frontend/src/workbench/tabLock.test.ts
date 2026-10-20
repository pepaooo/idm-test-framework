import { afterEach, describe, expect, it } from 'vitest';
import { waitFor } from '@testing-library/react';
import { createTabLock, type TabLock } from './tabLock';

const WS = '/home/ana/lab-tests';
const WAIT = 30;
const open: TabLock[] = [];

function tab(workspace = WS, id?: string, onYield?: () => Promise<void>): TabLock {
  const lock = createTabLock(workspace, { wait: WAIT, id, onYield });
  open.push(lock);
  return lock;
}

afterEach(() => {
  open.splice(0).forEach((lock) => lock.close());
});

describe('tabLock', () => {
  it('the first tab on a workspace holds it; a later tab opens read-only', async () => {
    const first = tab();
    expect(first.state()).toBe('probing');
    await waitFor(() => expect(first.state()).toBe('holder'));

    const second = tab();
    await waitFor(() => expect(second.state()).toBe('readOnly'));
    expect(first.state()).toBe('holder');
  });

  it('a tab on another workspace is not affected', async () => {
    const first = tab();
    await waitFor(() => expect(first.state()).toBe('holder'));
    const other = tab('/home/ana/other');
    await waitFor(() => expect(other.state()).toBe('holder'));
    expect(first.state()).toBe('holder');
  });

  it('take over: the newer tab holds the workspace and the older one becomes read-only', async () => {
    const first = tab();
    await waitFor(() => expect(first.state()).toBe('holder'));
    const second = tab();
    await waitFor(() => expect(second.state()).toBe('readOnly'));

    const changes: string[] = [];
    first.subscribe(() => changes.push(first.state()));
    await second.takeOver();

    expect(second.state()).toBe('holder');
    await waitFor(() => expect(first.state()).toBe('readOnly'));
    expect(changes).toContain('readOnly');
  });

  it('take over waits for the old holder to keep its drafts before it resolves', async () => {
    const order: string[] = [];
    const first = tab(WS, undefined, async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
      order.push('first kept its drafts');
    });
    await waitFor(() => expect(first.state()).toBe('holder'));
    const second = tab();
    await waitFor(() => expect(second.state()).toBe('readOnly'));

    await second.takeOver();
    order.push('take over done');

    expect(order).toEqual(['first kept its drafts', 'take over done']);
  });

  it('when the holder tab closes, a read-only tab takes the workspace', async () => {
    const first = tab();
    await waitFor(() => expect(first.state()).toBe('holder'));
    const second = tab();
    await waitFor(() => expect(second.state()).toBe('readOnly'));

    first.close();

    await waitFor(() => expect(second.state()).toBe('holder'));
  });

  it('releases on pagehide, so a tab opened afterwards holds the workspace', async () => {
    const first = tab();
    await waitFor(() => expect(first.state()).toBe('holder'));
    window.dispatchEvent(new Event('pagehide'));

    const second = tab();
    await waitFor(() => expect(second.state()).toBe('holder'));
  });

  it('two tabs opened together: exactly one holds the workspace', async () => {
    const a = tab(WS, 'a');
    const b = tab(WS, 'b');
    await waitFor(() => expect([a.state(), b.state()].sort()).toEqual(['holder', 'readOnly']));
    await new Promise((resolve) => setTimeout(resolve, WAIT * 3));
    expect([a.state(), b.state()].sort()).toEqual(['holder', 'readOnly']);
  });

  it('without BroadcastChannel, the tab simply holds the workspace', async () => {
    const saved = globalThis.BroadcastChannel;
    // @ts-expect-error simulating an old browser
    delete globalThis.BroadcastChannel;
    try {
      const only = tab();
      expect(only.state()).toBe('holder');
    } finally {
      globalThis.BroadcastChannel = saved;
    }
  });
});
