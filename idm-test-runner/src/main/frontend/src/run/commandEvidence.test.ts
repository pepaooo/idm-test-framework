import { describe, expect, it } from 'vitest';
import { evidenceText } from './FindingPanel';
import { systemIdOf, systemOf } from './systems';
import type { CommandEvidence } from './types';

const ran: CommandEvidence = {
  kind: 'command',
  command: 'driver-state',
  argv: ['dxcmd', '-user', 'admin', '-password', '****', '-getstate', 'cn=HR Driver,o=system'],
  exitCode: 0,
  durationMillis: 1200,
  timedOut: false,
  stdout: '2\n',
  stderr: '',
  stdoutBytes: 2,
  stderrBytes: 0,
};

describe('Command evidence', () => {
  it('reads as the command and its exit, the command line as started (an argument with spaces quoted), then what it wrote', () => {
    expect(evidenceText(ran).split('\n')).toEqual([
      'driver-state · exit 0 · 1.2 s',
      'dxcmd -user admin -password **** -getstate "cn=HR Driver,o=system"',
      'stdout:',
      '2',
    ]);
  });

  it('says when it timed out, and when it never started', () => {
    expect(evidenceText({ ...ran, exitCode: -1, timedOut: true, durationMillis: 30000, stdout: null }).split('\n')[0])
      .toBe('driver-state · timed out after 30 s');
    expect(evidenceText({ ...ran, argv: [], exitCode: null, durationMillis: null, timedOut: null, stdout: null, stderr: null }))
      .toBe('driver-state · not started');
  });

  it('an exec step talks to its command', () => {
    expect(systemIdOf({ type: 'exec.run', resolvedParams: { command: 'driver-start' } }, 's.yaml', null)).toBe('command:driver-start');
    expect(systemIdOf({ type: 'exec.verify', resolvedParams: {} }, 's.yaml', null)).toBeNull();
    expect(systemOf({ type: 'exec.run', resolvedParams: { command: 'driver-start' } }, 's.yaml', null).name).toBe('The command driver-start');
  });
});
