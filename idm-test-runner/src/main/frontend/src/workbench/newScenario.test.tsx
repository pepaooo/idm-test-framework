import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from './Workbench';
import { scenarioTemplate } from './scenarioTemplate';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';
import { setLanguage } from '../i18n';

vi.mock('./monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('./monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

const FILES = {
  'scenarios/a.yaml': 'name: A\n',
  'scenarios/b.yaml': 'name: B\n',
  'scenarios/users/create-user.yaml': 'name: create\n',
  'scenarios/roles/grant.yaml': 'name: grant\n',
};

afterEach(() => {
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

async function start() {
  const server = workbenchServer({ files: { ...FILES } });
  render(<Workbench workspace={WORKSPACE} lockWait={10} />);
  await screen.findByRole('button', { name: 'a.yaml' });
  // the tab holds the workspace: the tree's actions are enabled
  await waitFor(() => expect(screen.getByRole('button', { name: 'New scenario' })).toBeEnabled());
  return server;
}

async function open(name: string, path: string) {
  await userEvent.click(screen.getByRole('button', { name }));
  const editor = await screen.findByLabelText(`Edit ${path}`);
  await waitFor(() => expect(editor).not.toHaveAttribute('readonly'));
  return editor;
}

async function newScenario(name: string) {
  await userEvent.click(screen.getByRole('button', { name: 'New scenario' }));
  const dialog = screen.getByRole('dialog', { name: 'New scenario' });
  await userEvent.type(within(dialog).getByLabelText('Name of the new scenario'), `${name}{Enter}`);
}

describe('New scenario', () => {
  it('creates the file from the template in the selected folder, refreshes the tree and opens it', async () => {
    const server = await start();
    await userEvent.click(screen.getByRole('button', { name: 'users' }));
    const treeReads = server.sent.filter((r) => r.url === '/api/files/tree').length;

    await newScenario('disable-user');

    const editor = await screen.findByLabelText('Edit scenarios/users/disable-user.yaml');
    expect(server.creates().map((r) => r.body)).toEqual([
      { path: 'scenarios/users/disable-user.yaml', content: scenarioTemplate('disable-user') },
    ]);
    await waitFor(() => expect(editor).toHaveValue(scenarioTemplate('disable-user')));
    expect(server.sent.filter((r) => r.url === '/api/files/tree').length).toBeGreaterThan(treeReads);
    expect(await screen.findByRole('button', { name: 'disable-user.yaml' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it("with no folder selected it goes in the open scenario's folder", async () => {
    const server = await start();
    await open('grant.yaml', 'scenarios/roles/grant.yaml');

    await newScenario('revoke.yml');

    await screen.findByLabelText('Edit scenarios/roles/revoke.yml');
    expect(server.creates().map((r) => (r.body as { path: string }).path)).toEqual(['scenarios/roles/revoke.yml']);
    expect((server.creates()[0].body as { content: string }).content).toBe(scenarioTemplate('revoke'));
  });

  it('with nothing open and no folder selected it goes in scenarios/', async () => {
    const server = await start();
    await newScenario('first');
    await screen.findByLabelText('Edit scenarios/first.yaml');
    expect(server.creates().map((r) => (r.body as { path: string }).path)).toEqual(['scenarios/first.yaml']);
  });

  it('a folder outside scenarios/ is never the target', async () => {
    const server = workbenchServer({ files: { ...FILES, 'config/application-lab.yml': 'idm: {}\n' } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'New scenario' })).toBeEnabled());
    await userEvent.click(await screen.findByRole('button', { name: 'config' }));
    await newScenario('x');
    await screen.findByLabelText('Edit scenarios/x.yaml');
    expect(server.creates().map((r) => (r.body as { path: string }).path)).toEqual(['scenarios/x.yaml']);
  });

  it('a name that exists is refused with the server message; the dialog stays and a second name succeeds', async () => {
    const server = await start();
    await newScenario('a');

    const dialog = screen.getByRole('dialog', { name: 'New scenario' });
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('scenarios/a.yaml already exists; choose another name.');
    expect(server.disk.get('scenarios/a.yaml')?.content).toBe('name: A\n');

    const input = within(dialog).getByLabelText('Name of the new scenario');
    await userEvent.clear(input);
    await userEvent.type(input, 'a2');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    await screen.findByLabelText('Edit scenarios/a2.yaml');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('Duplicate', () => {
  it('copies what the editor shows, does not save the original, and opens the copy', async () => {
    const server = await start();
    const editor = await open('a.yaml', 'scenarios/a.yaml');
    await waitFor(() => expect(editor).toHaveValue('name: A\n'));
    fireEvent.change(editor, { target: { value: 'name: A edited\n' } });

    await userEvent.click(screen.getByRole('button', { name: 'Duplicate a.yaml' }));
    const dialog = screen.getByRole('dialog', { name: 'Duplicate' });
    const input = within(dialog).getByLabelText('Name of the copy');
    expect(input).toHaveValue('a-copy.yaml');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }));

    const copy = await screen.findByLabelText('Edit scenarios/a-copy.yaml');
    expect(server.creates().map((r) => r.body)).toEqual([{ path: 'scenarios/a-copy.yaml', content: 'name: A edited\n' }]);
    await waitFor(() => expect(copy).toHaveValue('name: A edited\n'));
    expect(server.puts()).toHaveLength(0);
    expect(server.disk.get('scenarios/a.yaml')?.content).toBe('name: A\n');
    const original = screen.getByRole('button', { name: /^a\.yaml/ });
    expect(within(original).getByRole('img', { name: 'unsaved' })).toBeInTheDocument();
  });

  it('of a file that is not open copies its content from the server, in its own folder', async () => {
    const server = await start();
    await userEvent.click(screen.getByRole('button', { name: 'Duplicate create-user.yaml' }));
    const dialog = screen.getByRole('dialog', { name: 'Duplicate' });
    expect(within(dialog).getByLabelText('Name of the copy')).toHaveValue('create-user-copy.yaml');
    await userEvent.type(within(dialog).getByLabelText('Name of the copy'), '{Enter}');

    await screen.findByLabelText('Edit scenarios/users/create-user-copy.yaml');
    expect(server.creates().map((r) => r.body)).toEqual([
      { path: 'scenarios/users/create-user-copy.yaml', content: 'name: create\n' },
    ]);
    expect(server.calls()).toContain('GET /api/files?path=scenarios%2Fusers%2Fcreate-user.yaml');
  });

  it('a copy name that exists is refused; the dialog stays open and a second name succeeds', async () => {
    const server = workbenchServer({ files: { ...FILES, 'scenarios/b-copy.yaml': 'name: old copy\n' } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'New scenario' })).toBeEnabled());
    await userEvent.click(await screen.findByRole('button', { name: 'Duplicate b.yaml' }));
    const dialog = screen.getByRole('dialog', { name: 'Duplicate' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('scenarios/b-copy.yaml already exists; choose another name.');
    expect(server.disk.get('scenarios/b-copy.yaml')?.content).toBe('name: old copy\n');

    const input = within(dialog).getByLabelText('Name of the copy');
    await userEvent.clear(input);
    await userEvent.type(input, 'b-second{Enter}');
    await screen.findByLabelText('Edit scenarios/b-second.yaml');
    expect(server.disk.get('scenarios/b-second.yaml')?.content).toBe('name: B\n');
  });
});
