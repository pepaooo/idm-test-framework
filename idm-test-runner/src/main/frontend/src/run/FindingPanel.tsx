import { useEffect, useState } from 'react';
import { api, withQuery } from '../api/client';
import { Badge, type BadgeStatus } from '../components/Badge';
import { t, useLanguage } from '../i18n';
import { useWorkbench } from '../workbench/context';
import { copyFinding, historyText, seconds, stepSubject, waitingChanged, waitingSummary, whenLabel } from './copyFinding';
import { stepLines } from './scenarioYaml';
import { isConnectionProblem, systemOf } from './systems';
import { scenarioPath, type CleanupResult, type Evidence, type HistoryEntry, type StepResult } from './types';

const HISTORY_BADGE: Record<HistoryEntry['result'], BadgeStatus> = { passed: 'ok', finding: 'finding', setup: 'error' };

/** A value as one line of text (objects as JSON); the server masked it. */
export function valueText(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (typeof value === 'string') {
    return value;
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function pretty(body: unknown): string {
  if (typeof body === 'string') {
    const trimmed = body.trim();
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      try {
        return JSON.stringify(JSON.parse(trimmed), null, 2);
      } catch {
        return body;
      }
    }
    return body;
  }
  return JSON.stringify(body, null, 2);
}

function headers(lines: string[], values: Record<string, string[]> | null) {
  Object.entries(values ?? {}).forEach(([name, list]) => lines.push(`${name}: ${(list ?? []).join(', ')}`));
}

/**
 * A step's evidence as plain text, in the shape the HTML report shows it (EvidenceHtml): the HTTP
 * request and response, the SQL with its rows, the LDAP entry's attributes. Already masked.
 */
export function evidenceText(evidence: Evidence): string {
  const lines: string[] = [];
  switch (evidence.kind) {
    case 'http':
      lines.push(`${evidence.method} ${evidence.url}`);
      headers(lines, evidence.requestHeaders);
      if (evidence.requestBody !== null && evidence.requestBody !== undefined && evidence.requestBody !== '') {
        lines.push('', pretty(evidence.requestBody));
      }
      lines.push('');
      if (evidence.status === null) {
        lines.push(`→ ${evidence.error ?? ''}`);
      } else {
        lines.push(`→ ${evidence.status} · ${seconds(evidence.durationMillis)}`);
        headers(lines, evidence.responseHeaders);
        if (evidence.responseBody !== null && evidence.responseBody !== undefined && evidence.responseBody !== '') {
          lines.push('', pretty(evidence.responseBody));
        }
      }
      break;
    case 'sql':
      lines.push(evidence.sql);
      if (evidence.params?.length) {
        lines.push(`params: ${JSON.stringify(evidence.params)}`);
      }
      if (evidence.updateCount !== null && evidence.updateCount !== undefined) {
        lines.push(`updated: ${evidence.updateCount}`);
      }
      (evidence.rows ?? []).forEach((r) => lines.push(Object.entries(r).map(([column, value]) => `${column}: ${valueText(value)}`).join(' · ')));
      if (evidence.totalRows !== null && evidence.rows && evidence.totalRows > evidence.rows.length) {
        lines.push(`… +${evidence.totalRows - evidence.rows.length}`);
      }
      break;
    case 'ldap':
      lines.push(
        [`${evidence.operation} ${evidence.dn}`, evidence.directory, evidence.present === false ? '(no entry)' : null].filter(Boolean).join(' · '),
      );
      Object.entries(evidence.attributes ?? {}).forEach(([name, value]) =>
        lines.push(`${name}: ${Array.isArray(value) ? value.map(valueText).join(', ') : valueText(value)}`),
      );
      break;
  }
  return lines.join('\n');
}

/**
 * `name: value` per resolved parameter. The server's run events mask them: the run's secret values
 * as `****`, and every value under a secret-looking key (`password`, `Authorization`, `token`, …) as
 * `***`, as report.html shows them — so a token bound while the run ran is hidden too.
 */
function Params({ params }: { params: Record<string, unknown> }) {
  const entries = Object.entries(params ?? {});
  if (entries.length === 0) {
    return null;
  }
  return (
    <dl className="run-params">
      {entries.map(([name, value]) => (
        <div key={name}>
          <dt>{name}</dt>
          <dd className="mono">{valueText(value)}</dd>
        </div>
      ))}
    </dl>
  );
}

/** The red panel of a step (or a scenario) whose setup needs fixing (spec §4.7). */
export function SetupPanel({ scenario, step, message }: { scenario: string; step: StepResult | null; message: string | null }) {
  useLanguage();
  const wb = useWorkbench();
  const system = step ? systemOf(step, scenario, wb.lab) : null;
  const connection = step !== null && isConnectionProblem(step);
  return (
    <section className="setup-panel" aria-labelledby="setup-title">
      <h3 id="setup-title">
        <Badge status="error" /> {t('ui.setup.title')}
      </h3>
      {message && <p className="setup-message">{message}</p>}
      <p className="muted">{t('ui.setup.hint')}</p>
      {connection && (
        <button type="button" className="secondary" onClick={() => wb.openLab(system?.inLab && system.id ? system.id : undefined)}>
          {system?.inLab ? t('ui.setup.openLab', { system: system.name }) : t('ui.setup.openLabAny')}
        </button>
      )}
    </section>
  );
}

/** Cleanup entries: ✓ / skipped, and failed ones amber with what may be left in the lab (spec §4.7). */
export function CleanupList({ entries }: { entries: readonly CleanupResult[] }) {
  if (entries.length === 0) {
    return null;
  }
  return (
    <section className="run-cleanup" aria-label={t('ui.run.cleanup')}>
      <h4>{t('ui.run.cleanup')}</h4>
      <ul>
        {entries.map((entry) => (
          <li key={entry.index} className={entry.status === 'FAILED' ? 'cleanup-failed' : undefined}>
            <span>
              <Badge
                status={entry.status === 'SUCCEEDED' ? 'ok' : entry.status === 'FAILED' ? 'finding' : 'warn'}
                label={t(`ui.run.cleanup.${entry.status}`)}
              />{' '}
              {entry.index + 1} {entry.type}
              {entry.description ? ` — ${entry.description}` : ''}
            </span>
            {entry.status === 'FAILED' && (
              <>
                {entry.message && <p>{entry.message}</p>}
                <p className="left-in-lab">{t('ui.run.cleanup.leftInLab')}</p>
                <Params params={entry.resolvedParams} />
              </>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** A stopped scenario: "Stopped — cleanup did not run", with what each skipped entry would have used. */
export function StoppedView({ skipped }: { skipped: readonly CleanupResult[] }) {
  return (
    <section className="run-stopped" aria-labelledby="stopped-title">
      <h3 id="stopped-title">
        <Badge status="warn" label={t('ui.run.state.stopped')} /> {t('ui.run.stopped.title')}
      </h3>
      {skipped.length === 0 ? (
        <p className="muted">{t('ui.run.stopped.none')}</p>
      ) : (
        <>
          <p>{t('ui.run.stopped.hint')}</p>
          <ul>
            {skipped.map((entry) => (
              <li key={entry.index}>
                <span className="mono">
                  {entry.index + 1} {entry.type}
                </span>
                {entry.description ? ` — ${entry.description}` : ''}
                <Params params={entry.resolvedParams} />
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

interface FindingProps {
  /** The scenario's path under scenarios/. */
  scenario: string;
  step: StepResult;
  runId: string | null;
  profile: string | null;
  reportUrl: string | null;
  /** Whether Rerun as is can run now. */
  canRerun: boolean;
  onRerun(): void;
}

async function writeClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // fall back below
  }
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.select();
  let done = false;
  try {
    done = document.execCommand?.('copy') ?? false;
  } catch {
    done = false;
  }
  area.remove();
  return done;
}

/**
 * The finding panel (spec §4.8, mockup finding-panel.html): the lab did not do what the step
 * expects — expected vs found, the waiting, the evidence, this scenario's recent runs — and Rerun as
 * is, Copy finding, Open full report, Edit scenario. Everything is text from the server, masked.
 */
export function FindingPanel({ scenario, step, runId, profile, reportUrl, canRerun, onRerun }: FindingProps) {
  useLanguage();
  const wb = useWorkbench();
  const [history, setHistory] = useState<HistoryEntry[] | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const path = scenarioPath(scenario);
  const system = systemOf(step, scenario, wb.lab);
  const checks = step.checks ?? [];
  const waiting = waitingSummary(step);
  const changed = waitingChanged(step);
  const now = new Date();

  useEffect(() => {
    let live = true;
    setHistory(null);
    api
      .get<HistoryEntry[]>(withQuery('/api/runs/history', { scenario: path }))
      .then((entries) => live && setHistory(entries))
      .catch(() => live && setHistory([]));
    return () => {
      live = false;
    };
  }, [path, runId]);

  const newest = history?.[0];
  const lastPass = history?.find((entry) => entry.result === 'passed');
  const usedToPass = newest && newest.result !== 'passed' && lastPass ? lastPass : null;

  const copy = async () => {
    const text = copyFinding(
      { scenario, step },
      { workspace: wb.workspace.name, profile, runId, system: { name: system.name, url: system.url }, history: history ?? [], now: new Date() },
    );
    setCopied((await writeClipboard(text)) ? t('ui.finding.copied') : t('ui.finding.copyFailed'));
  };

  const edit = async () => {
    await wb.openFile(path);
    const range = stepLines(wb.textOf(path) ?? '', step.index);
    if (range) {
      await wb.markLines(path, range.from, range.to);
      wb.focus(path, range.from);
    }
  };

  return (
    <section className="finding-panel" aria-label={t('ui.finding.label', { n: step.index + 1 })}>
      <div className="finding-head">
        <h3>{t('ui.finding.headline')}</h3>
        <p className="muted">{t(waiting ? 'ui.finding.sub' : 'ui.finding.subNoWait', { system: system.name })}</p>
        {checks.length === 0 && step.message && <p>{t('ui.finding.said', { message: step.message })}</p>}
      </div>

      {checks.length > 0 && (
        <>
          <h4>{t('ui.finding.expectedVsFound')}</h4>
          <table className="finding-checks">
            <thead>
              <tr>
                <th scope="col">{t('ui.finding.col.name')}</th>
                <th scope="col">{t('ui.finding.col.expected')}</th>
                <th scope="col">{t('ui.finding.col.found')}</th>
              </tr>
            </thead>
            <tbody>
              {checks.map((check, i) => (
                <tr key={`${check.name}-${i}`} className={check.matched ? 'check-matched' : 'check-differs'}>
                  <th scope="row" className="mono">
                    {check.name}
                  </th>
                  <td className="mono">{check.expected ?? ''}</td>
                  <td className="mono">
                    {check.matched ? (
                      <>
                        {check.found ?? ''} <span className="check-mark">✓ {t('ui.finding.matches')}</span>
                      </>
                    ) : (
                      <>
                        <strong>{check.found ?? t('ui.finding.notPresent')}</strong>{' '}
                        <span className="check-mark">✕ {t('ui.finding.differs')}</span>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {waiting && (
        <>
          <h4>{t('ui.finding.waiting')}</h4>
          <p className="finding-waiting">{waiting}</p>
          <p className="muted">{t(changed ? 'ui.finding.waiting.changedHint' : 'ui.finding.waiting.neverHint')}</p>
        </>
      )}

      {step.evidence && (
        <>
          <h4>{t('ui.finding.evidence')}</h4>
          <pre className="finding-evidence">{evidenceText(step.evidence)}</pre>
        </>
      )}

      <h4>{t('ui.finding.history')}</h4>
      {history === null ? (
        <p className="muted">{t('ui.finding.history.loading')}</p>
      ) : history.length === 0 ? (
        <p className="muted">{t('ui.finding.history.none')}</p>
      ) : (
        <>
          <ol className="finding-history" aria-label={historyText(history, now)}>
            {[...history].reverse().map((entry) => (
              <li key={entry.runId}>
                <Badge status={HISTORY_BADGE[entry.result]} label={t(`ui.run.state.${entry.result}`)} /> {whenLabel(entry.at, now)}
              </li>
            ))}
          </ol>
          {usedToPass && <p className="finding-hint">{t('ui.finding.usedToPass', { when: whenLabel(usedToPass.at, now, false) })}</p>}
        </>
      )}

      <div className="finding-actions">
        <button type="button" className="primary" disabled={!canRerun} onClick={onRerun}>
          {t('ui.finding.rerun')}
        </button>
        <button type="button" className="secondary" disabled={history === null} onClick={() => void copy()}>
          {t('ui.finding.copy')}
        </button>
        {reportUrl && (
          <a className="secondary button-link" href={reportUrl} target="_blank" rel="noopener noreferrer">
            {t('ui.finding.openReport')}
          </a>
        )}
        <button type="button" className="secondary" onClick={() => void edit()}>
          {t('ui.finding.edit')}
        </button>
      </div>
      <p className="finding-copied" role="status">
        {copied ?? ''}
      </p>
    </section>
  );
}

/** One step's line in the run panel: "2 ldap.verify — cn=u123,ou=users". */
export function stepTitle(step: Pick<StepResult, 'index' | 'type' | 'description' | 'resolvedParams'>): string {
  const subject = stepSubject(step);
  return `${step.index + 1} ${step.type}${subject ? ` — ${subject}` : ''}`;
}
