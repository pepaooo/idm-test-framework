import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { Badge } from '../components/Badge';
import { t } from '../i18n';

/** A part before the first dot that Windows keeps for a device, in any letter case. */
const WINDOWS_DEVICE = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i;

/**
 * The file name for what was typed (trimmed), `.yaml` added when it ends in neither .yaml nor
 * .yml; null when it cannot be a file name, as the server's `ui.file.badName` (WorkspaceFiles
 * .createScenario) decides: an empty name before the extension, a name starting with a dot, one with
 * `/`, `\`, `..`, `:` or `* ? " < > |`, one ending in a dot or space before the extension, or a
 * Windows device name (con, nul, com1, lpt9…) before its first dot.
 */
export function fileNameOf(typed: string): string | null {
  const trimmed = typed.trim();
  const name = /\.ya?ml$/.test(trimmed) ? trimmed : `${trimmed}.yaml`;
  const stem = name.replace(/\.ya?ml$/, '');
  if (
    !stem ||
    name.startsWith('.') ||
    /[/\\:*?"<>|]/.test(name) ||
    name.includes('..') ||
    /[. ]$/.test(stem) ||
    WINDOWS_DEVICE.test(stem.split('.')[0].trim())
  ) {
    return null;
  }
  return name;
}

interface Props {
  /** The dialog's title: New scenario or Duplicate. */
  title: string;
  /** The input's label. */
  label: string;
  /** Where the file goes (workspace-relative, under scenarios/). */
  folder: string;
  /** The name filled in to start with; its part before the extension is selected. */
  suggested: string;
  /**
   * Creates `folder/fileName`: resolves to null when done (the dialog is then closed by its owner),
   * or to the reason it was refused, shown in the dialog, which stays open for another name.
   */
  onCreate(fileName: string): Promise<string | null>;
  onCancel(): void;
}

/**
 * Asks for the name of a new scenario file (New scenario, Duplicate): a small labelled form. Enter
 * creates, Escape cancels; a refused name keeps the dialog open with the reason.
 */
export function NewScenarioDialog({ title, label, folder, suggested, onCreate, onCancel }: Props) {
  const [typed, setTyped] = useState(suggested);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const titleId = useId();
  const inputId = useId();
  const problemId = useId();

  useEffect(() => {
    input.current?.focus();
    const stem = suggested.replace(/\.ya?ml$/, '').length;
    input.current?.setSelectionRange(0, stem);
  }, [suggested]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) {
      return;
    }
    const fileName = fileNameOf(typed);
    if (fileName === null) {
      setProblem(t('ui.tree.badName', { name: typed.trim() }));
      input.current?.focus();
      return;
    }
    setBusy(true);
    setProblem(null);
    const refused = await onCreate(fileName);
    setBusy(false);
    if (refused !== null) {
      setProblem(refused);
      input.current?.focus();
    }
  };

  return (
    <div className="modal-backdrop">
      <form
        className="modal new-scenario"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onSubmit={(event) => void submit(event)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.stopPropagation();
            // a create on its way finishes first: closing now would leave a file made behind the dialog
            if (!busy) {
              onCancel();
            }
          }
        }}
      >
        <h2 id={titleId}>{title}</h2>
        <label htmlFor={inputId}>{label}</label>
        <div className="new-scenario-name">
          <span className="mono muted">{folder}/</span>
          <input
            id={inputId}
            ref={input}
            type="text"
            value={typed}
            spellCheck={false}
            autoComplete="off"
            aria-invalid={problem !== null}
            aria-describedby={problem ? problemId : undefined}
            onChange={(event) => setTyped(event.target.value)}
          />
        </div>
        {problem && (
          <p id={problemId} role="alert" className="notice">
            <Badge status="warn" /> {problem}
          </p>
        )}
        <div className="actions">
          <span className="spacer" />
          <button type="button" className="secondary" disabled={busy} onClick={onCancel}>
            {t('ui.tree.cancel')}
          </button>
          <button type="submit" className="primary" disabled={busy}>
            {t('ui.tree.create')}
          </button>
        </div>
      </form>
    </div>
  );
}
