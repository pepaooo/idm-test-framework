import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { t } from '../i18n';

interface Props {
  open: boolean;
  busy: boolean;
  error: string | null;
  profiles: readonly string[];
  onCancel(): void;
  onCreate(name: string): void;
}

/** A profile is a named, commented config file; its name is also its immutable CLI identifier. */
export function NewProfileDialog({ open, busy, error, profiles, onCancel, onCreate }: Props) {
  const [name, setName] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const titleId = useId();
  const inputId = useId();

  useEffect(() => {
    if (open) {
      setName('');
      setLocalError(null);
      input.current?.focus();
    }
  }, [open]);

  if (!open) return null;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    if (!name || name.includes('..') || !/^[A-Za-z0-9_.-]+$/.test(name)) {
      setLocalError(t('ui.profile.badName'));
      return;
    }
    if (profiles.some((profile) => profile.toLowerCase() === name.toLowerCase())) {
      setLocalError(t('ui.profile.exists'));
      return;
    }
    setLocalError(null);
    onCreate(name);
  };

  return <div className="modal-backdrop">
    <form className="modal new-profile" role="dialog" aria-modal="true" aria-labelledby={titleId}
      onSubmit={submit} onKeyDown={(event) => {
        if (event.key === 'Escape' && !busy) { event.stopPropagation(); onCancel(); }
      }}>
      <h2 id={titleId}>{t('ui.profile.new')}</h2>
      <label htmlFor={inputId}>{t('ui.profile.name')}</label>
      <input id={inputId} ref={input} value={name} autoComplete="off" spellCheck={false}
        onChange={(event) => { setName(event.target.value); setLocalError(null); }} />
      <p className="muted mono profile-preview">config/application-{name || '…'}.yml</p>
      <p className="muted">{t('ui.profile.starter')}</p>
      {(localError || error) && <p role="alert" className="notice">{localError || error}</p>}
      <div className="actions">
        <span className="spacer" />
        <button type="button" className="secondary" disabled={busy} onClick={onCancel}>{t('ui.profile.cancel')}</button>
        <button type="submit" className="primary" disabled={busy}>{t('ui.profile.create')}</button>
      </div>
    </form>
  </div>;
}
