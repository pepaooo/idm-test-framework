import { t } from '../i18n';
import type { LabField, LabState, LabSystem, LabView } from './types';

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
 * The lab's readiness as the chip shows it and Run (Task 14) checks it: missing (a required field
 * of a used system is not filled in) before untested before ready. With no view yet (loading, or
 * the settings could not be read) it is `untested`: nothing is known to be missing, so Run is not
 * blocked and the run itself reports what is wrong.
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

function fromView(view: LabView): LabState {
  const systems = countedSystems(view);
  const missing = systems.find((system) => system.status === 'missing');
  if (missing) {
    const field = missingField(missing);
    const label = field ? inSentence(field.label) : '';
    const key = /^[aeio]/i.test(label) ? 'ui.lab.chip.missingAn' : 'ui.lab.chip.missing';
    return { state: 'missing', missingSystemId: missing.id, message: t(key, { system: missing.name, field: label }) };
  }
  const ready = systems.filter((system) => system.status === 'ready').length;
  if (ready === systems.length) {
    return { state: 'ready', message: t('ui.lab.chip.ready') };
  }
  return { state: 'untested', message: t('ui.lab.chip.some', { ready, total: systems.length }) };
}
