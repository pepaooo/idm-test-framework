import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RunsView } from './RunsView';
import { Workbench } from '../workbench/Workbench';
import { WORKSPACE, workbenchServer } from '../test/workbenchServer';
import { setLanguage } from '../i18n';
import { fakeServer } from '../test/fakeServer';
import type { HistoryEntry } from './types';

vi.mock('../workbench/monaco/CodeEditor', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeCodeEditor }));
vi.mock('../workbench/monaco/DiffView', async () => ({ default: (await import('../test/fakeCodeEditor')).FakeDiffView }));

afterEach(() => {
  vi.unstubAllGlobals();
  setLanguage('en', false);
});

const HISTORY: HistoryEntry[] = [
  { runId: '20261020-091000-lab', at: '2026-10-20T09:10:00', profile: 'lab', result: 'finding' },
  { runId: '20261019-143205-uat', at: '2026-10-19T14:32:00', profile: 'uat', result: 'passed' },
  { runId: '20261018-080000-lab', at: '2026-10-18T08:00:00', profile: null, result: null },
];

describe('RunsView', () => {
  it('lists the runs newest first with id, date, profile and result, each with its report and PDF menu', async () => {
    const server = fakeServer({ 'GET /api/runs/history': { body: HISTORY } });
    render(<RunsView onClose={() => undefined} />);
    const rows = await screen.findAllByRole('listitem');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('20261020-091000-lab');
    expect(rows[0]).toHaveTextContent('lab');
    expect(rows[0]).toHaveTextContent('Finding');
    expect(rows[1]).toHaveTextContent('20261019-143205-uat');
    expect(rows[1]).toHaveTextContent('Passed');
    expect(within(rows[1]).getByRole('link', { name: 'Open report' })).toHaveAttribute('href', '/reports/20261019-143205-uat/report.html');
    expect(within(rows[1]).getByRole('button', { name: /Download PDF/ })).toBeInTheDocument();
    expect(server.calls()).toEqual(['GET /api/runs/history']);
  });

  it('says the results cannot be read, with no report link and no PDF menu', async () => {
    fakeServer({ 'GET /api/runs/history': { body: HISTORY } });
    render(<RunsView onClose={() => undefined} />);
    const rows = await screen.findAllByRole('listitem');
    expect(rows[2]).toHaveTextContent('The results cannot be read');
    expect(within(rows[2]).queryByRole('link')).not.toBeInTheDocument();
    expect(within(rows[2]).queryByRole('button')).not.toBeInTheDocument();
  });

  it('says there are no runs yet', async () => {
    fakeServer({ 'GET /api/runs/history': { body: [] } });
    render(<RunsView onClose={() => undefined} />);
    expect(await screen.findByText('No runs yet')).toBeInTheDocument();
  });

  it('closes with the button and with Escape, the focus starting on the button', async () => {
    fakeServer({ 'GET /api/runs/history': { body: [] } });
    const onClose = vi.fn();
    render(<RunsView onClose={onClose} />);
    const close = screen.getByRole('button', { name: 'Close' });
    await waitFor(() => expect(close).toHaveFocus());
    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
    await userEvent.click(close);
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('opens from the top bar button and gives the focus back when closed', async () => {
    workbenchServer({ files: { 'scenarios/a.yaml': 'name: A\n' }, routes: { 'GET /api/runs/history': { body: HISTORY } } });
    render(<Workbench workspace={WORKSPACE} lockWait={10} />);
    const button = await screen.findByRole('button', { name: 'Runs' });
    await userEvent.click(button);
    expect(await screen.findByRole('dialog', { name: 'Runs' })).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Runs' })).not.toBeInTheDocument();
    expect(button).toHaveFocus();
  });
});
