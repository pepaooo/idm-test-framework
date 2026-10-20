import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from './Workbench';
import { useWorkbench } from './context';
import { readDraft, writeDraft } from './drafts';
import { setLanguage } from '../i18n';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';
import type { Problem } from './types';

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
