import type { ReactNode } from 'react';
import { LanguageSelect } from '../components/LanguageSelect';
import { t } from '../i18n';
import { ThemeSwitch } from '../theme/ThemeSwitch';

interface Props {
  name: string;
  path: string;
  profiles: readonly string[];
  profile: string | null;
  onProfile(profile: string): void;
  unsaved: number;
  canSave: boolean;
  saving: boolean;
  onSave(): void;
  onResetLayout?(): void;
  /** The lab chip (Task 13). */
  lab?: ReactNode;
  /** The Run split button (Task 14). */
  run?: ReactNode;
}

/** Mockup B's top bar: workspace · profile · lab chip · unsaved · Save · Run, then language and theme. */
export function TopBar({ name, path, profiles, profile, onProfile, unsaved, canSave, saving, onSave, onResetLayout, lab, run }: Props) {
  return (
    <header className="top">
      <span className="logo">{t('ui.app.name')}</span>
      <h1 className="ws-name" title={path}>
        {name}
      </h1>
      <label className="profile">
        <span className="muted">{t('ui.top.profile')}</span>
        <select className="pill" value={profile ?? ''} disabled={profiles.length === 0} onChange={(event) => onProfile(event.target.value)}>
          {profiles.length === 0 && <option value="">{t('ui.top.noProfiles')}</option>}
          {profiles.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </select>
      </label>
      <div className="slot-lab">{lab}</div>
      <span className="spacer" />
      {unsaved > 0 && <span className="unsaved-count">{t('ui.top.unsaved', { count: unsaved })}</span>}
      <button type="button" className="secondary" disabled={!canSave || saving} title={t('ui.top.saveHint')} onClick={onSave}>
        {saving ? t('ui.top.saving') : t('ui.top.save')}
      </button>
      <div className="slot-run">{run}</div>
      {onResetLayout && <button type="button" className="pill" onClick={onResetLayout}>{t('ui.layout.reset')}</button>}
      <LanguageSelect />
      <ThemeSwitch />
    </header>
  );
}
