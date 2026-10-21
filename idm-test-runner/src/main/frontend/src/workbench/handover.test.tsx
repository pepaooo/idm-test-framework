import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from './Workbench';
import { readDraft } from './drafts';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';

vi.mock('./monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('./monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

/** IndexedDB made slow on purpose: draft writes and the draft listing take this long (ms). */
const slow = vi.hoisted(() => ({ write: 0, list: 0 }));
vi.mock('./drafts', async (importOriginal) => {
  const real = await importOriginal<typeof import('./drafts')>();
  const later = <T,>(ms: number, run: () => Promise<T>) =>
    ms ? new Promise<T>((resolve) => setTimeout(() => resolve(run()), ms)) : run();
  return {
    ...real,
    writeDraft: (...args: Parameters<typeof real.writeDraft>) => later(slow.write, () => real.writeDraft(...args)),
    listDrafts: (...args: Parameters<typeof real.listDrafts>) => later(slow.list, () => real.listDrafts(...args)),
  };
});

const A = 'scenarios/a.yaml';
const FILES = { [A]: 'name: A\n' };
const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function holderWithFileOpen() {
  const a = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
  await userEvent.click(await within(a.container).findByRole('button', { name: 'a.yaml' }));
  const editor = await within(a.container).findByLabelText(`Edit ${A}`);
  await waitFor(() => expect(editor).not.toHaveAttribute('readonly'));
  return { a, editor };
}

afterEach(() => {
  slow.write = 0;
  slow.list = 0;
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('Hand-over between tabs', () => {
  it('says yielded only after the drafts are committed, and refuses edits once the hand-over starts', async () => {
    // X2's check answers only after the test: X2 stays unchecked through the hand-over
    workbenchServer({
      files: FILES,
      validate: (_path, content) => (content.includes('X2') ? new Promise((resolve) => setTimeout(() => resolve([]), 5000)) : []),
    });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { a, editor: editorA } = await holderWithFileOpen();
    fireEvent.change(editorA, { target: { value: 'name: X1\n' } });
    await pause(450); // X1 checked
    fireEvent.change(editorA, { target: { value: 'name: X2\n' } }); // not checked yet

    // longer than the take-over's wait for the status answer (yieldTimeout, 1 s): the write the
    // status question starts is still running when B asks A to yield, so B sees X1 only because A
    // says `yielded` once its drafts are committed (onYield awaits store.lastFlush)
    slow.write = 1200;
    const b = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await userEvent.click(await within(b.container).findByRole('button', { name: 'Take over' }));
    await waitFor(() => expect(editorA).toHaveAttribute('readonly')); // A has started to yield
    fireEvent.change(editorA, { target: { value: 'name: X3\n' } }); // during the hand-over: refused

    const editorB = await within(b.container).findByLabelText(`Edit ${A}`, undefined, { timeout: 4000 });
    await waitFor(() => expect(editorB).toHaveValue('name: X1\n'), { timeout: 4000 });
    expect((await readDraft(WORKSPACE.path, A))?.content).toBe('name: X1\n');
    // A's unchecked X2 was not lost: it stays in A, read-only and unsaved
    const nowA = within(a.container).getByLabelText(`Edit ${A}`);
    expect(nowA).toHaveValue('name: X2\n');
    expect(nowA).toHaveAttribute('readonly');
  });

  it('a restored draft does not overwrite what the user typed after taking over', async () => {
    workbenchServer({ files: FILES });
    const { editor: editorA } = await holderWithFileOpen();
    fireEvent.change(editorA, { target: { value: 'name: from the other tab\n' } });
    await pause(450); // checked: kept as a draft when A yields

    const b = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await userEvent.click(await within(b.container).findByRole('button', { name: 'a.yaml' }));
    const editorB = await within(b.container).findByLabelText(`Edit ${A}`);
    await waitFor(() => expect(editorB).toHaveValue('name: A\n'));

    slow.list = 400; // restoring the drafts takes a while after B gets the lock
    await userEvent.click(within(b.container).getByRole('button', { name: 'Take over' }));
    await waitFor(() => expect(editorB).not.toHaveAttribute('readonly'));
    fireEvent.change(editorB, { target: { value: 'name: typed in B at once\n' } });

    await pause(700); // the restore has finished
    expect(editorB).toHaveValue('name: typed in B at once\n');
  });
});
