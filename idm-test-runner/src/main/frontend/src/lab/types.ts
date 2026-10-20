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

export interface LabView {
  systems: LabSystem[];
}

/**
 * The lab's overall readiness, for the chip and for Run (Task 14): `missing` blocks a run, with
 * `missingSystemId` the system to open the lab panel on; `message` is the chip's text.
 */
export interface LabState {
  state: 'ready' | 'untested' | 'missing';
  missingSystemId?: string;
  message: string;
}
