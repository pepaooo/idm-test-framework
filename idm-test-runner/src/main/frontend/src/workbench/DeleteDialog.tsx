import { useEffect, useId, useRef, useState } from 'react';
import { api, withQuery } from '../api/client';
import { Badge } from '../components/Badge';
import { t } from '../i18n';

type GitState = 'clean' | 'changed' | 'untracked' | 'none';

interface Props {
  /** The scenario to delete (workspace-relative). */
  path: string;
  /** The file has unsaved edits in this tab. */
  unsaved: boolean;
  /**
   * Deletes the file: resolves to null when done (the dialog is then closed by its owner), or to the
   * reason it was refused, shown in the dialog, which stays open.
   */
  onDelete(): Promise<string | null>;
  onCancel(): void;
}

/**
 * Asks before a scenario file is deleted. It says when the file has unsaved edits, and — once
 * `GET /api/files/git` answers `untracked` or `changed` — that git cannot bring it back. The
 * question shows at once and Delete never waits for git. The focus starts on Cancel; Escape cancels,
 * except while the delete is on its way.
 */
export function DeleteDialog({ path, unsaved, onDelete, onCancel }: Props) {
  const [git, setGit] = useState<GitState | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const cancel = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const textId = useId();

  useEffect(() => {
    cancel.current?.focus();
  }, []);

  useEffect(() => {
    let live = true;
    api
      .get<{ state: GitState }>(withQuery('/api/files/git', { path }))
      .then((answer) => {
        if (live) {
          setGit(answer.state);
        }
      })
      .catch(() => undefined); // no answer: nothing more is said
    return () => {
      live = false;
    };
  }, [path]);

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
        <h2 id={titleId}>{t('ui.tree.delete')}</h2>
        <div id={textId}>
          <p>{t('ui.tree.deleteConfirm', { path })}</p>
          {unsaved && (
            <p>
              <Badge status="warn" /> <span>{t('ui.tree.deleteUnsaved')}</span>
            </p>
          )}
          {(git === 'untracked' || git === 'changed') && (
            <p>
              <Badge status="warn" /> <span>{t('ui.tree.deleteNotInGit')}</span>
            </p>
          )}
        </div>
        {problem && (
          <p role="alert" className="notice">
            <Badge status="warn" /> {problem}
          </p>
        )}
        <div className="actions">
          <span className="spacer" />
          <button ref={cancel} type="button" className="secondary" disabled={busy} onClick={onCancel}>
            {t('ui.tree.cancel')}
          </button>
          <button type="button" className="primary" disabled={busy} onClick={() => void confirm()}>
            {t('ui.tree.delete')}
          </button>
        </div>
      </div>
    </div>
  );
}
