import { describe, expect, it } from 'vitest';
import pkg from '../../../package.json';

/**
 * Monaco runs only in a real browser, so this guards what the unit tests cannot see: monaco-yaml
 * 5.5 starts its worker through the old `editor.createWebWorker({moduleId, label, createData})`
 * options, which monaco-editor 0.54's entry still adapts and 0.57's does not (the YAML worker then
 * never starts: no schema completion, hover or folding). Upgrade them together, checking a real tab.
 */
describe('editor packages', () => {
  it('keeps monaco-editor on the 0.54 line that monaco-yaml 5.5 works with', () => {
    expect(pkg.dependencies['monaco-editor']).toMatch(/^0\.54\./);
    expect(pkg.dependencies['monaco-yaml']).toMatch(/^5\.5\./);
  });
});
