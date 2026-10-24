import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from './Workbench';
import { WorkbenchStore } from './store';
import { readDraft, writeDraft } from './drafts';
import { emitServerEvent } from '../test/browserFakes';
import { WORKSPACE, workbenchServer, type GitState, type WorkbenchServerOptions } from '../test/workbenchServer';
import { setLanguage } from '../i18n';

vi.mock('./monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('./monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

const A = 'scenarios/users/a.yaml';
const EVENTS = '/api/files/events';
const CONFIRM = `Delete ${A}? This cannot be undone in the workbench.`;
const UNSAVED = 'It has unsaved edits, which will be lost.';
const NOT_IN_GIT = 'This file is not in git yet, so it cannot be recovered.';
const B = 'scenarios/b.yaml';

afterEach(() => {
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

async function start(options: Partial<WorkbenchServerOptions> = {}) {
  const server = workbenchServer({ files: { [A]: 'name: A\n', 'scenarios/b.yaml': 'name: B\n' }, ...options });
  render(<Workbench workspace={WORKSPACE} lockWait={10} />);
  await screen.findByRole('button', { name: 'a.yaml' });
  await waitFor(() => expect(screen.getByRole('button', { name: 'Delete a.yaml' })).toBeEnabled());
  return server;
}

async function openA() {
  await userEvent.click(screen.getByRole('button', { name: 'a.yaml' }));
  const editor = await screen.findByLabelText(`Edit ${A}`);
  await waitFor(() => expect(editor).toHaveValue('name: A\n'));
  await waitFor(() => expect(editor).not.toHaveAttribute('readonly'));
  return editor;
}

/** Edits A and waits until the edits are kept as a draft. */
async function editA(editor: HTMLElement, text = 'name: mine\n') {
  fireEvent.change(editor, { target: { value: text } });
  await waitFor(async () => expect((await readDraft(WORKSPACE.path, A))?.content).toBe(text), { timeout: 3000 });
}

async function askToDelete() {
  await userEvent.click(screen.getByRole('button', { name: 'Delete a.yaml' }));
  return screen.getByRole('dialog', { name: 'Delete' });
}

async function gitAnswered(server: ReturnType<typeof workbenchServer>) {
  await waitFor(() => expect(server.calls()).toContain(`GET /api/files/git?path=${encodeURIComponent(A)}`));
  await new Promise((resolve) => setTimeout(resolve, 20));
}

describe('Delete a scenario', () => {
  it('delete asks first and removes the file', async () => {
    const server = await start();
    await openA();
    const button = screen.getByRole('button', { name: 'Delete a.yaml' });

    let dialog = await askToDelete();
    expect(dialog).toHaveTextContent(CONFIRM);
    expect(dialog).not.toHaveTextContent(UNSAVED);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(button).toHaveFocus();
    expect(server.deletes()).toHaveLength(0);

    dialog = await askToDelete();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(screen.queryByLabelText(`Edit ${A}`)).not.toBeInTheDocument());
    expect(server.deletes().map((r) => r.url)).toEqual([`/api/files?path=${encodeURIComponent(A)}&version=v1`]);
    expect(server.disk.has(A)).toBe(false);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('button', { name: 'a.yaml' })).not.toBeInTheDocument());
    expect(await readDraft(WORKSPACE.path, A)).toBeNull();
  });

  it('a file that is not open is read first, and deleted at the version read', async () => {
    const server = await start();
    server.changeOnDisk(A, 'name: newer\n'); // now v2

    const dialog = await askToDelete();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(server.disk.has(A)).toBe(false));
    expect(server.deletes().map((r) => r.url)).toEqual([`/api/files?path=${encodeURIComponent(A)}&version=v2`]);
    expect(server.calls()).toContain(`GET /api/files?path=${encodeURIComponent(A)}`);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('unsaved edits are mentioned, and their draft is removed with the file', async () => {
    const server = await start();
    await editA(await openA());

    const dialog = await askToDelete();
    expect(dialog).toHaveTextContent(CONFIRM);
    expect(dialog).toHaveTextContent(UNSAVED);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(screen.queryByLabelText(`Edit ${A}`)).not.toBeInTheDocument());
    expect(server.disk.has(A)).toBe(false);
    expect(server.puts()).toHaveLength(0);
    await waitFor(async () => expect(await readDraft(WORKSPACE.path, A)).toBeNull());
    // nothing comes back: no tab, no draft written later by a timer of the closed tab
    await new Promise((resolve) => setTimeout(resolve, 1200));
    expect(await readDraft(WORKSPACE.path, A)).toBeNull();
    expect(screen.queryByLabelText(`Edit ${A}`)).not.toBeInTheDocument();
  });

  it.each<[GitState, boolean]>([
    ['untracked', true],
    ['changed', true],
    ['clean', false],
    ['none', false],
  ])('not in git is mentioned (git state %s → %s)', async (state, mentioned) => {
    const server = await start({ gitState: () => state });
    const dialog = await askToDelete();
    if (mentioned) {
      expect(await within(dialog).findByText(NOT_IN_GIT)).toBeInTheDocument();
    } else {
      await gitAnswered(server);
      expect(dialog).not.toHaveTextContent(NOT_IN_GIT);
    }
    expect(dialog).toHaveTextContent(CONFIRM);
  });

  it('Delete never waits for the git answer', async () => {
    const server = await start({ routes: { 'GET /api/files/git': () => new Promise(() => undefined) } });
    const dialog = await askToDelete();
    expect(dialog).toHaveTextContent(CONFIRM);
    expect(within(dialog).getByRole('button', { name: 'Delete' })).toBeEnabled();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(server.disk.has(A)).toBe(false));
  });

  it('delete from another tab closes a file without edits silently', async () => {
    const server = await start();
    await openA();

    server.removeOnDisk(A);
    act(() => emitServerEvent(EVENTS, { type: 'deleted', path: A }));

    await waitFor(() => expect(screen.queryByLabelText(`Edit ${A}`)).not.toBeInTheDocument());
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('button', { name: 'a.yaml' })).not.toBeInTheDocument());
    expect(server.puts()).toHaveLength(0);
  });

  /** A has unsaved edits (kept as a draft), then another program deletes it. */
  async function deletedUnderEdits() {
    const server = await start();
    const editor = await openA();
    await editA(editor);
    server.removeOnDisk(A);
    act(() => emitServerEvent(EVENTS, { type: 'deleted', path: A }));
    const dialog = await screen.findByRole('dialog', { name: 'a.yaml was deleted on disk' });
    return { server, editor, dialog };
  }

  it('an outside delete of a file with unsaved edits keeps the tab and its draft, and asks', async () => {
    const { server, editor, dialog } = await deletedUnderEdits();
    expect(dialog).toHaveTextContent(`${A} was deleted on disk. Keep your version (the file is created again) or discard it?`);
    expect(editor).toHaveValue('name: mine\n');
    expect((await readDraft(WORKSPACE.path, A))?.content).toBe('name: mine\n');
    expect(within(dialog).getByRole('button', { name: 'Keep mine' })).toBeEnabled();
    expect(within(dialog).getByRole('button', { name: 'Discard' })).toBeEnabled();
    expect(within(dialog).queryByRole('button', { name: 'Compare' })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Load from disk' })).not.toBeInTheDocument();
    // a save cannot go over the missing file
    await userEvent.keyboard('{Control>}s{/Control}');
    expect(server.puts()).toHaveLength(0);
  });

  it('Keep mine creates the file again with the tab\'s text, never overwriting; the tab becomes clean', async () => {
    const { server, editor, dialog } = await deletedUnderEdits();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Keep mine' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(server.creates().map((r) => r.body)).toEqual([{ path: A, content: 'name: mine\n' }]);
    expect(server.disk.get(A)?.content).toBe('name: mine\n');
    expect(editor).toHaveValue('name: mine\n');
    await waitFor(() => expect(screen.queryAllByRole('img', { name: 'unsaved' })).toHaveLength(0));
    await waitFor(async () => expect(await readDraft(WORKSPACE.path, A)).toBeNull());
  });

  it('Keep mine when the file is back meanwhile does not overwrite it: the changed-on-disk choice opens', async () => {
    const { server, editor, dialog } = await deletedUnderEdits();
    server.disk.set(A, { content: 'name: back\n', version: 'v9' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Keep mine' }));

    expect(await screen.findByRole('dialog', { name: 'a.yaml changed on disk' })).toBeInTheDocument();
    expect(server.disk.get(A)?.content).toBe('name: back\n');
    expect(editor).toHaveValue('name: mine\n');
  });

  it('Discard closes the tab and drops the draft', async () => {
    const { dialog } = await deletedUnderEdits();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Discard' }));

    await waitFor(() => expect(screen.queryByLabelText(`Edit ${A}`)).not.toBeInTheDocument());
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(async () => expect(await readDraft(WORKSPACE.path, A)).toBeNull());
  });

  it('a read-only tab keeps the edits of a deleted file too: the deleted choice waits until it holds the workspace', async () => {
    const server = workbenchServer({ files: { [A]: 'name: A\n' } });
    const store = new WorkbenchStore(WORKSPACE.path, { validateDelay: 5, draftDelay: 5 });
    await store.open(A); // read-only: another tab holds the workspace, whose edits are this draft
    await writeDraft(WORKSPACE.path, A, { content: 'name: theirs edits\n', baseVersion: 'v1', savedAt: 1 });

    server.removeOnDisk(A);
    await store.fileDeleted(A);
    expect(store.doc(A)?.conflict).toEqual({ diskContent: null, diskVersion: '', deleted: true });
    expect((await readDraft(WORKSPACE.path, A))?.content).toBe('name: theirs edits\n');

    store.setReadOnly(false); // this tab holds it now: the draft comes back, still as a deleted file
    await waitFor(() => expect(store.doc(A)?.content).toBe('name: theirs edits\n'));
    expect(store.doc(A)?.conflict?.deleted).toBe(true);
    await expect(store.save(A)).rejects.toThrow();
    expect(server.puts()).toHaveLength(0);
    store.dispose();
  });

  it('a draft of a file not open in this tab survives a deleted event', async () => {
    const server = await start();
    await writeDraft(WORKSPACE.path, B, { content: 'name: kept\n', baseVersion: 'v1', savedAt: 1 });
    server.removeOnDisk(B);
    act(() => emitServerEvent(EVENTS, { type: 'deleted', path: B }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'b.yaml' })).not.toBeInTheDocument());
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect((await readDraft(WORKSPACE.path, B))?.content).toBe('name: kept\n');
  });

  it('a closed file is deleted at the version read when the dialog opened: a change meanwhile is refused', async () => {
    const server = await start();
    const dialog = await askToDelete();
    await waitFor(() => expect(server.calls()).toContain(`GET /api/files?path=${encodeURIComponent(A)}`));
    server.changeOnDisk(A, 'name: changed while asking\n'); // v2

    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(`${A} changed on disk`);
    expect(server.disk.get(A)?.content).toBe('name: changed while asking\n');
    expect(server.deletes().map((r) => r.url)).toEqual([`/api/files?path=${encodeURIComponent(A)}&version=v1`]);
  });

  it('a stored draft of a file not open here is mentioned as unsaved edits', async () => {
    await start();
    await writeDraft(WORKSPACE.path, B, { content: 'name: drafted\n', baseVersion: 'v1', savedAt: 1 });
    await userEvent.click(screen.getByRole('button', { name: 'Delete b.yaml' }));
    const dialog = screen.getByRole('dialog', { name: 'Delete' });
    expect(await within(dialog).findByText(UNSAVED)).toBeInTheDocument();
  });

  it('a deleted event for a file that is back (an atomic save elsewhere) is a change, not a delete', async () => {
    const server = await start();
    const editor = await openA();

    server.changeOnDisk(A, 'name: replaced\n');
    act(() => emitServerEvent(EVENTS, { type: 'deleted', path: A }));

    await waitFor(() => expect(editor).toHaveValue('name: replaced\n'));
    expect(screen.getByLabelText(`Edit ${A}`)).toBeInTheDocument();
  });

  it('a deleted event for an open file with edits that is back offers the changed-on-disk choice', async () => {
    const server = await start();
    const editor = await openA();
    await editA(editor);

    server.changeOnDisk(A, 'name: replaced\n');
    act(() => emitServerEvent(EVENTS, { type: 'deleted', path: A }));

    expect(await screen.findByRole('dialog', { name: 'a.yaml changed on disk' })).toBeInTheDocument();
    expect(editor).toHaveValue('name: mine\n');
    expect((await readDraft(WORKSPACE.path, A))?.content).toBe('name: mine\n');
  });

  it('refusals are shown: a scenario of the run that is going stays', async () => {
    const message = `${A} is part of the run that is going; wait for it to finish or stop it.`;
    const server = await start({
      routes: { 'DELETE /api/files': { status: 409, body: { key: 'ui.file.inRun', message } } },
    });
    await openA();
    const dialog = await askToDelete();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(message);
    expect(screen.getByRole('dialog', { name: 'Delete' })).toBe(dialog);
    expect(server.disk.has(A)).toBe(true);
    expect(screen.getByLabelText(`Edit ${A}`)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Delete' })).toBeEnabled();
  });

  it('refusals are shown: a file changed on disk stays, and the changed-on-disk choices are offered', async () => {
    const server = await start();
    const editor = await openA();
    await editA(editor);
    server.changeOnDisk(A, 'name: theirs\n'); // v2, no event yet

    const dialog = await askToDelete();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(`${A} changed on disk`);
    expect(server.disk.get(A)?.content).toBe('name: theirs\n');
    const disk = await screen.findByRole('dialog', { name: 'a.yaml changed on disk' });
    expect(within(disk).getByRole('button', { name: 'Keep mine' })).toBeInTheDocument();
    expect(within(disk).getByRole('button', { name: 'Compare' })).toBeInTheDocument();
    expect(editor).toHaveValue('name: mine\n');
    expect((await readDraft(WORKSPACE.path, A))?.content).toBe('name: mine\n');

    // the user re-checks (here: takes the disk's version), then deletes what they have seen
    await userEvent.click(within(disk).getByRole('button', { name: 'Load from disk' }));
    await waitFor(() => expect(editor).toHaveValue('name: theirs\n'));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(server.disk.has(A)).toBe(false));
    expect(server.deletes().map((r) => r.url)).toEqual([
      `/api/files?path=${encodeURIComponent(A)}&version=v1`,
      `/api/files?path=${encodeURIComponent(A)}&version=v2`,
    ]);
  });

  it('speaks Spanish', async () => {
    setLanguage('es', false);
    workbenchServer({ files: { [A]: 'name: A\n' }, gitState: () => 'untracked' });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Eliminar a.yaml' })).toBeEnabled());
    await userEvent.click(screen.getByRole('button', { name: 'a.yaml' }));
    const editor = await screen.findByLabelText(`Editar ${A}`);
    await waitFor(() => expect(editor).not.toHaveAttribute('readonly'));
    fireEvent.change(editor, { target: { value: 'name: mía\n' } });

    await userEvent.click(screen.getByRole('button', { name: 'Eliminar a.yaml' }));
    const dialog = screen.getByRole('dialog', { name: 'Eliminar' });
    expect(dialog).toHaveTextContent(`¿Eliminar ${A}? No se puede deshacer en el workbench.`);
    expect(dialog).toHaveTextContent('Tiene cambios sin guardar, que se perderán.');
    expect(await within(dialog).findByText('Este archivo aún no está en git, así que no se podrá recuperar.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Eliminar' })).toBeInTheDocument();
  });
});
