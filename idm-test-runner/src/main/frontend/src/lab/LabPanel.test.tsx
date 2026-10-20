import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from '../workbench/Workbench';
import { setLanguage } from '../i18n';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';
import { emitServerEvent } from '../test/browserFakes';
import { directory, hrDatabase, labRoutes, rest, type LabRoutesOptions } from '../test/labServer';
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
});
