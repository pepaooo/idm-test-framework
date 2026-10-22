import { api, ApiProblem, withQuery } from '../api/client';
import { stepCount } from './scenarioYaml';
import {
  interruptedStep,
  isStopped,
  outcomeOf,
  scenarioPath,
  type RunFinishedEvent,
  type RunStartedEvent,
  type ScenarioResult,
  type StepResult,
} from './types';

/** What a run was asked for. */
export type RunScope =
  | { kind: 'scenario'; path: string }
  | { kind: 'folder'; folder: string }
  | { kind: 'all' }
  | { kind: 'findings' };

/**
 * `notRun`: the run ended (stopped, refused) before it reached the scenario. `stopped`: a stop
 * interrupted it before it failed by itself (`isStopped`).
 */
export type ScenarioState = 'waiting' | 'running' | 'passed' | 'finding' | 'setup' | 'stopped' | 'notRun';

/** One scenario of the run, as the events tell it so far. */
export interface ScenarioRun {
  /** The path under scenarios/. */
  name: string;
  state: ScenarioState;
  /** The scenario counts as stopped (`state` is then `stopped`): its cleanup did not run. */
  stopped: boolean;
  /** The running step (from 0), while the scenario runs. */
  step: number | null;
  /** The running step's try under `wait:` (from 1). */
  attempt: number | null;
  /** How many steps the scenario has (from its file), when known. */
  totalSteps: number | null;
  steps: StepResult[];
  result: ScenarioResult | null;
  /** The server's one-line summary of what failed. */
  failureLine: string | null;
}

export interface RunState {
  phase: 'idle' | 'starting' | 'running' | 'finished';
  /** What this tab asked for; null for a run it only watches (another tab started it, or a reload). */
  scope: RunScope | null;
  runId: string | null;
  profile: string | null;
  scenarios: ScenarioRun[];
  outcome: RunFinishedEvent | null;
  /** Why the run was refused or ended early (red), from a `refused` event. */
  refused: string | null;
  warnings: string[];
  /** Why the last start or stop was not accepted (a 409 busy, nothing to run…). */
  problem: string | null;
  stopping: boolean;
  /** The scenario the panel shows the steps of, and the step it shows. */
  selected: string | null;
  selectedStep: number | null;
  /** The user chose them (the panel then stops following the run). */
  userSelected: boolean;
}

export const RUN_EVENTS = '/api/runs/current/events';
const NAMES = ['runStarted', 'scenarioStarted', 'stepStarted', 'attempt', 'stepFinished', 'scenarioFinished', 'refused', 'warning', 'runFinished'];
const TREE_DELAY = 100;

const idle: RunState = {
  phase: 'idle',
  scope: null,
  runId: null,
  profile: null,
  scenarios: [],
  outcome: null,
  refused: null,
  warnings: [],
  problem: null,
  stopping: false,
  selected: null,
  selectedStep: null,
  userSelected: false,
};

function row(name: string): ScenarioRun {
  return { name, state: 'waiting', stopped: false, step: null, attempt: null, totalSteps: null, steps: [], result: null, failureLine: null };
}

/** The profile a run id names (`yyyyMMdd-HHmmss-<profile>`, maybe `-<n>`). */
export function profileOfRunId(runId: string | null): string | null {
  const match = runId ? /^\d{8}-\d{6}-(.+?)(-\d+)?$/.exec(runId) : null;
  return match ? match[1] : null;
}

export interface RunStoreOptions {
  /** Reads the file tree again (its last-result badges), after a scenario finished. */
  refreshTree?: () => void;
  /** A scenario's saved text, to count its steps ("step 2 of 4"). */
  readScenario?: (path: string) => Promise<string>;
  /** A scenario (its workspace-relative path) starts running. */
  scenarioStarted?: (path: string) => void;
}

/**
 * The workbench's run (spec §4.6): starts runs with `POST /api/runs`, stops them, and follows the
 * current or most recent run through `GET /api/runs/current/events`. The stream is closed after
 * `runFinished` or `refused` (and when the server has nothing to send, 204), so the browser does not
 * reconnect in a loop. Never queues: a 409 busy is shown, not retried. Plain TypeScript, read
 * through `useSyncExternalStore`; holds only what the events carry, which the server has masked.
 */
export class RunStore {
  private state: RunState = idle;
  private readonly listeners = new Set<() => void>();
  private closeSource: (() => void) | null = null;
  private treeTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly options: RunStoreOptions;
  /** Step counts per scenario of this run, read once. */
  private counts = new Map<string, Promise<number | null>>();

  constructor(options: RunStoreOptions = {}) {
    this.options = options;
  }

  readonly subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  readonly getState = () => this.state;

  private set(patch: Partial<RunState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((listener) => listener());
  }

  private updateRow(name: string, patch: (current: ScenarioRun) => Partial<ScenarioRun>) {
    let found = false;
    const scenarios = this.state.scenarios.map((current) => {
      if (current.name !== name) {
        return current;
      }
      found = true;
      return { ...current, ...patch(current) };
    });
    if (!found) {
      const added = row(name);
      scenarios.push({ ...added, ...patch(added) });
    }
    this.set({ scenarios });
  }

  /** Whether a run is starting or going on (as far as this tab knows). */
  busy(): boolean {
    return this.state.phase === 'starting' || this.state.phase === 'running';
  }

  /** Follows the current or most recent run (from its first event). */
  connect(): void {
    this.disconnect();
    if (typeof EventSource === 'undefined') {
      return;
    }
    let close: (() => void) | null = null;
    close = api.events(
      RUN_EVENTS,
      (name, data) => this.onEvent(name, data, () => close?.()),
      NAMES,
      (closed) => {
        if (closed && this.closeSource === close) {
          this.closeSource = null;
        }
      },
    );
    this.closeSource = close;
  }

  private disconnect() {
    this.closeSource?.();
    this.closeSource = null;
  }

  /**
   * Starts a run of `scope` with `profile`. Resolves once the server accepted it (the events then
   * tell the rest) or refused it (`problem`); never queues behind a run that is going on.
   */
  async start(scope: RunScope, profile: string, locale: string): Promise<boolean> {
    if (this.state.phase === 'starting') {
      return false;
    }
    const scenarios = scope.kind === 'scenario' ? [scope.path] : scope.kind === 'folder' ? { folder: scope.folder } : 'all';
    const previous = this.state;
    this.set({ phase: 'starting', problem: null });
    try {
      await api.post<{ runId: string | null }>('/api/runs', { profile, scenarios, onlyFindings: scope.kind === 'findings', locale });
    } catch (e) {
      this.set({ phase: previous.phase, problem: e instanceof ApiProblem ? e.message : String(e) });
      return false;
    }
    this.counts = new Map();
    this.state = { ...idle, phase: 'starting', scope, profile };
    this.connect();
    this.set({});
    return true;
  }

  /** Stops the run at once (its current scenario's cleanup does not run). */
  async stop(): Promise<void> {
    if (!this.busy() || this.state.stopping) {
      return;
    }
    this.set({ stopping: true, problem: null });
    try {
      await api.post('/api/runs/current/stop');
    } catch (e) {
      this.set({ stopping: false, problem: e instanceof ApiProblem ? e.message : String(e) });
    }
  }

  /** Shows `name`'s steps, and `step` (from 0) when given. */
  select(name: string, step: number | null = null) {
    this.set({ selected: name, selectedStep: step, userSelected: true });
  }

  selectStep(step: number | null) {
    this.set({ selectedStep: step, userSelected: true });
  }

  dismissProblem() {
    this.set({ problem: null });
  }

  private onEvent(name: string, data: unknown, close: () => void) {
    const event = (data ?? {}) as Record<string, unknown>;
    switch (name) {
      case 'runStarted': {
        const started = event as unknown as RunStartedEvent;
        const names = Array.isArray(started.scenarios) ? started.scenarios : [];
        this.set({
          phase: 'running',
          runId: started.runId ?? null,
          profile: this.state.profile ?? profileOfRunId(started.runId ?? null),
          scenarios: names.map(row),
          outcome: null,
          refused: null,
          stopping: false,
          selected: this.state.userSelected ? this.state.selected : null,
        });
        break;
      }
      case 'scenarioStarted': {
        const scenario = String(event.name);
        this.updateRow(scenario, () => ({ state: 'running', step: null, attempt: null, steps: [], result: null }));
        this.follow(scenario);
        this.options.scenarioStarted?.(scenarioPath(scenario));
        void this.countSteps(scenario);
        break;
      }
      case 'stepStarted':
        this.updateRunning((current) => ({ step: Number(event.index), attempt: null, steps: current.steps }));
        break;
      case 'attempt':
        this.updateRunning(() => ({ attempt: Number(event.attempt) }));
        break;
      case 'stepFinished': {
        const step = event as unknown as StepResult;
        this.updateRunning((current) => ({ steps: [...current.steps.filter((s) => s.index !== step.index), step] }));
        break;
      }
      case 'scenarioFinished': {
        const result = event.result as ScenarioResult | undefined;
        if (!result) {
          break;
        }
        const stopped = isStopped(result);
        const outcome = stopped ? 'stopped' : outcomeOf(result);
        this.updateRow(result.sourceName, () => ({
          state: outcome,
          stopped,
          step: null,
          attempt: null,
          steps: result.steps,
          result,
          failureLine: typeof event.failureLine === 'string' ? event.failureLine : null,
        }));
        if (outcome !== 'passed' && !this.state.userSelected && !this.firstFailureBefore(result.sourceName)) {
          // the last failure of its own, not the step the stop interrupted (unless that is all)
          const interrupted = interruptedStep(result);
          const failed =
            [...result.steps].reverse().find((s) => !s.success && s !== interrupted) ?? [...result.steps].reverse().find((s) => !s.success);
          this.set({ selected: result.sourceName, selectedStep: failed ? failed.index : null });
        }
        this.scheduleTree();
        break;
      }
      case 'warning':
        if (typeof event.message === 'string') {
          this.set({ warnings: [...this.state.warnings, event.message] });
        }
        break;
      case 'refused':
        this.set({
          phase: 'finished',
          refused: typeof event.message === 'string' ? event.message : String(event.key ?? ''),
          stopping: false,
          scenarios: this.endRows('setup'),
        });
        close();
        this.scheduleTree();
        break;
      case 'runFinished':
        this.set({
          phase: 'finished',
          outcome: event as unknown as RunFinishedEvent,
          stopping: false,
          runId: (event.runId as string) ?? this.state.runId,
          scenarios: this.endRows(null),
        });
        close();
        break;
      default:
        break;
    }
  }

  /** The rows once the run is over: those it never reached are `notRun`; a running one becomes `running` (or that). */
  private endRows(running: 'setup' | null): ScenarioRun[] {
    return this.state.scenarios.map((s) => {
      if (s.state === 'waiting') {
        return { ...s, state: 'notRun' as const };
      }
      if (s.state === 'running' && running) {
        return { ...s, state: running, step: null, attempt: null };
      }
      return s;
    });
  }

  private updateRunning(patch: (current: ScenarioRun) => Partial<ScenarioRun>) {
    const running = this.state.scenarios.find((s) => s.state === 'running');
    if (running) {
      this.updateRow(running.name, patch);
    }
  }

  /** Whether a scenario before `name` already failed (the panel keeps showing the first failure). */
  private firstFailureBefore(name: string): boolean {
    for (const scenario of this.state.scenarios) {
      if (scenario.name === name) {
        return false;
      }
      if (scenario.state === 'finding' || scenario.state === 'setup') {
        return true;
      }
    }
    return false;
  }

  /** Until the user chooses, or a scenario has failed, the panel shows the running scenario. */
  private follow(name: string) {
    if (this.state.userSelected) {
      return;
    }
    const failed = this.state.scenarios.some((s) => s.name !== name && (s.state === 'finding' || s.state === 'setup'));
    if (!failed) {
      this.set({ selected: name, selectedStep: null });
    }
  }

  private async countSteps(name: string) {
    const read = this.options.readScenario;
    if (!read) {
      return;
    }
    let count = this.counts.get(name);
    if (!count) {
      count = read(scenarioPath(name)).then(stepCount, () => null);
      this.counts.set(name, count);
    }
    const total = await count;
    if (total !== null) {
      this.updateRow(name, () => ({ totalSteps: total }));
    }
  }

  private scheduleTree() {
    if (!this.options.refreshTree || this.treeTimer) {
      return;
    }
    this.treeTimer = setTimeout(() => {
      this.treeTimer = null;
      this.options.refreshTree?.();
    }, TREE_DELAY);
  }

  /** Stops following (the workbench unmounts; under StrictMode it may mount again and reconnect). */
  dispose() {
    this.disconnect();
    if (this.treeTimer) {
      clearTimeout(this.treeTimer);
      this.treeTimer = null;
    }
  }
}

/** A scenario's saved text, through the files API. */
export async function readScenarioFile(path: string): Promise<string> {
  return (await api.get<{ content: string }>(withQuery('/api/files', { path }))).content;
}
