import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from '../workbench/Workbench';
import { setLanguage } from '../i18n';
import { directory, hrDatabase } from '../test/labServer';
import { act } from '@testing-library/react';
import { emit, RUN_ID, runServer, WORKSPACE } from '../test/runServer';

/** The accepted run starts and ends at once (so Run can start another). */
function finishRun() {
  act(() => {
    emit('runStarted', { runId: RUN_ID, scenarios: [], locale: 'en' });
    emit('runFinished', { runId: RUN_ID, total: 0, passed: 0, findings: 0, setupProblems: 0, stopped: false, exitCode: 0, reportUrl: null });
  });
}

vi.mock('../workbench/monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('../workbench/monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

const CREATE = 'scenarios/users/create-user.yaml';

afterEach(() => {
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

function runButton() {
  return screen.getByRole('button', { name: 'Run' });
}

async function openCreate(container: HTMLElement = document.body) {
  await userEvent.click(await within(container).findByRole('button', { name: /^create-user\.yaml/ }));
  await waitFor(() => expect((within(container).getByLabelText(`Edit ${CREATE}`) as HTMLTextAreaElement).value).toContain('name: Create'));
}

/** Waits for the lab to be read (the chip says so) and the tab to hold the workspace. */
async function ready() {
  await waitFor(() => expect(screen.getByRole('button', { name: 'Lab ready' })).toBeInTheDocument());
  await waitFor(() => expect(screen.getByRole('button', { name: 'Add files' })).toBeEnabled());
}

describe('Run button', () => {
  it('runs the active scenario with the top bar profile and the page language', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await ready();
    expect(runButton()).toBeDisabled(); // no scenario open yet
    await openCreate();
    await userEvent.click(runButton());
    await waitFor(() => expect(server.runStarts()).toHaveLength(1));
    expect(server.runStarts()[0].body).toEqual({ profile: 'lab', scenarios: [CREATE], onlyFindings: false, locale: 'en' });
  });

  it('reads "Save & Run" with unsaved changes, saves first and then runs', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await ready();
    await openCreate();
    await userEvent.type(screen.getByLabelText(`Edit ${CREATE}`), '# more');
    const saveAndRun = await screen.findByRole('button', { name: 'Save & Run' });
    await userEvent.click(saveAndRun);
    await waitFor(() => expect(server.runStarts()).toHaveLength(1));
    const order = server.sent.filter((r) => r.method !== 'GET' && r.url !== '/api/validate').map((r) => r.method + ' ' + r.url.split('?')[0]);
    expect(order).toEqual(['PUT /api/files', 'POST /api/runs']);
    expect(server.disk.get(CREATE)!.content).toContain('# more');
    expect(await screen.findByRole('button', { name: 'Run' })).toBeInTheDocument();
  });

  it('does not run when the save opens the changed-on-disk choice', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await ready();
    await openCreate();
    await userEvent.type(screen.getByLabelText(`Edit ${CREATE}`), '# mine');
    server.changeOnDisk(CREATE, 'name: Theirs\n');
    await userEvent.click(await screen.findByRole('button', { name: 'Save & Run' }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(server.runStarts()).toHaveLength(0);
  });

  it('⌘↵ / Ctrl+Enter runs the active scenario, ⇧⌘↵ runs all of them', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await ready();
    await openCreate();
    fireEvent.keyDown(screen.getByLabelText(`Edit ${CREATE}`), { key: 'Enter', ctrlKey: true });
    await waitFor(() => expect(server.runStarts()).toHaveLength(1));
    expect(server.runStarts()[0].body).toMatchObject({ scenarios: [CREATE] });
    // a new run once the first has ended (the server would say busy otherwise)
    finishRun();
    fireEvent.keyDown(window, { key: 'Enter', metaKey: true, shiftKey: true });
    await waitFor(() => expect(server.runStarts()).toHaveLength(2));
    expect(server.runStarts()[1].body).toMatchObject({ scenarios: 'all', onlyFindings: false });
  });

  it('the shortcuts do nothing while the lab panel or the changed-on-disk choice is open', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await ready();
    await openCreate();
    await userEvent.click(screen.getByRole('button', { name: 'Lab ready' }));
    const lab = await screen.findByRole('dialog', { name: /Lab connection/ });
    fireEvent.keyDown(within(lab).getAllByRole('textbox')[0], { key: 'Enter', ctrlKey: true });
    fireEvent.keyDown(within(lab).getAllByRole('textbox')[0], { key: 'Enter', metaKey: true, shiftKey: true });
    await userEvent.click(within(lab).getByRole('button', { name: /close/i }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Lab connection/ })).not.toBeInTheDocument());

    await userEvent.type(screen.getByLabelText(`Edit ${CREATE}`), '# mine');
    server.changeOnDisk(CREATE, 'name: Theirs\n');
    await userEvent.keyboard('{Control>}s{/Control}');
    expect(await screen.findByRole('dialog')).toHaveAttribute('aria-modal', 'true');
    fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true });
    fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true, shiftKey: true });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(server.runStarts()).toHaveLength(0);
  });

  it('the menu offers this scenario, its folder, all and the ones that found something, with counts, by keyboard', async () => {
    const server = runServer({ results: { [CREATE]: 'finding', 'scenarios/hr-sync.yaml': 'setup', 'scenarios/roles-grant.yaml': 'passed' } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await ready();
    await openCreate();
    const more = screen.getByRole('button', { name: 'More ways to run' });
    more.focus();
    await userEvent.keyboard('{ArrowDown}');
    const menu = await screen.findByRole('menu');
    const items = within(menu).getAllByRole('menuitem');
    expect(items.map((item) => item.textContent)).toEqual([
      expect.stringContaining('Run this scenario'),
      expect.stringContaining('Run folder users/ (2)'),
      expect.stringContaining('Run all scenarios (5)'),
      expect.stringContaining('Run only the ones that found something (2)'),
    ]);
    expect(items[0]).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(items[1]).toHaveFocus();
    await userEvent.keyboard('{End}');
    expect(items[3]).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(more).toHaveFocus();

    await userEvent.click(more);
    await userEvent.click(screen.getByRole('menuitem', { name: /Run folder users/ }));
    await waitFor(() => expect(server.runStarts()).toHaveLength(1));
    expect(server.runStarts()[0].body).toMatchObject({ scenarios: { folder: 'scenarios/users' }, onlyFindings: false });
    finishRun();

    await userEvent.click(more);
    await userEvent.click(screen.getByRole('menuitem', { name: /only the ones that found something/ }));
    await waitFor(() => expect(server.runStarts()).toHaveLength(2));
    expect(server.runStarts()[1].body).toMatchObject({ scenarios: 'all', onlyFindings: true });
  });

  it('is blocked with the lab message when the lab misses something; the button opens the lab panel there', async () => {
    const server = runServer({ lab: { systems: [directory('ready'), hrDatabase('missing')] } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Lab: HR database needs a password' })).toBeInTheDocument());
    await openCreate();
    expect(runButton()).toBeDisabled();
    expect(runButton()).toHaveAttribute('title', 'Run is blocked: Lab: HR database needs a password');
    const run = screen.getByRole('complementary', { name: 'Run' });
    expect(within(run).getByText('Run is blocked: Lab: HR database needs a password')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true });
    await userEvent.click(within(run).getByRole('button', { name: 'Open the lab panel' }));
    const panel = await screen.findByRole('dialog', { name: /Lab connection/ });
    expect(within(panel).getByRole('tab', { selected: true })).toHaveTextContent('HR database');
    expect(server.runStarts()).toHaveLength(0);
  });

  it('a failed lab reload does not block Run but shows a warning line', async () => {
    let fail = false;
    const server = runServer({
      routes: {
        'GET /api/lab': () =>
          fail ? { status: 500, body: { key: 'runner.ui.lab.unreadable', message: 'config/application-lab.yml could not be read' } } : { body: { systems: [directory('ready')] } },
      },
    });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await ready();
    await openCreate();
    fail = true;
    await userEvent.click(screen.getByRole('button', { name: 'Lab ready' })); // opening the panel reads the lab again
    const run = screen.getByRole('complementary', { name: 'Run' });
    expect(
      await within(run).findByText(/The lab settings could not be read again \(config\/application-lab.yml could not be read\)/),
    ).toBeInTheDocument();
    expect(runButton()).toBeEnabled();
    await userEvent.click(runButton());
    await waitFor(() => expect(server.runStarts()).toHaveLength(1));
  });

  it('a 409 busy shows the server message and is not queued', async () => {
    const server = runServer({
      routes: { 'POST /api/runs': { status: 409, body: { key: 'runner.ui.run.busy', message: 'A run is already going on in this workbench.' } } },
    });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await ready();
    await openCreate();
    await userEvent.click(runButton());
    expect(await screen.findByRole('alert')).toHaveTextContent('A run is already going on in this workbench.');
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(server.runStarts()).toHaveLength(1);
    expect(runButton()).toBeEnabled();
  });

  it('is disabled in a second (read-only) tab, with the banner reason', async () => {
    runServer();
    const first = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await within(first.container).findByRole('button', { name: /^create-user\.yaml/ });
    const second = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await within(second.container).findByText('This workspace is open in another tab');
    await openCreate(second.container);
    const run = within(second.container).getByRole('button', { name: 'Run' });
    expect(run).toBeDisabled();
    expect(run).toHaveAttribute('title', 'This workspace is open in another tab');
    expect(within(second.container).getByRole('button', { name: 'More ways to run' })).toBeDisabled();
  });

  it('speaks Spanish', async () => {
    setLanguage('es', false);
    runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ejecutar' })).toHaveAttribute('title', 'Abre un escenario para ejecutarlo'));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Más formas de ejecutar' })).toBeEnabled());
    await userEvent.click(screen.getByRole('button', { name: 'Más formas de ejecutar' }));
    expect(screen.getByRole('menuitem', { name: /Ejecutar todos los escenarios \(5\)/ })).toBeInTheDocument();
  });
});
