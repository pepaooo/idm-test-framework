import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from './Workbench';
import { useWorkbench } from './context';
import { readDraft, writeDraft } from './drafts';
import { emitServerEvent, openEventStreams } from '../test/browserFakes';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';

vi.mock('./monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('./monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

const A = 'scenarios/a.yaml';
const EVENTS = '/api/files/events';

async function openA() {
  await userEvent.click(await screen.findByRole('button', { name: 'a.yaml' }));
  const editor = await screen.findByLabelText(`Edit ${A}`);
  await waitFor(() => expect(editor).toHaveValue('name: A\n'));
  await waitFor(() => expect(editor).not.toHaveAttribute('readonly'));
  return editor;
}

/** Edits A, then another program changes it on disk, then the user presses Save. */
async function conflictOnSave() {
  const server = workbenchServer({ files: { [A]: 'name: A\n' } });
  render(<Workbench workspace={WORKSPACE} lockWait={10} />);
  const editor = await openA();
  fireEvent.change(editor, { target: { value: 'name: mine\n' } });
  server.changeOnDisk(A, 'name: theirs\n');
  await userEvent.keyboard('{Control>}s{/Control}');
  const dialog = await screen.findByRole('dialog', { name: 'a.yaml changed on disk' });
  return { server, editor, dialog };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Changed on disk', () => {
  it('a save over a changed file (409) never overwrites it: the choice opens', async () => {
    const { server, dialog } = await conflictOnSave();
    expect(server.puts()).toHaveLength(1);
    expect(server.puts()[0].body).toEqual({ content: 'name: mine\n', version: 'v1' });
    expect(server.disk.get(A)!.content).toBe('name: theirs\n');
    expect(within(dialog).getByRole('button', { name: 'Keep mine' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Load from disk' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Compare' })).toBeInTheDocument();
  });

  it('Load from disk drops my edits and their draft', async () => {
    const { editor, dialog } = await conflictOnSave();
    await writeDraft(WORKSPACE.path, A, { content: 'name: mine\n', baseVersion: 'v1', savedAt: 1 });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Load from disk' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(editor).toHaveValue('name: theirs\n'));
    expect(screen.queryAllByRole('img', { name: 'unsaved' })).toHaveLength(0);
    await waitFor(async () => expect(await readDraft(WORKSPACE.path, A)).toBeNull());
  });

  it("Keep mine asks first, then overwrites over the server's current version", async () => {
    const { server, dialog } = await conflictOnSave();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Keep mine' }));
    expect(server.puts()).toHaveLength(1);
    expect(within(dialog).getByText(/Overwrite the file on disk with your version\?/)).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Back' }));
    expect(server.puts()).toHaveLength(1);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Keep mine' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Overwrite' }));

    await waitFor(() => expect(server.puts()).toHaveLength(2));
    expect(server.puts()[1].body).toEqual({ content: 'name: mine\n', version: 'v2' });
    expect(server.disk.get(A)!.content).toBe('name: mine\n');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryAllByRole('img', { name: 'unsaved' })).toHaveLength(0);
  });

  it('Compare shows mine against the file on disk', async () => {
    const { dialog } = await conflictOnSave();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Compare' }));
    expect(await within(dialog).findByLabelText('disk side')).toHaveTextContent('name: theirs');
    expect(within(dialog).getByLabelText('mine side')).toHaveTextContent('name: mine');
  });

  it('saveActive rejects while the file changed on disk, so a run does not start on a stale file', async () => {
    const server = workbenchServer({ files: { [A]: 'name: A\n' } });
    let api: ReturnType<typeof useWorkbench> | null = null;
    function Probe() {
      api = useWorkbench();
      return null;
    }
    render(<Workbench workspace={WORKSPACE} lockWait={10} runPanel={<Probe />} />);
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: mine\n' } });
    server.changeOnDisk(A, 'name: theirs\n');
    await act(async () => {
      await expect(api!.saveActive()).rejects.toThrow();
    });
    expect(await screen.findByRole('dialog', { name: 'a.yaml changed on disk' })).toBeInTheDocument();
  });

  it('a changed event for an open file with unsaved edits opens the choice', async () => {
    const server = workbenchServer({ files: { [A]: 'name: A\n' } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: mine\n' } });
    expect(openEventStreams()).toContain(EVENTS);

    server.changeOnDisk(A, 'name: theirs\n');
    act(() => emitServerEvent(EVENTS, { type: 'changed', path: A }));

    const dialog = await screen.findByRole('dialog', { name: 'a.yaml changed on disk' });
    expect(editor).toHaveValue('name: mine\n');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Compare' }));
    expect(await within(dialog).findByLabelText('disk side')).toHaveTextContent('name: theirs');
  });

  it('a changed (or created) event for an open file without edits reloads it silently', async () => {
    const server = workbenchServer({ files: { [A]: 'name: A\n' } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    const editor = await openA();

    server.changeOnDisk(A, 'name: pulled\n');
    act(() => emitServerEvent(EVENTS, { type: 'changed', path: A }));
    await waitFor(() => expect(editor).toHaveValue('name: pulled\n'));

    server.changeOnDisk(A, 'name: recreated\n');
    act(() => emitServerEvent(EVENTS, { type: 'created', path: A }));
    await waitFor(() => expect(editor).toHaveValue('name: recreated\n'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    // the next save goes over the reloaded version
    fireEvent.change(editor, { target: { value: 'name: next\n' } });
    await userEvent.keyboard('{Control>}s{/Control}');
    await waitFor(() => expect(server.puts()).toHaveLength(1));
    expect(server.puts()[0].body).toEqual({ content: 'name: next\n', version: 'v3' });
  });

  it("the event of the workbench's own save changes nothing", async () => {
    const server = workbenchServer({ files: { [A]: 'name: A\n' } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: saved\n' } });
    await userEvent.keyboard('{Control>}s{/Control}');
    await waitFor(() => expect(server.puts()).toHaveLength(1));
    fireEvent.change(editor, { target: { value: 'name: saved and more\n' } });

    act(() => emitServerEvent(EVENTS, { type: 'changed', path: A }));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(editor).toHaveValue('name: saved and more\n');
  });

  it('when the file cannot be read back after a 409, Load and Compare wait for a retry (never an empty file)', async () => {
    const server = workbenchServer({ files: { [A]: 'name: A\n' } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: mine\n' } });
    server.changeOnDisk(A, 'name: theirs\n');
    server.failNextReads(1);
    await userEvent.keyboard('{Control>}s{/Control}');

    const dialog = await screen.findByRole('dialog', { name: 'a.yaml changed on disk' });
    expect(within(dialog).getByText(/could not be read/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Load from disk' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: 'Compare' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: 'Keep mine' })).toBeEnabled();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Load from disk' })).toBeEnabled());
    await userEvent.click(within(dialog).getByRole('button', { name: 'Compare' }));
    expect(await within(dialog).findByLabelText('disk side')).toHaveTextContent('name: theirs');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Load from disk' }));
    await waitFor(() => expect(editor).toHaveValue('name: theirs\n'));
  });

  it('a read-only tab never shows the choice: it reloads, and Take over stays usable', async () => {
    const server = workbenchServer({ files: { [A]: 'name: A\n' } });
    const a = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await userEvent.click(await within(a.container).findByRole('button', { name: 'a.yaml' }));
    const editorA = await within(a.container).findByLabelText(`Edit ${A}`);
    await waitFor(() => expect(editorA).not.toHaveAttribute('readonly'));
    fireEvent.change(editorA, { target: { value: 'name: X\n' } });
    await new Promise((resolve) => setTimeout(resolve, 500));

    const b = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await userEvent.click(await within(b.container).findByRole('button', { name: 'Take over' }));
    const editorB = await within(b.container).findByLabelText(`Edit ${A}`);
    await waitFor(() => expect(editorB).toHaveValue('name: X\n'));
    await waitFor(() => expect(within(b.container).getByRole('button', { name: 'Save' })).toBeEnabled());
    await userEvent.click(within(b.container).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(server.disk.get(A)!.content).toBe('name: X\n'));

    act(() => emitServerEvent(EVENTS, { type: 'changed', path: A }));
    await waitFor(() => expect(editorA).toHaveValue('name: X\n'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(within(a.container).getByRole('button', { name: 'Take over' })).toBeEnabled();
  });

  it('a draft made over an older version of the file opens the choice instead of using either', async () => {
    const server = workbenchServer({ files: { [A]: 'name: A\n' } });
    server.changeOnDisk(A, 'name: changed meanwhile\n'); // now v2
    await writeDraft(WORKSPACE.path, A, { content: 'name: my draft\n', baseVersion: 'v1', savedAt: 1 });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);

    const dialog = await screen.findByRole('dialog', { name: 'a.yaml changed on disk' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Compare' }));
    expect(await within(dialog).findByLabelText('disk side')).toHaveTextContent('name: changed meanwhile');
    expect(within(dialog).getByLabelText('mine side')).toHaveTextContent('name: my draft');
    expect(server.puts()).toHaveLength(0);
  });
});
