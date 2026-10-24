/** What `GET /api/lab?profile=` answers (LabSettings.View): a secret's value is always null. */
export interface LabField {
  key: string;
  label: string;
  value: string | null;
  secret: boolean;
  required: boolean;
  state: 'set' | 'not set' | 'environment' | 'application.yml';
}

export interface TestResult {
  ok: boolean;
  message: string;
  millis: number;
}

export interface LabSystem {
  /** `ldap`, `soap`, `directory:<name>`, `database:<name>` or `rest:<name>`. */
  id: string;
  kind: 'ldap' | 'database' | 'rest' | 'soap';
  name: string;
  usedByScenarios: string[];
  fields: LabField[];
  status: 'ready' | 'untested' | 'missing';
  lastTest: TestResult | null;
}

/**
 * Steps without `directory:` that `idmtest run` cannot place (LabSettings.DefaultDirectory): several
 * directories and no `default-directory`, or one that names none of them. Null in the view otherwise.
 */
export interface DefaultDirectory {
  usedByScenarios: string[];
  /** The directories the runner knows, sorted. */
  choices: string[];
  /** The configured `idm.test.default-directory`, or null when unset. */
  value: string | null;
  /** Where `value` comes from (null when unset). */
  source: 'profile' | 'application.yml' | 'environment' | null;
  state: 'missing' | 'invalid';
}

export interface LabView {
  systems: LabSystem[];
  /** Absent or null when steps without `directory:` have a directory. */
  defaultDirectory?: DefaultDirectory | null;
}

/**
 * The lab's overall readiness, for the chip and for Run (Task 14): `missing` blocks a run, with
 * `missingSystemId` the system to open the lab panel on; `message` is the chip's text.
 */
export interface LabState {
  state: 'ready' | 'untested' | 'missing';
  missingSystemId?: string;
  message: string;
  /** Why the last reload failed, while the state shown is the last good one; absent otherwise. */
  error?: string;
}
