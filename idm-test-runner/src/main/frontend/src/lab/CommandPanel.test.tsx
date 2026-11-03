import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from '../workbench/Workbench';
import { setLanguage } from '../i18n';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';
import { directory, javaCommand, labRoutes, type LabRoutesOptions } from '../test/labServer';
import { commandsByScenario, commandsFor, deriveLabState, missingFor } from './labState';
import type { LabView } from './types';

vi.mock('../workbench/monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('../workbench/monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

const FILES = { 'scenarios/java.yaml': 'name: Java\n', 'scenarios/plain.yaml': 'name: Plain\n' };

function start(views: LabRoutesOptions['views'], extra: Partial<LabRoutesOptions> = {}) {
  const server = workbenchServer({ files: FILES, routes: labRoutes({ views, ...extra }) });
  render(<Workbench workspace={WORKSPACE} lockWait={10} />);
  return server;
}

async function openPanel() {
  const chip = await screen.findByRole('button', { name: /^Lab[: ]/ });
  await waitFor(() => expect(chip).not.toHaveTextContent('checking'));
  await userEvent.click(chip);
  return screen.findByRole('dialog', { name: /Lab connection/ });
}

const approvals = (server: ReturnType<typeof start>) => server.sent.filter((r) => r.url.startsWith('/api/lab/commands/approve'));

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

describe('Registered commands in the lab', () => {
  it('a command not approved here blocks the run that uses it, and only that run', () => {
    const view: LabView = { systems: [directory('ready'), javaCommand('not-approved')] };
    expect(deriveLabState(view, null)).toMatchObject({
      state: 'missing',
      missingSystemId: 'command:java-version',
      message: 'Lab: Command java-version is not approved on this machine',
    });
    expect(missingFor(view, ['java.yaml'])?.missingSystemId).toBe('command:java-version');
    expect(missingFor({ systems: [{ ...directory('ready'), usedByScenarios: ['plain.yaml'] }, javaCommand('not-approved')] }, ['plain.yaml'])).toBeNull();
    expect(deriveLabState({ systems: [javaCommand('not-registered')] }, null).message).toBe(
      'Lab: Command java-version is not registered in the settings',
    );
    expect(deriveLabState({ systems: [javaCommand('changed')] }, null).message).toBe(
      'Lab: Command java-version changed since it was approved on this machine',
    );
    expect(deriveLabState({ systems: [javaCommand('approved')] }, null).state).toBe('ready');
  });

  it('names the commands a run starts, and the scenarios that run them', () => {
    const view: LabView = { systems: [directory('ready'), javaCommand('approved', ['java.yaml', 'b.yaml'])] };
    expect(commandsFor(view, null)).toEqual(['java-version']);
    expect(commandsFor(view, ['java.yaml'])).toEqual(['java-version']);
    expect(commandsFor(view, ['plain.yaml'])).toEqual([]);
    expect(commandsFor(null, null)).toEqual([]);
    expect([...commandsByScenario(view)]).toEqual([
      ['java.yaml', ['java-version']],
      ['b.yaml', ['java-version']],
    ]);
  });

  it('shows the definition as written, read-only, and approves exactly the definition shown', async () => {
    let approved = false;
    const server = start(
      { lab: () => ({ systems: [javaCommand(approved ? 'approved' : 'not-approved')] }) },
      {
        onApprove: () => {
          approved = true;
          return { body: { systems: [javaCommand('approved')] } };
        },
      },
    );
    const panel = await openPanel();
    const form = within(panel).getByRole('tabpanel', { name: 'Command java-version' });
    expect(form).toHaveTextContent('Not approved on this machine');
    expect(form).toHaveTextContent('/opt/java/bin/java');
    expect(form).toHaveTextContent('-version "two words"');
    expect(form).toHaveTextContent('Parameter driver');
    expect(form).toHaveTextContent('[a-z]+');
    expect(form).toHaveTextContent('CMD_TOKEN');
    expect(form).toHaveTextContent('the workspace folder');
    expect(form).toHaveTextContent('60 s · 65536 bytes of output · exit codes 0');
    expect(within(form).queryByRole('textbox')).not.toBeInTheDocument();
    expect(within(form).queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
    expect(within(form).queryByRole('button', { name: 'Delete connection' })).not.toBeInTheDocument();

    await userEvent.click(within(form).getByRole('button', { name: 'Approve on this machine' }));
    await waitFor(() => expect(approvals(server)).toHaveLength(1));
    expect(approvals(server)[0].url).toBe('/api/lab/commands/approve?profile=lab');
    expect(approvals(server)[0].body).toEqual({ name: 'java-version', hash: 'a'.repeat(64) });
    await waitFor(() => expect(form).toHaveTextContent('Approved on this machine'));
    expect(within(form).queryByRole('button', { name: 'Approve on this machine' })).not.toBeInTheDocument();
  });

  it('says why when the definition changed since it was shown, and reads the lab again', async () => {
    const server = start(
      { lab: { systems: [javaCommand('changed')] } },
      {
        onApprove: () => ({
          status: 409,
          body: { key: 'ui.lab.command.changed', message: 'The definition of command java-version changed since the panel showed it' },
        }),
      },
    );
    const panel = await openPanel();
    const form = within(panel).getByRole('tabpanel', { name: 'Command java-version' });
    expect(form).toHaveTextContent('Changed since it was approved on this machine');
    const reads = () => server.sent.filter((r) => r.method === 'GET' && r.url.startsWith('/api/lab')).length;
    const before = reads();
    await userEvent.click(within(form).getByRole('button', { name: 'Approve on this machine' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('changed since the panel showed it');
    await waitFor(() => expect(reads()).toBeGreaterThan(before));
  });

  it('a command no settings register has nothing to approve', async () => {
    start({ lab: { systems: [javaCommand('not-registered')] } });
    const panel = await openPanel();
    const form = within(panel).getByRole('tabpanel', { name: 'Command java-version' });
    expect(form).toHaveTextContent('the settings do not register it under idm.test.commands');
    expect(within(form).queryByRole('button', { name: 'Approve on this machine' })).not.toBeInTheDocument();
  });

  it('marks the scenarios that run commands in the tree, and says before a run which commands it starts', async () => {
    start({ lab: { systems: [directory('ready'), javaCommand('approved')] } });
    expect(await screen.findByRole('img', { name: 'runs commands: java-version' })).toBeInTheDocument();
    const plain = screen.getByRole('button', { name: /^plain\.yaml/ });
    expect(within(plain).queryByRole('img', { name: /runs commands/ })).not.toBeInTheDocument();
    expect(await screen.findByText('This run starts commands on this machine: java-version')).toBeInTheDocument();
  });
});
