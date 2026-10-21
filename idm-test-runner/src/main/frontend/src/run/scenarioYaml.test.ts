import { describe, expect, it } from 'vitest';
import { blockItems, stepCount, stepLines } from './scenarioYaml';

const SCENARIO = `name: Create a user and verify it
fixtures:
  user: { generate: random-user }
steps:
  - ldap.create:
      dn: cn=\${user.userId},ou=users

  # the check
  - ldap.verify:
      dn: cn=\${user.userId},ou=users
      expect:
        sn: { equals: \${user.lastName} }
      wait: { timeout: 30s }
  - set: { done: true }

cleanup:
  - ldap.delete: { dn: \${createdDn} }
`;

describe('scenario YAML lines', () => {
  it('finds the Nth item under steps:, blank and comment lines after it left out', () => {
    expect(stepLines(SCENARIO, 0)).toEqual({ from: 5, to: 6 });
    expect(stepLines(SCENARIO, 1)).toEqual({ from: 9, to: 13 });
    expect(stepLines(SCENARIO, 2)).toEqual({ from: 14, to: 14 });
    expect(stepLines(SCENARIO, 3)).toBeNull();
    expect(stepCount(SCENARIO)).toBe(3);
    expect(blockItems(SCENARIO, 'cleanup')).toEqual([{ from: 17, to: 17 }]);
  });

  it('reads items written at the left edge too', () => {
    const text = 'steps:\n- ldap.create:\n    dn: a\n- ldap.verify: { dn: a }\nname: x\n';
    expect(blockItems(text, 'steps')).toEqual([
      { from: 2, to: 3 },
      { from: 4, to: 4 },
    ]);
  });

  it('gives nothing for a flow sequence or no steps', () => {
    expect(stepLines('steps: [ { ldap.create: { dn: a } } ]\n', 0)).toBeNull();
    expect(stepCount('name: nothing\n')).toBeNull();
  });
});
