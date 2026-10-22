import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from '../workbench/Workbench';
import { setLanguage } from '../i18n';
import type { ScenarioResult } from './types';
import { emit, findingStep, openRunStreams, playScenario, RUN_ID, runServer, scenario, step, WORKSPACE } from '../test/runServer';

vi.mock('../workbench/monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('../workbench/monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

/** Leaves out the screen-reader live region, which repeats the progress. */
const VISIBLE = { ignore: '.sr-only, script, style' };

const NAMES = ['users/create-user.yaml', 'users/disable-user.yaml', 'roles-grant.yaml', 'resources-grant.yaml', 'hr-sync.yaml'];

afterEach(() => {
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

function panel() {
  return screen.getByRole('complementary', { name: 'Run' });
}

function rows() {
  return within(within(panel()).getByRole('list', { name: 'Scenarios of the run' })).getAllByRole('button');
}

/** Starts "Run all" from the menu and waits for the server to accept it. */
async function runAll(server: ReturnType<typeof runServer>) {
  await waitFor(() => expect(screen.getByRole('button', { name: 'Lab ready' })).toBeInTheDocument());
  await waitFor(() => expect(screen.getByRole('button', { name: 'More ways to run' })).toBeEnabled());
  await userEvent.click(screen.getByRole('button', { name: 'More ways to run' }));
  await userEvent.click(await screen.findByRole('menuitem', { name: /Run all scenarios/ }));
  await waitFor(() => expect(server.runStarts()).toHaveLength(1));
  await waitFor(() => expect(openRunStreams()).toHaveLength(1));
}

const passed = (name: string) => scenario(name, [step(0, 'ldap.create')]);
/** The step a stop interrupted, as the engine reports it. */
const interrupted = () =>
  findingStep({ failureKind: 'SETUP', message: 'Stopped — cleanup did not run', messageKey: 'engine.run.stopped', checks: [] });
const stoppedIn = (name: string, extra: Partial<ScenarioResult> = {}) =>
  scenario(name, [step(0, 'ldap.create'), interrupted()], { success: false, stopped: true, failureDetail: 'Stopped — cleanup did not run', ...extra });
const finding = (name: string) => scenario(name, [step(0, 'ldap.create'), findingStep()]);

describe('Run panel', () => {
  it('shows the progress, each scenario’s state, and stops at once', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: NAMES, locale: 'en' });
      playScenario(passed(NAMES[0]));
      playScenario(finding(NAMES[1]), 'step 2 failed');
      emit('scenarioStarted', { name: NAMES[2] });
      emit('stepStarted', { index: 0, type: 'ldap.create', description: null });
      emit('stepFinished', step(0, 'ldap.create'));
      emit('stepStarted', { index: 1, type: 'roles.grant', description: null });
    });
    expect(within(panel()).getByText('Running all · 3 of 5', VISIBLE)).toBeInTheDocument();
    // the step count comes from the scenario's file
    await waitFor(() => expect(rows()[2]).toHaveTextContent('step 2 of 4…'));
    const [first, second, third, fourth] = rows();
    expect(within(first).getByRole('img', { name: 'Passed' })).toHaveTextContent('✓');
    expect(within(second).getByRole('img', { name: 'Finding' })).toHaveClass('badge-finding');
    expect(second).toHaveTextContent('finding');
    expect(within(third).getByRole('img', { name: 'Running' })).toBeInTheDocument();
    expect(fourth).toHaveTextContent('waiting');
    // the live region says how far the run is
    expect(panel().querySelector('[aria-live="polite"]')).toHaveTextContent('Running all · 3 of 5');

    await userEvent.click(within(panel()).getByRole('button', { name: '■ Stop' }));
    await waitFor(() => expect(server.sent.some((r) => r.method === 'POST' && r.url === '/api/runs/current/stop')).toBe(true));
    expect(within(panel()).getByRole('button', { name: 'Stopping…' })).toBeDisabled();
  });

  it('ends with "5 scenarios: 3 passed, 2 findings · Open full report", closes the stream, and the tree badges follow', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    const readsBefore = server.treeReads();
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: NAMES, locale: 'en' });
      playScenario(passed(NAMES[0]));
    });
    server.tree.results = { 'scenarios/users/create-user.yaml': 'passed' };
    await waitFor(() => expect(server.treeReads()).toBeGreaterThan(readsBefore));
    const tree = screen.getByRole('navigation', { name: 'Workspace files' });
    await waitFor(() => expect(within(tree).getByRole('button', { name: /create-user.yaml/ })).toContainElement(within(tree).getByRole('img', { name: /passed/i })));
    act(() => {
      playScenario(finding(NAMES[1]));
      playScenario(passed(NAMES[2]));
      playScenario(finding(NAMES[3]));
      playScenario(passed(NAMES[4]));
      emit('runFinished', { runId: RUN_ID, total: 5, passed: 3, findings: 2, setupProblems: 0, stopped: false, exitCode: 1, reportUrl: `/reports/${RUN_ID}/report.html` });
    });
    const end = within(panel()).getByText(/5 scenarios: 3 passed, 2 findings/, VISIBLE);
    expect(end).toHaveTextContent('5 scenarios: 3 passed, 2 findings · Open full report');
    const link = within(end).getByRole('link', { name: 'Open full report' });
    // a link cannot send a header: it names the workspace it is for
    expect(link).toHaveAttribute('href', `/reports/${RUN_ID}/report.html?workspace=${encodeURIComponent(WORKSPACE.path)}`);
    expect(link).toHaveAttribute('target', '_blank');
    expect(within(panel()).getByText('Last run · 14:32 · lab', VISIBLE)).toBeInTheDocument();
    expect(within(panel()).queryByRole('button', { name: '■ Stop' })).not.toBeInTheDocument();
    expect(openRunStreams()).toHaveLength(0);
  });

  it('counts setup problems on their own, singular and plural', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: NAMES.slice(0, 3), locale: 'en' });
      playScenario(passed(NAMES[0]));
      playScenario(finding(NAMES[1]));
      playScenario(scenario(NAMES[2], [step(0, 'ldap.create', { success: false, failureKind: 'SETUP', message: 'could not reach edir.lab:636: Connection refused' })]));
      emit('runFinished', { runId: RUN_ID, total: 3, passed: 1, findings: 1, setupProblems: 1, stopped: false, exitCode: 1, reportUrl: null });
    });
    expect(within(panel()).getByText('3 scenarios: 1 passed, 1 finding, 1 setup problem', VISIBLE)).toBeInTheDocument();
    expect(within(rows()[2]).getByRole('img', { name: 'Setup problem' })).toHaveClass('badge-error');
  });

  it('opens the first finding by itself; clicking a scenario shows its steps', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: NAMES.slice(0, 2), locale: 'en' });
      playScenario(finding(NAMES[0]));
      playScenario(passed(NAMES[1]));
    });
    expect(within(panel()).getByText('The lab did not do what this step expects')).toBeInTheDocument();
    await userEvent.click(rows()[1]);
    const steps = within(panel()).getByRole('region', { name: 'Steps of users/disable-user.yaml' });
    expect(within(steps).getByRole('button', { name: /1 ldap.create — cn=u123,ou=users/ })).toBeInTheDocument();
    expect(within(panel()).queryByText('The lab did not do what this step expects')).not.toBeInTheDocument();
    await userEvent.click(rows()[0]);
    expect(within(panel()).getByText('The lab did not do what this step expects')).toBeInTheDocument();
  });

  it('end line counts stopped: "5 scenarios: 2 passed, 1 finding, 1 stopped, 1 not run", never a setup problem', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: NAMES, locale: 'en' });
      playScenario(passed(NAMES[0]));
      playScenario(passed(NAMES[1]));
      playScenario(finding(NAMES[2]));
      playScenario(stoppedIn(NAMES[3]));
      emit('runFinished', { runId: RUN_ID, total: 5, passed: 2, findings: 1, setupProblems: 0, stoppedScenarios: 1, stopped: true, exitCode: 1, reportUrl: null });
    });
    expect(within(panel()).getByText('5 scenarios: 2 passed, 1 finding, 1 stopped, 1 not run', VISIBLE)).toBeInTheDocument();
    expect(panel()).not.toHaveTextContent(/setup problem/i);
    const marker = within(rows()[3]).getByRole('img', { name: 'Stopped' });
    expect(marker).toHaveTextContent('!');
    expect(marker).toHaveClass('badge-warn');
    expect(rows()[3]).toHaveTextContent('stopped');
    expect(rows()[4]).toHaveTextContent('not run');

    act(() => setLanguage('es', false));
    expect(within(panel()).getByText('5 escenarios: 2 correctos, 1 hallazgo, 1 detenido, 1 no ejecutado', VISIBLE)).toBeInTheDocument();
  });

  it('stopped keeps the tree badge: a scenario that last passed still shows ✓ after a stop', async () => {
    const server = runServer({ results: { 'scenarios/users/create-user.yaml': 'passed' } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    const tree = screen.getByRole('navigation', { name: 'Workspace files' });
    const createUser = () => within(tree).getByRole('button', { name: /create-user.yaml/ });
    await waitFor(() => expect(within(createUser()).getByRole('img', { name: 'last run passed' })).toBeInTheDocument());
    const readsBefore = server.treeReads();
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: NAMES, locale: 'en' });
      playScenario(stoppedIn(NAMES[0]));
    });
    await waitFor(() => expect(server.treeReads()).toBeGreaterThan(readsBefore));
    act(() => emit('runFinished', { runId: RUN_ID, total: 5, passed: 0, findings: 0, setupProblems: 0, stoppedScenarios: 1, stopped: true, exitCode: 1, reportUrl: null }));
    expect(within(rows()[0]).getByRole('img', { name: 'Stopped' })).toBeInTheDocument();
    const badge = within(createUser()).getByRole('img', { name: 'last run passed' });
    expect(badge).toHaveTextContent('✓');
    expect(within(createUser()).queryByRole('img', { name: /stopped|setup/i })).not.toBeInTheDocument();
  });

  it('a stop after a finding counts the scenario as a finding (amber), not as stopped', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: NAMES.slice(0, 2), locale: 'en' });
      playScenario(
        scenario(NAMES[0], [step(0, 'ldap.create'), findingStep()], {
          success: false,
          stopped: true,
          skippedCleanup: [
            { index: 0, type: 'ldap.delete', resolvedParams: { dn: 'cn=u123,ou=users' }, status: 'SKIPPED', durationMillis: 0, message: 'stopped', description: null },
          ],
        }),
      );
      emit('runFinished', { runId: RUN_ID, total: 2, passed: 0, findings: 1, setupProblems: 0, stoppedScenarios: 0, stopped: true, exitCode: 1, reportUrl: null });
    });
    const first = rows()[0];
    expect(within(first).getByRole('img', { name: 'Finding' })).toHaveClass('badge-finding');
    expect(within(first).queryByRole('img', { name: 'Stopped' })).not.toBeInTheDocument();
    expect(first).toHaveTextContent('finding');
    expect(first).not.toHaveTextContent('stopped');
    expect(within(panel()).getByText('2 scenarios: 0 passed, 1 finding, 1 not run', VISIBLE)).toBeInTheDocument();
    // the failed step is the finding, not a stop; the skipped cleanup is still listed
    await userEvent.click(first);
    expect(within(within(panel()).getByRole('button', { name: /2 ldap.verify/ })).getByRole('img', { name: 'Finding' })).toBeInTheDocument();
    expect(within(panel()).getByRole('region', { name: /Stopped — cleanup did not run/ })).toBeInTheDocument();
  });

  it('a stopped scenario says "Stopped — cleanup did not run" with the skipped entries and their values', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: NAMES.slice(0, 2), locale: 'en' });
      playScenario(
        scenario(NAMES[0], [step(0, 'ldap.create'), interrupted()], {
          success: false,
          stopped: true,
          failureDetail: 'The run was stopped before the cleanup',
          skippedCleanup: [
            { index: 0, type: 'ldap.delete', resolvedParams: { dn: 'cn=u123,ou=users' }, status: 'SKIPPED', durationMillis: 0, message: 'stopped', description: null },
          ],
        }),
      );
      emit('runFinished', { runId: RUN_ID, total: 2, passed: 0, findings: 0, setupProblems: 0, stoppedScenarios: 1, stopped: true, exitCode: 1, reportUrl: null });
    });
    await userEvent.click(rows()[0]);
    const stopped = within(panel()).getByRole('region', { name: /Stopped — cleanup did not run/ });
    expect(within(stopped).getByText('1 ldap.delete')).toBeInTheDocument();
    expect(within(stopped).getByText('dn')).toBeInTheDocument();
    expect(within(stopped).getByText('cn=u123,ou=users')).toBeInTheDocument();
    // the step the stop interrupted is marked stopped, not a setup problem or a finding
    expect(within(panel()).queryByRole('region', { name: /Setup problem/ })).not.toBeInTheDocument();
    expect(within(panel()).queryByText('The lab did not do what this step expects')).not.toBeInTheDocument();
    expect(within(within(panel()).getByRole('button', { name: /2 ldap.verify/ })).getByRole('img', { name: 'Stopped' })).toBeInTheDocument();
    expect(rows()[0]).toHaveTextContent('stopped');
    expect(within(rows()[0]).getByRole('img', { name: 'Stopped' })).toHaveTextContent('!');
    expect(within(panel()).getByText('2 scenarios: 0 passed, 1 stopped, 1 not run', VISIBLE)).toBeInTheDocument();
  });

  it('scenarios a stopped or refused run never reached read "not run", not "waiting"', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: NAMES, locale: 'en' });
      playScenario(passed(NAMES[0]));
      emit('runFinished', { runId: RUN_ID, total: 5, passed: 1, findings: 0, setupProblems: 0, stopped: true, exitCode: 1, reportUrl: null });
    });
    const after = rows();
    for (const later of after.slice(1)) {
      expect(later).toHaveTextContent('not run');
      expect(later).not.toHaveTextContent('waiting');
      expect(within(later).getByRole('img', { name: 'Not run' })).toBeInTheDocument();
    }
  });

  it('a run refused half-way marks the running one a setup problem and the rest "not run"', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: NAMES.slice(0, 3), locale: 'en' });
      playScenario(passed(NAMES[0]));
      emit('scenarioStarted', { name: NAMES[1] });
      emit('refused', { key: 'runner.run.stopped', message: 'The run stopped: out of memory' });
    });
    const [, second, third] = rows();
    expect(within(second).getByRole('img', { name: 'Setup problem' })).toBeInTheDocument();
    expect(third).toHaveTextContent('not run');
  });

  it('a failed cleanup entry is amber and says something may be left in the lab', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: [NAMES[0]], locale: 'en' });
      playScenario(
        scenario(NAMES[0], [step(0, 'ldap.create')], {
          success: false,
          cleanup: [
            { index: 0, type: 'ldap.delete', resolvedParams: { dn: 'cn=u123,ou=users' }, status: 'FAILED', durationMillis: 5, message: 'the directory refused the delete', description: null, failureKind: 'FINDING' },
          ],
        }),
      );
    });
    const cleanup = within(panel()).getByRole('region', { name: 'Cleanup' });
    expect(within(cleanup).getByRole('img', { name: 'failed' })).toHaveClass('badge-finding');
    expect(within(cleanup).getByText('Something may be left in the lab:')).toBeInTheDocument();
    expect(within(cleanup).getByText('cn=u123,ou=users')).toBeInTheDocument();
    expect(within(rows()[0]).getByRole('img', { name: 'Finding' })).toBeInTheDocument();
  });

  it('a refused run shows the reason in red and closes the stream', async () => {
    const server = runServer({ routes: { 'POST /api/runs': { status: 202, body: { runId: null } } } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    act(() => emit('refused', { key: 'runner.run.needsDriver', message: 'No JDBC driver for database hr in drivers/' }));
    const alert = within(panel()).getByRole('alert');
    expect(alert).toHaveTextContent('No JDBC driver for database hr in drivers/');
    expect(within(alert).getByRole('img')).toHaveClass('badge-error');
    expect(openRunStreams()).toHaveLength(0);
  });

  it('follows the last run on load, and stops listening when the server has nothing (204)', async () => {
    runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await waitFor(() => expect(openRunStreams()).toHaveLength(1));
    act(() => openRunStreams()[0].fail(true));
    expect(openRunStreams()[0].readyState).toBe(2); // closed by the browser: no reconnect loop
    expect(within(panel()).getByText('The current or last run shows here, step by step.')).toBeInTheDocument();
  });

  it('never shows a secret: events come masked and nothing reads secrets.env', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runAll(server);
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: [NAMES[0]], locale: 'en' });
      playScenario(scenario(NAMES[0], [step(0, 'ldap.create', { resolvedParams: { dn: 'cn=u123', userPassword: '***' } }), findingStep()]));
    });
    expect(within(panel()).getByText(/userPassword: \*\*\*/)).toBeInTheDocument(); // the evidence, as masked
    expect(document.body.innerHTML).not.toContain('fake-secret-value');
    expect(document.body.innerHTML).toContain('***');
    expect(server.sent.some((r) => r.url.includes('secrets.env'))).toBe(false);
  });
});
