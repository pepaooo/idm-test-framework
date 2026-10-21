import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Workbench } from '../workbench/Workbench';
import { setLanguage } from '../i18n';
import { directory } from '../test/labServer';
import { emit, findingStep, openRunStreams, playScenario, RUN_ID, runServer, scenario, step, WORKSPACE } from '../test/runServer';
import type { HistoryEntry, StepResult } from './types';

vi.mock('../workbench/monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('../workbench/monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

const CREATE = 'scenarios/users/create-user.yaml';
const NAME = 'users/create-user.yaml';
const today = (h: number, m: number) => {
  const d = new Date();
  d.setHours(h, m, 0, 0);
  return d.toISOString();
};
const daysAgo = (days: number, h: number, m: number) => {
  const d = new Date();
  d.setDate(d.getDate() - days);
  d.setHours(h, m, 0, 0);
  return d.toISOString();
};

const HISTORY: HistoryEntry[] = [
  { runId: RUN_ID, at: today(0, 2), profile: 'lab', result: 'finding' },
  { runId: '20261013-164501-lab', at: daysAgo(3, 16, 45), profile: 'lab', result: 'passed' },
];

let writeText: ReturnType<typeof vi.fn>;

beforeEach(() => {
  writeText = vi.fn(async () => undefined);
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

function panel() {
  return screen.getByRole('complementary', { name: 'Run' });
}

/** Runs create-user.yaml (Run on the open file) and plays `steps` as its result. */
async function runWith(server: ReturnType<typeof runServer>, steps: StepResult[]) {
  await waitFor(() => expect(screen.getByRole('button', { name: 'Lab ready' })).toBeInTheDocument());
  await userEvent.click(await screen.findByRole('button', { name: 'create-user.yaml' }));
  await waitFor(() => expect((screen.getByLabelText(`Edit ${CREATE}`) as HTMLTextAreaElement).value).toContain('name: Create'));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Run' })).toBeEnabled());
  await userEvent.click(screen.getByRole('button', { name: 'Run' }));
  await waitFor(() => expect(server.runStarts()).toHaveLength(1));
  await waitFor(() => expect(openRunStreams()).toHaveLength(1));
  act(() => {
    emit('runStarted', { runId: RUN_ID, scenarios: [NAME], locale: 'en' });
    playScenario(scenario(NAME, steps));
    emit('runFinished', { runId: RUN_ID, total: 1, passed: 0, findings: 1, setupProblems: 0, stopped: false, exitCode: 1, reportUrl: `/reports/${RUN_ID}/report.html` });
  });
}

function finding() {
  return within(panel()).getByRole('region', { name: 'Finding in step 2' });
}

describe('Finding panel', () => {
  it('says the lab did not do what the step expects, names the system and lines up expected vs found', async () => {
    const server = runServer({ history: HISTORY });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runWith(server, [step(0, 'ldap.create'), findingStep()]);
    const box = finding();
    expect(within(box).getByRole('heading', { name: 'The lab did not do what this step expects' })).toBeInTheDocument();
    expect(within(box).getByText('The scenario ran as written. eDirectory never reached the expected state within the wait.')).toBeInTheDocument();
    const table = within(box).getByRole('table');
    const [, missing, matched] = within(table).getAllByRole('row');
    expect(missing).toHaveTextContent('groupMembershipcontains cn=Finance,ou=groups— (not present) ✕ differs');
    expect(matched).toHaveTextContent('loginDisabledFALSEFALSE ✓ matches');
    expect(within(box).getByText('30 tries every 2 s over 60 s — the value never changed')).toBeInTheDocument();
    expect(within(box).getByText('Looks like it was never provisioned, not just slow.')).toBeInTheDocument();
    const evidence = box.querySelector('pre')!;
    expect(evidence).toHaveTextContent('read cn=u123,ou=users');
    expect(evidence.textContent).toContain('userPassword: ***');
  });

  it('shows the recent runs with "used to pass", without claiming the file is unchanged', async () => {
    const server = runServer({ history: HISTORY });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runWith(server, [step(0, 'ldap.create'), findingStep()]);
    const box = finding();
    const runs = await within(box).findByRole('list', { name: /passed .*; failed today 00:02/ });
    const items = within(runs).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent(/✓ \w+ 16:45/);
    expect(items[1]).toHaveTextContent('✕ Today 00:02');
    expect(within(box).getByText(/It used to pass: last on \w+ 16:45\. If the scenario has not changed since/)).toBeInTheDocument();
    expect(server.sent.some((r) => r.url === `/api/runs/history?scenario=${encodeURIComponent(CREATE)}`)).toBe(true);
  });

  it('says on which try the value changed, and renders evidence as text, never as markup', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    const changed = findingStep({
      waitHistory: [
        { attempt: 1, elapsedMillis: 10, message: 'absent' },
        { attempt: 3, elapsedMillis: 4010, message: 'cn=Other' },
      ],
      evidence: { kind: 'http', method: 'GET', url: 'https://idm.lab/api/users/u1', requestHeaders: { Authorization: ['***'] }, requestBody: null, status: 200, responseHeaders: null, responseBody: '<img src=x onerror=alert(1)>', durationMillis: 120, error: null },
    });
    await runWith(server, [step(0, 'ldap.create'), changed]);
    const box = finding();
    expect(within(box).getByText('30 tries every 2 s over 60 s — the value changed on try 3')).toBeInTheDocument();
    expect(within(box).getByText('It changed while waiting: a slow driver may need a longer wait.')).toBeInTheDocument();
    expect(box.querySelector('pre')!.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(box.querySelector('img')).toBeNull();
    expect(box.querySelector('pre')!.textContent).toContain('Authorization: ***');
  });

  it('Rerun as is runs exactly that scenario again without saving', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runWith(server, [step(0, 'ldap.create'), findingStep()]);
    await userEvent.type(screen.getByLabelText(`Edit ${CREATE}`), '# not saved');
    await userEvent.click(within(finding()).getByRole('button', { name: '↻ Rerun as is' }));
    await waitFor(() => expect(server.runStarts()).toHaveLength(2));
    expect(server.runStarts()[1].body).toMatchObject({ scenarios: [CREATE], onlyFindings: false });
    expect(server.puts()).toHaveLength(0);
  });

  it('Copy finding puts the plain-text summary on the clipboard and says "Copied"', async () => {
    const server = runServer({
      history: HISTORY,
      lab: { systems: [{ ...directory('ready'), fields: [{ key: 'url', label: 'URL', value: 'ldaps://admin:fake-pw@edir.lab:636', secret: false, required: true, state: 'set' }] }] },
    });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runWith(server, [step(0, 'ldap.create'), findingStep()]);
    await within(finding()).findByRole('list', { name: /passed/ });
    await userEvent.click(within(finding()).getByRole('button', { name: 'Copy finding' }));
    expect(await within(finding()).findByRole('status')).toHaveTextContent('Copied');
    const text = writeText.mock.calls[0][0] as string;
    expect(text.split('\n').slice(0, 4)).toEqual([
      'idmtest finding · lab-tests / users/create-user.yaml',
      `Profile: lab · Run ${RUN_ID} · eDirectory ldaps://edir.lab:636`,
      '',
      'Step 2 ldap.verify cn=u123,ou=users — the lab did not do what this step expects',
    ]);
    expect(text).toContain(`Report: reports/${RUN_ID}/report.html`);
    expect(text).not.toContain('fake-pw');
  });

  it('Open full report opens the run’s report in a new tab; Edit scenario marks the step’s lines', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runWith(server, [step(0, 'ldap.create'), findingStep()]);
    const report = within(finding()).getByRole('link', { name: 'Open full report' });
    // a link cannot send a header: it names the workspace it is for
    expect(report).toHaveAttribute('href', `/reports/${RUN_ID}/report.html?workspace=${encodeURIComponent(WORKSPACE.path)}`);
    expect(report).toHaveAttribute('target', '_blank');
    expect(report).toHaveAttribute('rel', expect.stringContaining('noopener'));
    await userEvent.click(within(finding()).getByRole('button', { name: 'Edit scenario' }));
    const editor = screen.getByLabelText(`Edit ${CREATE}`);
    // step 2 is the second item under steps: (lines 5-8 of the fixture)
    await waitFor(() => expect(editor).toHaveAttribute('data-marks', '5-8'));
    expect(editor).toHaveAttribute('data-focus-line', '5');
  });

  it('the editor marks the failed step by itself, also when the file is opened later, and a new run clears it', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    // the open file is another one: create-user.yaml is neither opened nor activated by the finding
    await waitFor(() => expect(screen.getByRole('button', { name: 'Lab ready' })).toBeInTheDocument());
    await userEvent.click(await screen.findByRole('button', { name: /^hr-sync\.yaml/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'More ways to run' })).toBeEnabled());
    await userEvent.click(screen.getByRole('button', { name: 'More ways to run' }));
    await userEvent.click(await screen.findByRole('menuitem', { name: /Run all scenarios/ }));
    await waitFor(() => expect(openRunStreams()).toHaveLength(1));
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: [NAME], locale: 'en' });
      playScenario(scenario(NAME, [step(0, 'ldap.create'), findingStep()]));
    });
    expect(finding()).toBeInTheDocument();
    expect(screen.queryByLabelText(`Edit ${CREATE}`)).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { selected: true })).toHaveTextContent('hr-sync.yaml');

    await userEvent.click(screen.getByRole('button', { name: /^create-user\.yaml/ }));
    const editor = await screen.findByLabelText(`Edit ${CREATE}`);
    await waitFor(() => expect(editor).toHaveAttribute('data-marks', '5-8'));

    act(() => emit('runFinished', { runId: RUN_ID, total: 1, passed: 0, findings: 1, setupProblems: 0, stopped: false, exitCode: 1, reportUrl: null }));
    await userEvent.click(within(finding()).getByRole('button', { name: '↻ Rerun as is' }));
    await waitFor(() => expect(server.runStarts()).toHaveLength(2));
    await waitFor(() => expect(openRunStreams()).toHaveLength(1));
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: [NAME], locale: 'en' });
      emit('scenarioStarted', { name: NAME });
    });
    await waitFor(() => expect(screen.getByLabelText(`Edit ${CREATE}`)).toHaveAttribute('data-marks', ''));
  });

  it('a file already open is marked at once; a setup problem marks its step too', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runWith(server, [step(0, 'ldap.create', { success: false, failureKind: 'SETUP', message: 'dn must be a string' })]);
    await waitFor(() => expect(screen.getByLabelText(`Edit ${CREATE}`)).toHaveAttribute('data-marks', '3-4'));
  });

  it('Copy finding waits for the recent runs, so the History line is never left out', async () => {
    let answer: (value: unknown) => void = () => undefined;
    const server = runServer({
      routes: { 'GET /api/runs/history': () => new Promise((resolve) => (answer = resolve)) as never },
    });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runWith(server, [step(0, 'ldap.create'), findingStep()]);
    const copy = within(finding()).getByRole('button', { name: 'Copy finding' });
    expect(copy).toBeDisabled();
    await act(async () => answer({ body: HISTORY }));
    await waitFor(() => expect(copy).toBeEnabled());
    await userEvent.click(copy);
    await waitFor(() => expect(writeText).toHaveBeenCalled());
    expect(writeText.mock.calls[0][0]).toMatch(/^History: passed .*; failed today 00:02\.$/m);
  });

  it('a SETUP step shows a red "Setup problem" with a way to the lab panel for a connection problem', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runWith(server, [
      step(0, 'ldap.create', { success: false, failureKind: 'SETUP', message: 'could not reach edir.lab:636: Connection refused' }),
    ]);
    const setup = within(panel()).getByRole('region', { name: /Setup problem/ });
    expect(within(setup).getByRole('img')).toHaveClass('badge-error');
    expect(within(setup).getByText('could not reach edir.lab:636: Connection refused')).toBeInTheDocument();
    expect(within(panel()).queryByText('The lab did not do what this step expects')).not.toBeInTheDocument();
    await userEvent.click(within(setup).getByRole('button', { name: 'Check the lab settings for eDirectory' }));
    const lab = await screen.findByRole('dialog', { name: /Lab connection/ });
    expect(within(lab).getByRole('tab', { selected: true })).toHaveTextContent('eDirectory');
  });

  it('a malformed-parameter SETUP step has no lab link', async () => {
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await runWith(server, [step(0, 'ldap.create', { success: false, failureKind: 'SETUP', message: "dn must be a string, got [a, b]" })]);
    const setup = within(panel()).getByRole('region', { name: /Setup problem/ });
    expect(within(setup).queryByRole('button')).not.toBeInTheDocument();
  });

  it('speaks Spanish', async () => {
    setLanguage('es', false);
    const server = runServer();
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Lab listo' })).toBeInTheDocument());
    await userEvent.click(await screen.findByRole('button', { name: 'create-user.yaml' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ejecutar' })).toBeEnabled());
    await userEvent.click(screen.getByRole('button', { name: 'Ejecutar' }));
    await waitFor(() => expect(server.runStarts()).toHaveLength(1));
    await waitFor(() => expect(openRunStreams()).toHaveLength(1));
    act(() => {
      emit('runStarted', { runId: RUN_ID, scenarios: [NAME], locale: 'es' });
      playScenario(scenario(NAME, [step(0, 'ldap.create'), findingStep()]));
    });
    expect(screen.getByText('El laboratorio no hizo lo que este paso espera')).toBeInTheDocument();
    expect(screen.getByText('30 intentos cada 2 s durante 60 s — el valor nunca cambió')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '↻ Repetir tal cual' })).toBeInTheDocument();
  });
});
