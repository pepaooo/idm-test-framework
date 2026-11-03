import { describe, expect, it } from 'vitest';
import { evidenceText } from './FindingPanel';
import { systemIdOf } from './systems';
import type { FileEvidence } from './types';

const read: FileEvidence = {
  kind: 'file',
  folder: 'hr-out',
  file: 'out.csv',
  operation: 'read',
  present: true,
  size: 120,
  header: ['id', 'status'],
  rows: [
    { number: 1, selected: false, values: { id: 'E1', status: 'pending' } },
    { number: 2, selected: true, values: { id: 'E2', status: '<b>ok</b>' } },
  ],
  totalRows: 60,
  selectedRows: 1,
};

describe('File evidence', () => {
  it('reads as the file and its folder name, then one line per row, the selected ones marked, and what was left out', () => {
    expect(evidenceText(read).split('\n')).toEqual([
      'read out.csv · folder hr-out · 120 bytes',
      '  1  id: E1 · status: pending',
      '▸ 2  id: E2 · status: <b>ok</b>',
      '… +58',
    ]);
  });

  it('says when no file matched yet, and when a file to delete was already gone', () => {
    expect(evidenceText({ ...read, present: false, size: null, header: [], rows: [], totalRows: null, selectedRows: null, file: 'out-*.csv' }))
      .toBe('read out-*.csv · folder hr-out · (no matching file yet)');
    expect(evidenceText({ ...read, operation: 'delete', present: false, size: null, header: [], rows: [], totalRows: null, selectedRows: null }))
      .toBe('delete out.csv · folder hr-out · (already absent)');
  });

  it('a file step talks to its folder', () => {
    expect(systemIdOf({ type: 'file.verify', resolvedParams: { folder: 'hr-out', file: '*.csv' } }, 's.yaml', null)).toBe('folder:hr-out');
    expect(systemIdOf({ type: 'file.write', resolvedParams: {} }, 's.yaml', null)).toBeNull();
  });
});
