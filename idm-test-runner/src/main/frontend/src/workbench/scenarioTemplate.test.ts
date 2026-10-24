import { describe, expect, it } from 'vitest';
// a copy of FilesControllerTest.TEMPLATE (which the real validator passes with zero problems) for
// the name disable-user: keep the two identical
import fixture from '../test/scenario-template.yaml?raw';
import { scenarioTemplate } from './scenarioTemplate';

describe('scenarioTemplate', () => {
  it('is the template the server test validates, byte for byte, with the name filled in', () => {
    expect(scenarioTemplate('disable-user')).toBe(fixture);
  });

  it('names the scenario after the file', () => {
    expect(scenarioTemplate('grant role').split('\n')[0]).toBe('name: grant role');
    expect(scenarioTemplate('grant role').slice('name: grant role'.length)).toBe(fixture.slice('name: disable-user'.length));
  });

  it('quotes a name YAML would not read as plain text', () => {
    expect(scenarioTemplate('#1 [x]').split('\n')[0]).toBe('name: "#1 [x]"');
    expect(scenarioTemplate('niño 2.0').split('\n')[0]).toBe('name: niño 2.0');
  });
});
