import { t } from '../i18n';
import type { LabField, LabState, LabSystem, LabView } from './types';

/** The lab panel's tab for the default directory (no system has this id: system ids hold a kind). */
export const DEFAULT_DIRECTORY_TAB = 'default-directory';

/** The systems the chip counts: those a scenario uses (all of them when no scenario uses any). */
export function countedSystems(view: LabView): LabSystem[] {
  const used = view.systems.filter((system) => system.usedByScenarios.length > 0);
  return used.length > 0 ? used : view.systems;
}

/** The field a missing system lacks: the first required one not filled in. */
export function missingField(system: LabSystem): LabField | undefined {
  const required = system.fields.filter((f) => f.required);
  return required.find((f) => f.state === 'not set') ?? required.find((f) => !f.secret && !f.value) ?? required[0];
}

/** "Password" → "password", "URL" / "OAuth client" stay: only a plain capitalised word is lowered. */
function inSentence(label: string): string {
  return label.length > 1 && label[1] === label[1].toLowerCase() ? label[0].toLowerCase() + label.slice(1) : label;
}

/**
 * The lab's readiness as the chip shows it and Run (Task 14) checks it: missing (steps without
 * `directory:` have no default directory, or a required field of a used system is not filled in)
 * before untested before ready. With no view yet (loading, or the settings could not be read) it
 * is `untested`: nothing is known to be missing, so Run is not blocked and the run itself reports
 * what is wrong.
 */
export function deriveLabState(view: LabView | null, error: string | null, hasProfile = true): LabState {
  if (!hasProfile) {
    return { state: 'untested', message: t('ui.lab.chip.noProfile') };
  }
  if (!view) {
    return { state: 'untested', message: t(error !== null ? 'ui.lab.chip.unreadable' : 'ui.lab.chip.checking') };
  }
  const state = fromView(view);
  return error !== null ? { ...state, error } : state;
}

function missingState(missing: LabSystem): LabState {
  const field = missingField(missing);
  const label = field ? inSentence(field.label) : '';
  const key = /^[aeio]/i.test(label) ? 'ui.lab.chip.missingAn' : 'ui.lab.chip.missing';
  return { state: 'missing', missingSystemId: missing.id, message: t(key, { system: missing.name, field: label }) };
}

function defaultDirectoryState(): LabState {
  return { state: 'missing', missingSystemId: DEFAULT_DIRECTORY_TAB, message: t('ui.lab.chip.defaultDirectory') };
}

/**
 * Whether a run of `scenarios` (null: all) is refused for the default directory: an invalid one
 * refuses every run up front, as `idmtest run` does; a missing one only the runs with a scenario
 * that has steps without `directory:`.
 */
function defaultDirectoryBlocks(view: LabView, scenarios: readonly string[] | null): boolean {
  const block = view.defaultDirectory;
  if (!block) {
    return false;
  }
  return block.state === 'invalid' || scenarios === null || block.usedByScenarios.some((name) => scenarios.includes(name));
}

/**
 * What blocks a run of `scenarios` (names as under scenarios/, as `usedByScenarios` has them; null:
 * all of them): the default directory when the runner would refuse it for them, else the first
 * missing system one of them uses (spec §4.5: "something a selected scenario needs is absent"), or
 * null. A system no selected scenario uses does not block. When no
 * scenario is known to use any system, every system counts, as for the chip.
 */
export function missingFor(view: LabView | null, scenarios: readonly string[] | null): LabState | null {
  if (!view) {
    return null;
  }
  if (defaultDirectoryBlocks(view, scenarios)) {
    return defaultDirectoryState();
  }
  const counted = countedSystems(view);
  const anyUsed = view.systems.some((system) => system.usedByScenarios.length > 0);
  const needed =
    scenarios === null || !anyUsed
      ? counted
      : counted.filter((system) => system.usedByScenarios.some((name) => scenarios.includes(name)));
  const missing = needed.find((system) => system.status === 'missing');
  return missing ? missingState(missing) : null;
}

function fromView(view: LabView): LabState {
  if (view.defaultDirectory) {
    return defaultDirectoryState();
  }
  const systems = countedSystems(view);
  const missing = systems.find((system) => system.status === 'missing');
  if (missing) {
    return missingState(missing);
  }
  const ready = systems.filter((system) => system.status === 'ready').length;
  if (ready === systems.length) {
    return { state: 'ready', message: t('ui.lab.chip.ready') };
  }
  return { state: 'untested', message: t('ui.lab.chip.some', { ready, total: systems.length }) };
}
