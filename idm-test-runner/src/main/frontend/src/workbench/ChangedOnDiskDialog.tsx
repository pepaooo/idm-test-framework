import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { Badge } from '../components/Badge';
import { t } from '../i18n';
import { languageOf } from './types';

const DiffView = lazy(() => import('./monaco/DiffView'));

interface Props {
  path: string;
  mine: string;
  /** null: the file on disk could not be read back; Load and Compare wait for a retry. */
  disk: string | null;
  error: string | null;
  busy: boolean;
  /** The file is gone from disk: Keep mine creates it again, Discard drops the edits. */
  deleted?: boolean;
  onRetry(): void;
  onKeepMine(): void;
  onLoadFromDisk(): void;
  onDiscard?(): void;
}

/**
 * A file changed on disk while this tab has unsaved edits (a `changed` event, a 409 on Save, or a
 * draft made over an older version). Nothing is overwritten until the user chooses: Keep mine
 * (overwrites the disk, after a confirm), Load from disk (drops the edits), or Compare first.
 * When the file was deleted on disk instead (`deleted`), there is nothing to load or compare: Keep
 * mine creates it again (nothing is overwritten, so no confirm), Discard closes the tab.
 */
export function ChangedOnDiskDialog({
  path,
  mine,
  disk,
  error,
  busy,
  deleted = false,
  onRetry,
  onKeepMine,
  onLoadFromDisk,
  onDiscard = () => undefined,
}: Props) {
  const [confirming, setConfirming] = useState(false);
  const [comparing, setComparing] = useState(false);
  const first = useRef<HTMLButtonElement>(null);
  const name = path.split('/').pop() ?? path;

  useEffect(() => {
    first.current?.focus();
  }, [confirming]);

  if (deleted) {
    return (
      <div className="modal-backdrop">
        <div className="modal disk-dialog" role="dialog" aria-modal="true" aria-labelledby="disk-title">
          <h2 id="disk-title">{t('ui.disk.deletedTitle', { name })}</h2>
          <p className="mono muted">{path}</p>
          <p>{t('ui.disk.deletedText', { path })}</p>
          {error && (
            <p role="alert" className="notice">
              <Badge status="error" /> {error}
            </p>
          )}
          <div className="actions">
            <span className="spacer" />
            <button ref={first} type="button" className="secondary" disabled={busy} onClick={onDiscard}>
              {t('ui.disk.discard')}
            </button>
            <button type="button" className="primary" disabled={busy} onClick={onKeepMine}>
              {t('ui.disk.keepMine')}
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="modal-backdrop">
      <div
        className={`modal disk-dialog${comparing ? ' disk-dialog-wide' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="disk-title"
        onKeyDown={(event) => {
          if (event.key === 'Escape' && (confirming || comparing)) {
            event.stopPropagation();
            setConfirming(false);
            setComparing(false);
          }
        }}
      >
        <h2 id="disk-title">{t('ui.disk.title', { name })}</h2>
        <p className="mono muted">{path}</p>
        <p>{t('ui.disk.text')}</p>
        {error && (
          <p role="alert" className="notice">
            <Badge status="error" /> {error}
          </p>
        )}
        {disk === null && (
          <p role="alert" className="notice">
            <Badge status="warn" /> {t('ui.disk.unreadable')}{' '}
            <button type="button" className="secondary" onClick={onRetry}>
              {t('ui.disk.retry')}
            </button>
          </p>
        )}
        {confirming ? (
          <div className="disk-confirm">
            <p>
              <Badge status="warn" /> {t('ui.disk.confirm')}
            </p>
            <div className="actions">
              <button ref={first} type="button" className="secondary" onClick={() => setConfirming(false)}>
                {t('ui.disk.back')}
              </button>
              <button
                type="button"
                className="primary"
                disabled={busy}
                onClick={() => {
                  setConfirming(false);
                  onKeepMine();
                }}
              >
                {t('ui.disk.overwrite')}
              </button>
            </div>
          </div>
        ) : (
          <div className="actions">
            <button
              ref={first}
              type="button"
              className="secondary"
              disabled={disk === null}
              onClick={() => setComparing((value) => !value)}
            >
              {comparing ? t('ui.disk.hideCompare') : t('ui.disk.compare')}
            </button>
            <span className="spacer" />
            <button type="button" className="secondary" disabled={busy || disk === null} onClick={onLoadFromDisk}>
              {t('ui.disk.load')}
            </button>
            <button type="button" className="primary" disabled={busy} onClick={() => setConfirming(true)}>
              {t('ui.disk.keepMine')}
            </button>
          </div>
        )}
        {comparing && disk !== null && (
          <div className="diff">
            <div className="diff-heads">
              <span>{t('ui.disk.onDisk')}</span>
              <span>{t('ui.disk.mine')}</span>
            </div>
            <div className="diff-body">
              <Suspense fallback={<p className="muted">{t('ui.editor.loading')}</p>}>
                <DiffView path={path} language={languageOf(path)} disk={disk} mine={mine} />
              </Suspense>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
