import { t } from '../i18n';
import type { LabField, LabSystem } from './types';

type Kind = LabSystem['kind'];

/**
 * The fields of a system not configured yet ("+ add"), as LabSettings lists them per kind (its
 * FieldSpec lists): the server only describes systems it already knows, so a new one's form is
 * built here. Saving it sends these keys under the new id, which the server checks again.
 */
const TEMPLATES: Record<Kind, [key: string, label: string, secret?: boolean][]> = {
  ldap: [
    ['label', 'ui.lab.field.label'],
    ['url', 'ui.lab.field.url'],
    ['base-dn', 'ui.lab.field.baseDn'],
    ['user-dn', 'ui.lab.field.userDn'],
    ['password', 'ui.lab.field.password', true],
  ],
  database: [
    ['label', 'ui.lab.field.label'],
    ['jdbc-url', 'ui.lab.field.jdbcUrl'],
    ['username', 'ui.lab.field.username'],
    ['password', 'ui.lab.field.password', true],
    ['driver-class-name', 'ui.lab.field.driverClassName'],
  ],
  rest: [
    ['label', 'ui.lab.field.label'],
    ['base-url', 'ui.lab.field.baseUrl'],
    ['username', 'ui.lab.field.username'],
    ['password', 'ui.lab.field.password', true],
    ['use-oauth', 'ui.lab.field.useOauth'],
    ['oauth-client-name', 'ui.lab.field.oauthClientName'],
    ['client-id', 'ui.lab.field.clientId'],
    ['client-secret', 'ui.lab.field.clientSecret', true],
    ['token-uri', 'ui.lab.field.tokenUri'],
    ['scope', 'ui.lab.field.scope'],
  ],
  soap: [
    ['label', 'ui.lab.field.label'],
    ['url', 'ui.lab.field.roleServiceUrl'],
    ['resource-url', 'ui.lab.field.resourceServiceUrl'],
    ['username', 'ui.lab.field.username'],
    ['password', 'ui.lab.field.password', true],
  ],
};

/**
 * The fields a new system needs, as LabSettings.required decides them for a configured one: its
 * URL; with a user (bind DN, username), the password; with Use OAuth on, the OAuth client, its id,
 * secret and token URL instead. SOAP needs the role service URL (the resource service URL only once
 * a scenario uses resources, which a new system has none of).
 */
export function requiredForNew(kind: Kind, valueOf: (key: string) => string): Set<string> {
  const filled = (key: string) => valueOf(key).trim() !== '';
  const required = new Set<string>();
  switch (kind) {
    case 'ldap':
      required.add('url');
      if (filled('user-dn')) required.add('password');
      break;
    case 'database':
      required.add('jdbc-url');
      if (filled('username')) required.add('password');
      break;
    case 'rest':
      required.add('base-url');
      if (valueOf('use-oauth').trim().toLowerCase() === 'true') {
        ['oauth-client-name', 'client-id', 'client-secret', 'token-uri'].forEach((key) => required.add(key));
      } else if (filled('username')) {
        required.add('password');
      }
      break;
    case 'soap':
      required.add('url');
      if (filled('username')) required.add('password');
      break;
  }
  return required;
}

/** The form of a new system of `kind`, with `valueOf` the plain values typed so far. */
export function templateFields(kind: Kind, valueOf: (key: string) => string = () => ''): LabField[] {
  const required = requiredForNew(kind, valueOf);
  return TEMPLATES[kind].map(([key, label, secret = false]) => ({
    key,
    label: t(label),
    value: null,
    secret,
    required: required.has(key),
    state: 'not set',
  }));
}

/** The REST fields that belong to its OAuth client: shown only while "Use OAuth" is on. */
export const OAUTH_KEYS = new Set(['oauth-client-name', 'client-id', 'client-secret', 'token-uri', 'scope']);

/** Names the server accepts in a system id (LabSettings.NAME). */
export const SYSTEM_NAME = /^[A-Za-z0-9_-]+$/;

/** The id of a new system of `kind` named `name` (the default directory and SOAP have no name). */
export function newSystemId(kind: Kind, name: string): string {
  switch (kind) {
    case 'ldap':
      return name === '' ? 'ldap' : `directory:${name}`;
    case 'database':
      return `database:${name}`;
    case 'rest':
      return `rest:${name}`;
    case 'soap':
      return 'soap';
  }
}
