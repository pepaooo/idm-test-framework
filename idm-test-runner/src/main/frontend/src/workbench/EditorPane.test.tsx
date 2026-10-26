import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from './Workbench';
import { useWorkbench } from './context';
import { readDraft, writeDraft } from './drafts';
import { IDBObjectStore as FakeObjectStore } from 'fake-indexeddb';
import { setLanguage } from '../i18n';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';
import type { Problem } from './types';
import { DEFAULT_LAYOUT, readPanelLayout, writePanelLayout } from './panelLayout';
import { emit, openRunStreams, playScenario, runServer, RUN_ID, scenario, step } from '../test/runServer';

vi.mock('./monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('./monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

const A = 'scenarios/a.yaml';
const FILES = { [A]: 'name: A\n', 'scenarios/users/b.yaml': 'name: B\n', 'config/application-lab.yml': 'x: 1\n' };

function renderWorkbench(runPanel?: React.ReactNode) {
  return render(<Workbench workspace={WORKSPACE} lockWait={10} runPanel={runPanel} />);
}

async function openA() {
  await userEvent.click(await screen.findByRole('button', { name: 'a.yaml' }));
  const editor = await screen.findByLabelText(`Edit ${A}`);
  await waitFor(() => expect(editor).toHaveValue('name: A\n'));
  await waitFor(() => expect(editor).not.toHaveAttribute('readonly'));
  return editor;
}

function unsavedMarks(scope: HTMLElement = document.body) {
  return within(scope).queryAllByRole('img', { name: 'unsaved' });
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

describe('workbench side panels', () => {
  it('collapses and restores Workspace and Run', async () => {
    workbenchServer({ files: FILES });
    renderWorkbench();
    await screen.findByRole('navigation', { name: 'Workspace files' });
    await userEvent.click(screen.getByRole('button', { name: 'Collapse Workspace' }));
    expect(screen.getByRole('button', { name: 'Expand Workspace' })).toBeVisible();
    expect(screen.queryByRole('navigation', { name: 'Workspace files' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Expand Workspace' }));
    expect(screen.getByRole('navigation', { name: 'Workspace files' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Collapse Run' }));
    expect(screen.getByRole('button', { name: 'Expand Run' })).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Expand Run' }));
    expect(screen.getByRole('complementary', { name: 'Run' })).toBeInTheDocument();
  });

  it('shows live run progress in a collapsed rail', async () => {
    runServer();
    renderWorkbench();
    await waitFor(() => expect(openRunStreams()).toHaveLength(1));
    await userEvent.click(screen.getByRole('button', { name: 'Collapse Run' }));
    const names = ['a.yaml', 'b.yaml', 'c.yaml', 'd.yaml', 'e.yaml'];
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: names, locale: 'en' });
      playScenario(scenario(names[0], [step(0, 'ldap.create')]));
      playScenario(scenario(names[1], [step(0, 'ldap.create')]));
      emit('scenarioStarted', { name: names[2] });
    });
    expect(screen.getByLabelText('Run progress')).toHaveTextContent('3/5');
  });

  it('resets saved layout and clamps oversized widths for a narrow viewport', async () => {
    vi.stubGlobal('innerWidth', 800);
    writePanelLayout(WORKSPACE.path, { ...DEFAULT_LAYOUT, workspaceWidth: 600, runWidth: 500 });
    workbenchServer({ files: FILES });
    const page = renderWorkbench();
    await screen.findByRole('navigation', { name: 'Workspace files' });
    const grid = page.container.querySelector('.wb-grid') as HTMLElement;
    const tree = Number.parseInt(grid.style.getPropertyValue('--workspace-width'), 10);
    const run = Number.parseInt(grid.style.getPropertyValue('--run-width'), 10);
    expect(tree + run).toBeLessThanOrEqual(480);
    await userEvent.click(screen.getByRole('button', { name: 'Reset panel layout' }));
    expect(readPanelLayout(WORKSPACE.path)).toEqual(DEFAULT_LAYOUT);
  });
});

describe('Problems layout', () => {
  it('bounds a saved Problems height to leave the editor usable on a narrow screen', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return { height: this.classList.contains('editor-pane') ? 700 : 0 } as DOMRect;
    });
    try {
      writePanelLayout(WORKSPACE.path, { ...DEFAULT_LAYOUT, problemsHeight: 810 });
      workbenchServer({ files: FILES });
      renderWorkbench();
      const slot = screen.getByRole('region', { name: 'Problems' }).closest('.problems-slot') as HTMLElement;
      await waitFor(() => expect(Number.parseInt(slot.style.height, 10)).toBeLessThanOrEqual(570));
    } finally {
      rect.mockRestore();
    }
  });
  it('collapses Problems without stopping validation', async () => {
    workbenchServer({ files: FILES, validate: (path) => path === A ? [
      { severity: 'error', line: 1, column: 1, message: 'first problem', key: 'first' },
      { severity: 'warning', line: 2, column: 1, message: 'second problem', key: 'second' },
    ] : [] });
    renderWorkbench();
    await openA();
    const panel = screen.getByRole('region', { name: 'Problems' });
    expect(await within(panel).findByText('first problem')).toBeInTheDocument();
    await userEvent.click(within(panel).getByRole('button', { name: 'Collapse Problems' }));
    expect(within(panel).getByRole('button', { name: 'Expand Problems' })).toBeVisible();
    expect(panel).toHaveTextContent('2');
    expect(within(panel).queryByText('first problem')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'b.yaml' }));
    await screen.findByLabelText('Edit scenarios/users/b.yaml');
    expect(panel).not.toHaveTextContent('2');
  });

  it('restores Problems height and resizes upward by keyboard', async () => {
    workbenchServer({ files: FILES });
    renderWorkbench();
    const divider = screen.getByRole('separator', { name: 'Resize Problems' });
    expect(divider).toHaveAttribute('aria-valuenow', '180');
    fireEvent.keyDown(divider, { key: 'ArrowUp' });
    expect(divider).toHaveAttribute('aria-valuenow', '196');
    await userEvent.click(screen.getByRole('button', { name: 'Collapse Problems' }));
    expect(screen.queryByRole('separator', { name: 'Resize Problems' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Expand Problems' }));
    expect(screen.getByRole('separator', { name: 'Resize Problems' })).toHaveAttribute('aria-valuenow', '196');
  });

  it('hides the Problems divider in narrow stacked layout', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
    workbenchServer({ files: FILES });
    renderWorkbench();
    expect(screen.queryByRole('separator', { name: 'Resize Problems' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Collapse Problems' })).toBeVisible();
    expect(screen.getByRole('region', { name: 'Problems' })).toBeInTheDocument();
  });
});

describe('editor workspace chooser', () => {
  it('opens a closed workspace file and reactivates an open tab without duplicating it', async () => {
    workbenchServer({ files: FILES });
    renderWorkbench();
    await screen.findByRole('navigation', { name: 'Workspace files' });
    await userEvent.click(screen.getByRole('button', { name: 'Collapse Workspace' }));
    await userEvent.click(screen.getByRole('button', { name: 'Open workspace file' }));
    await userEvent.click(screen.getByRole('option', { name: 'scenarios/users/b.yaml' }));
    expect(await screen.findByRole('tab', { name: 'b.yaml', selected: true })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Open workspace file' }));
    await userEvent.click(screen.getByRole('option', { name: 'scenarios/a.yaml' }));
    expect(await screen.findByRole('tab', { name: 'a.yaml', selected: true })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Open workspace file' }));
    await userEvent.click(screen.getByRole('option', { name: 'scenarios/users/b.yaml' }));
    expect(screen.getAllByRole('tab')).toHaveLength(2);
    expect(screen.getByRole('tab', { name: 'b.yaml', selected: true })).toBeInTheDocument();
  });
});

describe('Workbench editor', () => {
  it('lays out the top bar, file tree, editor with Problems, and the run panel', async () => {
    workbenchServer({ files: FILES });
    renderWorkbench();
    expect(await screen.findByRole('heading', { name: 'lab-tests' })).toBeInTheDocument();
    expect(screen.getByLabelText('Profile')).toHaveValue('lab');
    expect(screen.getByRole('navigation', { name: 'Workspace files' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Problems' })).toBeInTheDocument();
    expect(screen.getByRole('complementary', { name: 'Run' })).toHaveTextContent(
      'The current or last run shows here, step by step.',
    );
    expect(screen.getByText('Open a file from the tree to edit it.')).toBeInTheDocument();
  });

  it('opens a file in a tab and shows its problems: errors red ✕, warnings amber !, with line links', async () => {
    const problems: Problem[] = [
      { severity: 'error', line: 2, column: 3, message: "step 1 (ldap.verify): unknown parameter 'expct'", key: 'validation.step.unknownParam' },
      { severity: 'warning', line: 4, column: 1, message: 'something to look at', key: 'ui.lint.other' },
    ];
    workbenchServer({ files: FILES, validate: () => problems });
    renderWorkbench();
    await openA();

    expect(screen.getByRole('tab', { name: 'a.yaml', selected: true })).toBeInTheDocument();
    const panel = screen.getByRole('region', { name: 'Problems' });
    const error = (await within(panel).findByText(/unknown parameter 'expct'/)).closest('li')!;
    expect(within(error).getByRole('img', { name: 'error' })).toHaveTextContent('✕');
    expect(within(error).getByRole('img', { name: 'error' })).toHaveClass('badge-error');
    const warning = within(panel).getByText('something to look at').closest('li')!;
    expect(within(warning).getByRole('img', { name: 'warning' })).toHaveTextContent('!');
    expect(within(warning).getByRole('img', { name: 'warning' })).toHaveClass('badge-warn');

    await userEvent.click(within(error).getByRole('button', { name: 'line 2' }));
    expect(screen.getByLabelText(`Edit ${A}`)).toHaveAttribute('data-focus-line', '2');
  });

  it('asks for Problems 400 ms after typing stops, once', async () => {
    const server = workbenchServer({ files: FILES });
    renderWorkbench();
    const editor = await openA();
    await waitFor(() => expect(server.validations()).toHaveLength(1)); // on open

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fireEvent.change(editor, { target: { value: 'name: A1\n' } });
    await act(() => vi.advanceTimersByTimeAsync(200));
    fireEvent.change(editor, { target: { value: 'name: A12\n' } });
    await act(() => vi.advanceTimersByTimeAsync(399));
    expect(server.validations()).toHaveLength(1);

    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(server.validations()).toHaveLength(2);
    expect(server.validations()[1].body).toEqual({ path: A, content: 'name: A12\n' });
  });

  it('ignores an older Problems answer that arrives after a newer one', async () => {
    workbenchServer({
      files: FILES,
      validate: (_path, content) =>
        content.includes('slow')
          ? new Promise((resolve) =>
              setTimeout(
                () => resolve([{ severity: 'error', line: 1, column: 1, message: 'old answer', key: 'k' }]),
                600,
              ),
            )
          : content.includes('fast')
            ? [{ severity: 'error', line: 1, column: 1, message: 'new answer', key: 'k' }]
            : [],
    });
    renderWorkbench();
    const editor = await openA();

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fireEvent.change(editor, { target: { value: 'slow\n' } });
    await act(() => vi.advanceTimersByTimeAsync(450)); // the slow request is out
    fireEvent.change(editor, { target: { value: 'fast\n' } });
    await act(() => vi.advanceTimersByTimeAsync(450)); // the fast one is out and answered
    const panel = screen.getByRole('region', { name: 'Problems' });
    expect(within(panel).getByText('new answer')).toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(600)); // the slow answer arrives late
    expect(within(panel).getByText('new answer')).toBeInTheDocument();
    expect(within(panel).queryByText('old answer')).not.toBeInTheDocument();
  });

  it('⌘S / Ctrl+S saves the active tab over the version last read, then the one saved; ● marks unsaved', async () => {
    const server = workbenchServer({ files: FILES });
    renderWorkbench();
    const editor = await openA();
    expect(unsavedMarks()).toHaveLength(0);

    fireEvent.change(editor, { target: { value: 'name: B\n' } });
    const tablist = screen.getByRole('tablist');
    expect(unsavedMarks(tablist)).toHaveLength(1);
    expect(unsavedMarks(screen.getByRole('navigation', { name: 'Workspace files' }))).toHaveLength(1);
    expect(screen.getByText('● 1 unsaved')).toBeInTheDocument();

    await userEvent.keyboard('{Control>}s{/Control}');
    await waitFor(() => expect(server.puts()).toHaveLength(1));
    expect(server.puts()[0].url).toBe('/api/files?path=scenarios%2Fa.yaml');
    expect(server.puts()[0].body).toEqual({ content: 'name: B\n', version: 'v1' });
    await waitFor(() => expect(unsavedMarks()).toHaveLength(0));
    expect(screen.queryByText('● 1 unsaved')).not.toBeInTheDocument();

    fireEvent.change(editor, { target: { value: 'name: C\n' } });
    await userEvent.keyboard('{Meta>}s{/Meta}');
    await waitFor(() => expect(server.puts()).toHaveLength(2));
    expect(server.puts()[1].body).toEqual({ content: 'name: C\n', version: 'v2' });
    expect(server.disk.get(A)!.content).toBe('name: C\n');

    fireEvent.change(editor, { target: { value: 'name: D\n' } });
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(server.puts()).toHaveLength(3));
    expect(server.puts()[2].body).toEqual({ content: 'name: D\n', version: 'v3' });
  });

  it('keeps unsaved edits as a draft that survives a reload, marked unsaved, and clears it after Save', async () => {
    const server = workbenchServer({ files: FILES });
    const page = renderWorkbench();
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: draft\n' } });
    await waitFor(async () => expect(await readDraft(WORKSPACE.path, A)).not.toBeNull(), { timeout: 2500 });
    const draft = await readDraft(WORKSPACE.path, A);
    expect(draft).toMatchObject({ content: 'name: draft\n', baseVersion: 'v1' });
    expect(typeof draft!.savedAt).toBe('number');

    page.unmount(); // the page is reloaded
    renderWorkbench();
    const restored = await screen.findByLabelText(`Edit ${A}`);
    await waitFor(() => expect(restored).toHaveValue('name: draft\n'));
    expect(unsavedMarks(screen.getByRole('tablist'))).toHaveLength(1);
    expect(server.disk.get(A)!.content).toBe('name: A\n');

    await waitFor(() => expect(restored).not.toHaveAttribute('readonly'));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(async () => expect(await readDraft(WORKSPACE.path, A)).toBeNull());
    expect(server.disk.get(A)!.content).toBe('name: draft\n');
  });

  it('does not keep a draft of a file with a password in plain text, deletes the old one, and says so', async () => {
    workbenchServer({
      files: FILES,
      validate: (_path, content) =>
        content.includes('password:')
          ? [
              {
                severity: 'warning',
                line: 2,
                column: 11,
                message: "a password is written in plain text — use '${IDM_TEST_…}' and put the value in secrets.env",
                key: 'ui.lint.plainSecret',
              },
            ]
          : [],
    });
    await writeDraft(WORKSPACE.path, A, { content: 'name: earlier draft\n', baseVersion: 'v1', savedAt: 1 });
    renderWorkbench();
    const editor = await screen.findByLabelText(`Edit ${A}`);
    await waitFor(() => expect(editor).toHaveValue('name: earlier draft\n'));
    await waitFor(() => expect(editor).not.toHaveAttribute('readonly'));

    fireEvent.change(editor, { target: { value: 'name: A\npassword: fake-not-real\n' } });
    expect(
      await screen.findByText(
        'Unsaved edits to this file are not kept if the page is reloaded: it contains a password in plain text.',
      ),
    ).toBeInTheDocument();
    await waitFor(async () => expect(await readDraft(WORKSPACE.path, A)).toBeNull());
    await new Promise((resolve) => setTimeout(resolve, 1200)); // past the draft delay
    expect(await readDraft(WORKSPACE.path, A)).toBeNull();
  });

  it('a second tab on the workspace opens read-only with a banner; Take over moves the lock', async () => {
    workbenchServer({ files: FILES });
    const first = renderWorkbench();
    await within(first.container).findByRole('button', { name: 'a.yaml' });
    await waitFor(() => expect(within(first.container).getByRole('button', { name: 'Add files' })).toBeEnabled());

    const second = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    expect(await within(second.container).findByText('This workspace is open in another tab')).toBeInTheDocument();
    await userEvent.click(await within(second.container).findByRole('button', { name: 'a.yaml' }));
    const readOnly = await within(second.container).findByLabelText(`Edit ${A}`);
    await waitFor(() => expect(readOnly).toHaveValue('name: A\n'));
    expect(readOnly).toHaveAttribute('readonly');
    expect(within(second.container).getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(within(first.container).queryByText('This workspace is open in another tab')).not.toBeInTheDocument();

    await userEvent.click(within(second.container).getByRole('button', { name: 'Take over' }));
    expect(await within(first.container).findByText('This workspace is open in another tab')).toBeInTheDocument();
    await waitFor(() =>
      expect(within(second.container).queryByText('This workspace is open in another tab')).not.toBeInTheDocument(),
    );
    expect(within(second.container).getByLabelText(`Edit ${A}`)).not.toHaveAttribute('readonly');
  });

  it('keeps the edits as a draft when the page goes away (pagehide), before the draft delay', async () => {
    workbenchServer({ files: FILES });
    renderWorkbench();
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: closing the tab\n' } });
    await new Promise((resolve) => setTimeout(resolve, 550)); // checked (400 ms), draft not yet due (1 s)
    expect(await readDraft(WORKSPACE.path, A)).toBeNull();

    window.dispatchEvent(new Event('pagehide'));

    await waitFor(async () =>
      expect(await readDraft(WORKSPACE.path, A)).toMatchObject({ content: 'name: closing the tab\n', baseVersion: 'v1' }),
    );
  });

  it.each(['beforeunload', 'pagehide'])('issues the draft put synchronously inside the %s dispatch (the connection is already open)', async (type) => {
    workbenchServer({ files: FILES });
    renderWorkbench();
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: leaving now\n' } });
    await new Promise((resolve) => setTimeout(resolve, 550)); // checked, draft not yet due
    const put = vi.spyOn(FakeObjectStore.prototype, 'put');
    try {
      window.dispatchEvent(new Event(type, { cancelable: true }));
      // no await: the put must have been issued while the page was still handling pagehide
      expect(put).toHaveBeenCalledWith(
        expect.objectContaining({ content: 'name: leaving now\n', baseVersion: 'v1' }),
        `${WORKSPACE.path}\u0000${A}`,
      );
    } finally {
      put.mockRestore();
    }
  });

  it.each([
    [
      'a plain-text password',
      (_path: string, content: string): Problem[] | Promise<Problem[]> =>
        content.includes('password:')
          ? [{ severity: 'warning', line: 2, column: 11, message: 'a password is written in plain text', key: 'ui.lint.plainSecret' }]
          : [],
    ],
    ['the check not answering', null],
  ])('take over when the holder has edits it cannot keep as a draft (%s): asks first, and the edits stay in that tab', async (_why, validate) => {
    workbenchServer({
      files: FILES,
      ...(validate ? { validate } : { routes: { 'POST /api/validate': { status: 500, body: { key: 'k', message: 'down' } } } }),
    });
    const confirm = vi.spyOn(window, 'confirm');
    try {
      const a = renderWorkbench();
      await userEvent.click(await within(a.container).findByRole('button', { name: 'a.yaml' }));
      const editorA = await within(a.container).findByLabelText(`Edit ${A}`);
      await waitFor(() => expect(editorA).not.toHaveAttribute('readonly'));
      const mine = 'name: A\npassword: fake-not-real\n';
      fireEvent.change(editorA, { target: { value: mine } });
      await new Promise((resolve) => setTimeout(resolve, 500));

      const b = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
      const takeOverB = await within(b.container).findByRole('button', { name: 'Take over' });

      confirm.mockReturnValueOnce(false); // "no": nothing changes
      await userEvent.click(takeOverB);
      await waitFor(() =>
        expect(confirm).toHaveBeenCalledWith('The other tab has edits that could not be kept as a draft. Take over anyway?'),
      );
      await new Promise((resolve) => setTimeout(resolve, 50));
      expect(within(b.container).getByText('This workspace is open in another tab')).toBeInTheDocument();
      expect(editorA).not.toHaveAttribute('readonly');

      confirm.mockReturnValueOnce(true);
      await userEvent.click(takeOverB);
      expect(await within(a.container).findByText('This workspace is open in another tab')).toBeInTheDocument();
      // A keeps its edits, read-only, unsaved, and says why
      expect(editorA).toHaveValue(mine);
      expect(editorA).toHaveAttribute('readonly');
      expect(unsavedMarks(within(a.container).getByRole('tablist'))).toHaveLength(1);
      expect(
        within(a.container).getByText(
          'Your unsaved edits to this file could not be kept as a draft: they are only in this tab. Take over to go on editing them.',
        ),
      ).toBeInTheDocument();
      expect(await readDraft(WORKSPACE.path, A)).toBeNull();

      // back in A: the edits are editable again
      await userEvent.click(within(a.container).getByRole('button', { name: 'Take over' }));
      await waitFor(() => expect(editorA).not.toHaveAttribute('readonly'));
      expect(editorA).toHaveValue(mine);
    } finally {
      confirm.mockRestore();
    }
  });

  it.each([
    ['IndexedDB is missing', 'missing'],
    ['the draft write aborts', 'abort'],
  ])('take over when %s: asks first, and the edits stay in the yielding tab', async (_why, how) => {
    workbenchServer({ files: FILES });
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const original = FakeObjectStore.prototype.put;
    const put = vi.spyOn(FakeObjectStore.prototype, 'put');
    if (how === 'abort') {
      put.mockImplementation(function (this: IDBObjectStore, ...args: Parameters<IDBObjectStore['put']>) {
        const request = original.apply(this, args);
        this.transaction.abort();
        return request;
      });
    }
    const saved = globalThis.indexedDB;
    try {
      if (how === 'missing') {
        // @ts-expect-error simulating a browser without IndexedDB (a private window)
        delete globalThis.indexedDB;
      }
      const a = renderWorkbench();
      await userEvent.click(await within(a.container).findByRole('button', { name: 'a.yaml' }));
      const editorA = await within(a.container).findByLabelText(`Edit ${A}`);
      await waitFor(() => expect(editorA).not.toHaveAttribute('readonly'));
      fireEvent.change(editorA, { target: { value: 'name: X\n' } });
      await new Promise((resolve) => setTimeout(resolve, 500)); // checked: a draft would be fine

      const b = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
      await userEvent.click(await within(b.container).findByRole('button', { name: 'Take over' }));
      await waitFor(() =>
        expect(confirm).toHaveBeenCalledWith('The other tab has edits that could not be kept as a draft. Take over anyway?'),
      );
      expect(await within(a.container).findByText('This workspace is open in another tab')).toBeInTheDocument();
      expect(editorA).toHaveValue('name: X\n');
      expect(editorA).toHaveAttribute('readonly');
      expect(
        within(a.container).getByText(
          'Your unsaved edits to this file could not be kept as a draft: they are only in this tab. Take over to go on editing them.',
        ),
      ).toBeInTheDocument();
    } finally {
      globalThis.indexedDB = saved;
      put.mockRestore();
      confirm.mockRestore();
    }
  });

  it('after another tab changed the drafts, this tab does not trust what it wrote before', async () => {
    workbenchServer({ files: FILES });
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    try {
      const a = renderWorkbench();
      await userEvent.click(await within(a.container).findByRole('button', { name: 'a.yaml' }));
      const editorA = await within(a.container).findByLabelText(`Edit ${A}`);
      await waitFor(() => expect(editorA).not.toHaveAttribute('readonly'));
      fireEvent.change(editorA, { target: { value: 'name: X\n' } });
      await waitFor(async () => expect((await readDraft(WORKSPACE.path, A))?.content).toBe('name: X\n'), { timeout: 2500 });

      // B takes over and puts the text back as on disk: its draft is removed
      const b = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
      await userEvent.click(await within(b.container).findByRole('button', { name: 'Take over' }));
      const editorB = await within(b.container).findByLabelText(`Edit ${A}`);
      await waitFor(() => expect(editorB).toHaveValue('name: X\n'));
      await waitFor(() => expect(editorB).not.toHaveAttribute('readonly'));
      fireEvent.change(editorB, { target: { value: 'name: A\n' } });
      await waitFor(async () => expect(await readDraft(WORKSPACE.path, A)).toBeNull(), { timeout: 2500 });

      // A takes back and types X again: the same text it once wrote, but that draft is gone now
      await userEvent.click(within(a.container).getByRole('button', { name: 'Take over' }));
      await waitFor(() => expect(editorA).not.toHaveAttribute('readonly'));
      fireEvent.change(editorA, { target: { value: 'name: X\n' } });
      await new Promise((resolve) => setTimeout(resolve, 500)); // checked, draft not yet due

      await userEvent.click(within(b.container).getByRole('button', { name: 'Take over' }));
      await waitFor(() => expect(editorB).toHaveValue('name: X\n'));
      expect((await readDraft(WORKSPACE.path, A))?.content).toBe('name: X\n');
    } finally {
      confirm.mockRestore();
    }
  });

  it('a leave prompt the user cancels does not cancel a draft that is still due', async () => {
    workbenchServer({ files: FILES });
    renderWorkbench();
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: still here\n' } });
    window.dispatchEvent(new Event('beforeunload', { cancelable: true })); // before the check: nothing to keep yet
    expect(await readDraft(WORKSPACE.path, A)).toBeNull();
    // the user stayed: once the check answers and the delay passes, the draft is written as usual
    await waitFor(async () => expect((await readDraft(WORKSPACE.path, A))?.content).toBe('name: still here\n'), {
      timeout: 2500,
    });
  });

  it('take over and back, with edits on both sides: nothing is lost and the newest edits win', async () => {
    const server = workbenchServer({ files: FILES });
    const a = renderWorkbench();
    await userEvent.click(await within(a.container).findByRole('button', { name: 'a.yaml' }));
    const editorA = await within(a.container).findByLabelText(`Edit ${A}`);
    await waitFor(() => expect(editorA).not.toHaveAttribute('readonly'));
    fireEvent.change(editorA, { target: { value: 'name: X\n' } });
    await new Promise((resolve) => setTimeout(resolve, 500)); // X checked

    const b = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await userEvent.click(await within(b.container).findByRole('button', { name: 'Take over' }));
    // B works from A's draft; A no longer holds edits of its own
    const editorB = await within(b.container).findByLabelText(`Edit ${A}`);
    await waitFor(() => expect(editorB).toHaveValue('name: X\n'));
    await waitFor(() => expect(editorB).not.toHaveAttribute('readonly'));
    await waitFor(() => expect(editorA).toHaveValue('name: A\n'));
    expect(editorA).toHaveAttribute('readonly');

    fireEvent.change(editorB, { target: { value: 'name: Y\n' } });
    await new Promise((resolve) => setTimeout(resolve, 500)); // Y checked, draft not yet due

    await userEvent.click(within(a.container).getByRole('button', { name: 'Take over' }));
    await waitFor(() => expect(editorA).toHaveValue('name: Y\n'));
    await waitFor(() => expect(editorA).not.toHaveAttribute('readonly'));
    await waitFor(() => expect(editorB).toHaveValue('name: A\n'));

    await userEvent.click(within(a.container).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(server.disk.get(A)!.content).toBe('name: Y\n'));
    expect(server.puts().map((p) => p.body)).toEqual([{ content: 'name: Y\n', version: 'v1' }]);
  });

  it('never keeps a draft when the check does not answer: it might hold a plain-text password', async () => {
    workbenchServer({
      files: FILES,
      routes: { 'POST /api/validate': { status: 500, body: { key: 'ui.unexpected', message: 'down' } } },
    });
    renderWorkbench();
    const editor = await openA();
    fireEvent.change(editor, { target: { value: 'name: A\npassword: fake-not-real\n' } });
    await new Promise((resolve) => setTimeout(resolve, 1300)); // past the check and the draft delay
    expect(await readDraft(WORKSPACE.path, A)).toBeNull();

    window.dispatchEvent(new Event('pagehide'));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(await readDraft(WORKSPACE.path, A)).toBeNull();
  });

  it('a file dropped outside the tree, or on the tree of a read-only tab, is not opened by the browser', async () => {
    const server = workbenchServer({ files: FILES });
    renderWorkbench();
    await openA();
    const files = [new File(['name: x\n'], 'x.yaml')];
    expect(fireEvent.dragOver(screen.getByLabelText(`Edit ${A}`), { dataTransfer: { files, types: ['Files'] } })).toBe(false);
    expect(fireEvent.drop(screen.getByLabelText(`Edit ${A}`), { dataTransfer: { files, types: ['Files'] } })).toBe(false);
    expect(fireEvent.drop(document.body, { dataTransfer: { files, types: ['Files'] } })).toBe(false);
    expect(server.calls()).not.toContain('POST /api/files/add');

    const second = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await within(second.container).findByText('This workspace is open in another tab');
    const tree = within(second.container).getByRole('navigation', { name: 'Workspace files' });
    expect(fireEvent.drop(tree, { dataTransfer: { files, types: ['Files'] } })).toBe(false);
    expect(server.calls()).not.toContain('POST /api/files/add');
  });

  it('gives the run panel openFile, markLines and saveActive', async () => {
    const server = workbenchServer({ files: FILES });
    let api: ReturnType<typeof useWorkbench> | null = null;
    function Probe() {
      api = useWorkbench();
      return <span>run panel here</span>;
    }
    renderWorkbench(<Probe />);
    await screen.findByText('run panel here');
    await waitFor(() => expect(api!.readOnly).toBe(false));

    await act(() => api!.markLines('scenarios/users/b.yaml', 5, 3));
    const editor = await screen.findByLabelText('Edit scenarios/users/b.yaml');
    expect(editor).toHaveAttribute('data-marks', '3-5');
    expect(api!.activePath).toBe('scenarios/users/b.yaml');

    await act(() => api!.openFile(A));
    const a = await screen.findByLabelText(`Edit ${A}`);
    await waitFor(() => expect(a).toHaveValue('name: A\n'));
    fireEvent.change(a, { target: { value: 'name: saved by run\n' } });
    await act(() => api!.saveActive());
    expect(server.puts().map((p) => p.body)).toEqual([{ content: 'name: saved by run\n', version: 'v1' }]);
    expect(api!.isDirty(A)).toBe(false);
  });
});
