import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from './Workbench';
import { readDraft } from './drafts';
import { emitServerEvent, eventStreams } from '../test/browserFakes';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';
import { setLanguage } from '../i18n';

vi.mock('./monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('./monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

const A = 'scenarios/a.yaml';
const EVENTS = '/api/files/events';
const SWITCHED = {
  status: 409,
  body: {
    key: 'ui.workspace.switched',
    message: 'This workspace is no longer the one open in the workbench (another tab opened other-tests).',
  },
};
const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function openA() {
  await userEvent.click(await screen.findByRole('button', { name: 'a.yaml' }));
  const editor = await screen.findByLabelText(`Edit ${A}`);
  await waitFor(() => expect(editor).toHaveValue('name: A\n'));
  await waitFor(() => expect(editor).not.toHaveAttribute('readonly'));
  return editor;
}

afterEach(() => {
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

describe('Another tab opens another workspace', () => {
  it('says which workspace every call and stream is for', async () => {
    const server = workbenchServer({ files: { [A]: 'name: A\n' } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await openA();

    const encoded = encodeURIComponent(WORKSPACE.path);
    const tree = server.sent.find((r) => r.url === '/api/files/tree');
    expect(tree?.headers['x-idmtest-workspace']).toBe(encoded);
    expect(server.sent.find((r) => r.url.startsWith('/api/files?'))?.headers['x-idmtest-workspace']).toBe(encoded);
    expect(eventStreams(EVENTS).map((s) => s.url)).toEqual([`${EVENTS}?workspace=${encoded}`]);
  });

  it('a save refused with ui.workspace.switched shows the blocking banner and keeps the edits and their draft', async () => {
    const server = workbenchServer({ files: { [A]: 'name: A\n' }, routes: { 'PUT /api/files': SWITCHED } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: mine\n' } });
    await pause(1700); // checked, and kept as a draft
    expect((await readDraft(WORKSPACE.path, A))?.content).toBe('name: mine\n');

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const banner = await screen.findByRole('alertdialog');
    expect(banner).toHaveTextContent(SWITCHED.body.message);
    expect(banner).toHaveTextContent('Nothing is saved from this tab.');
    expect(screen.getByRole('button', { name: 'Reload' })).toHaveFocus();
    // not a "changed on disk" choice: nothing to compare or keep, the server refused the workspace
    expect(screen.queryByRole('dialog', { name: /changed on disk/ })).not.toBeInTheDocument();
    expect(editor).toHaveValue('name: mine\n');
    expect((await readDraft(WORKSPACE.path, A))?.content).toBe('name: mine\n');
    // ⌘S does not try again
    const puts = server.puts().length;
    await userEvent.keyboard('{Control>}s{/Control}');
    expect(server.puts()).toHaveLength(puts);
  });

  it('a files stream the server ends for good reads the tree again, which shows the banner', async () => {
    let switched = false;
    workbenchServer({
      files: { [A]: 'name: A\n' },
      routes: { 'GET /api/files/tree': () => (switched ? SWITCHED : { body: { name: 'lab-tests', path: '', dir: true, kind: null, lastResult: null, children: [] } }) },
    });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await waitFor(() => expect(eventStreams(EVENTS)).toHaveLength(1));

    switched = true;
    act(() => eventStreams(EVENTS)[0].fail(true)); // 409: the browser does not reconnect

    expect(await screen.findByRole('alertdialog')).toHaveTextContent(SWITCHED.body.message);
  });

  it('speaks Spanish', async () => {
    setLanguage('es', false);
    workbenchServer({ files: { [A]: 'name: A\n' }, routes: { 'GET /api/files/tree': SWITCHED } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);

    await screen.findByRole('alertdialog');
    expect(screen.getByRole('button', { name: 'Recargar' })).toBeInTheDocument();
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Desde esta pestaña no se guarda nada.');
  });
});

describe('Lost file events (overflow)', () => {
  it('reads the tree and the open files again', async () => {
    const server = workbenchServer({ files: { [A]: 'name: A\n' } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    const editor = await openA();
    const treeReads = server.sent.filter((r) => r.url === '/api/files/tree').length;

    server.changeOnDisk(A, 'name: pulled while events were lost\n');
    act(() => emitServerEvent(EVENTS, { type: 'overflow', path: '' }));

    await waitFor(() => expect(editor).toHaveValue('name: pulled while events were lost\n'));
    expect(server.sent.filter((r) => r.url === '/api/files/tree').length).toBeGreaterThan(treeReads);
  });
});
