import type { Answer } from './fakeServer';
import type { DefaultDirectory, LabField, LabSystem, LabView, TestResult } from '../lab/types';

/** Lab fixtures and the `/api/lab` routes of a fake server (secret values never in a view, as on the server). */

export function field(key: string, label: string, value: string | null, state: LabField['state'], extra: Partial<LabField> = {}): LabField {
  return { key, label, value, secret: false, required: false, state, ...extra };
}

export function secret(key: string, label: string, state: LabField['state'], required = true): LabField {
  return { key, label, value: null, secret: true, required, state };
}

export function directory(status: LabSystem['status'] = 'ready'): LabSystem {
  return {
    id: 'ldap',
    kind: 'ldap',
    name: 'eDirectory',
    usedByScenarios: ['create-user.yaml'],
    fields: [
      field('label', 'Label', null, 'not set'),
      field('url', 'URL', 'ldaps://edir.lab:636', 'set', { required: true }),
      field('base-dn', 'Base DN', 'o=data', 'application.yml'),
      field('user-dn', 'Bind DN', 'cn=admin,o=system', 'environment'),
      secret('password', 'Password', 'set'),
    ],
    status,
    lastTest: null,
  };
}

export function rest(status: LabSystem['status'] = 'untested'): LabSystem {
  return {
    id: 'rest:idm',
    kind: 'rest',
    name: 'IdM REST',
    usedByScenarios: ['create-user.yaml'],
    fields: [
      field('label', 'Label', null, 'not set'),
      field('base-url', 'Base URL', 'https://idm.lab/api', 'set', { required: true }),
      field('username', 'Username', null, 'not set'),
      secret('password', 'Password', 'not set', false),
      field('use-oauth', 'Use OAuth', null, 'not set'),
      field('oauth-client-name', 'OAuth client', null, 'not set'),
      field('client-id', 'Client ID', null, 'not set'),
      secret('client-secret', 'Client secret', 'not set', false),
      field('token-uri', 'Token URL', null, 'not set'),
      field('scope', 'Scope', null, 'not set'),
    ],
    status,
    lastTest: null,
  };
}

export function hrDatabase(status: LabSystem['status'] = 'missing'): LabSystem {
  return {
    id: 'database:hr',
    kind: 'database',
    name: 'HR database',
    usedByScenarios: ['hr-sync.yaml'],
    fields: [
      field('label', 'Label', null, 'not set'),
      field('jdbc-url', 'JDBC URL', 'jdbc:postgresql://hr.lab/hr', 'set', { required: true }),
      field('username', 'Username', 'hr_reader', 'set'),
      secret('password', 'Password', status === 'missing' ? 'not set' : 'set'),
      field('driver-class-name', 'Driver class', null, 'not set'),
    ],
    status,
    lastTest: null,
  };
}

/** The reported setup: example.yaml has steps without directory:, two directories, no default. */
export function defaultDirectoryNeeded(extra: Partial<DefaultDirectory> = {}): DefaultDirectory {
  return { usedByScenarios: ['example.yaml'], choices: ['ad', 'edirectory'], value: null, source: null, state: 'missing', ...extra };
}

/** A named directory (`directory:<name>`). */
export function namedDirectory(name: string, status: LabSystem['status'] = 'ready', usedByScenarios: string[] = []): LabSystem {
  return { ...directory(status), id: `directory:${name}`, name: `Directory ${name}`, usedByScenarios };
}

export interface LabRoutesOptions {
  /** The view per profile (a function: computed per request). */
  views: Record<string, LabView | (() => LabView)>;
  /** What a save answers: the new view, or an error; default the profile's view as it is. */
  onSave?: (profile: string, body: unknown) => Answer;
  onTest?: (body: unknown) => TestResult | Answer;
  /** What `PUT /api/lab/default-directory` answers; default the profile's view as it is. */
  onSaveDefault?: (profile: string, body: unknown) => Answer;
  onDelete?: (profile: string, systemId: string) => Answer;
}

function profileOf(url: string) {
  return new URL(url, 'http://127.0.0.1').searchParams.get('profile') ?? '';
}

export function labRoutes({ views, onSave, onTest, onSaveDefault, onDelete }: LabRoutesOptions) {
  const view = (profile: string): Answer => {
    const found = views[profile];
    if (!found) {
      return { status: 400, body: { key: 'runner.ui.lab.badProfile', message: `no profile ${profile}` } };
    }
    return { body: typeof found === 'function' ? found() : found };
  };
  return {
    'GET /api/lab': (request: { url: string }) => view(profileOf(request.url)),
    'PUT /api/lab': (request: { url: string; body: unknown }) =>
      onSave ? onSave(profileOf(request.url), request.body) : view(profileOf(request.url)),
    'PUT /api/lab/default-directory': (request: { url: string; body: unknown }) =>
      onSaveDefault ? onSaveDefault(profileOf(request.url), request.body) : view(profileOf(request.url)),
    'POST /api/lab/test': (request: { body: unknown }) => {
      const answer = onTest ? onTest(request.body) : { ok: true, message: 'Connected · bind OK', millis: 112 };
      return 'ok' in answer ? { body: answer } : answer;
    },
    'DELETE /api/lab': (request: { url: string }) =>
      onDelete ? onDelete(profileOf(request.url), new URL(request.url, 'http://127.0.0.1').searchParams.get('systemId') ?? '')
        : view(profileOf(request.url)),
  };
}
