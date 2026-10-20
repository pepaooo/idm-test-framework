import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from '../workbench/Workbench';
import { useWorkbench } from '../workbench/context';
import { setLanguage } from '../i18n';
import { emitServerEvent } from '../test/browserFakes';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';
import { directory, hrDatabase, labRoutes, rest } from '../test/labServer';
import type { LabState, LabView } from './types';

vi.mock('../workbench/monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('../workbench/monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

/** Shows what `useWorkbench().labState()` says, as Task 14's Run button reads it. */
function LabProbe() {
  const state: LabState = useWorkbench().labState();
  return <output data-testid="lab-state">{JSON.stringify(state)}</output>;
}

function start(views: Record<string, LabView | (() => LabView)>) {
  const server = workbenchServer({ files: { 'scenarios/a.yaml': 'name: A\n' }, routes: labRoutes({ views }) });
  render(<Workbench workspace={WORKSPACE} lockWait={10} runPanel={<LabProbe />} />);
  return server;
}

function chip() {
  return screen.getByRole('button', { name: /^Lab/ });
}

function labState(): LabState {
  return JSON.parse(screen.getByTestId('lab-state').textContent ?? '{}') as LabState;
}

afterEach(() => {
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

describe('Lab chip', () => {
  it('reads "Lab ready" with ✓ when every system the scenarios use is ready', async () => {
    start({ lab: { systems: [directory('ready'), { ...rest('ready') }] } });
    await waitFor(() => expect(chip()).toHaveTextContent('Lab ready'));
    expect(within(chip()).getByRole('img')).toHaveClass('badge-ok');
    expect(within(chip()).getByRole('img')).toHaveTextContent('✓');
    expect(labState()).toEqual({ state: 'ready', message: 'Lab ready' });
  });

  it('reads "Lab: 2 of 3 ready" with ! when some are not tested yet', async () => {
    const third = { ...directory('ready'), id: 'directory:hr', name: 'HR directory' };
    start({ lab: { systems: [directory('ready'), rest('untested'), third] } });
    await waitFor(() => expect(chip()).toHaveTextContent('Lab: 2 of 3 ready'));
    expect(within(chip()).getByRole('img')).toHaveClass('badge-warn');
    expect(within(chip()).getByRole('img')).toHaveTextContent('!');
    expect(labState()).toEqual({ state: 'untested', message: 'Lab: 2 of 3 ready' });
  });

  it('reads "Lab: HR database needs a password" with ✕ when a required field is missing', async () => {
    start({ lab: { systems: [directory('ready'), rest('untested'), hrDatabase('missing')] } });
    await waitFor(() => expect(chip()).toHaveTextContent('Lab: HR database needs a password'));
    expect(within(chip()).getByRole('img')).toHaveClass('badge-missing');
    expect(within(chip()).getByRole('img')).toHaveTextContent('✕');
    expect(labState()).toEqual({
      state: 'missing',
      missingSystemId: 'database:hr',
      message: 'Lab: HR database needs a password',
    });
  });

  it('says "an" before a vowel, and keeps a label that starts with an acronym as it is', async () => {
    const oauth = rest('missing');
    oauth.fields = oauth.fields.map((f) => (f.key === 'oauth-client-name' ? { ...f, required: true } : f));
    start({ lab: { systems: [oauth] } });
    await waitFor(() => expect(chip()).toHaveTextContent('Lab: IdM REST needs an OAuth client'));
  });

  it('leaves out systems no scenario uses (configured only)', async () => {
    const unused = { ...hrDatabase('missing'), usedByScenarios: [] };
    start({ lab: { systems: [directory('ready'), unused] } });
    await waitFor(() => expect(chip()).toHaveTextContent('Lab ready'));
  });

  it('switching the profile reloads the lab for that profile', async () => {
    const server = start({
      lab: { systems: [directory('ready')] },
      uat: { systems: [directory('ready'), hrDatabase('missing')] },
    });
    await waitFor(() => expect(chip()).toHaveTextContent('Lab ready'));
    await userEvent.selectOptions(screen.getByLabelText('Profile'), 'uat');
    await waitFor(() => expect(chip()).toHaveTextContent('Lab: HR database needs a password'));
    expect(server.calls()).toContain('GET /api/lab?profile=uat');
  });

  it('reloads when a config/*.yml file changes on disk', async () => {
    let systems = [directory('untested')];
    start({ lab: () => ({ systems }) });
    await waitFor(() => expect(chip()).toHaveTextContent('Lab: 0 of 1 ready'));
    systems = [directory('ready')];
    emitServerEvent('/api/files/events', { type: 'changed', path: 'config/application-lab.yml' });
    await waitFor(() => expect(chip()).toHaveTextContent('Lab ready'));
  });

  it('says why when the lab settings cannot be read, without blocking Run', async () => {
    workbenchServer({
      files: {},
      routes: {
        'GET /api/lab': { status: 400, body: { key: 'runner.ui.lab.settingsUnreadable', message: 'the lab settings could not be read: bad.yml' } },
      },
    });
    render(<Workbench workspace={WORKSPACE} lockWait={10} runPanel={<LabProbe />} />);
    await waitFor(() => expect(chip()).toHaveTextContent('Lab: settings could not be read'));
    expect(labState().state).toBe('untested');
  });

  it('speaks Spanish', async () => {
    setLanguage('es', false);
    start({ lab: { systems: [directory('ready'), rest('untested')] } });
    await waitFor(() => expect(screen.getByRole('button', { name: /^Lab/ })).toHaveTextContent('Lab: 1 de 2 listos'));
  });
});
