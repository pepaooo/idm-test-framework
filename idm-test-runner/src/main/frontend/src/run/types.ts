/**
 * What the runs API sends (RunsController / RunSessions): the events of `GET /api/runs/current/events`
 * and the history of `GET /api/runs/history`. Every text in them is masked by the server.
 */

/** One comparison a verify step made on its last try (engine `Check`). */
export interface Check {
  name: string;
  expected: string | null;
  found: string | null;
  matched: boolean;
}

/** A try under `wait:` whose observation differed from the try before (the first always counts). */
export interface WaitObservation {
  attempt: number;
  elapsedMillis: number;
  message: string | null;
}

export interface HttpEvidence {
  kind: 'http';
  method: string;
  url: string;
  requestHeaders: Record<string, string[]> | null;
  requestBody: unknown;
  status: number | null;
  responseHeaders: Record<string, string[]> | null;
  responseBody: unknown;
  durationMillis: number;
  error: string | null;
}

export interface SqlEvidence {
  kind: 'sql';
  database: string;
  sql: string;
  params: unknown[];
  rows: Record<string, unknown>[] | null;
  totalRows: number | null;
  updateCount: number | null;
}

export interface LdapEvidence {
  kind: 'ldap';
  directory: string | null;
  dn: string;
  operation: string;
  attributes: Record<string, unknown>;
  present: boolean | null;
}

export type Evidence = HttpEvidence | SqlEvidence | LdapEvidence;

/** What one step did (engine `StepResult`, as in results.json). */
export interface StepResult {
  /** From 0. */
  index: number;
  type: string;
  resolvedParams: Record<string, unknown>;
  success: boolean;
  durationMillis: number;
  message: string | null;
  attempts: number | null;
  waitedMillis: number | null;
  description: string | null;
  evidence?: Evidence;
  messageKey?: string;
  messageArgs?: unknown[];
  failureKind?: 'FINDING' | 'SETUP';
  waitHistory?: WaitObservation[];
  checks?: Check[];
}

export interface CleanupResult {
  index: number;
  type: string;
  resolvedParams: Record<string, unknown>;
  status: 'SUCCEEDED' | 'FAILED' | 'SKIPPED';
  durationMillis: number;
  message: string | null;
  description: string | null;
  messageKey?: string;
  failureKind?: 'FINDING';
}

/** The outcome of one scenario (engine `ScenarioResult`). */
export interface ScenarioResult {
  name: string;
  /** The path under scenarios/, e.g. `users/create-user.yaml`. */
  sourceName: string;
  success: boolean;
  durationMillis: number;
  steps: StepResult[];
  cleanup: CleanupResult[];
  failureDetail: string | null;
  /** The message key of `failureDetail`, when it has one. */
  failureDetailKey?: string;
  description: string | null;
  stopped?: boolean;
  skippedCleanup?: CleanupResult[];
}

export interface RunStartedEvent {
  runId: string;
  scenarios: string[];
  locale: string;
}

export interface RunFinishedEvent {
  runId: string | null;
  total: number;
  passed: number;
  findings: number;
  /** Not in `setupProblems`; `total` minus every count is the scenarios the run never reached. */
  setupProblems: number;
  /** The scenarios a stop interrupted (older servers leave it out). */
  stoppedScenarios?: number;
  stopped: boolean;
  exitCode: number;
  reportUrl: string | null;
}

/** A `GET /api/runs/history` entry, newest first. `at` is ISO-8601. */
export interface HistoryEntry {
  runId: string;
  at: string;
  profile: string | null;
  result: 'passed' | 'finding' | 'setup' | 'stopped';
}

export type Outcome = 'passed' | 'finding' | 'setup';

/** How a scenario ended, as the engine counts it (`ScenarioResult.failureKind`). */
export function outcomeOf(result: ScenarioResult): Outcome {
  if (result.success) {
    return 'passed';
  }
  for (let i = result.steps.length - 1; i >= 0; i--) {
    const step = result.steps[i];
    // the step a stop interrupted says nothing about the lab: an earlier failure decides
    if (!step.success && step.messageKey !== STOPPED_KEY) {
      return step.failureKind === 'SETUP' ? 'setup' : 'finding';
    }
  }
  const cleanupFailed = result.cleanup.some((entry) => entry.status === 'FAILED');
  return result.failureDetail === null && cleanupFailed ? 'finding' : 'setup';
}

/** The engine's message key on the step a stop interrupted. */
export const STOPPED_KEY = 'engine.run.stopped';

/** The step a stop interrupted (its failure is the engine's stop), when the run was stopped there. */
export function interruptedStep(result: ScenarioResult): StepResult | null {
  if (!result.stopped) {
    return null;
  }
  return result.steps.find((s) => !s.success && s.messageKey === STOPPED_KEY) ?? null;
}

/**
 * Whether the stop is all that went wrong, so the scenario counts as stopped — the server's
 * `ScenarioResult.stoppedOnly()`: a stop ended it, and either every step passed, no cleanup entry
 * FAILED and the failure detail is none or the stop's; or exactly one step failed, the one the stop
 * interrupted. Anything else (a step that had already failed, a failed cleanup) counts by its kind.
 */
export function isStopped(result: ScenarioResult): boolean {
  if (!result.stopped) {
    return false;
  }
  const failed = result.steps.filter((s) => !s.success);
  if (failed.length === 0) {
    const cleanupFailed = result.cleanup.some((entry) => entry.status === 'FAILED');
    return !cleanupFailed && (result.failureDetail === null || result.failureDetailKey === STOPPED_KEY);
  }
  return failed.length === 1 && failed[0].messageKey === STOPPED_KEY;
}

/** The workspace-relative path of a scenario named by its path under scenarios/. */
export function scenarioPath(sourceName: string): string {
  return `scenarios/${sourceName}`;
}
