import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FileTree } from './FileTree';
import { setLanguage } from '../i18n';
import { fakeServer } from '../test/fakeServer';
import { treeOf } from '../test/workbenchServer';
import { scenarioTemplate } from './scenarioTemplate';

const TREE = treeOf(
  [
    'scenarios/users/create-user.yaml',
    'scenarios/users/disable-user.yaml',
    'scenarios/roles-grant.yaml',
    'config/application-lab.yml',
    'reports/2026-10-19/report.html',
    'secrets.env',
  ],
  { 'scenarios/users/create-user.yaml': 'finding', 'scenarios/roles-grant.yaml': 'passed', 'scenarios/users/disable-user.yaml': 'setup' },
);

function renderTree(props: Partial<React.ComponentProps<typeof FileTree>> = {}) {
  const onOpen = vi.fn();
  const onAdded = vi.fn();
  render(
    <FileTree
      tree={TREE}
      active={null}
      dirty={new Set(['scenarios/users/create-user.yaml'])}
      readOnly={false}
      onOpen={onOpen}
      onAdded={onAdded}
      {...props}
    />,
  );
  return { onOpen, onAdded };
}

function yamlFile(name: string, text = 'name: x\n') {
  return new File([text], name, { type: 'application/x-yaml' });
}

afterEach(() => {
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

describe('FileTree', () => {
  it('shows the workspace with scenarios expanded, last-result badges and ● for unsaved files', () => {
    renderTree();
    const nav = screen.getByRole('navigation', { name: 'Workspace files' });
    const create = within(nav).getByRole('button', { name: /^create-user\.yaml/ });
    expect(within(create).getByRole('img', { name: 'unsaved' })).toHaveTextContent('●');
    expect(within(create).getByRole('img', { name: 'last run: finding' })).toHaveClass('badge-finding');
    const roles = within(nav).getByRole('button', { name: /^roles-grant\.yaml/ });
    expect(within(roles).getByRole('img', { name: 'last run passed' })).toHaveClass('badge-ok');
    expect(within(roles).queryByRole('img', { name: 'unsaved' })).not.toBeInTheDocument();
    const disable = within(nav).getByRole('button', { name: /^disable-user\.yaml/ });
    expect(within(disable).getByRole('img', { name: 'last run: setup problem' })).toHaveClass('badge-error');

    // other folders start folded
    expect(within(nav).getByRole('button', { name: 'config' })).toHaveAttribute('aria-expanded', 'false');
    expect(within(nav).queryByRole('button', { name: /application-lab\.yml/ })).not.toBeInTheDocument();
  });

  it('opens a file on click; folders fold and unfold', async () => {
    const { onOpen } = renderTree();
    await userEvent.click(screen.getByRole('button', { name: /^roles-grant\.yaml/ }));
    expect(onOpen).toHaveBeenCalledWith('scenarios/roles-grant.yaml');

    await userEvent.click(screen.getByRole('button', { name: 'config' }));
    await userEvent.click(screen.getByRole('button', { name: /application-lab\.yml/ }));
    expect(onOpen).toHaveBeenCalledWith('config/application-lab.yml');

    await userEvent.click(screen.getByRole('button', { name: 'users' }));
    expect(screen.queryByRole('button', { name: /^create-user\.yaml/ })).not.toBeInTheDocument();
  });

  it('secrets.env shows a lock and cannot be opened', async () => {
    const { onOpen } = renderTree();
    const secrets = screen.getByRole('button', { name: /secrets\.env/ });
    expect(within(secrets).getByRole('img', { name: 'locked' })).toHaveTextContent('🔒');
    expect(secrets).toHaveAttribute('aria-disabled', 'true');
    expect(secrets).toHaveAttribute('title', 'secrets.env is never opened in the editor');
    await userEvent.click(secrets);
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('"Add files" sends the chosen .yaml files to POST /api/files/add, then refreshes the tree', async () => {
    const server = fakeServer({ 'POST /api/files/add': { body: { added: ['scenarios/new.yaml', 'scenarios/two.yml'] } } });
    const { onAdded } = renderTree();
    await userEvent.upload(screen.getByLabelText('Add files'), [yamlFile('new.yaml'), yamlFile('two.yml')]);

    await waitFor(() => expect(onAdded).toHaveBeenCalled());
    expect(server.calls()).toEqual(['POST /api/files/add']);
    const form = server.sent[0].body as FormData;
    expect(form.getAll('files').map((f) => (f as File).name)).toEqual(['new.yaml', 'two.yml']);
    expect(await screen.findByText('Added 2 files to scenarios/')).toBeInTheDocument();
  });

  it('dropping files on the tree adds the .yaml ones and says the others were left out', async () => {
    const server = fakeServer({ 'POST /api/files/add': { body: { added: ['scenarios/dropped.yaml'] } } });
    const { onAdded } = renderTree();
    const nav = screen.getByRole('navigation', { name: 'Workspace files' });
    const files = [yamlFile('dropped.yaml'), new File(['x'], 'notes.txt', { type: 'text/plain' })];
    fireEvent.dragOver(nav, { dataTransfer: { files, types: ['Files'] } });
    fireEvent.drop(nav, { dataTransfer: { files, types: ['Files'] } });

    await waitFor(() => expect(onAdded).toHaveBeenCalled());
    const form = server.sent[0].body as FormData;
    expect(form.getAll('files').map((f) => (f as File).name)).toEqual(['dropped.yaml']);
    expect(await screen.findByText('Only .yaml and .yml files are added: notes.txt was left out.')).toBeInTheDocument();
  });

  it("shows the server's reason when adding fails", async () => {
    fakeServer({
      'POST /api/files/add': { status: 400, body: { key: 'runner.ui.file.exists', message: 'scenarios/new.yaml already exists' } },
    });
    const { onAdded } = renderTree();
    await userEvent.upload(screen.getByLabelText('Add files'), [yamlFile('new.yaml')]);
    expect(await screen.findByRole('alert')).toHaveTextContent('scenarios/new.yaml already exists');
    expect(onAdded).not.toHaveBeenCalled();
  });

  it('clicking a folder selects it, and still folds and unfolds it', async () => {
    const onSelectFolder = vi.fn();
    const { onOpen } = renderTree({ onSelectFolder });
    const users = screen.getByRole('button', { name: 'users' });
    expect(users).not.toHaveAttribute('aria-current');
    await userEvent.click(users);
    expect(onSelectFolder).toHaveBeenCalledWith('scenarios/users');
    expect(users).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: /^create-user\.yaml/ })).not.toBeInTheDocument();
    await userEvent.click(users);
    expect(users).toHaveAttribute('aria-expanded', 'true');
    // selecting does not open a file
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('the selected folder row is marked, one at a time', () => {
    renderTree({ selectedFolder: 'scenarios/users' });
    const users = screen.getByRole('button', { name: 'users' });
    expect(users).toHaveAttribute('aria-current', 'true');
    expect(users).toHaveClass('tree-selected');
    expect(screen.getByRole('button', { name: 'scenarios' })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('button', { name: 'config' })).not.toHaveAttribute('aria-current');
  });

  it('the selected folder says it is the folder Run folder runs, apart from the active file', () => {
    renderTree({ selectedFolder: 'scenarios/users', active: 'scenarios/roles-grant.yaml' });
    const users = screen.getByRole('button', { name: 'users' });
    expect(users).toHaveAccessibleName('users');
    expect(users).toHaveAccessibleDescription('Run folder');
    expect(screen.getByRole('button', { name: /^roles-grant\.yaml/ })).not.toHaveAccessibleDescription('Run folder');
    expect(screen.getByRole('button', { name: 'config' })).toHaveAccessibleDescription('');
  });

  it('speaks Spanish for the selected folder', () => {
    setLanguage('es', false);
    renderTree({ selectedFolder: 'scenarios/users' });
    expect(screen.getByRole('button', { name: 'users' })).toHaveAccessibleDescription('Carpeta a ejecutar');
  });

  it('the keyboard selects a folder too', async () => {
    const onSelectFolder = vi.fn();
    const { onOpen } = renderTree({ onSelectFolder });
    screen.getByRole('button', { name: 'users' }).focus();
    await userEvent.keyboard('{Enter}');
    expect(onSelectFolder).toHaveBeenCalledWith('scenarios/users');
    await userEvent.keyboard(' ');
    expect(onSelectFolder).toHaveBeenCalledTimes(2);
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('marks every scenario of the run: running, waiting, and the others keep their badges', () => {
    renderTree({
      runStates: new Map([
        ['scenarios/users/create-user.yaml', 'running'],
        ['scenarios/users/disable-user.yaml', 'waiting'],
        ['scenarios/roles-grant.yaml', 'waiting'],
      ]),
    });
    const create = screen.getByRole('button', { name: /^create-user\.yaml/ });
    expect(within(create).getByRole('img', { name: 'Running' })).toHaveTextContent('◌');
    expect(within(create).queryByRole('img', { name: /last run/ })).not.toBeInTheDocument();
    for (const name of [/^disable-user\.yaml/, /^roles-grant\.yaml/]) {
      const row = screen.getByRole('button', { name });
      expect(within(row).getByRole('img', { name: 'Waiting' })).toHaveTextContent('○');
      expect(within(row).queryByRole('img', { name: /last run/ })).not.toBeInTheDocument();
    }
  });

  it('scenarios outside the run keep their last-result badge', () => {
    renderTree({ runStates: new Map([['scenarios/users/create-user.yaml', 'running']]) });
    const roles = screen.getByRole('button', { name: /^roles-grant\.yaml/ });
    expect(within(roles).getByRole('img', { name: 'last run passed' })).toBeInTheDocument();
    expect(within(roles).queryByRole('img', { name: 'Waiting' })).not.toBeInTheDocument();
  });

  it('a read-only tab cannot add files', () => {
    renderTree({ readOnly: true });
    expect(screen.getByRole('button', { name: 'Add files' })).toBeDisabled();
  });

  it('a read-only tab cannot create or duplicate scenarios', () => {
    renderTree({ readOnly: true });
    expect(screen.getByRole('button', { name: 'New scenario' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Duplicate roles-grant.yaml' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Duplicate create-user.yaml' })).toBeDisabled();
  });

  it('the name dialog closes when the tab turns read-only', async () => {
    const props = {
      tree: TREE,
      active: null,
      dirty: new Set<string>(),
      onOpen: vi.fn(),
      onAdded: vi.fn(),
    };
    const { rerender } = render(<FileTree {...props} readOnly={false} />);
    await userEvent.click(screen.getByRole('button', { name: 'New scenario' }));
    expect(screen.getByRole('dialog', { name: 'New scenario' })).toBeInTheDocument();
    rerender(<FileTree {...props} readOnly />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    rerender(<FileTree {...props} readOnly={false} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('only scenario rows can be duplicated', async () => {
    renderTree();
    await userEvent.click(screen.getByRole('button', { name: 'config' }));
    expect(screen.queryByRole('button', { name: /^Duplicate application-lab/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Duplicate secrets/ })).not.toBeInTheDocument();
  });

  it.each(['', '   ', 'x/y', 'x\\y', '..', 'a..b', 'c:x'])('refuses the name %j before sending anything', async (name) => {
    const server = fakeServer({ 'POST /api/files/new': { status: 201, body: { path: 'scenarios/x.yaml', version: 'v1' } } });
    const onCreated = vi.fn();
    renderTree({ onCreated });
    await userEvent.click(screen.getByRole('button', { name: 'New scenario' }));
    const dialog = screen.getByRole('dialog', { name: 'New scenario' });
    const input = within(dialog).getByLabelText('Name of the new scenario');
    if (name) {
      fireEvent.change(input, { target: { value: name } });
    }
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent(
      `'${name.trim()}' is not a file name: use letters, digits, spaces, - and _, without / \\ .. or :.`,
    );
    expect(server.sent).toHaveLength(0);
    expect(onCreated).not.toHaveBeenCalled();
  });

  it('adds .yaml to a name without it and creates the file in the given folder', async () => {
    const server = fakeServer({ 'POST /api/files/new': { status: 201, body: { path: 'scenarios/users/x.yaml', version: 'v1' } } });
    const onCreated = vi.fn();
    renderTree({ onCreated, newFolder: 'scenarios/users' });
    await userEvent.click(screen.getByRole('button', { name: 'New scenario' }));
    const dialog = screen.getByRole('dialog', { name: 'New scenario' });
    expect(dialog).toHaveTextContent('scenarios/users/');
    await userEvent.type(within(dialog).getByLabelText('Name of the new scenario'), ' x {Enter}');
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith('scenarios/users/x.yaml'));
    expect(server.sent.map((r) => r.body)).toEqual([{ path: 'scenarios/users/x.yaml', content: scenarioTemplate('x') }]);
  });

  it('Escape cancels and gives the focus back to the button that opened the dialog', async () => {
    const server = fakeServer({});
    renderTree();
    const button = screen.getByRole('button', { name: 'New scenario' });
    await userEvent.click(button);
    expect(within(screen.getByRole('dialog')).getByLabelText('Name of the new scenario')).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(button).toHaveFocus();

    const duplicate = screen.getByRole('button', { name: 'Duplicate roles-grant.yaml' });
    await userEvent.click(duplicate);
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(duplicate).toHaveFocus();
    expect(server.sent).toHaveLength(0);
  });

  it('duplicate copies the text it is given for an open file, without reading the disk', async () => {
    const server = fakeServer({ 'POST /api/files/new': { status: 201, body: { path: 'scenarios/roles-grant-copy.yaml', version: 'v1' } } });
    const onCreated = vi.fn();
    renderTree({ onCreated, textOf: (path) => (path === 'scenarios/roles-grant.yaml' ? 'name: mine\n' : undefined) });
    await userEvent.click(screen.getByRole('button', { name: 'Duplicate roles-grant.yaml' }));
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith('scenarios/roles-grant-copy.yaml'));
    expect(server.calls()).toEqual(['POST /api/files/new']);
    expect(server.sent[0].body).toEqual({ path: 'scenarios/roles-grant-copy.yaml', content: 'name: mine\n' });
  });

  it('speaks Spanish for new and duplicate', async () => {
    setLanguage('es', false);
    fakeServer({});
    renderTree();
    expect(screen.getByRole('button', { name: 'Duplicar roles-grant.yaml' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Nuevo escenario' }));
    const dialog = screen.getByRole('dialog', { name: 'Nuevo escenario' });
    const input = within(dialog).getByLabelText('Nombre del nuevo escenario');
    fireEvent.change(input, { target: { value: 'a:b' } });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Crear' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent(
      "'a:b' no es un nombre de archivo: use letras, dígitos, espacios, - y _, sin / \\ .. ni :.",
    );
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancelar' }));
    await userEvent.click(screen.getByRole('button', { name: 'Duplicar roles-grant.yaml' }));
    expect(within(screen.getByRole('dialog', { name: 'Duplicar' })).getByLabelText('Nombre de la copia')).toHaveValue(
      'roles-grant-copy.yaml',
    );
  });
});
