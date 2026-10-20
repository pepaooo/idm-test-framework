import { afterEach, describe, expect, it } from 'vitest';
import { setLanguage } from '../i18n';
import { copyFinding, safeUrl, waitingSummary } from './copyFinding';
import type { HistoryEntry, StepResult } from './types';

const at = (day: number, hour: number, minute: number) => new Date(2026, 9, day, hour, minute).toISOString();

/** The spec's example (mockup finding-panel.html, "Copy finding gives"). */
const verify: StepResult = {
  index: 1,
  type: 'ldap.verify',
  resolvedParams: { dn: 'cn=u123,ou=users', expect: { groupMembership: { contains: 'cn=Finance,ou=groups' } }, wait: { timeout: '60s', poll: '2s' } },
  success: false,
  durationMillis: 60200,
  message: 'groupMembership: expected contains cn=Finance,ou=groups, found nothing',
  attempts: 30,
  waitedMillis: 60200,
  description: null,
  failureKind: 'FINDING',
  waitHistory: [{ attempt: 1, elapsedMillis: 10, message: 'groupMembership absent' }],
  checks: [
    { name: 'groupMembership', expected: 'contains cn=Finance,ou=groups', found: null, matched: false },
    { name: 'loginDisabled', expected: 'FALSE', found: 'FALSE', matched: true },
  ],
};

/** Newest first, as the server sends it; the run being copied is the newest. */
const history: HistoryEntry[] = [
  { runId: '20261019-143205-lab', at: at(19, 14, 32), profile: 'lab', result: 'finding' },
  { runId: '20261019-110210-lab', at: at(19, 11, 2), profile: 'lab', result: 'finding' },
  { runId: '20261013-164501-lab', at: at(13, 16, 45), profile: 'lab', result: 'passed' },
  { runId: '20261012-091003-lab', at: at(12, 9, 10), profile: 'lab', result: 'passed' },
];

const run = {
  workspace: 'lab-tests',
  profile: 'lab',
  runId: '20261019-143205-lab',
  system: { name: 'eDirectory', url: 'ldaps://edir.lab:636' },
  history,
  now: new Date(2026, 9, 19, 14, 40),
};

afterEach(() => setLanguage('en', false));

describe('copyFinding', () => {
  it("gives the spec's plain-text block, exactly", () => {
    expect(copyFinding({ scenario: 'users/create-user.yaml', step: verify }, run)).toBe(
      [
        'idmtest finding · lab-tests / users/create-user.yaml',
        'Profile: lab · Run 20261019-143205-lab · eDirectory ldaps://edir.lab:636',
        '',
        'Step 2 ldap.verify cn=u123,ou=users — the lab did not do what this step expects',
        '  groupMembership: expected contains cn=Finance,ou=groups — found (not present)',
        '  loginDisabled:   expected FALSE — found FALSE ✓',
        'Waited 60 s (30 tries every 2 s); value never changed.',
        'History: passed Mon 09:10, Tue 16:45; failed today 11:02, 14:32.',
        'Report: reports/20261019-143205-lab/report.html',
      ].join('\n'),
    );
  });

  it('says on which try the value changed, leaves out what it does not know, and uses the message without checks', () => {
    const step: StepResult = {
      ...verify,
      type: 'rest.request',
      resolvedParams: { target: 'idm', method: 'POST', path: '/users' },
      description: 'Create the user in the IdM',
      checks: [],
      message: 'HTTP 500 from the IdM',
      attempts: 15,
      waitedMillis: 30000,
      waitHistory: [
        { attempt: 1, elapsedMillis: 0, message: 'a' },
        { attempt: 3, elapsedMillis: 4000, message: 'b' },
      ],
    };
    expect(
      copyFinding({ scenario: 'a.yaml', step }, { workspace: 'ws', profile: 'uat', runId: 'r1', system: null, history: [], now: run.now }),
    ).toBe(
      [
        'idmtest finding · ws / a.yaml',
        'Profile: uat · Run r1',
        '',
        'Step 2 rest.request Create the user in the IdM — the lab did not do what this step expects',
        '  HTTP 500 from the IdM',
        'Waited 30 s (15 tries every 2 s); value changed on try 3.',
        'Report: reports/r1/report.html',
      ].join('\n'),
    );
  });

  it('has no waiting line for a step that did not wait, and an older run shows its date', () => {
    const step: StepResult = { ...verify, attempts: null, waitedMillis: null, waitHistory: undefined };
    const old: HistoryEntry[] = [{ runId: 'r0', at: at(1, 8, 5), profile: 'lab', result: 'setup' }];
    const text = copyFinding({ scenario: 'a.yaml', step }, { ...run, history: old });
    expect(text).not.toContain('Waited');
    expect(text).toContain('History: setup problem 2026-10-01 08:05.');
  });

  it('in Spanish', () => {
    setLanguage('es', false);
    const text = copyFinding({ scenario: 'users/create-user.yaml', step: verify }, run);
    expect(text).toContain('Paso 2 ldap.verify cn=u123,ou=users — el laboratorio no hizo lo que este paso espera');
    expect(text).toContain('Esperó 60 s (30 intentos cada 2 s); el valor nunca cambió.');
  });
});

describe('safeUrl (a system URL without credentials)', () => {
  it.each([
    ['ldaps://edir.lab:636', 'ldaps://edir.lab:636'],
    ['ldaps://admin:fake-pw@edir.lab:636/o=data', 'ldaps://edir.lab:636/o=data'],
    ['https://ana@idm.lab/api?client_secret=fake&scope=read&access_token=x', 'https://idm.lab/api?scope=read'],
    ['jdbc:postgresql://reader:fake-pw@hr.lab/hr?password=fake&ssl=true', 'jdbc:postgresql://hr.lab/hr?ssl=true'],
    ['jdbc:sqlserver://hr.lab;databaseName=hr;user=sa;password=fake-pw', 'jdbc:sqlserver://hr.lab;databaseName=hr;user=sa'],
    ['jdbc:oracle:thin:scott/fake-pw@hr.lab:1521:HR', 'jdbc:oracle:thin:@hr.lab:1521:HR'],
    ['https://idm.lab/api?apiKey=fake#top', 'https://idm.lab/api#top'],
  ])('%s → %s', (url, safe) => {
    expect(safeUrl(url)).toBe(safe);
  });
});

describe('waitingSummary', () => {
  it('reads "30 tries every 2 s over 60 s — the value never changed"', () => {
    expect(waitingSummary(verify)).toBe('30 tries every 2 s over 60 s — the value never changed');
  });

  it('reads "changed on try 3" when a later try saw something else', () => {
    const step = { ...verify, waitHistory: [...verify.waitHistory!, { attempt: 3, elapsedMillis: 4000, message: 'x' }] };
    expect(waitingSummary(step)).toBe('30 tries every 2 s over 60 s — the value changed on try 3');
  });

  it('is null for a step that did not wait', () => {
    expect(waitingSummary({ ...verify, attempts: null, waitedMillis: null })).toBeNull();
  });
});
