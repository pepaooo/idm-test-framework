import { currentLanguage, t } from '../i18n';
import type { HistoryEntry, StepResult } from './types';

/**
 * The plain-text "Copy finding" block (spec §4.8, mockup finding-panel.html) and the small
 * formatters the finding panel shares with it. Every value comes from the server already masked;
 * system URLs additionally lose their credentials here ({@link safeUrl}).
 */

/** Query or `;` parameters whose value must never be copied. */
const SECRET_PARAM = /pass|pwd|secret|token|key|credential|auth|signature|session/i;

/**
 * `url` without credentials: no `user:password@` (also Oracle's `thin:user/password@`) and no
 * parameter whose name looks secret (`?password=`, `;password=`, `client_secret`, `apiKey`…).
 */
export function safeUrl(url: string): string {
  // up to the LAST @ of the authority: a password may hold a raw "@"
  let out = url.replace(/^(jdbc:oracle:thin:)[^?;#]*@/i, '$1@');
  if (/^[a-z][a-z0-9+.:-]*:\/\//i.test(out)) {
    out = out.replace(/^([a-z][a-z0-9+.:-]*:\/\/)[^/?#;]*@/i, '$1');
  } else if (!/^jdbc:/i.test(out)) {
    out = out.replace(/^[^/?#;]*@/, ''); // no scheme: user:password@host…
  }
  const hash = out.indexOf('#');
  const fragment = hash >= 0 ? out.slice(hash) : '';
  out = hash >= 0 ? out.slice(0, hash) : out;
  const question = out.indexOf('?');
  if (question >= 0) {
    const kept = out
      .slice(question + 1)
      .split('&')
      .filter((pair) => pair !== '' && !SECRET_PARAM.test(decodeName(pair.split('=')[0])));
    out = out.slice(0, question) + (kept.length ? `?${kept.join('&')}` : '');
  }
  if (out.includes(';')) {
    const [head, ...pairs] = out.split(';');
    const kept = pairs.filter((pair) => !(pair.includes('=') && SECRET_PARAM.test(pair.split('=')[0])));
    out = [head, ...kept].join(';');
  }
  return out + fragment;
}

function decodeName(name: string): string {
  try {
    return decodeURIComponent(name);
  } catch {
    return name;
  }
}

/** Milliseconds as "60 s", "2 s", "0.4 s" (whole seconds from 10 s up). */
export function seconds(millis: number): string {
  const value = millis / 1000;
  const rounded = value >= 10 ? Math.round(value) : Math.round(value * 10) / 10;
  return t('ui.unit.seconds', { n: new Intl.NumberFormat(currentLanguage(), { maximumFractionDigits: 1 }).format(rounded) });
}

/** A `wait.poll` such as "2s", "1m" in milliseconds, or null. */
function pollMillis(step: StepResult): number | null {
  const wait = step.resolvedParams?.wait;
  const poll = wait && typeof wait === 'object' ? (wait as Record<string, unknown>).poll : undefined;
  const match = typeof poll === 'string' ? /^\s*(\d+)\s*([smh])\s*$/.exec(poll) : null;
  if (!match) {
    return null;
  }
  return Number(match[1]) * { s: 1000, m: 60_000, h: 3_600_000 }[match[2] as 's' | 'm' | 'h'];
}

/** The waiting of a step that waited: tries, interval, time, and the tries whose value changed. */
interface Waiting {
  tries: number;
  interval: number | null;
  waited: number;
  /** The tries (after the first) whose observation differed from the one before. */
  changedOn: number[];
}

function waitingOf(step: StepResult): Waiting | null {
  if (step.attempts == null || step.waitedMillis == null) {
    return null;
  }
  const tries = step.attempts;
  const interval = tries > 1 ? (pollMillis(step) ?? Math.round(step.waitedMillis / tries)) : null;
  const changedOn = (step.waitHistory ?? []).slice(1).map((entry) => entry.attempt);
  return { tries, interval, waited: step.waitedMillis, changedOn };
}

function changeText(changedOn: number[], key: 'ui.finding.waiting' | 'ui.copy') {
  if (changedOn.length === 0) {
    return t(`${key}.never`);
  }
  return t(`${key}.changed`, { tries: changedOn.join(', '), count: changedOn.length });
}

function triesText(waiting: Waiting): string {
  const tries = t('ui.finding.tries', { count: waiting.tries });
  return waiting.interval === null ? tries : t('ui.finding.triesEvery', { tries, interval: seconds(waiting.interval) });
}

/**
 * The finding panel's waiting row: "30 tries every 2 s over 60 s — the value never changed", or
 * "— the value changed on try 3"; null when the step did not wait.
 */
export function waitingSummary(step: StepResult): string | null {
  const waiting = waitingOf(step);
  if (!waiting) {
    return null;
  }
  return t('ui.finding.waiting.summary', {
    tries: triesText(waiting),
    waited: seconds(waiting.waited),
    change: changeText(waiting.changedOn, 'ui.finding.waiting'),
  });
}

/** Whether the waited-for value changed during the wait (a slow driver rather than nothing at all). */
export function waitingChanged(step: StepResult): boolean | null {
  const waiting = waitingOf(step);
  return waiting ? waiting.changedOn.length > 0 : null;
}

const DAY = 86_400_000;

function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}

/**
 * When a run was, as a short label: "today" (or "Today"), a weekday within the last 7 days, else
 * the date.
 */
export function dayLabel(iso: string, now: Date, capital = false): string {
  const date = new Date(iso);
  const days = Math.round((startOfDay(now) - startOfDay(date)) / DAY);
  if (days === 0) {
    return t(capital ? 'ui.time.Today' : 'ui.time.today');
  }
  if (days > 0 && days <= 7) {
    return new Intl.DateTimeFormat(currentLanguage(), { weekday: 'short' }).format(date);
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function timeLabel(iso: string): string {
  const date = new Date(iso);
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

/** "Mon 09:10", "Today 14:32". */
export function whenLabel(iso: string, now: Date, capital = true): string {
  return `${dayLabel(iso, now, capital)} ${timeLabel(iso)}`;
}

/**
 * "passed Mon 09:10, Tue 16:45; failed today 11:02, 14:32": oldest first, runs with the same
 * outcome in a row grouped, a day said once per group.
 */
export function historyText(history: readonly HistoryEntry[], now: Date): string {
  const groups: { outcome: HistoryEntry['result']; times: string[]; lastDay: string }[] = [];
  for (const entry of [...history].reverse()) {
    const day = dayLabel(entry.at, now);
    let group = groups[groups.length - 1];
    if (!group || group.outcome !== entry.result) {
      group = { outcome: entry.result, times: [], lastDay: '' };
      groups.push(group);
    }
    group.times.push(day === group.lastDay ? timeLabel(entry.at) : `${day} ${timeLabel(entry.at)}`);
    group.lastDay = day;
  }
  return groups.map((group) => `${t(`ui.copy.outcome.${group.outcome}`)} ${group.times.join(', ')}`).join('; ');
}

/** What a step looked at, for its one-line title: its description, else its main parameter. */
export function stepSubject(step: Pick<StepResult, 'description' | 'resolvedParams'>): string {
  if (step.description) {
    return step.description;
  }
  const params = step.resolvedParams ?? {};
  for (const name of ['dn', 'path', 'url', 'query', 'user', 'role', 'roles', 'resource', 'resources', 'sql']) {
    const value = params[name];
    if (typeof value === 'string' && value !== '') {
      return value;
    }
    if (Array.isArray(value) && value.length > 0) {
      return value.map(String).join(', ');
    }
  }
  return '';
}

export interface FindingToCopy {
  /** The scenario's path under scenarios/. */
  scenario: string;
  step: StepResult;
}

export interface RunToCopy {
  /** The workspace's name. */
  workspace: string;
  profile: string | null;
  runId: string | null;
  /** The system the step talked to, with its URL (credentials are removed here). */
  system: { name: string; url: string | null } | null;
  /** This scenario's recent runs, newest first (the copied run included). */
  history: readonly HistoryEntry[];
  now: Date;
}

/** The plain-text summary "Copy finding" puts on the clipboard (spec §4.8). */
export function copyFinding(result: FindingToCopy, run: RunToCopy): string {
  const { step } = result;
  const lines: string[] = [t('ui.copy.head', { workspace: run.workspace, scenario: result.scenario })];
  const where = [t('ui.copy.profile', { profile: run.profile ?? '—' })];
  if (run.runId) {
    where.push(t('ui.copy.run', { runId: run.runId }));
  }
  if (run.system) {
    where.push(run.system.url ? `${run.system.name} ${safeUrl(run.system.url)}` : run.system.name);
  }
  lines.push(where.join(' · '), '');
  const subject = stepSubject(step);
  lines.push(t('ui.copy.step', { n: step.index + 1, type: step.type, subject: subject ? ` ${subject}` : '' }));
  const checks = step.checks ?? [];
  if (checks.length > 0) {
    const width = Math.max(...checks.map((check) => check.name.length + 1)) + 1;
    for (const check of checks) {
      const found = check.found ?? t('ui.copy.notPresent');
      lines.push(
        `  ${`${check.name}:`.padEnd(width)}${t('ui.copy.check', { expected: check.expected ?? '', found })}${check.matched ? ' ✓' : ''}`,
      );
    }
  } else if (step.message) {
    lines.push(`  ${step.message}`);
  }
  const waiting = waitingOf(step);
  if (waiting) {
    lines.push(
      t('ui.copy.waited', { waited: seconds(waiting.waited), tries: triesText(waiting), change: changeText(waiting.changedOn, 'ui.copy') }),
    );
  }
  if (run.history.length > 0) {
    lines.push(t('ui.copy.history', { history: historyText(run.history, run.now) }));
  }
  if (run.runId) {
    lines.push(t('ui.copy.report', { runId: run.runId }));
  }
  return lines.join('\n');
}
