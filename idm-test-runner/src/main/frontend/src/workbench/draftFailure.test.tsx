import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { IDBFactory } from 'fake-indexeddb';
import { Workbench } from './Workbench';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';

vi.mock('./monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('./monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

const A = 'scenarios/a.yaml';
const FILES = { [A]: 'name: A\n' };
const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** An IndexedDB that opens but refuses every transaction (a full quota, a broken profile). */
function failingIndexedDb(): IDBFactory {
  const real = new IDBFactory();
  return new Proxy(real, {
    get(target, name) {
      if (name === 'open') {
        return (...args: Parameters<IDBFactory['open']>) => {
          const request = target.open(...args);
          // before the page's own onsuccess sees the connection
          request.addEventListener('success', () => {
            Object.defineProperty(request.result, 'transaction', {
              value: () => {
                throw new DOMException('the quota is used up (simulated)', 'QuotaExceededError');
              },
            });
          });
          return request;
        };
      }
      const value = Reflect.get(target, name);
      return typeof value === 'function' ? value.bind(target) : value;
    },
  });
}

async function openA() {
  render(<Workbench workspace={WORKSPACE} lockWait={10} />);
  await userEvent.click(await screen.findByRole('button', { name: 'a.yaml' }));
  const editor = await screen.findByLabelText(`Edit ${A}`);
  await waitFor(() => expect(editor).toHaveValue('name: A\n'));
  await waitFor(() => expect(editor).not.toHaveAttribute('readonly'));
  return editor;
}

/** Whether leaving the page now would ask first. */
function leavingAsks(): boolean {
  const event = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

const saved = globalThis.indexedDB;

afterEach(() => {
  globalThis.indexedDB = saved;
  vi.unstubAllGlobals();
});

describe('Leaving the page when drafts cannot be kept', () => {
  it('asks before leaving when a draft write has failed, although the text was checked', async () => {
    globalThis.indexedDB = failingIndexedDb();
    workbenchServer({ files: FILES });
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: only in this tab\n' } });
    await pause(1700); // checked (400 ms), then the draft write (1 s) was tried and failed

    expect(leavingAsks()).toBe(true);
    expect(editor).toHaveValue('name: only in this tab\n');
  });

  it('asks before leaving when there is no IndexedDB at all (a private window)', async () => {
    // @ts-expect-error a browser without IndexedDB
    delete globalThis.indexedDB;
    workbenchServer({ files: FILES });
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: private window\n' } });
    await pause(550); // checked: a working IndexedDB would keep it

    expect(leavingAsks()).toBe(true);
  });

  it('does not ask when the checked text was kept as a draft', async () => {
    workbenchServer({ files: FILES });
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: kept\n' } });
    await pause(1700); // checked, and its draft committed

    expect(leavingAsks()).toBe(false);
  });
});
