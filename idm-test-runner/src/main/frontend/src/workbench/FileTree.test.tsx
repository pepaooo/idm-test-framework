import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FileTree } from './FileTree';
import { setLanguage } from '../i18n';
import { fakeServer } from '../test/fakeServer';
import { treeOf } from '../test/workbenchServer';

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

  it('a read-only tab cannot add files', () => {
    renderTree({ readOnly: true });
    expect(screen.getByRole('button', { name: 'Add files' })).toBeDisabled();
  });
});
