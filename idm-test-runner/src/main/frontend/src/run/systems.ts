import { t } from '../i18n';
import type { LabSystem, LabView } from '../lab/types';
import type { StepResult } from './types';

/**
 * The lab system a step talked to, by the same rule the server uses for "used by" (ScenarioNeeds /
 * LabSettings): `ldap.*` → `directory:<directory>` when the step names one the lab has, else the
 * ldap system this scenario uses (the default directory, `ldap`); `db.*` → `database:<database>`;
 * `rest.*` and `http.*` → `rest:<target>`; `roles.*` and `resources.*` → `soap`.
 */
export function systemIdOf(step: Pick<StepResult, 'type' | 'resolvedParams'>, scenario: string, lab: LabView | null): string | null {
  const params = step.resolvedParams ?? {};
  const family = step.type.split('.')[0];
  const has = (id: string) => lab?.systems.some((system) => system.id === id) ?? false;
  switch (family) {
    case 'ldap': {
      const directory = typeof params.directory === 'string' ? params.directory : null;
      if (directory && has(`directory:${directory}`)) {
        return `directory:${directory}`;
      }
      if (directory && directory !== 'default') {
        return `directory:${directory}`;
      }
      const used = (lab?.systems ?? []).filter((s) => s.kind === 'ldap' && s.usedByScenarios.includes(scenario));
      return used.length === 1 ? used[0].id : 'ldap';
    }
    case 'db':
      return typeof params.database === 'string' ? `database:${params.database}` : null;
    case 'rest':
    case 'http':
      return typeof params.target === 'string' ? `rest:${params.target}` : null;
    case 'roles':
    case 'resources':
      return 'soap';
    default:
      return null;
  }
}

/** The URL a system is reached at (from the lab view; secrets are never in it). */
function urlOf(system: LabSystem): string | null {
  for (const key of ['url', 'jdbc-url', 'base-url']) {
    const field = system.fields.find((f) => f.key === key && !f.secret);
    if (field?.value) {
      return field.value;
    }
  }
  return null;
}

export interface StepSystem {
  /** The lab system's id, when known (to open the lab panel on it). */
  id: string | null;
  /** The lab panel's name for it, else a generic one ("The directory", "The database hr"). */
  name: string;
  url: string | null;
  /** Whether the lab panel has this system. */
  inLab: boolean;
}

function fallbackName(id: string | null): string {
  if (id === null) {
    return t('ui.system.unknown');
  }
  const [kind, name] = id.split(':');
  if (kind === 'ldap') {
    return t('ui.system.ldap');
  }
  return name ? t(`ui.system.${kind}`, { name }) : t(`ui.system.${kind}`);
}

/** The system of a step, named as the lab panel names it when it is there. */
export function systemOf(step: Pick<StepResult, 'type' | 'resolvedParams'>, scenario: string, lab: LabView | null): StepSystem {
  const id = systemIdOf(step, scenario, lab);
  const system = id ? lab?.systems.find((s) => s.id === id) : undefined;
  if (system) {
    return { id, name: system.name, url: urlOf(system), inLab: true };
  }
  return { id, name: fallbackName(id), url: null, inLab: false };
}

/**
 * Whether a SETUP failure is about reaching or logging in to a system (so the lab panel is where
 * to fix it): by its message key (`*.auth.*`, `*connect*`, a token request) or, failing that, its
 * text (refused or unreachable connections, timeouts, TLS, unknown hosts, rejected credentials,
 * 401/403). Malformed parameters and unexpected exceptions are not.
 */
export function isConnectionProblem(step: Pick<StepResult, 'messageKey' | 'message'>): boolean {
  const key = step.messageKey ?? '';
  if (/\.auth\.|connect|unreachable|token|credential|bind/i.test(key)) {
    return true;
  }
  return /connection refused|could not (reach|connect)|unreachable|timed? ?out|timeout|\btls\b|\bssl\b|certificate|handshake|unknown host|no route to host|credential|authenticat|bind (failed|rejected)|invalid password|\b40[13]\b|unauthori[sz]ed|forbidden|login/i.test(
    step.message ?? '',
  );
}
