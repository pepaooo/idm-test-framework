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
    ['url', 'ui.lab.field.url'],
    ['base-dn', 'ui.lab.field.baseDn'],
    ['user-dn', 'ui.lab.field.userDn'],
    ['password', 'ui.lab.field.password', true],
  ],
  database: [
    ['jdbc-url', 'ui.lab.field.jdbcUrl'],
    ['username', 'ui.lab.field.username'],
    ['password', 'ui.lab.field.password', true],
    ['driver-class-name', 'ui.lab.field.driverClassName'],
  ],
  rest: [
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
    ['url', 'ui.lab.field.roleServiceUrl'],
    ['resource-url', 'ui.lab.field.resourceServiceUrl'],
    ['username', 'ui.lab.field.username'],
    ['password', 'ui.lab.field.password', true],
  ],
};

export function templateFields(kind: Kind): LabField[] {
  return TEMPLATES[kind].map(([key, label, secret = false]) => ({
    key,
    label: t(label),
    value: null,
    secret,
    required: false,
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
