/**
 * Where a scenario's steps are in its YAML text, without a YAML parser: the items of the top-level
 * `steps:` (or `cleanup:`) block sequence, found by indentation. The Nth item is step N (from 0),
 * as the engine numbers them. A flow sequence (`steps: [ … ]`) or a file that does not parse this
 * way gives no ranges; the caller then only opens the file.
 */

export interface LineRange {
  /** 1-based, inclusive. */
  from: number;
  to: number;
}

const indentOf = (line: string) => line.length - line.trimStart().length;
const isBlank = (line: string) => line.trim() === '' || line.trimStart().startsWith('#');

/** The line ranges of the items of the top-level block `key:`, in order. */
export function blockItems(text: string, key: 'steps' | 'cleanup'): LineRange[] {
  const lines = text.split(/\r?\n/);
  const head = lines.findIndex((line) => new RegExp(`^${key}\\s*:\\s*(#.*)?$`).test(line));
  if (head < 0) {
    return [];
  }
  let itemIndent = -1;
  const starts: number[] = [];
  let end = lines.length;
  for (let i = head + 1; i < lines.length; i++) {
    const line = lines[i];
    if (isBlank(line)) {
      continue;
    }
    const indent = indentOf(line);
    const item = /^-(\s|$)/.test(line.trimStart());
    if (itemIndent < 0) {
      if (!item) {
        return [];
      }
      itemIndent = indent;
    }
    if (indent < itemIndent || (indent === itemIndent && !item) || (indent === 0 && !item)) {
      end = i;
      break;
    }
    if (indent === itemIndent && item) {
      starts.push(i);
    }
  }
  return starts.map((start, n) => {
    let last = (n + 1 < starts.length ? starts[n + 1] : end) - 1;
    while (last > start && isBlank(lines[last])) {
      last--;
    }
    return { from: start + 1, to: last + 1 };
  });
}

/** The lines of step `index` (from 0), or null when they cannot be found. */
export function stepLines(text: string, index: number): LineRange | null {
  return blockItems(text, 'steps')[index] ?? null;
}

/** How many steps the scenario has, or null when that cannot be told. */
export function stepCount(text: string): number | null {
  const items = blockItems(text, 'steps');
  return items.length > 0 ? items.length : null;
}
