import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StartPage } from './StartPage';
import { workspaceSwitch } from '../api/client';
import { setLanguage } from '../i18n';
import { fakeServer, type Answer } from '../test/fakeServer';

const LAB = '/home/ana/Work/acme/lab-tests';
const HR = '/home/ana/Work/acme/hr-onboarding';

const RECENT = [
  { name: 'lab-tests', path: LAB, exists: true, lastRun: null },
  { name: 'acme-hr-onboarding', path: HR, exists: true, lastRun: null },
];

const RUN_GOING: Answer = {
  status: 409,
  body: {
    key: 'ui.workspace.runGoing',
    message: 'A run is going in lab-tests. Wait for it to finish, or stop it.',
    runningWorkspace: { path: LAB, name: 'lab-tests' },
  },
};

const OPENED_HR: Answer = { body: { path: HR, profiles: ['uat'] } };

/** Answers each open in turn from `answers`, the last one from then on. */
function inTurn(answers: Answer[]) {
  let index = 0;
  return () => answers[Math.min(index++, answers.length - 1)];
}

function renderStart() {
  const onOpened = vi.fn();
  render(<StartPage git onOpened={onOpened} />);
  return onOpened;
}

/** Runs the timers due in the next `ms` and the promises they settle. */
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

/** Clicks Open acme-hr-onboarding and waits for the notice (real timers). */
async function openHrAndGetNotice() {
  await userEvent.click(await screen.findByRole('button', { name: 'Open acme-hr-onboarding' }));
  return screen.findByRole('alert');
}

describe('the run-going notice on the start page', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    setLanguage('en', false);
  });

  it('shows the run-going notice', async () => {
    fakeServer({ 'GET /api/workspaces/recent': { body: RECENT }, 'POST /api/workspaces/open': RUN_GOING });
    const onOpened = renderStart();

    const notice = await openHrAndGetNotice();
    expect(notice).toHaveTextContent('A run is going in lab-tests. Wait for it to finish, or stop it.');
    expect(within(notice).getByRole('button', { name: 'Stop the run' })).toBeInTheDocument();
    expect(within(notice).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(onOpened).not.toHaveBeenCalled();
    expect(workspaceSwitch.get()).toBeNull();
  });

  it('cancel closes it', async () => {
    const server = fakeServer({ 'GET /api/workspaces/recent': { body: RECENT }, 'POST /api/workspaces/open': RUN_GOING });
    renderStart();
    const notice = await openHrAndGetNotice();
    const before = server.sent.length;

    await userEvent.click(within(notice).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(server.sent).toHaveLength(before);
  });

  it('asks before stopping', async () => {
    const server = fakeServer({ 'GET /api/workspaces/recent': { body: RECENT }, 'POST /api/workspaces/open': RUN_GOING });
    renderStart();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const notice = await openHrAndGetNotice();
    const before = server.sent.length;

    await userEvent.click(within(notice).getByRole('button', { name: 'Stop the run' }));
    expect(confirm).toHaveBeenCalledWith('Stop the run in lab-tests? Its cleanup will not run.');
    expect(server.sent).toHaveLength(before);
    expect(within(notice).getByRole('button', { name: 'Stop the run' })).toBeInTheDocument();
  });

  it("stops the run with the running workspace's header", async () => {
    const server = fakeServer({
      'GET /api/workspaces/recent': { body: RECENT },
      'POST /api/workspaces/open': RUN_GOING,
      'POST /api/runs/current/stop': { status: 202, body: { runId: 'r1' } },
    });
    renderStart();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const notice = await openHrAndGetNotice();

    await userEvent.click(within(notice).getByRole('button', { name: 'Stop the run' }));
    const stop = server.sent.find((r) => r.url === '/api/runs/current/stop');
    expect(stop?.method).toBe('POST');
    expect(stop?.headers['x-idmtest-workspace']).toBe(encodeURIComponent(LAB));
    expect(await screen.findByText('Stopping the run…')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Stop the run' })).not.toBeInTheDocument();
  });

  it('opens once the run has ended', async () => {
    const server = fakeServer({
      'GET /api/workspaces/recent': { body: RECENT },
      'POST /api/workspaces/open': inTurn([RUN_GOING, RUN_GOING, OPENED_HR]),
      'POST /api/runs/current/stop': { status: 202, body: { runId: 'r1' } },
    });
    const onOpened = renderStart();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const notice = await openHrAndGetNotice();
    const opens = () => server.sent.filter((r) => r.url === '/api/workspaces/open').length;

    vi.useFakeTimers();
    fireEvent.click(within(notice).getByRole('button', { name: 'Stop the run' }));
    await advance(0);
    expect(server.calls()).toContain('POST /api/runs/current/stop');
    expect(opens()).toBe(1);
    await advance(500);
    expect(opens()).toBe(2); // first retry: still going
    expect(onOpened).not.toHaveBeenCalled();
    await advance(500);
    expect(opens()).toBe(3); // second retry: opens
    expect(onOpened).toHaveBeenCalledTimes(1);
    expect(onOpened).toHaveBeenCalledWith({ path: HR, name: 'hr-onboarding', profiles: ['uat'] }, undefined);
    expect(server.sent.filter((r) => r.url === '/api/workspaces/open').every((r) => (r.body as { path: string }).path === HR)).toBe(true);
    await advance(5000);
    expect(opens()).toBe(3);
  });

  it('opens at once when the run had already ended before the stop', async () => {
    const server = fakeServer({
      'GET /api/workspaces/recent': { body: RECENT },
      'POST /api/workspaces/open': inTurn([RUN_GOING, OPENED_HR]),
      'POST /api/runs/current/stop': { status: 409, body: { key: 'ui.run.none', message: 'No run is going.' } },
    });
    const onOpened = renderStart();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const notice = await openHrAndGetNotice();

    vi.useFakeTimers();
    fireEvent.click(within(notice).getByRole('button', { name: 'Stop the run' }));
    await advance(0);
    expect(server.sent.filter((r) => r.url === '/api/workspaces/open')).toHaveLength(2);
    expect(onOpened).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('No run is going.')).not.toBeInTheDocument();
  });

  it('gives up waiting after 30 s', async () => {
    const server = fakeServer({
      'GET /api/workspaces/recent': { body: RECENT },
      'POST /api/workspaces/open': RUN_GOING,
      'POST /api/runs/current/stop': { status: 202, body: { runId: 'r1' } },
    });
    const onOpened = renderStart();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const notice = await openHrAndGetNotice();
    const opens = () => server.sent.filter((r) => r.url === '/api/workspaces/open').length;

    vi.useFakeTimers();
    fireEvent.click(within(notice).getByRole('button', { name: 'Stop the run' }));
    await advance(29_000);
    expect(screen.getByText('Stopping the run…')).toBeInTheDocument();
    await advance(1_500);
    const after = opens();
    expect(screen.queryByText('Stopping the run…')).not.toBeInTheDocument();
    const back = screen.getByRole('alert');
    expect(back).toHaveTextContent('A run is going in lab-tests. Wait for it to finish, or stop it.');
    expect(within(back).getByRole('button', { name: 'Stop the run' })).toBeInTheDocument();
    expect(within(back).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    await advance(5_000);
    expect(opens()).toBe(after);
    expect(onOpened).not.toHaveBeenCalled();
  });

  it('create during a run: the folder is created, and it opens after the stop', async () => {
    const server = fakeServer({
      'GET /api/workspaces/recent': { body: [] },
      'POST /api/workspaces': { body: { path: '/home/ana/Work/new-lab', warning: null } },
      'POST /api/workspaces/open': inTurn([RUN_GOING, { body: { path: '/home/ana/Work/new-lab', profiles: ['lab'] } }]),
      'POST /api/runs/current/stop': { status: 202, body: { runId: 'r1' } },
    });
    const onOpened = renderStart();
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    const create = screen.getByRole('form', { name: 'Create a workspace' });
    await userEvent.type(within(create).getByLabelText('Name'), 'new-lab');
    await userEvent.type(within(create).getByLabelText('Where'), '/home/ana/Work');
    await userEvent.click(within(create).getByRole('button', { name: 'Create workspace' }));
    const notice = await within(create).findByRole('alert');
    expect(notice).toHaveTextContent('A run is going in lab-tests.');
    expect(screen.getAllByRole('alert')).toHaveLength(1);

    vi.useFakeTimers();
    fireEvent.click(within(notice).getByRole('button', { name: 'Stop the run' }));
    await advance(500);
    expect(onOpened).toHaveBeenCalledWith({ path: '/home/ana/Work/new-lab', name: 'new-lab', profiles: ['lab'] }, undefined);
    expect(server.calls().filter((c) => c === 'POST /api/workspaces')).toHaveLength(1);
  });

  it('open by path during a run shows the notice in that form', async () => {
    fakeServer({ 'GET /api/workspaces/recent': { body: [] }, 'POST /api/workspaces/open': RUN_GOING });
    renderStart();
    const open = screen.getByRole('form', { name: 'Open a workspace' });
    await userEvent.type(within(open).getByLabelText('Folder'), '/home/ana/ws{Enter}');
    const notice = await within(open).findByRole('alert');
    expect(within(notice).getByRole('button', { name: 'Stop the run' })).toBeInTheDocument();
    await userEvent.click(within(notice).getByRole('button', { name: 'Cancel' }));
    expect(within(open).getByRole('button', { name: 'Open' })).toBeEnabled();
  });

  it('only one notice at a time', async () => {
    fakeServer({ 'GET /api/workspaces/recent': { body: RECENT }, 'POST /api/workspaces/open': RUN_GOING });
    renderStart();
    await openHrAndGetNotice();
    const open = screen.getByRole('form', { name: 'Open a workspace' });
    await userEvent.type(within(open).getByLabelText('Folder'), '/home/ana/ws{Enter}');
    await within(open).findByRole('alert');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  it('in Spanish', async () => {
    setLanguage('es', false);
    fakeServer({
      'GET /api/workspaces/recent': { body: RECENT },
      'POST /api/workspaces/open': {
        status: 409,
        body: {
          ...(RUN_GOING.body as object),
          message: 'Hay una ejecución en curso en lab-tests. Espere a que termine o deténgala.',
        },
      },
      'POST /api/runs/current/stop': { status: 202, body: { runId: 'r1' } },
    });
    renderStart();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    await userEvent.click(await screen.findByRole('button', { name: 'Abrir acme-hr-onboarding' }));
    const notice = await screen.findByRole('alert');
    expect(notice).toHaveTextContent('Hay una ejecución en curso en lab-tests. Espere a que termine o deténgala.');
    expect(within(notice).getByRole('button', { name: 'Cancelar' })).toBeInTheDocument();
    await userEvent.click(within(notice).getByRole('button', { name: 'Detener la ejecución' }));
    expect(confirm).toHaveBeenCalledWith('¿Detener la ejecución en lab-tests? Su limpieza no se ejecutará.');
    expect(await screen.findByText('Deteniendo la ejecución…')).toBeInTheDocument();
  });
});
