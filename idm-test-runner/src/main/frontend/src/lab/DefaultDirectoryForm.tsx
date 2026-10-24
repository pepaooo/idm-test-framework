import { useEffect, useId, useState } from 'react';
import { api, ApiProblem, withQuery } from '../api/client';
import { Badge } from '../components/Badge';
import { t } from '../i18n';
import type { DefaultDirectory, LabView } from './types';

interface Props {
  block: DefaultDirectory;
  profile: string;
  readOnly: boolean;
  hidden: boolean;
  panelId: string;
  /** The save answered with the lab as it is now. */
  onSaved(view: LabView): void;
  onDirty(dirty: boolean): void;
}

/**
 * The lab panel's "Default directory" tab: scenarios have steps without `directory:` and
 * `idmtest run` cannot tell which directory they use (several directories and no
 * `default-directory`, or one that names none of them). Save writes the choice to the profile file
 * (`PUT /api/lab/default-directory`); a value set by the environment cannot be changed here.
 */
export function DefaultDirectoryForm({ block, profile, readOnly, hidden, panelId, onSaved, onDirty }: Props) {
  const uid = useId();
  const [choice, setChoice] = useState('');
  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const fromEnvironment = block.source === 'environment';
  const editable = !readOnly && !fromEnvironment && !busy;
  const dirty = choice !== '';

  useEffect(() => onDirty(dirty), [dirty, onDirty]);
  useEffect(() => () => onDirty(false), [onDirty]); // a tab that is gone has nothing unsaved

  const save = async () => {
    if (!editable || !dirty) {
      return;
    }
    setBusy(true);
    setSaveError(null);
    try {
      const view = await api.put<LabView>(withQuery('/api/lab/default-directory', { profile }), { name: choice });
      setChoice('');
      onSaved(view);
    } catch (e) {
      setSaveError(e instanceof ApiProblem ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const id = `${uid}-choice`;
  const profileFile = `config/application-${profile}.yml`;
  return (
    <div role="tabpanel" id={panelId} aria-label={t('ui.lab.defaultDirectory.title')} hidden={hidden} className="lab-form-panel">
      <p className="lab-used">{t('ui.lab.defaultDirectory.explain', { scenarios: block.usedByScenarios.join(', ') })}</p>
      {block.state === 'invalid' && (
        <p className="lab-used">
          <Badge status="missing" /> {t('ui.lab.defaultDirectory.invalid', { value: block.value ?? '' })}
        </p>
      )}
      <div className="lab-form">
        <div className="lab-row">
          <label htmlFor={id}>{t('ui.lab.defaultDirectory.choice')}</label>
          <div className="lab-cell">
            <select
              id={id}
              value={choice}
              disabled={!editable}
              aria-describedby={`${id}-notes`}
              onChange={(event) => {
                setChoice(event.target.value);
                setSaveError(null);
              }}
            >
              <option value="">{t('ui.lab.defaultDirectory.placeholder')}</option>
              {block.choices.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>
            <span id={`${id}-notes`} className="lab-notes">
              {fromEnvironment && <span className="lab-note">{t('ui.lab.fromEnvironment')}</span>}
              {block.source === 'application.yml' && <span className="lab-note">{t('ui.lab.fromApplicationYml')}</span>}
              {fromEnvironment && <span className="lab-note">{t('ui.lab.defaultDirectory.environment')}</span>}
            </span>
          </div>
        </div>
      </div>
      <div className="lab-actions">
        <span className="spacer" />
        <button type="button" className="primary" disabled={!editable || !dirty} onClick={() => void save()}>
          {busy ? t('ui.top.saving') : t('ui.lab.save')}
        </button>
      </div>
      {saveError && (
        <p role="alert" className="lab-error">
          <Badge status="error" /> {saveError}
        </p>
      )}
      <p className="lab-where">
        {t('ui.lab.defaultDirectory.where.before')}
        <code>{profileFile}</code>
        {t('ui.lab.defaultDirectory.where.after')}
      </p>
    </div>
  );
}
