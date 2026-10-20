import type { LastRun } from '../api/client';
import type { BadgeStatus } from '../components/Badge';
import { translate, type Language } from '../i18n';

/** The last part of a folder path, either separator, trailing ones ignored. */
export function baseName(path: string): string {
  const parts = path.split(/[\\/]+/).filter((part) => part !== '');
  return parts.length ? parts[parts.length - 1] : path;
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** "today 14:32", "yesterday", else the date ("12 Oct", with the year when it is not this one). */
export function when(at: string, now: Date, lang: Language): string {
  const date = new Date(at);
  if (Number.isNaN(date.getTime())) {
    return at;
  }
  if (sameDay(date, now)) {
    const time = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
    return translate(lang, 'ui.when.today', { time });
  }
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (sameDay(date, yesterday)) {
    return translate(lang, 'ui.when.yesterday');
  }
  return new Intl.DateTimeFormat(lang, {
    day: 'numeric',
    month: 'short',
    ...(date.getFullYear() === now.getFullYear() ? {} : { year: 'numeric' }),
  }).format(date);
}

/** Red when a setup problem stopped a scenario, amber for findings, else green. */
export function runStatus(run: LastRun): BadgeStatus {
  if (run.setupProblems > 0) {
    return 'error';
  }
  return run.findings > 0 ? 'finding' : 'ok';
}

/** "2 findings", "1 finding, 1 setup problem", "12 passed". */
export function runSummary(run: LastRun, lang: Language): string {
  const parts: string[] = [];
  if (run.findings > 0) {
    parts.push(translate(lang, 'ui.result.findings', { count: run.findings }));
  }
  if (run.setupProblems > 0) {
    parts.push(translate(lang, 'ui.result.setup', { count: run.setupProblems }));
  }
  return parts.length ? parts.join(', ') : translate(lang, 'ui.result.passed', { count: run.passed });
}
