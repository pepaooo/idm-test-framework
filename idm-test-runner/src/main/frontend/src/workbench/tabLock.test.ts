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

  it('a slow yield (longer than the answer timeout) is waited for: one holder, drafts written first', async () => {
    const order: string[] = [];
    const first = createTabLock(WS, {
      wait: WAIT,
      yieldTimeout: 40,
      onYield: async () => {
        await new Promise((resolve) => setTimeout(resolve, 150));
        order.push('first kept its drafts');
      },
    });
    open.push(first);
    await waitFor(() => expect(first.state()).toBe('holder'));
    const second = createTabLock(WS, { wait: WAIT, yieldTimeout: 40 });
    open.push(second);
    await waitFor(() => expect(second.state()).toBe('readOnly'));

    const seen: string[] = [];
    const sample = () => seen.push(`${first.state()}/${second.state()}`);
    first.subscribe(sample);
    second.subscribe(sample);
    const taking = second.takeOver().then(() => order.push('take over done'));

    // a third tab opening during the hand-over is not handed the lock by anyone
    await new Promise((resolve) => setTimeout(resolve, 60));
    const third = tab();
    await taking;
    expect(order).toEqual(['first kept its drafts', 'take over done']);
    expect(second.state()).toBe('holder');
    await waitFor(() => expect(third.state()).toBe('readOnly'));
    expect(first.state()).toBe('readOnly');
    expect(seen).not.toContain('holder/holder');
    await new Promise((resolve) => setTimeout(resolve, WAIT * 3));
    expect([first.state(), second.state(), third.state()].filter((s) => s === 'holder')).toHaveLength(1);
  });

  it('a newcomer that claimed before the holder answered gives the lock back to the established holder', async () => {
    const first = tab(WS, 'z-established'); // a larger id: the id tie-break alone would pick the newcomer
    await waitFor(() => expect(first.state()).toBe('holder'));
    const changes: string[] = [];
    first.subscribe(() => changes.push(first.state()));
    const hasty = createTabLock(WS, { wait: 0, id: 'a-newcomer' });
    open.push(hasty);
    await waitFor(() => expect(hasty.state()).toBe('readOnly'));
    await new Promise((resolve) => setTimeout(resolve, WAIT * 2));
    expect(first.state()).toBe('holder');
    expect(changes).not.toContain('readOnly');
  });

  it('turns read-only before it says released on pagehide, so its listeners keep edits while it holds', async () => {
    const first = tab();
    await waitFor(() => expect(first.state()).toBe('holder'));
    const states: string[] = [];
    first.subscribe(() => states.push(first.state()));
    window.dispatchEvent(new Event('pagehide'));
    expect(states).toEqual(['probing']); // synchronously, inside pagehide
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
