import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from '../workbench/Workbench';
import { setLanguage } from '../i18n';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';
import { emitServerEvent } from '../test/browserFakes';
import { defaultDirectoryNeeded, directory, hrDatabase, labRoutes, namedDirectory, rest, type LabRoutesOptions } from '../test/labServer';
import type { Answer } from '../test/fakeServer';
import type { LabSystem, LabView } from './types';

vi.mock('../workbench/monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('../workbench/monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

/** Obviously fake, and unique so the DOM can be searched for it. */
const TYPED = 'fake-typed-secret-7Qx';

function start(options: Partial<LabRoutesOptions> & { systems?: LabSystem[] } = {}) {
  const views: Record<string, LabView | (() => LabView)> = options.views ?? {
    lab: { systems: options.systems ?? [directory('ready'), rest('untested'), hrDatabase('missing')] },
  };
  const server = workbenchServer({ files: {}, routes: labRoutes({ ...options, views }) });
  const view = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
  return { server, ...view };
}

async function openPanel() {
  const chip = await screen.findByRole('button', { name: /^Lab[: ]/ });
  await waitFor(() => expect(chip).not.toHaveTextContent('checking'));
  await userEvent.click(chip);
  return screen.findByRole('dialog', { name: /Lab connection/ });
}

async function selectTab(panel: HTMLElement, name: RegExp) {
  await userEvent.click(within(panel).getByRole('tab', { name }));
  return within(panel).getByRole('tabpanel');
}

const puts = (server: ReturnType<typeof start>['server']) => server.sent.filter((r) => r.method === 'PUT' && r.url.startsWith('/api/lab'));
const tests = (server: ReturnType<typeof start>['server']) => server.sent.filter((r) => r.url === '/api/lab/test');

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

describe('Lab panel', () => {
  it('blocks deletion of a referenced connection and names the scenario', async () => {
    start({ systems: [{ ...directory('ready'), deletion: { allowed: false, reason: 'referenced', files: ['use-directory.yaml'] } }] });
    const form = within(await openPanel()).getByRole('tabpanel');
    expect(within(form).getByRole('button', { name: 'Delete connection' })).toBeDisabled();
    expect(form).toHaveTextContent('use-directory.yaml');
  });

  it('confirms deletion in a dialog, sends the stable ID and profile, then selects Add', async () => {
    const confirm = vi.spyOn(window, 'confirm');
    const { server } = start({ systems: [{ ...directory('ready'), usedByScenarios: [],
      deletion: { allowed: true, reason: null, files: [] } }],
      onDelete: () => ({ body: { systems: [] } }),
    });
    const panel = await openPanel();
    const deleteButton = within(panel).getByRole('button', { name: 'Delete connection' });
    await userEvent.click(deleteButton);
    let dialog = screen.getByRole('dialog', { name: 'Delete connection' });
    expect(dialog).toHaveTextContent('Delete eDirectory (ldap) from config/application-lab.yml?');
    expect(dialog).toHaveTextContent('Its passwords stay in secrets.env');
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog', { name: 'Delete connection' })).not.toBeInTheDocument();
    expect(deleteButton).toHaveFocus();
    expect(server.sent.filter((request) => request.method === 'DELETE')).toHaveLength(0);

    await userEvent.click(deleteButton);
    dialog = screen.getByRole('dialog', { name: 'Delete connection' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete connection' }));
    await waitFor(() => expect(server.sent.filter((request) => request.method === 'DELETE')).toHaveLength(1));
    expect(server.sent.find((request) => request.method === 'DELETE')?.url).toBe('/api/lab?profile=lab&systemId=ldap');
    await waitFor(() => expect(within(panel).queryByRole('tab', { name: /eDirectory/ })).not.toBeInTheDocument());
    expect(screen.queryByRole('dialog', { name: 'Delete connection' })).not.toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: '+ add' })).toHaveFocus();
    expect(confirm).not.toHaveBeenCalled();
  });

  it('Escape cancels the delete dialog and leaves the lab panel open', async () => {
    const { server } = start({ systems: [{ ...directory('ready'), usedByScenarios: [],
      deletion: { allowed: true, reason: null, files: [] } }],
    });
    const panel = await openPanel();
    await userEvent.click(within(panel).getByRole('button', { name: 'Delete connection' }));
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Delete connection' })).not.toBeInTheDocument();
    expect(panel).toBeInTheDocument();
    expect(server.sent.filter((request) => request.method === 'DELETE')).toHaveLength(0);
  });

  it('asks in Spanish', async () => {
    setLanguage('es', false);
    start({ systems: [{ ...directory('ready'), usedByScenarios: [],
      deletion: { allowed: true, reason: null, files: [] } }],
    });
    const chip = await screen.findByRole('button', { name: /^Lab[: ]/ });
    await userEvent.click(chip);
    const panel = await screen.findByRole('dialog');
    await userEvent.click(within(panel).getByRole('button', { name: 'Eliminar conexión' }));
    const dialog = screen.getByRole('dialog', { name: 'Eliminar conexión' });
    expect(dialog).toHaveTextContent('¿Eliminar eDirectory (ldap) de config/application-lab.yml?');
    expect(dialog).toHaveTextContent('Sus contraseñas permanecen en secrets.env');
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toBeInTheDocument();
  });

  it('keeps the dialog and the form when a stale server check refuses deletion', async () => {
    start({ systems: [{ ...directory('ready'), usedByScenarios: [],
      deletion: { allowed: true, reason: null, files: [] } }],
      onDelete: () => ({ status: 400, body: { key: 'ui.lab.deleteBlocked.referenced', message: 'Now used by new.yaml' } }),
    });
    const panel = await openPanel();
    await userEvent.click(within(panel).getByRole('button', { name: 'Delete connection' }));
    const dialog = screen.getByRole('dialog', { name: 'Delete connection' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete connection' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Now used by new.yaml');
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeEnabled();
    expect(within(panel).getByRole('tab', { name: /eDirectory/ })).toBeInTheDocument();
  });

  it.each([
    ['shared', 'Configured in shared application.yml'],
    ['environment', 'Configured by the environment'],
    ['default-directory', 'Selected as the default directory'],
    ['invalid-scenario', 'Check unreadable or dynamic scenarios before deleting'],
  ])('explains the %s deletion gate', async (reason, explanation) => {
    start({ systems: [{ ...directory('ready'), deletion: { allowed: false, reason, files: ['blocked.yaml'] } }] });
    const form = within(await openPanel()).getByRole('tabpanel');
    expect(within(form).getByRole('button', { name: 'Delete connection' })).toBeDisabled();
    expect(form).toHaveTextContent(explanation);
    expect(form).toHaveTextContent('blocked.yaml');
  });

  it('disables Delete while the form has unsaved edits', async () => {
    start({ systems: [{ ...directory('ready'), usedByScenarios: [],
      deletion: { allowed: true, reason: null, files: [] } }] });
    const form = within(await openPanel()).getByRole('tabpanel');
    await userEvent.type(within(form).getByLabelText('Label'), 'edited');
    expect(within(form).getByRole('button', { name: 'Delete connection' })).toBeDisabled();
  });

  it('names the deletion gate in Spanish', async () => {
    setLanguage('es', false);
    start({ systems: [{ ...directory('ready'), deletion: { allowed: false, reason: 'referenced', files: ['uso.yaml'] } }] });
    const chip = await screen.findByRole('button', { name: /^Lab[: ]/ });
    await userEvent.click(chip);
    const panel = await screen.findByRole('dialog');
    const form = within(panel).getByRole('tabpanel');
    expect(within(form).getByRole('button', { name: 'Eliminar conexión' })).toBeDisabled();
    expect(form).toHaveTextContent('La usan los escenarios');
    expect(form).toHaveTextContent('uso.yaml');
  });
  it('keeps the form visible beside a searchable bounded connection list', async () => {
    const systems = Array.from({ length: 25 }, (_, index) => ({
      ...hrDatabase('untested'), id: `database:db${index}`, name: `Database ${index}`,
    }));
    start({ systems });
    const panel = await openPanel();
    expect(panel.querySelector('.lab-list-scroll')).toBeInTheDocument();
    expect(within(panel).getAllByRole('tab')).toHaveLength(25);
    expect(within(panel).getByRole('tabpanel')).toBeVisible();
    await userEvent.type(within(panel).getByRole('searchbox', { name: 'Find connections' }), 'db24');
    expect(within(panel).getAllByRole('tab')).toHaveLength(1);
    expect(within(panel).getByRole('tabpanel')).toBeVisible();
    await userEvent.click(within(panel).getByRole('tab', { name: /Database 24/ }));
    expect(within(panel).getByRole('tabpanel')).toHaveAccessibleName('Database 24');
    expect(within(within(panel).getByRole('tabpanel')).getByLabelText('Identifier')).toHaveValue('database:db24');
  });

  it('saves a display label and updates the list without renaming the identifier', async () => {
    let named = directory('ready');
    const { server } = start({
      views: { lab: () => ({ systems: [named] }) },
      onSave: (_profile, body) => {
        const value = (body as { values: { label: string } }).values.label;
        named = { ...named, name: value || 'Directory', fields: named.fields.map((field) =>
          field.key === 'label' ? { ...field, value, state: 'set' } : field) };
        return { body: { systems: [named] } };
      },
    });
    const panel = await openPanel();
    const form = within(panel).getByRole('tabpanel');
    await userEvent.type(within(form).getByLabelText('Label'), 'Primary directory');
    await userEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(within(panel).getByRole('tab', { name: /Primary directory/ })).toBeInTheDocument());
    expect(within(within(panel).getByRole('tabpanel')).getByLabelText('Identifier')).toHaveValue('ldap');
    expect(puts(server)[0].body).toEqual({ systemId: 'ldap', values: { label: 'Primary directory' }, secrets: {} });
  });

  it('has one tab per system with its badge, opens on the missing one, and names the files it saves to', async () => {
    start();
    const panel = await openPanel();
    expect(panel).toHaveAccessibleName('Lab connection · profile lab');
    const tablist = within(panel).getByRole('tablist');
    const tabs = within(tablist).getAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['✓ eDirectory', '! IdM REST', '✕ HR database']);
    expect(within(tabs[0]).getByRole('img')).toHaveClass('badge-ok');
    expect(within(tabs[1]).getByRole('img')).toHaveClass('badge-warn');
    expect(within(tabs[2]).getByRole('img')).toHaveClass('badge-missing');
    expect(tabs[2]).toHaveAttribute('aria-selected', 'true');
    expect(tabs[2]).toHaveAccessibleName('missing HR database');
    expect(within(panel).getByRole('tabpanel')).toHaveAccessibleName('HR database');
    expect(within(panel).getByRole('button', { name: '+ add' })).toBeInTheDocument();
    expect(panel).toHaveTextContent('config/application-lab.yml');
    expect(panel).toHaveTextContent('secrets.env');
    expect(screen.getByRole('button', { name: /^Lab/ })).toHaveAttribute('aria-expanded', 'true');
  });

  it('moves between tabs with the arrow keys and closes on Escape', async () => {
    start();
    const panel = await openPanel();
    within(panel).getByRole('tab', { name: /HR database/ }).focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(within(panel).getByRole('tab', { name: /eDirectory/ })).toHaveAttribute('aria-selected', 'true');
    expect(within(panel).getByRole('tab', { name: /eDirectory/ })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: /Lab connection/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Lab/ })).toHaveFocus();
  });

  it('shows environment values read-only and inherited ones as from application.yml (editable, override noted)', async () => {
    start();
    const form = await selectTab(await openPanel(), /eDirectory/);
    const bindDn = within(form).getByLabelText(/^Bind DN/);
    expect(bindDn).toHaveValue('cn=admin,o=system');
    expect(bindDn).toHaveAttribute('readonly');
    expect(within(form).getByText('from the environment')).toBeInTheDocument();
    const baseDn = within(form).getByLabelText(/^Base DN/);
    expect(baseDn).not.toHaveAttribute('readonly');
    expect(within(form).getByText('from application.yml')).toBeInTheDocument();
    expect(form).toHaveTextContent('A value from application.yml that you change here is saved as an override in config/application-lab.yml');
  });

  it('a saved secret shows only dots; Change gives an empty field; Save sends only what changed and forgets the secret', async () => {
    let saved: unknown;
    const { server, container } = start({
      onSave: (_profile, body) => {
        saved = body;
        return { body: { systems: [directory('untested'), rest('untested'), hrDatabase('missing')] } };
      },
    });
    const form = await selectTab(await openPanel(), /eDirectory/);
    const password = within(form).getByLabelText(/^Password/);
    expect(password).toHaveAttribute('type', 'password');
    expect(password).toHaveValue('');
    expect(password).toHaveAttribute('placeholder', '••••••••');
    expect(password).toHaveAttribute('readonly');

    await userEvent.click(within(form).getByRole('button', { name: 'Change Password' }));
    const editable = within(form).getByLabelText(/^Password/);
    expect(editable).not.toHaveAttribute('readonly');
    expect(editable).toHaveValue('');
    expect(editable).toHaveFocus();
    await userEvent.type(editable, TYPED);
    await userEvent.clear(within(form).getByLabelText(/^URL/));
    await userEvent.type(within(form).getByLabelText(/^URL/), 'ldaps://edir2.lab:636');

    await userEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(puts(server)).toHaveLength(1));
    expect(puts(server)[0].url).toBe('/api/lab?profile=lab');
    expect(saved).toEqual({ systemId: 'ldap', values: { url: 'ldaps://edir2.lab:636' }, secrets: { password: TYPED } });

    // the status came back with the save, and the secret is gone from the page
    await waitFor(() => expect(within(screen.getByRole('tablist')).getByRole('tab', { name: /eDirectory/ })).toHaveTextContent('!'));
    const after = within(screen.getByRole('tabpanel')).getByLabelText(/^Password/);
    expect(after).toHaveValue('');
    expect(after).toHaveAttribute('readonly');
    expect(container.innerHTML).not.toContain(TYPED);
    expect(document.body.innerHTML).not.toContain(TYPED);
  });

  it('a secret left alone is not sent, so the saved one stays', async () => {
    let saved: unknown;
    start({ onSave: (_p, body) => ((saved = body), { body: { systems: [directory('ready')] } }) , systems: [directory('ready')] });
    const form = within(await openPanel()).getByRole('tabpanel');
    await userEvent.type(within(form).getByLabelText(/^Base DN/), ',o=more');
    await userEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toEqual({ systemId: 'ldap', values: { 'base-dn': 'o=data,o=more' }, secrets: {} }));
  });

  it('Save is off until something changed', async () => {
    start({ systems: [directory('ready')] });
    const form = within(await openPanel()).getByRole('tabpanel');
    expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(within(form).getByRole('button', { name: 'Delete connection' })).toBeDisabled();
    await userEvent.type(within(form).getByLabelText(/^URL/), '9');
    expect(within(form).getByRole('button', { name: 'Save' })).toBeEnabled();
  });

  it('Test connection tests the form as typed, says "✓ Connected · bind OK · 112 ms", refreshes the status and forgets the secret', async () => {
    let systems = [directory('untested')];
    const { server, container } = start({
      views: { lab: () => ({ systems }) },
      onTest: () => {
        systems = [directory('ready')];
        return { ok: true, message: 'Connected · bind OK', millis: 112 };
      },
    });
    const form = within(await openPanel()).getByRole('tabpanel');
    await userEvent.click(within(form).getByRole('button', { name: 'Change Password' }));
    await userEvent.type(within(form).getByLabelText(/^Password/), TYPED);
    await userEvent.click(within(form).getByRole('button', { name: 'Test connection' }));

    const result = await within(form).findByRole('status');
    await waitFor(() => expect(result).toHaveTextContent('✓ Connected · bind OK · 112 ms'));
    expect(tests(server)[0].body).toEqual({ systemId: 'ldap', profile: 'lab', values: {}, secrets: { password: TYPED } });
    await waitFor(() => expect(screen.getByRole('button', { name: /^Lab/ })).toHaveTextContent('Lab ready'));
    expect(within(form).getByLabelText(/^Password/)).toHaveValue('');
    expect(container.innerHTML).not.toContain(TYPED);
    expect(form).toHaveTextContent('type it again to save it');
  });

  it('shows the reason when the test fails', async () => {
    start({ systems: [directory('untested')], onTest: () => ({ ok: false, message: 'credentials rejected: invalid credentials', millis: 40 }) });
    const form = within(await openPanel()).getByRole('tabpanel');
    await userEvent.click(within(form).getByRole('button', { name: 'Test connection' }));
    const result = await within(form).findByRole('status');
    await waitFor(() => expect(result).toHaveTextContent('✕ credentials rejected: invalid credentials'));
    expect(within(result).getByRole('img')).toHaveClass('badge-error');
  });

  it("shows the server's reason when a save is refused", async () => {
    start({
      systems: [directory('ready')],
      onSave: (): Answer => ({
        status: 400,
        body: { key: 'runner.ui.lab.reference', message: "the value of url cannot contain '${': the workbench writes references to secrets.env itself" },
      }),
    });
    const form = within(await openPanel()).getByRole('tabpanel');
    await userEvent.type(within(form).getByLabelText(/^URL/), '{{}');
    await userEvent.click(within(form).getByRole('button', { name: 'Save' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent("the value of url cannot contain '${'");
  });

  it('marks required fields that are not filled in', async () => {
    start();
    const form = within(await openPanel()).getByRole('tabpanel');
    const password = within(form).getByLabelText(/^Password/);
    expect(password).toBeRequired();
    expect(password).not.toHaveAttribute('readonly'); // not set: nothing to Change, type it
    expect(within(form).getByText('needed')).toBeInTheDocument();
  });

  it('REST shows the OAuth fields only when Use OAuth is on', async () => {
    start();
    const form = await selectTab(await openPanel(), /IdM REST/);
    expect(within(form).queryByLabelText(/^Client ID/)).not.toBeInTheDocument();
    await userEvent.click(within(form).getByLabelText(/^Use OAuth/));
    expect(within(form).getByLabelText(/^Client ID/)).toBeInTheDocument();
    expect(within(form).getByLabelText(/^Client secret/)).toHaveAttribute('type', 'password');
  });

  it('in a read-only tab the panel cannot save, but can still test', async () => {
    workbenchServer({ files: {}, routes: labRoutes({ views: { lab: { systems: [directory('untested')] } } }) });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await screen.findByRole('button', { name: /^Lab[: ]/ });
    await new Promise((resolve) => setTimeout(resolve, 60)); // the first tab holds the workspace
    const second = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await within(second.container).findByText('This workspace is open in another tab');
    const chip = within(second.container).getByRole('button', { name: /^Lab[: ]/ });
    await waitFor(() => expect(chip).not.toHaveTextContent('checking'));
    await userEvent.click(chip);
    const panel = await within(second.container).findByRole('dialog', { name: /Lab connection/ });
    const form = within(panel).getByRole('tabpanel');
    expect(within(form).getByLabelText(/^URL/)).toHaveAttribute('readonly');
    expect(within(form).queryByRole('button', { name: 'Change Password' })).not.toBeInTheDocument();
    expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(within(form).getByRole('button', { name: 'Test connection' })).toBeEnabled();
    expect(within(form).getByRole('button', { name: 'Delete connection' })).toBeDisabled();
    expect(panel).toHaveTextContent('This workspace is open in another tab: the lab settings are read-only here.');
  });

  it('+ add saves a new system under the name given', async () => {
    let saved: unknown;
    const crm: LabSystem = { ...hrDatabase('untested'), id: 'database:crm', name: 'Database crm', usedByScenarios: [] };
    start({
      systems: [directory('ready')],
      onSave: (_p, body) => ((saved = body), { body: { systems: [directory('ready'), crm] } }),
    });
    const panel = await openPanel();
    await userEvent.click(within(panel).getByRole('button', { name: '+ add' }));
    const form = within(panel).getByRole('tabpanel');
    await userEvent.selectOptions(within(form).getByLabelText('Kind'), 'database');
    await userEvent.type(within(form).getByLabelText(/^Name/), 'crm');
    await userEvent.type(within(form).getByLabelText(/^JDBC URL/), 'jdbc:postgresql://crm.lab/crm');
    await userEvent.type(within(form).getByLabelText(/^Password/), TYPED);
    await userEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(saved).toEqual({ systemId: 'database:crm', values: { 'jdbc-url': 'jdbc:postgresql://crm.lab/crm' }, secrets: { password: TYPED } }),
    );
    await waitFor(() => expect(within(panel).getByRole('tab', { name: /Database crm/ })).toHaveAttribute('aria-selected', 'true'));
    expect(document.body.innerHTML).not.toContain(TYPED);
  });

  it('opening the panel reads the lab again (secrets.env changes have no file event)', async () => {
    const { server } = start({ systems: [directory('ready')] });
    await screen.findByRole('button', { name: /^Lab ready/ });
    const before = server.calls().filter((c) => c.startsWith('GET /api/lab')).length;
    await openPanel();
    await waitFor(() => expect(server.calls().filter((c) => c.startsWith('GET /api/lab')).length).toBe(before + 1));
  });

  it('never puts a typed secret into browser storage', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    start({ systems: [directory('ready')] });
    const form = within(await openPanel()).getByRole('tabpanel');
    await userEvent.click(within(form).getByRole('button', { name: 'Change Password' }));
    await userEvent.type(within(form).getByLabelText(/^Password/), TYPED);
    await userEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(within(form).getByLabelText(/^Password/)).toHaveValue(''));
    for (const call of setItem.mock.calls) {
      expect(String(call[1])).not.toContain(TYPED);
    }
    expect(window.location.href).not.toContain(TYPED);
  });

  it('speaks Spanish', async () => {
    setLanguage('es', false);
    start({ systems: [directory('ready')] });
    const chip = await screen.findByRole('button', { name: /^Lab/ });
    await waitFor(() => expect(chip).toHaveTextContent('Lab listo'));
    await userEvent.click(chip);
    const panel = await screen.findByRole('dialog', { name: /Conexión al lab/ });
    expect(within(panel).getByRole('button', { name: 'Probar la conexión' })).toBeInTheDocument();
    expect(within(panel).getByText('desde application.yml')).toBeInTheDocument();
  });

  describe('fix round 1', () => {
    it('a failed reload keeps the forms and what was typed, with the reason above them', async () => {
      let fail = false;
      workbenchServer({
        files: {},
        routes: {
          ...labRoutes({ views: { lab: { systems: [directory('ready')] } } }),
          'GET /api/lab': () =>
            fail
              ? { status: 400, body: { key: 'runner.ui.lab.settingsUnreadable', message: 'the lab settings could not be read: bad.yml' } }
              : { body: { systems: [directory('ready')] } },
        },
      });
      render(<Workbench workspace={WORKSPACE} lockWait={10} />);
      const panel = await openPanel();
      const form = within(panel).getByRole('tabpanel');
      await userEvent.type(within(form).getByLabelText(/^Base DN/), ',o=kept');
      fail = true;
      emitServerEvent('/api/files/events', { type: 'changed', path: 'config/application-lab.yml' });
      expect(await within(panel).findByRole('alert')).toHaveTextContent('the lab settings could not be read: bad.yml');
      expect(within(panel).getByLabelText(/^Base DN/)).toHaveValue('o=data,o=kept');
    });

    it('switching profile with unsaved typing in the panel asks first', async () => {
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
      const { server } = start({ views: { lab: { systems: [directory('ready')] }, uat: { systems: [directory('untested')] } } });
      const form = within(await openPanel()).getByRole('tabpanel');
      await userEvent.type(within(form).getByLabelText(/^Base DN/), ',o=kept');
      await userEvent.selectOptions(screen.getByLabelText('Profile'), 'uat');
      expect(confirm).toHaveBeenCalledWith('Switch the profile? What you typed in the lab panel and did not save is lost.');
      expect(screen.getByLabelText('Profile')).toHaveValue('lab');
      expect(within(screen.getByRole('tabpanel')).getByLabelText(/^Base DN/)).toHaveValue('o=data,o=kept');
      expect(server.calls()).not.toContain('GET /api/lab?profile=uat');
      confirm.mockReturnValue(true);
      await userEvent.selectOptions(screen.getByLabelText('Profile'), 'uat');
      await waitFor(() => expect(server.calls()).toContain('GET /api/lab?profile=uat'));
      expect(screen.getByLabelText('Profile')).toHaveValue('uat');
    });

    it('a secret input filled without the user typing (browser autofill) is never sent', async () => {
      const { server } = start({ systems: [directory('untested')] });
      const form = within(await openPanel()).getByRole('tabpanel');
      await userEvent.click(within(form).getByRole('button', { name: 'Change Password' }));
      const password = within(form).getByLabelText(/^Password/) as HTMLInputElement;
      expect(password).toHaveAttribute('autocomplete', 'new-password');
      password.value = 'fake-autofilled-value'; // no input event
      await userEvent.click(within(form).getByRole('button', { name: 'Test connection' }));
      await waitFor(() => expect(tests(server)).toHaveLength(1));
      expect(tests(server)[0].body).toEqual({ systemId: 'ldap', profile: 'lab', values: {}, secrets: {} });
      expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled();
    });

    it('+ add marks what the server will need: the URL, and a password once a user is given', async () => {
      start({ systems: [directory('ready')] });
      const panel = await openPanel();
      await userEvent.click(within(panel).getByRole('button', { name: '+ add' }));
      const form = within(panel).getByRole('tabpanel');
      await userEvent.selectOptions(within(form).getByLabelText('Kind'), 'database');
      expect(within(form).getByLabelText(/^JDBC URL/)).toBeRequired();
      expect(within(form).getByLabelText(/^Password/)).not.toBeRequired();
      expect(within(form).getAllByText('needed')).toHaveLength(1);
      await userEvent.type(within(form).getByLabelText(/^Username/), 'hr_reader');
      expect(within(form).getByLabelText(/^Password/)).toBeRequired();
      expect(within(form).getAllByText('needed')).toHaveLength(2);
      await userEvent.selectOptions(within(form).getByLabelText('Kind'), 'rest');
      expect(within(form).getByLabelText(/^Base URL/)).toBeRequired();
      await userEvent.click(within(form).getByLabelText(/^Use OAuth/));
      expect(within(form).getByLabelText(/^Client ID/)).toBeRequired();
      expect(within(form).getByLabelText(/^Client secret/)).toBeRequired();
    });

    it('turning Use OAuth off drops the typed client secret: the form is no longer dirty', async () => {
      start();
      const form = await selectTab(await openPanel(), /IdM REST/);
      const oauth = within(form).getByLabelText(/^Use OAuth/);
      await userEvent.click(oauth);
      await userEvent.type(within(form).getByLabelText(/^Client secret/), TYPED);
      await userEvent.click(oauth);
      expect(within(form).queryByLabelText(/^Client secret/)).not.toBeInTheDocument();
      expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled();
    });

    it('changing the kind in + add starts that kind\'s form empty', async () => {
      let saved: unknown;
      start({ systems: [directory('ready')], onSave: (_p, body) => ((saved = body), { body: { systems: [directory('ready')] } }) });
      const panel = await openPanel();
      await userEvent.click(within(panel).getByRole('button', { name: '+ add' }));
      const form = within(panel).getByRole('tabpanel');
      await userEvent.selectOptions(within(form).getByLabelText('Kind'), 'database');
      await userEvent.type(within(form).getByLabelText(/^Name/), 'crm');
      await userEvent.type(within(form).getByLabelText(/^Username/), 'crm_user');
      await userEvent.type(within(form).getByLabelText(/^Password/), TYPED);
      await userEvent.selectOptions(within(form).getByLabelText('Kind'), 'rest');
      expect(within(form).getByLabelText(/^Username/)).toHaveValue('');
      expect(within(form).getByLabelText(/^Password/)).toHaveValue('');
      expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled();
      await userEvent.type(within(form).getByLabelText(/^Base URL/), 'https://crm.lab');
      await userEvent.click(within(form).getByRole('button', { name: 'Save' }));
      await waitFor(() => expect(saved).toEqual({ systemId: 'rest:crm', values: { 'base-url': 'https://crm.lab' }, secrets: {} }));
    });
  });
  describe('default directory choice', () => {
    const ad = () => namedDirectory('ad', 'ready');
    const edirectory = (used: string[] = []) => namedDirectory('edirectory', 'ready', used);
    const needing = (extra = {}): LabView => ({ systems: [ad(), edirectory()], defaultDirectory: defaultDirectoryNeeded(extra) });
    const defaultPuts = (server: ReturnType<typeof start>['server']) =>
      server.sent.filter((r) => r.method === 'PUT' && r.url.startsWith('/api/lab/default-directory'));

    it('opens on a first "Default directory" tab with ✕; choosing one and Save writes it and clears the tab and the chip', async () => {
      const { server } = start({
        views: { lab: needing() },
        onSaveDefault: () => ({ body: { systems: [ad(), edirectory(['example.yaml'])], defaultDirectory: null } }),
      });
      await screen.findByRole('button', { name: 'Lab: choose a default directory' });
      const panel = await openPanel();
      const tabs = within(within(panel).getByRole('tablist')).getAllByRole('tab');
      expect(tabs.map((tab) => tab.textContent)).toEqual(['✕ Default directory', '✓ Directory ad', '✓ Directory edirectory']);
      expect(within(tabs[0]).getByRole('img')).toHaveClass('badge-missing');
      expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
      const form = within(panel).getByRole('tabpanel');
      expect(form).toHaveAccessibleName('Default directory');
      expect(form).toHaveTextContent(
        'example.yaml use steps without directory:, and there is no default directory. Choose which directory those steps use.',
      );
      const select = within(form).getByLabelText('Directory');
      expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Choose a directory', 'ad', 'edirectory']);
      expect(form).toHaveTextContent('config/application-lab.yml');
      const save = within(form).getByRole('button', { name: 'Save' });
      expect(save).toBeDisabled();

      await userEvent.selectOptions(select, 'edirectory');
      await userEvent.click(save);
      await waitFor(() => expect(defaultPuts(server)).toHaveLength(1));
      expect(defaultPuts(server)[0].url).toBe('/api/lab/default-directory?profile=lab');
      expect(defaultPuts(server)[0].body).toEqual({ name: 'edirectory' });
      await waitFor(() => expect(within(panel).queryByRole('tab', { name: /Default directory/ })).not.toBeInTheDocument());
      expect(within(within(panel).getByRole('tablist')).getAllByRole('tab').map((tab) => tab.textContent)).toEqual([
        '✓ Directory ad',
        '✓ Directory edirectory',
      ]);
      expect(screen.getByRole('button', { name: 'Lab ready' })).toBeInTheDocument();
    });

    it('an invalid default-directory says so, with the value', async () => {
      start({ views: { lab: needing({ value: 'nope', source: 'profile', state: 'invalid' }) } });
      const form = within(await openPanel()).getByRole('tabpanel');
      expect(form).toHaveAccessibleName('Default directory');
      expect(form).toHaveTextContent('default-directory is "nope", which is not one of the configured directories.');
      // every step may name its directory: the "steps without directory:" sentence would not be true
      expect(form).not.toHaveTextContent('use steps without directory:');
      expect(within(form).getByLabelText('Directory')).toHaveValue('');
    });

    it('an invalid default-directory with no scenario using ldap.* steps names no scenario', async () => {
      start({ views: { lab: needing({ usedByScenarios: [], value: 'nope', source: 'profile', state: 'invalid' }) } });
      await screen.findByRole('button', { name: 'Lab: choose a default directory' });
      const form = within(await openPanel()).getByRole('tabpanel');
      expect(form).toHaveAccessibleName('Default directory');
      expect(form).toHaveTextContent('default-directory is "nope", which is not one of the configured directories.');
      expect(form).not.toHaveTextContent('use steps without directory:');
    });

    it('a chosen directory that a refreshed view no longer offers is dropped, and nothing is left unsaved', async () => {
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
      let current = needing();
      const { server } = start({ views: { lab: () => current } });
      const form = within(await openPanel()).getByRole('tabpanel');
      await userEvent.selectOptions(within(form).getByLabelText('Directory'), 'edirectory');
      expect(within(form).getByRole('button', { name: 'Save' })).toBeEnabled();
      current = { systems: [ad()], defaultDirectory: defaultDirectoryNeeded({ choices: ['ad', 'openldap'] }) };
      const reads = server.calls().filter((c) => c.startsWith('GET /api/lab')).length;
      emitServerEvent('/api/files/events', { type: 'changed', path: 'config/application.yml' });
      await waitFor(() => expect(server.calls().filter((c) => c.startsWith('GET /api/lab')).length).toBeGreaterThan(reads));
      await waitFor(() => expect(within(form).getByLabelText('Directory')).toHaveValue(''));
      expect(within(within(form).getByLabelText('Directory')).getAllByRole('option').map((o) => o.textContent)).toEqual([
        'Choose a directory',
        'ad',
        'openldap',
      ]);
      expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled();
      await userEvent.click(screen.getByRole('button', { name: 'Close the lab panel' }));
      expect(confirm).not.toHaveBeenCalled();
      expect(screen.queryByRole('dialog', { name: /Lab connection/ })).not.toBeInTheDocument();
    });

    it('a value set by the environment is shown read-only, with where to change it', async () => {
      const { server } = start({ views: { lab: needing({ value: 'nope', source: 'environment', state: 'invalid' }) } });
      const form = within(await openPanel()).getByRole('tabpanel');
      expect(within(form).getByLabelText('Directory')).toBeDisabled();
      expect(within(form).getByText('from the environment')).toBeInTheDocument();
      // the profile may hold a ${VAR} reference: the variable is not always IDM_TEST_DEFAULTDIRECTORY
      expect(form).toHaveTextContent('Set through an environment variable: change it there.');
      expect(form).not.toHaveTextContent('IDM_TEST_DEFAULTDIRECTORY');
      expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled();
      expect(defaultPuts(server)).toHaveLength(0);
    });

    it("shows the server's reason when the save is refused", async () => {
      start({
        views: { lab: needing() },
        onSaveDefault: (): Answer => ({ status: 400, body: { key: 'ui.lab.badDefaultDirectory', message: '"edirectory" is not one of the directories' } }),
      });
      const form = within(await openPanel()).getByRole('tabpanel');
      await userEvent.selectOptions(within(form).getByLabelText('Directory'), 'edirectory');
      await userEvent.click(within(form).getByRole('button', { name: 'Save' }));
      expect(await within(form).findByRole('alert')).toHaveTextContent('"edirectory" is not one of the directories');
    });

    it('in a read-only tab Save stays off', async () => {
      workbenchServer({ files: {}, routes: labRoutes({ views: { lab: needing() } }) });
      render(<Workbench workspace={WORKSPACE} lockWait={10} />);
      await screen.findByRole('button', { name: /^Lab[: ]/ });
      await new Promise((resolve) => setTimeout(resolve, 60)); // the first tab holds the workspace
      const second = render(<Workbench workspace={WORKSPACE} lockWait={10} />);
      await within(second.container).findByText('This workspace is open in another tab');
      const chip = within(second.container).getByRole('button', { name: 'Lab: choose a default directory' });
      await userEvent.click(chip);
      const panel = await within(second.container).findByRole('dialog', { name: /Lab connection/ });
      const form = within(panel).getByRole('tabpanel');
      expect(form).toHaveAccessibleName('Default directory');
      expect(within(form).getByLabelText('Directory')).toBeDisabled();
      expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled();
    });

    it('speaks Spanish', async () => {
      setLanguage('es', false);
      start({ views: { lab: needing() } });
      const chip = await screen.findByRole('button', { name: 'Lab: elija un directorio predeterminado' });
      await userEvent.click(chip);
      const panel = await screen.findByRole('dialog', { name: /Conexión al lab/ });
      expect(within(panel).getByRole('tab', { selected: true })).toHaveTextContent('Directorio predeterminado');
      const form = within(panel).getByRole('tabpanel');
      expect(form).toHaveTextContent(
        'example.yaml usan pasos sin directory: y no hay un directorio predeterminado. Elija qué directorio usan esos pasos.',
      );
      expect(within(form).getByLabelText('Directorio')).toBeInTheDocument();
    });

    it('speaks Spanish for an invalid value', async () => {
      setLanguage('es', false);
      start({ views: { lab: needing({ value: 'nope', source: 'profile', state: 'invalid' }) } });
      await userEvent.click(await screen.findByRole('button', { name: 'Lab: elija un directorio predeterminado' }));
      const panel = await screen.findByRole('dialog', { name: /Conexión al lab/ });
      expect(within(panel).getByRole('tabpanel')).toHaveTextContent(
        'default-directory es "nope", que no es ninguno de los directorios configurados.',
      );
    });
  });
});
