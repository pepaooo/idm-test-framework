import { useEffect, useSyncExternalStore } from 'react';
import { withWorkspace } from '../api/client';
import { Badge, type BadgeStatus } from '../components/Badge';
import { currentLanguage, t, useLanguage } from '../i18n';
import { missingFor } from '../lab/labState';
import { useWorkbench } from '../workbench/context';
import { isScenario, scopeScenarios } from './RunButton';
import { seconds, timeLabel } from './copyFinding';
import { stepLines } from './scenarioYaml';
import { CleanupList, FindingPanel, SetupPanel, StoppedView, stepTitle } from './FindingPanel';
import type { RunState, RunStore, ScenarioRun } from './runStore';
import { scenarioPath, type StepResult } from './types';
import './Run.css';

const STATE_BADGE: Record<'passed' | 'finding' | 'setup', BadgeStatus> = { passed: 'ok', finding: 'finding', setup: 'error' };

/** The marker in front of a scenario: ✓ / ✕ amber / ✕ red / ! stopped, or running / waiting. */
function ScenarioMarker({ scenario }: { scenario: ScenarioRun }) {
  if (scenario.stopped) {
    return <Badge status="warn" label={t('ui.run.state.stopped')} />;
  }
  if (scenario.state === 'running') {
    return (
      <span className="run-spin" role="img" aria-label={t('ui.run.state.running')}>
        ◌
      </span>
    );
  }
  if (scenario.state === 'notRun') {
    return (
      <span className="run-wait muted" role="img" aria-label={t('ui.run.state.notRun')}>
        –
      </span>
    );
  }
  if (scenario.state === 'waiting') {
    return (
      <span className="run-wait muted" role="img" aria-label={t('ui.run.state.waiting')}>
        ○
      </span>
    );
  }
  return <Badge status={STATE_BADGE[scenario.state]} label={t(`ui.run.state.${scenario.state}`)} />;
}

/** The right-hand text of a scenario's row: its time and kind, or "step 2 of 4…", or "waiting". */
function rowDetail(scenario: ScenarioRun): string {
  switch (scenario.state) {
    case 'waiting':
      return t('ui.run.row.waiting');
    case 'notRun':
      return t('ui.run.row.notRun');
    case 'running': {
      if (scenario.step === null) {
        return t('ui.run.row.starting');
      }
      const step = scenario.totalSteps
        ? t('ui.run.row.step', { n: scenario.step + 1, total: scenario.totalSteps })
        : t('ui.run.row.stepOnly', { n: scenario.step + 1 });
      return scenario.attempt && scenario.attempt > 1 ? `${step} · ${t('ui.run.row.try', { n: scenario.attempt })}` : step;
    }
    default: {
      const time = scenario.result ? seconds(scenario.result.durationMillis) : '';
      const word = scenario.stopped ? t('ui.run.row.stopped') : scenario.state === 'passed' ? '' : t(`ui.run.row.${scenario.state}`);
      return [time, word].filter(Boolean).join(' · ');
    }
  }
}

function scopeLabel(run: RunState): string {
  const scope = run.scope;
  if (!scope) {
    return run.scenarios.length === 1 ? run.scenarios[0].name : t('ui.run.scope.all');
  }
  switch (scope.kind) {
    case 'scenario':
      return scope.path.split('/').pop() ?? scope.path;
    case 'folder':
      return `${scope.folder.slice('scenarios/'.length)}/`;
    case 'findings':
      return t('ui.run.scope.findings');
    default:
      return t('ui.run.scope.all');
  }
}

/** "Running all · 3 of 5" while running; "Last run · 14:32" after. */
export function headline(run: RunState): string {
  if (run.phase === 'starting' || (run.phase === 'running' && run.scenarios.length === 0)) {
    return t('ui.run.head.starting');
  }
  if (run.phase === 'running') {
    const finished = run.scenarios.filter((s) => s.state !== 'waiting' && s.state !== 'running' && s.state !== 'notRun').length;
    const running = run.scenarios.some((s) => s.state === 'running') ? 1 : 0;
    const total = run.scenarios.length;
    return total === 1
      ? t('ui.run.head.runningOne', { scope: scopeLabel(run) })
      : t('ui.run.head.running', { scope: scopeLabel(run), done: Math.min(total, finished + running), total });
  }
  const match = run.runId ? /^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})/.exec(run.runId) : null;
  const when = match ? timeLabel(new Date(+match[1], +match[2] - 1, +match[3], +match[4], +match[5]).toISOString()) : '';
  return t('ui.run.head.last', { when: [when, run.profile].filter(Boolean).join(' · ') });
}

/** "5 scenarios: 3 passed, 2 findings" (setup problems counted on their own). */
export function endLine(run: RunState): string | null {
  const outcome = run.outcome;
  if (!outcome) {
    return null;
  }
  const parts = [t('ui.result.passed', { count: outcome.passed })];
  if (outcome.findings > 0) {
    parts.push(t('ui.result.findings', { count: outcome.findings }));
  }
  if (outcome.setupProblems > 0) {
    parts.push(t('ui.result.setup', { count: outcome.setupProblems }));
  }
  const line = t('ui.run.end', { scenarios: t('ui.run.scenarios', { count: outcome.total }), parts: parts.join(', ') });
  return outcome.stopped ? `${line} · ${t('ui.run.end.stopped')}` : line;
}

function StepBadge({ step, stopped }: { step: StepResult; stopped: boolean }) {
  if (stopped) {
    return <Badge status="warn" label={t('ui.run.state.stopped')} />;
  }
  if (step.success) {
    return <Badge status="ok" label={t('ui.run.state.passed')} />;
  }
  return step.failureKind === 'SETUP' ? (
    <Badge status="error" label={t('ui.run.state.setup')} />
  ) : (
    <Badge status="finding" label={t('ui.run.state.finding')} />
  );
}

/** One scenario's steps, then the selected step's finding or setup problem, cleanup and stop. */
function ScenarioDetail({ run, store, scenario, rerunBlocked }: { run: RunState; store: RunStore; scenario: ScenarioRun; rerunBlocked: boolean }) {
  const wb = useWorkbench();
  const steps = scenario.steps;
  const selected = steps.find((step) => step.index === run.selectedStep) ?? null;
  const report = run.outcome?.reportUrl ?? (run.runId ? `/reports/${run.runId}/report.html` : null);
  // a link cannot send a header: the workspace it is for goes in the address
  const reportUrl = report === null ? null : withWorkspace(report);
  const result = scenario.result;
  // the step a stop interrupted failed because of the stop, not of the lab or the setup
  const stoppedStep = result?.stopped ? ([...steps].reverse().find((s) => !s.success)?.index ?? null) : null;
  const shown = selected && selected.index !== stoppedStep ? selected : null;
  // spec §4.8: the editor marks the failed step's lines (when the file is open, now or later);
  // a new run of the scenario clears them (Workbench, on scenarioStarted)
  const markIndex = shown && !shown.success && (scenario.state === 'finding' || scenario.state === 'setup') ? shown.index : null;
  const path = scenarioPath(scenario.name);
  const { markWhenOpen } = wb;
  useEffect(() => {
    if (markIndex !== null) {
      markWhenOpen(path, (text) => stepLines(text, markIndex));
    }
  }, [markWhenOpen, path, markIndex]);
  const rerun = () => {
    if (wb.profile !== null) {
      void store.start({ kind: 'scenario', path: scenarioPath(scenario.name) }, wb.profile, currentLanguage());
    }
  };
  return (
    <section className="run-detail" aria-label={t('ui.run.stepsOf', { name: scenario.name })}>
      <h3 className="run-detail-name">{scenario.name}</h3>
      {steps.length === 0 && scenario.state !== 'running' && scenario.state !== 'waiting' && (
        <p className="muted">{t('ui.run.noSteps')}</p>
      )}
      <ul className="run-steps">
        {steps.map((step) => (
          <li key={step.index}>
            <button
              type="button"
              className={`run-row${step.index === run.selectedStep ? ' run-row-on' : ''}`}
              aria-pressed={step.index === run.selectedStep}
              onClick={() => store.selectStep(step.index === run.selectedStep ? null : step.index)}
            >
              <span>
                <StepBadge step={step} stopped={step.index === stoppedStep} /> {stepTitle(step)}
              </span>
              <span className="muted">{seconds(step.durationMillis)}</span>
            </button>
          </li>
        ))}
        {scenario.state === 'running' && scenario.step !== null && !steps.some((s) => s.index === scenario.step) && (
          <li className="run-row muted">
            <span>
              <span className="run-spin" role="img" aria-label={t('ui.run.state.running')}>
                ◌
              </span>{' '}
              {scenario.step + 1}
            </span>
            <span>{rowDetail(scenario)}</span>
          </li>
        )}
      </ul>
      {shown && !shown.success && shown.failureKind === 'SETUP' && (
        <SetupPanel scenario={scenario.name} step={shown} message={shown.message} />
      )}
      {shown && !shown.success && shown.failureKind !== 'SETUP' && (
        <FindingPanel
          scenario={scenario.name}
          step={shown}
          runId={run.runId}
          profile={run.profile}
          reportUrl={reportUrl}
          canRerun={!rerunBlocked}
          onRerun={rerun}
        />
      )}
      {result && !result.success && result.steps.every((s) => s.success) && result.failureDetail !== null && !result.stopped && (
        <SetupPanel scenario={scenario.name} step={null} message={scenario.failureLine ?? result.failureDetail} />
      )}
      {result && <CleanupList entries={result.cleanup} />}
      {result?.stopped && <StoppedView skipped={result.skippedCleanup ?? []} />}
    </section>
  );
}

/**
 * The run panel (spec §4.6, mockup run-all.html): the current or last run — progress, each
 * scenario's state, ■ Stop, the end line with the full report — and, for the scenario clicked, its
 * steps and the finding panel. Also says why Run is blocked (the lab misses something) and warns
 * when the lab settings could not be read again.
 */
export function RunPanel({ store }: { store: RunStore }) {
  useLanguage();
  const wb = useWorkbench();
  const run = useSyncExternalStore(store.subscribe, store.getState);
  const lab = wb.labState();
  const selected = run.scenarios.find((s) => s.name === run.selected) ?? null;
  const busy = run.phase === 'starting' || run.phase === 'running';
  const head = run.phase === 'idle' ? null : headline(run);
  const end = endLine(run);
  // what blocks Run: for the active scenario (Run's own scope), else for all of them
  const block = missingFor(
    wb.lab,
    scopeScenarios(isScenario(wb.activePath) ? { kind: 'scenario', path: wb.activePath } : { kind: 'all' }, wb.tree),
  );
  const rerunBlocked =
    busy || wb.readOnly || wb.profile === null || (selected !== null && missingFor(wb.lab, [selected.name]) !== null);

  return (
    <div className="run-panel">
      {block?.state === 'missing' && (
        <div className="run-notice run-blocked" role="status">
          <p>
            <Badge status="missing" /> {t('ui.run.blocked', { message: block.message })}
          </p>
          <button type="button" className="secondary" onClick={() => wb.openLab(block.missingSystemId)}>
            {t('ui.run.openLab')}
          </button>
        </div>
      )}
      {lab.error && (
        <p className="run-notice" role="status">
          <Badge status="warn" /> {t('ui.run.labError', { error: lab.error })}
        </p>
      )}
      {run.problem && (
        <p className="run-notice" role="alert">
          <Badge status="warn" /> {run.problem}
        </p>
      )}
      {run.warnings.map((warning) => (
        <p key={warning} className="run-notice" role="status">
          <Badge status="warn" /> {warning}
        </p>
      ))}
      {run.refused && (
        <p className="run-notice run-refused" role="alert">
          <Badge status="error" /> {run.refused}
        </p>
      )}

      <p className="sr-only" aria-live="polite">
        {[head, end].filter(Boolean).join('. ')}
      </p>

      {head === null ? (
        <p className="muted run-placeholder">{t('ui.run.placeholder')}</p>
      ) : (
        <>
          <div className="run-head">
            <span>{head}</span>
            {busy && (
              <button type="button" className="secondary run-stop" disabled={run.stopping || run.phase === 'starting'} onClick={() => void store.stop()}>
                {run.stopping ? t('ui.run.stopping') : t('ui.run.stop')}
              </button>
            )}
          </div>
          {busy && run.scenarios.length > 1 && (
            <progress
              className="run-progress"
              max={run.scenarios.length}
              value={run.scenarios.filter((s) => s.state !== 'waiting' && s.state !== 'running').length}
              aria-label={head}
            />
          )}
          {run.scenarios.length > 0 && (
            <ul className="run-scenarios" aria-label={t('ui.run.scenariosLabel')}>
              {run.scenarios.map((scenario) => (
                <li key={scenario.name}>
                  <button
                    type="button"
                    className={`run-row${scenario.name === run.selected ? ' run-row-on' : ''}`}
                    aria-pressed={scenario.name === run.selected}
                    onClick={() => {
                      const failed = [...scenario.steps].reverse().find((s) => !s.success);
                      store.select(scenario.name, failed ? failed.index : null);
                    }}
                  >
                    <span className="run-row-name">
                      <ScenarioMarker scenario={scenario} /> {scenario.name}
                    </span>
                    <span className="muted">{rowDetail(scenario)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {end && (
            <p className="run-end">
              {end}
              {run.outcome?.reportUrl && (
                <>
                  {' · '}
                  <a href={withWorkspace(run.outcome.reportUrl)} target="_blank" rel="noopener noreferrer">
                    {t('ui.run.openReport')}
                  </a>
                </>
              )}
            </p>
          )}
          {selected && <ScenarioDetail run={run} store={store} scenario={selected} rerunBlocked={rerunBlocked} />}
        </>
      )}
    </div>
  );
}
