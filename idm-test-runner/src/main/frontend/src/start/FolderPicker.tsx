import { useEffect, useRef, useState } from 'react';
import { api, ApiProblem, withQuery, type FolderListing } from '../api/client';
import { t, useLanguage } from '../i18n';

/**
 * Browse…: the folders on this computer, as the workbench lists them (a browser page cannot). Starts
 * at `start` when it is a folder, else at the user's home; Escape or Cancel closes it.
 */
export function FolderPicker({
  start,
  onChoose,
  onCancel,
}: {
  start: string;
  onChoose: (path: string) => void;
  onCancel: () => void;
}) {
  useLanguage();
  const [listing, setListing] = useState<FolderListing | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const dialog = useRef<HTMLDivElement>(null);

  const load = async (path: string | undefined, fallBackHome: boolean) => {
    try {
      setListing(await api.get<FolderListing>(withQuery('/api/folders', { path })));
      setProblem(null);
    } catch (e) {
      if (fallBackHome && path !== undefined) {
        await load(undefined, false);
      } else {
        setProblem(e instanceof ApiProblem ? e.message : String(e));
      }
    }
  };

  useEffect(() => {
    dialog.current?.focus();
    void load(start.trim() || undefined, true);
  }, []);

  return (
    <div className="backdrop">
      <div
        ref={dialog}
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="picker-title"
        tabIndex={-1}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.stopPropagation();
            onCancel();
          }
        }}
      >
        <h3 id="picker-title">{t('ui.picker.title')}</h3>
        <div className="picker-path">
          <button
            type="button"
            className="secondary"
            disabled={!listing?.parent}
            onClick={() => listing?.parent && void load(listing.parent, false)}
          >
            ↑ {t('ui.picker.up')}
          </button>
          <span className="mono">{listing?.path ?? t('ui.loading')}</span>
        </div>
        {problem && (
          <p role="alert" className="problem">
            {problem}
          </p>
        )}
        <ul className="picker-list">
          {listing?.entries.map((entry) => (
            <li key={entry.path}>
              <button type="button" onClick={() => void load(entry.path, false)}>
                <span>{entry.name}</span>
                {entry.isWorkspace && <span className="tag"> {t('ui.picker.workspace')}</span>}
              </button>
            </li>
          ))}
          {listing && listing.entries.length === 0 && <li className="muted">{t('ui.picker.empty')}</li>}
        </ul>
        <div className="actions">
          <button type="button" className="secondary" onClick={onCancel}>
            {t('ui.picker.cancel')}
          </button>
          <button type="button" className="primary" disabled={!listing} onClick={() => listing && onChoose(listing.path)}>
            {t('ui.picker.choose')}
          </button>
        </div>
      </div>
    </div>
  );
}
