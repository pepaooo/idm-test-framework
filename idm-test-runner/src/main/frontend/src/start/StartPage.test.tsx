import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StartPage } from './StartPage';
import { App } from '../App';
import { setLanguage } from '../i18n';
import { fakeServer } from '../test/fakeServer';

const RECENT = [
  {
    name: 'lab-tests',
    path: '/home/ana/Work/acme/lab-tests',
    exists: true,
    lastRun: { runId: '20261020-143200-lab', at: '2026-10-20T14:32:05Z', profile: 'lab', passed: 3, findings: 2, setupProblems: 0 },
  },
  {
    name: 'acme-hr-onboarding',
    path: '/home/ana/Work/acme/hr-onboarding',
    exists: true,
    lastRun: { runId: '20261019-091000-uat', at: '2026-10-19T09:10:00Z', profile: 'uat', passed: 12, findings: 0, setupProblems: 0 },
  },
  { name: 'roles-regression', path: '/home/ana/Projects/roles-regression', exists: true, lastRun: null },
  { name: 'old-demo', path: '/home/ana/Desktop/old-demo', exists: false, lastRun: null },
];

const HEALTH = { version: '1.0.0', git: true, locale: 'en', workspace: null };

function renderStart(git = true) {
  const onOpened = vi.fn();
  render(<StartPage git={git} onOpened={onOpened} />);
  return onOpened;
}

describe('StartPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-20T16:00:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    setLanguage('en', false);
  });

  it('lists recent workspaces with their last result', async () => {
    fakeServer({ 'GET /api/workspaces/recent': { body: RECENT } });
    renderStart();

    const lab = await screen.findByRole('listitem', { name: /lab-tests/ });
    expect(within(lab).getByText('/home/ana/Work/acme/lab-tests')).toBeInTheDocument();
    expect(within(lab).getByTestId('last-result')).toHaveTextContent(/^✕ 2 findings · today 14:32 · lab$/);
    expect(within(lab).getByRole('img', { name: 'Finding' })).toBeInTheDocument();

    const hr = screen.getByRole('listitem', { name: /acme-hr-onboarding/ });
    expect(within(hr).getByTestId('last-result')).toHaveTextContent(/^✓ 12 passed · yesterday · uat$/);

    const roles = screen.getByRole('listitem', { name: /roles-regression/ });
    expect(within(roles).getByTestId('last-result')).toHaveTextContent(/^never run$/);
  });

  it('a setup problem in the last run is shown as one, in red, not as a finding', async () => {
    fakeServer({
      'GET /api/workspaces/recent': {
        body: [{ ...RECENT[0], lastRun: { ...RECENT[0].lastRun!, findings: 1, setupProblems: 1 } }],
      },
    });
    renderStart();
    const lab = await screen.findByRole('listitem', { name: /lab-tests/ });
    expect(within(lab).getByTestId('last-result')).toHaveTextContent(
      /^✕ 1 finding, 1 setup problem · today 14:32 · lab$/,
    );
    expect(within(lab).getByRole('img', { name: 'Setup problem' })).toBeInTheDocument();
  });

  it('greys a folder that no longer exists and offers to remove it from the list', async () => {
    const server = fakeServer({
      'GET /api/workspaces/recent': { body: RECENT },
      'DELETE /api/workspaces/recent': { status: 204 },
    });
    renderStart();

    const old = await screen.findByRole('listitem', { name: /old-demo/ });
    expect(old).toHaveClass('missing');
    expect(within(old).getByText('folder not found')).toBeInTheDocument();
    expect(within(old).queryByRole('button', { name: /Open old-demo/ })).not.toBeInTheDocument();

    await userEvent.click(within(old).getByRole('button', { name: 'Remove from list' }));
    await waitFor(() => expect(screen.queryByRole('listitem', { name: /old-demo/ })).not.toBeInTheDocument());
    expect(server.calls()).toContain('DELETE /api/workspaces/recent?path=%2Fhome%2Fana%2FDesktop%2Fold-demo');
  });

  it('opens a recent workspace when clicked', async () => {
    const server = fakeServer({
      'GET /api/workspaces/recent': { body: RECENT },
      'POST /api/workspaces/open': { body: { path: '/home/ana/Work/acme/lab-tests', profiles: ['lab', 'uat'] } },
    });
    const onOpened = renderStart();

    await userEvent.click(await screen.findByRole('button', { name: 'Open lab-tests' }));
    await waitFor(() =>
      expect(onOpened).toHaveBeenCalledWith({ path: '/home/ana/Work/acme/lab-tests', name: 'lab-tests', profiles: ['lab', 'uat'] }, undefined),
    );
    expect(server.sent.find((r) => r.url === '/api/workspaces/open')?.body).toEqual({ path: '/home/ana/Work/acme/lab-tests' });
  });

  it('creates a workspace with name, where, example and git, then opens it', async () => {
    const server = fakeServer({
      'GET /api/workspaces/recent': { body: [] },
      'POST /api/workspaces': { body: { path: '/home/ana/Work/new-lab-tests', warning: null } },
      'POST /api/workspaces/open': { body: { path: '/home/ana/Work/new-lab-tests', profiles: ['lab'] } },
    });
    const onOpened = renderStart();

    const create = screen.getByRole('form', { name: 'Create a workspace' });
    await userEvent.type(within(create).getByLabelText('Name'), 'new-lab-tests');
    await userEvent.type(within(create).getByLabelText('Where'), '/home/ana/Work');
    expect(within(create).getByLabelText('Include the example scenario')).toBeChecked();
    await userEvent.click(within(create).getByLabelText('Include the example scenario'));
    await userEvent.click(within(create).getByLabelText('Start a Git repository here'));
    await userEvent.click(within(create).getByRole('button', { name: 'Create workspace' }));

    await waitFor(() => expect(onOpened).toHaveBeenCalled());
    expect(server.sent.find((r) => r.url === '/api/workspaces')?.body).toEqual({
      name: 'new-lab-tests',
      parent: '/home/ana/Work',
      includeExample: false,
      gitInit: true,
    });
    expect(onOpened).toHaveBeenCalledWith({ path: '/home/ana/Work/new-lab-tests', name: 'new-lab-tests', profiles: ['lab'] }, undefined);
  });

  it('passes on the warning when git init did not work', async () => {
    const warning = { key: 'ui.workspace.gitFailed', message: 'git init did not work in /x' };
    fakeServer({
      'GET /api/workspaces/recent': { body: [] },
      'POST /api/workspaces': { body: { path: '/x', warning } },
      'POST /api/workspaces/open': { body: { path: '/x', profiles: [] } },
    });
    const onOpened = renderStart();
    const create = screen.getByRole('form', { name: 'Create a workspace' });
    await userEvent.type(within(create).getByLabelText('Name'), 'x');
    await userEvent.type(within(create).getByLabelText('Where'), '/');
    await userEvent.click(within(create).getByRole('button', { name: 'Create workspace' }));
    await waitFor(() => expect(onOpened).toHaveBeenCalledWith({ path: '/x', name: 'x', profiles: [] }, warning));
  });

  it('hides "Start a Git repository here" when git is not installed', async () => {
    const server = fakeServer({
      'GET /api/workspaces/recent': { body: [] },
      'POST /api/workspaces': { body: { path: '/w/n', warning: null } },
      'POST /api/workspaces/open': { body: { path: '/w/n', profiles: [] } },
    });
    const onOpened = renderStart(false);
    const create = screen.getByRole('form', { name: 'Create a workspace' });
    expect(within(create).queryByLabelText('Start a Git repository here')).not.toBeInTheDocument();
    await userEvent.type(within(create).getByLabelText('Name'), 'n');
    await userEvent.type(within(create).getByLabelText('Where'), '/w');
    await userEvent.click(within(create).getByRole('button', { name: 'Create workspace' }));
    await waitFor(() => expect(onOpened).toHaveBeenCalled());
    expect(server.sent.find((r) => r.url === '/api/workspaces')?.body).toMatchObject({ gitInit: false });
  });

  it("shows the server's message when creating fails", async () => {
    fakeServer({
      'GET /api/workspaces/recent': { body: [] },
      'POST /api/workspaces': { status: 400, body: { key: 'ui.workspace.notEmpty', message: 'The folder /w/n is not empty.' } },
    });
    const onOpened = renderStart();
    const create = screen.getByRole('form', { name: 'Create a workspace' });
    await userEvent.type(within(create).getByLabelText('Name'), 'n');
    await userEvent.type(within(create).getByLabelText('Where'), '/w');
    await userEvent.click(within(create).getByRole('button', { name: 'Create workspace' }));
    expect(await within(create).findByRole('alert')).toHaveTextContent('The folder /w/n is not empty.');
    expect(onOpened).not.toHaveBeenCalled();
  });

  it('opens a typed folder', async () => {
    const server = fakeServer({
      'GET /api/workspaces/recent': { body: [] },
      'POST /api/workspaces/open': { body: { path: '/home/ana/ws', profiles: ['lab'] } },
    });
    const onOpened = renderStart();
    const open = screen.getByRole('form', { name: 'Open a workspace' });
    await userEvent.type(within(open).getByLabelText('Folder'), '/home/ana/ws{Enter}');
    await waitFor(() => expect(onOpened).toHaveBeenCalledWith({ path: '/home/ana/ws', name: 'ws', profiles: ['lab'] }, undefined));
    expect(server.sent.find((r) => r.url === '/api/workspaces/open')?.body).toEqual({ path: '/home/ana/ws' });
  });

  it("shows the server's not-a-workspace message, sent in the page's language", async () => {
    const server = fakeServer({
      'GET /api/workspaces/recent': { body: [] },
      'POST /api/workspaces/open': {
        status: 400,
        body: { key: 'ui.workspace.notWorkspace', message: '/tmp is not a workspace: it has no scenarios/ folder.' },
      },
    });
    const onOpened = renderStart();
    const open = screen.getByRole('form', { name: 'Open a workspace' });
    await userEvent.type(within(open).getByLabelText('Folder'), '/tmp');
    await userEvent.click(within(open).getByRole('button', { name: 'Open' }));
    expect(await within(open).findByRole('alert')).toHaveTextContent('/tmp is not a workspace: it has no scenarios/ folder.');
    expect(onOpened).not.toHaveBeenCalled();
    const sent = server.sent.find((r) => r.url === '/api/workspaces/open')!;
    expect(sent.headers['accept-language']).toBe('en');
    expect(sent.credentials).toBe('same-origin');
  });

  it('Browse… picks the folder to open from the folder picker', async () => {
    fakeServer({
      'GET /api/workspaces/recent': { body: [] },
      'GET /api/folders': (request) =>
        request.url === '/api/folders'
          ? {
              body: {
                path: '/home/ana',
                parent: '/home',
                entries: [
                  { name: 'Work', path: '/home/ana/Work', isWorkspace: false },
                  { name: 'lab-tests', path: '/home/ana/lab-tests', isWorkspace: true },
                ],
              },
            }
          : { body: { path: '/home/ana/Work', parent: '/home/ana', entries: [] } },
    });
    renderStart();
    const open = screen.getByRole('form', { name: 'Open a workspace' });
    await userEvent.click(within(open).getByRole('button', { name: 'Browse…' }));

    const picker = await screen.findByRole('dialog', { name: 'Choose a folder' });
    const ws = await within(picker).findByRole('button', { name: /lab-tests/ });
    expect(ws).toHaveTextContent('workspace');
    await userEvent.click(within(picker).getByRole('button', { name: /^Work/ }));
    expect(await within(picker).findByText('/home/ana/Work')).toBeInTheDocument();
    await userEvent.click(within(picker).getByRole('button', { name: 'Choose this folder' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(within(open).getByLabelText('Folder')).toHaveValue('/home/ana/Work');
  });
});

describe('App', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setLanguage('en', false);
  });

  it('the language switch changes the visible text to Spanish, and back', async () => {
    fakeServer({ 'GET /api/health': { body: HEALTH }, 'GET /api/workspaces/recent': { body: [] } });
    render(<App />);
    expect(await screen.findByRole('heading', { name: 'Recent workspaces' })).toBeInTheDocument();
    expect(screen.getByText('idmtest 1.0.0 · listening on this computer only')).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText('Language'), 'es');
    expect(screen.getByRole('heading', { name: 'Espacios de trabajo recientes' })).toBeInTheDocument();
    expect(screen.getByRole('form', { name: 'Crear un espacio de trabajo' })).toBeInTheDocument();
    expect(screen.getByText('idmtest 1.0.0 · escuchando solo en este equipo')).toBeInTheDocument();
    expect(window.localStorage.getItem('idmtest.lang')).toBe('es');

    await userEvent.selectOptions(screen.getByLabelText('Idioma'), 'en');
    expect(screen.getByRole('heading', { name: 'Recent workspaces' })).toBeInTheDocument();
  });

  it("starts in the server's language when none was chosen", async () => {
    fakeServer({ 'GET /api/health': { body: { ...HEALTH, locale: 'es' } }, 'GET /api/workspaces/recent': { body: [] } });
    render(<App />);
    expect(await screen.findByRole('heading', { name: 'Espacios de trabajo recientes' })).toBeInTheDocument();
  });

  it('after opening a workspace, shows it instead of the start page', async () => {
    fakeServer({
      'GET /api/health': { body: HEALTH },
      'GET /api/workspaces/recent': { body: [] },
      'POST /api/workspaces/open': { body: { path: '/home/ana/ws', profiles: ['lab'] } },
    });
    render(<App />);
    const open = await screen.findByRole('form', { name: 'Open a workspace' });
    await userEvent.type(within(open).getByLabelText('Folder'), '/home/ana/ws{Enter}');
    expect(await screen.findByRole('heading', { name: 'ws' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Workspace files' })).toBeInTheDocument();
    expect(screen.getByLabelText('Profile')).toHaveValue('lab');
    expect(screen.queryByRole('heading', { name: 'Recent workspaces' })).not.toBeInTheDocument();
  });

  it('started with --workspace, goes straight into that workspace', async () => {
    const server = fakeServer({
      'GET /api/health': { body: { ...HEALTH, workspace: '/home/ana/lab-tests' } },
      'POST /api/workspaces/open': { body: { path: '/home/ana/lab-tests', profiles: [] } },
    });
    render(<App />);
    expect(await screen.findByRole('heading', { name: 'lab-tests' })).toBeInTheDocument();
    expect(server.calls()).not.toContain('GET /api/workspaces/recent');
  });

  it('when the --workspace folder cannot be opened, says why and shows the start page', async () => {
    fakeServer({
      'GET /api/health': { body: { ...HEALTH, workspace: '/home/ana/nothing' } },
      'POST /api/workspaces/open': {
        status: 400,
        body: { key: 'ui.workspace.notWorkspace', message: 'not a workspace: /home/ana/nothing has no scenarios/ folder' },
      },
      'GET /api/workspaces/recent': { body: [] },
    });
    render(<App />);
    expect(await screen.findByRole('heading', { name: 'Recent workspaces' })).toBeInTheDocument();
    expect(screen.getByText(/not a workspace: \/home\/ana\/nothing/)).toBeInTheDocument();
  });

  it('the theme switch flips light and dark and remembers it', async () => {
    fakeServer({ 'GET /api/health': { body: HEALTH }, 'GET /api/workspaces/recent': { body: [] } });
    render(<App />);
    const toggle = await screen.findByRole('button', { name: 'Switch to dark mode' });
    expect(toggle).toHaveTextContent('Light');
    await userEvent.click(toggle);
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(window.localStorage.getItem('idmtest.theme')).toBe('dark');
    expect(screen.getByRole('button', { name: 'Switch to light mode' })).toHaveTextContent('Dark');
  });
});
