import { useEffect, useId, useRef, useState } from 'react';
import { Badge } from '../components/Badge';
import { t } from '../i18n';

interface Props {
  /** The connection's display name and its identifier (`database:sap`). */
  name: string;
  id: string;
  /** The profile file the connection's block is removed from. */
  path: string;
  /**
   * Deletes the connection: resolves to null when done (the dialog is then closed by its owner), or to
   * the reason the server refused it, shown in the dialog, which stays open.
   */
  onDelete(): Promise<string | null>;
  onCancel(): void;
}

/**
 * Asks before a connection's block is deleted from the profile file, and says that its passwords stay
 * in `secrets.env`. The focus starts on Cancel; Escape cancels, except while the delete is on its way,
 * and never reaches the lab panel (which it would close).
 */
export function DeleteConnectionDialog({ name, id, path, onDelete, onCancel }: Props) {
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const cancel = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const textId = useId();

  useEffect(() => {
    cancel.current?.focus();
  }, []);

  const confirm = async () => {
    if (busy) {
      return;
    }
    setBusy(true);
    setProblem(null);
    const refused = await onDelete();
    if (refused !== null) {
      setBusy(false);
      setProblem(refused);
    }
  };

  return (
    <div className="modal-backdrop">
      <div
        className="modal delete-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={textId}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.stopPropagation();
            // a delete on its way finishes first
            if (!busy) {
              onCancel();
            }
          }
        }}
      >
        <h2 id={titleId}>{t('ui.lab.delete.button')}</h2>
        <div id={textId}>
          <p>{t('ui.lab.delete.confirm', { name, id, path })}</p>
          <p className="muted">{t('ui.lab.delete.secrets')}</p>
        </div>
        {problem && (
          <p role="alert" className="notice">
            <Badge status="warn" /> {problem}
          </p>
        )}
        <div className="actions">
          <span className="spacer" />
          <button ref={cancel} type="button" className="secondary" disabled={busy} onClick={onCancel}>
            {t('ui.lab.delete.cancel')}
          </button>
          <button type="button" className="primary" disabled={busy} onClick={() => void confirm()}>
            {t('ui.lab.delete.button')}
          </button>
        </div>
      </div>
    </div>
  );
}
