import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { Badge } from '../components/Badge';
import { t } from '../i18n';
import { languageOf } from './types';

const DiffView = lazy(() => import('./monaco/DiffView'));

interface Props {
  path: string;
  mine: string;
  disk: string;
  error: string | null;
  busy: boolean;
  onKeepMine(): void;
  onLoadFromDisk(): void;
}

/**
 * A file changed on disk while this tab has unsaved edits (a `changed` event, a 409 on Save, or a
 * draft made over an older version). Nothing is overwritten until the user chooses: Keep mine
 * (overwrites the disk, after a confirm), Load from disk (drops the edits), or Compare first.
 */
export function ChangedOnDiskDialog({ path, mine, disk, error, busy, onKeepMine, onLoadFromDisk }: Props) {
  const [confirming, setConfirming] = useState(false);
  const [comparing, setComparing] = useState(false);
  const first = useRef<HTMLButtonElement>(null);
  const name = path.split('/').pop() ?? path;

  useEffect(() => {
    first.current?.focus();
  }, [confirming]);

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
            <button ref={first} type="button" className="secondary" onClick={() => setComparing((value) => !value)}>
              {comparing ? t('ui.disk.hideCompare') : t('ui.disk.compare')}
            </button>
            <span className="spacer" />
            <button type="button" className="secondary" disabled={busy} onClick={onLoadFromDisk}>
              {t('ui.disk.load')}
            </button>
            <button type="button" className="primary" disabled={busy} onClick={() => setConfirming(true)}>
              {t('ui.disk.keepMine')}
            </button>
          </div>
        )}
        {comparing && (
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
