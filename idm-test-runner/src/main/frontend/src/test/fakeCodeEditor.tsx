import type { CodeEditorProps, DiffViewProps } from '../workbench/types';

/**
 * Stands in for Monaco in the unit tests (Monaco needs a real browser): a textarea with the same
 * props, exposing what the workbench told it as data attributes.
 */
export function FakeCodeEditor(props: CodeEditorProps) {
  return (
    <textarea
      aria-label={props.label}
      data-path={props.path}
      data-language={props.language}
      data-marks={props.marks ? `${props.marks.from}-${props.marks.to}` : ''}
      data-focus-line={props.focus ? String(props.focus.line) : ''}
      readOnly={props.readOnly}
      value={props.value}
      onChange={(event) => props.onChange(event.target.value)}
    />
  );
}

export function FakeDiffView({ disk, mine }: DiffViewProps) {
  return (
    <div data-testid="diff">
      <pre aria-label="disk side">{disk}</pre>
      <pre aria-label="mine side">{mine}</pre>
    </div>
  );
}
