import { useEffect, useId, useRef, useState } from 'react';
import { api, ApiProblem, withQuery } from '../api/client';
import { Badge } from '../components/Badge';
import { t } from '../i18n';
import { newSystemId, OAUTH_KEYS, SYSTEM_NAME, templateFields } from './fieldTemplates';
import type { LabField, LabSystem, LabView, TestResult } from './types';

interface Props {
  /** The system shown; null for "+ add" (a new one, its kind and name chosen here). */
  system: LabSystem | null;
  /** The ids already in the panel (a new system cannot take one). */
  existingIds: readonly string[];
  profile: string;
  readOnly: boolean;
  hidden: boolean;
  /** The panel's name: the system's (its tab also says its status). */
  label: string;
  panelId: string;
  /** A save answered with the lab as it is now. */
  onSaved(view: LabView, systemId: string): void;
  onDeleted(view: LabView, systemId: string): void;
  /** A test finished (the server now knows whether these values are ready). */
  onTested(): void;
  onDirty(dirty: boolean): void;
}

const DOTS = '••••••••';
type Kind = LabSystem['kind'];

/** A secret the server has: it shows as dots and can only be replaced ("Change"). */
function hasSavedSecret(field: LabField) {
  return field.state === 'set' || field.state === 'application.yml';
}

/**
 * One system's form in the lab panel. Plain values are React state (and only the changed ones are
 * sent). A secret is never in React state: its input is uncontrolled, starts empty, is read once
 * when Save or Test connection sends it and is emptied as soon as that finishes, whatever the answer
 * — so a typed secret never outlives the request that needs it, and is never in the DOM's markup.
 */
export function SystemForm({ system, existingIds, profile, readOnly, hidden, label: panelLabel, panelId, onSaved, onDeleted, onTested, onDirty }: Props) {
  const uid = useId();
  const [kind, setKind] = useState<Kind>(() => (existingIds.includes('ldap') ? 'database' : 'ldap'));
  const [name, setName] = useState('');
  const [edits, setEdits] = useState<Record<string, string>>({});
  /** Saved secrets being replaced (their empty input is shown). */
  const [changing, setChanging] = useState<ReadonlySet<string>>(new Set());
  /** Secret inputs with something typed in them (a flag only, never the text). */
  const [typed, setTyped] = useState<ReadonlySet<string>>(new Set());
  /** Secrets emptied after a test or a save that did not keep them. */
  const [cleared, setCleared] = useState<ReadonlySet<string>>(new Set());
  const [busy, setBusy] = useState<'saving' | 'testing' | 'deleting' | null>(null);
  const [result, setResult] = useState<TestResult | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const secretInputs = useRef(new Map<string, HTMLInputElement>());
  /** One stable ref callback per secret: an input that goes (e.g. Use OAuth turned off) no longer counts as typed. */
  const secretRefs = useRef(new Map<string, (input: HTMLInputElement | null) => void>());
  const secretRef = (key: string) => {
    let callback = secretRefs.current.get(key);
    if (!callback) {
      callback = (input) => {
        if (input) {
          secretInputs.current.set(key, input);
          return;
        }
        secretInputs.current.delete(key);
        setTyped((before) => {
          if (!before.has(key)) return before;
          const next = new Set(before);
          next.delete(key);
          return next;
        });
      };
      secretRefs.current.set(key, callback);
    }
    return callback;
  };
  const focusNext = useRef<string | null>(null);

  const adding = system === null;
  const fields = system ? system.fields : templateFields(kind, (key) => edits[key] ?? '');
  const original = (field: LabField) => (field.secret ? '' : (field.value ?? ''));
  const current = (field: LabField) => edits[field.key] ?? original(field);
  // busy: nothing typed while a save or test is out (it would be emptied when that finishes)
  const editable = (field: LabField) => !readOnly && busy === null && field.state !== 'environment';
  const useOauth = fields.some((f) => f.key === 'use-oauth') && current(fields.find((f) => f.key === 'use-oauth')!).trim().toLowerCase() === 'true';
  const shown = fields.filter((f) => !OAUTH_KEYS.has(f.key) || useOauth);

  const changedValues = () => {
    const values: Record<string, string> = {};
    for (const field of fields) {
      if (!field.secret && field.key in edits && edits[field.key] !== original(field)) {
        values[field.key] = edits[field.key];
      }
    }
    return values;
  };
  /** Reads the typed secrets (only those with something typed) for one request. */
  const typedSecrets = () => {
    const secrets: Record<string, string> = {};
    for (const field of shown) {
      const input = secretInputs.current.get(field.key);
      // only what the user typed (an input event set the flag), never a value the browser filled in
      if (field.secret && input && typed.has(field.key) && input.value !== '' && editable(field)) {
        secrets[field.key] = input.value;
      }
    }
    return secrets;
  };
  /** Empties every secret input; remembers which had something in it, to say it was not kept. */
  const forgetSecrets = (note: boolean) => {
    const had = new Set<string>();
    secretInputs.current.forEach((input, key) => {
      if (input.value !== '') {
        had.add(key);
      }
      input.value = '';
    });
    setTyped(new Set());
    setCleared(note ? had : new Set());
  };

  const newId = adding ? newSystemId(kind, name.trim()) : null;
  const nameNeeded = adding && kind !== 'soap' && !(kind === 'ldap' && !existingIds.includes('ldap'));
  const nameProblem = !adding
    ? null
    : name.trim() !== '' && !SYSTEM_NAME.test(name.trim())
      ? t('ui.lab.add.badName')
      : nameNeeded && name.trim() === ''
        ? t('ui.lab.add.nameNeeded')
        : existingIds.includes(newId!)
          ? t('ui.lab.add.exists')
          : null;
  const dirty = Object.keys(changedValues()).length > 0 || typed.size > 0;
  const systemId = system?.id ?? newId!;

  useEffect(() => onDirty(dirty), [dirty, onDirty]);
  useEffect(() => () => onDirty(false), [onDirty]); // a system that is gone has nothing unsaved
  useEffect(() => {
    if (focusNext.current) {
      secretInputs.current.get(focusNext.current)?.focus();
      focusNext.current = null;
    }
  });

  const setValue = (key: string, value: string) => {
    setEdits((before) => ({ ...before, [key]: value }));
    setSaveError(null);
  };

  const save = async () => {
    if (readOnly || !dirty || busy || nameProblem) {
      return;
    }
    setBusy('saving');
    setSaveError(null);
    const values = changedValues();
    const secrets = typedSecrets();
    try {
      const view = await api.put<LabView>(withQuery('/api/lab', { profile }), { systemId, values, secrets });
      forgetSecrets(false);
      setEdits({});
      setChanging(new Set());
      onSaved(view, systemId);
    } catch (e) {
      forgetSecrets(Object.keys(secrets).length > 0);
      setSaveError(e instanceof ApiProblem ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  };

  const test = async () => {
    if (busy || nameProblem) {
      return;
    }
    setBusy('testing');
    setResult(null);
    const secrets = typedSecrets();
    try {
      setResult(await api.post<TestResult>('/api/lab/test', { systemId, profile, values: changedValues(), secrets }));
    } catch (e) {
      setResult({ ok: false, message: e instanceof ApiProblem ? e.message : String(e), millis: -1 });
    } finally {
      forgetSecrets(Object.keys(secrets).length > 0);
      setBusy(null);
      onTested();
    }
  };

  const deletion = system?.deletion;
  const deletionReason = deletion?.reason
    ? t(`ui.lab.delete.reason.${deletion.reason}`) + (deletion.files.length ? ` · ${deletion.files.join(', ')}` : '')
    : null;
  const remove = async () => {
    if (!system || !deletion?.allowed || readOnly || dirty || busy) return;
    if (!window.confirm(t('ui.lab.delete.confirm', { name: system.name, id: system.id,
      path: `config/application-${profile}.yml` }))) return;
    setBusy('deleting');
    setSaveError(null);
    try {
      const view = await api.del<LabView>(withQuery('/api/lab', { profile, systemId: system.id }));
      onDeleted(view, system.id);
    } catch (e) {
      setSaveError(e instanceof ApiProblem ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  };

  const shownResult = result ?? system?.lastTest ?? null;
  const fromSharedFile = fields.some((f) => f.state === 'application.yml');
  const profileFile = `config/application-${profile}.yml`;

  return (
    <div role="tabpanel" id={panelId} aria-label={panelLabel} hidden={hidden} className="lab-form-panel">
      {system && <div className="lab-identity">
        <label htmlFor={`${uid}-identifier`}>{t('ui.lab.identifier')}</label>
        <input id={`${uid}-identifier`} value={system.id} readOnly />
      </div>}
      {adding && (
        <div className="lab-form">
          <label htmlFor={`${uid}-kind`}>{t('ui.lab.add.kind')}</label>
          <select id={`${uid}-kind`} value={kind} disabled={readOnly} onChange={(event) => {
              // a new kind is a new form: nothing typed for the old one carries over
              setKind(event.target.value as Kind);
              setEdits({});
              setChanging(new Set());
              setTyped(new Set());
              setCleared(new Set());
              setResult(null);
              setSaveError(null);
            }}>
            <option value="ldap">{t('ui.lab.kind.ldap')}</option>
            <option value="database">{t('ui.lab.kind.database')}</option>
            <option value="rest">{t('ui.lab.kind.rest')}</option>
            {!existingIds.includes('soap') && <option value="soap">{t('ui.lab.kind.soap')}</option>}
          </select>
          {kind !== 'soap' && (
            <>
              <label htmlFor={`${uid}-name`}>{t('ui.lab.add.name')}</label>
              <div className="lab-cell">
                <input
                  id={`${uid}-name`}
                  value={name}
                  readOnly={readOnly}
                  aria-invalid={nameProblem ? true : undefined}
                  aria-describedby={`${uid}-name-hint`}
                  spellCheck={false}
                  onChange={(event) => setName(event.target.value)}
                />
                <span id={`${uid}-name-hint`} className={nameProblem ? 'lab-note lab-bad' : 'lab-note'}>
                  {nameProblem ?? t('ui.lab.add.nameHint')}
                </span>
              </div>
            </>
          )}
        </div>
      )}
      {system && system.usedByScenarios.length === 0 && <p className="muted lab-used">{t('ui.lab.unused')}</p>}
      {system && system.usedByScenarios.length > 0 && (
        <p className="muted lab-used">{t('ui.lab.usedBy', { names: system.usedByScenarios.join(', ') })}</p>
      )}
      <div className="lab-form">
        {shown.map((field) => {
          const id = `${uid}-${field.key}`;
          const notes: string[] = [];
          if (field.state === 'environment') notes.push(t('ui.lab.fromEnvironment'));
          if (field.state === 'application.yml') notes.push(t('ui.lab.fromApplicationYml'));
          const needed = field.required && field.state === 'not set' && !(field.key in edits && edits[field.key] !== '') && !typed.has(field.key);
          const describedBy = `${id}-notes`;
          const fieldLabel = field.key === 'label' ? t('ui.lab.field.label') : field.label;
          const label = (
            <label htmlFor={id}>
              {fieldLabel}
              {field.secret && <span aria-hidden="true"> 🔒</span>}
            </label>
          );
          const noteLine = (
            <span id={describedBy} className="lab-notes">
              {needed && (
                <span className="lab-note lab-needed">
                  <span aria-hidden="true">✕</span> <span>{t('ui.lab.needed')}</span>
                </span>
              )}
              {notes.map((note) => (
                <span key={note} className="lab-note">
                  {note}
                </span>
              ))}
              {field.secret && cleared.has(field.key) && <span className="lab-note lab-bad">{t('ui.lab.secretCleared')}</span>}
              {field.secret && field.state !== 'environment' && <span className="lab-note">{t('ui.lab.secretHint')}</span>}
            </span>
          );
          if (field.key === 'use-oauth') {
            return (
              <div key={`${kind}:${field.key}`} className="lab-row">
                {label}
                <div className="lab-cell">
                  <input
                    id={id}
                    type="checkbox"
                    checked={current(field).trim().toLowerCase() === 'true'}
                    disabled={!editable(field)}
                    aria-describedby={describedBy}
                    onChange={(event) =>
                      setValue(field.key, event.target.checked ? 'true' : original(field) === '' ? '' : 'false')
                    }
                  />
                  {noteLine}
                </div>
              </div>
            );
          }
          if (field.secret) {
            const locked = !editable(field) || (hasSavedSecret(field) && !changing.has(field.key)) || field.state === 'environment';
            return (
              <div key={`${kind}:${field.key}`} className="lab-row">
                {label}
                <div className="lab-cell">
                  <div className="lab-secret">
                    <input
                      id={id}
                      type="password"
                      // "off" is ignored for passwords; this asks the browser not to fill in a saved one
                      autoComplete="new-password"
                      spellCheck={false}
                      defaultValue=""
                      readOnly={locked}
                      required={field.required}
                      placeholder={field.state === 'not set' || changing.has(field.key) ? '' : DOTS}
                      aria-describedby={describedBy}
                      ref={secretRef(field.key)}
                      onInput={(event) => {
                        const has = (event.target as HTMLInputElement).value !== '';
                        setTyped((before) => {
                          const next = new Set(before);
                          if (has) next.add(field.key);
                          else next.delete(field.key);
                          return next;
                        });
                        setCleared(new Set());
                        setSaveError(null);
                      }}
                    />
                    {editable(field) && hasSavedSecret(field) && !changing.has(field.key) && (
                      <button
                        type="button"
                        className="link"
                        aria-label={t('ui.lab.change', { field: field.label })}
                        onClick={() => {
                          focusNext.current = field.key;
                          setChanging((before) => new Set(before).add(field.key));
                        }}
                      >
                        {t('ui.lab.changeShort')}
                      </button>
                    )}
                    {editable(field) && changing.has(field.key) && (
                      <button
                        type="button"
                        className="link"
                        aria-label={t('ui.lab.keep', { field: field.label })}
                        onClick={() => {
                          const input = secretInputs.current.get(field.key);
                          if (input) input.value = '';
                          setTyped((before) => {
                            const next = new Set(before);
                            next.delete(field.key);
                            return next;
                          });
                          setChanging((before) => {
                            const next = new Set(before);
                            next.delete(field.key);
                            return next;
                          });
                        }}
                      >
                        {t('ui.lab.keepShort')}
                      </button>
                    )}
                  </div>
                  {noteLine}
                </div>
              </div>
            );
          }
          return (
            <div key={`${kind}:${field.key}`} className="lab-row">
              {label}
              <div className="lab-cell">
                <input
                  id={id}
                  value={current(field)}
                  readOnly={!editable(field)}
                  required={field.required}
                  spellCheck={false}
                  aria-describedby={describedBy}
                  onChange={(event) => setValue(field.key, event.target.value)}
                />
                {noteLine}
              </div>
            </div>
          );
        })}
      </div>
      <div className="lab-actions">
        {system && <button type="button" className="secondary lab-delete"
          disabled={!deletion?.allowed || readOnly || dirty || busy !== null}
          title={deletionReason ?? undefined}
          onClick={() => void remove()}>{t('ui.lab.delete.button')}</button>}
        <button type="button" className="secondary" disabled={busy !== null || Boolean(nameProblem)} onClick={() => void test()}>
          {busy === 'testing' ? t('ui.lab.testing') : t('ui.lab.test')}
        </button>
        <span role="status" className="lab-result">
          {shownResult && (
            <>
              <Badge status={shownResult.ok ? 'ok' : 'error'} /> {shownResult.message}
              {shownResult.ok && shownResult.millis >= 0 ? ` · ${shownResult.millis} ms` : ''}
            </>
          )}
        </span>
        <span className="spacer" />
        <button
          type="button"
          className="secondary"
          disabled={!dirty || busy !== null}
          onClick={() => {
            setEdits({});
            setChanging(new Set());
            forgetSecrets(false);
            setSaveError(null);
          }}
        >
          {t('ui.lab.discard')}
        </button>
        <button
          type="button"
          className="primary"
          disabled={readOnly || !dirty || busy !== null || Boolean(nameProblem)}
          onClick={() => void save()}
        >
          {busy === 'saving' ? t('ui.top.saving') : t('ui.lab.save')}
        </button>
      </div>
      {system && deletionReason && <p className="lab-delete-note">{deletionReason}</p>}
      {saveError && (
        <p role="alert" className="lab-error">
          <Badge status="error" /> {saveError}
        </p>
      )}
      <p className="lab-where">
        {t('ui.lab.where.before')}
        <code>{profileFile}</code>
        {t('ui.lab.where.middle')}
        <code>secrets.env</code>
        {t('ui.lab.where.after')}
      </p>
      {fromSharedFile && (
        <p className="lab-where">
          {t('ui.lab.override.before')}
          <code>{profileFile}</code>
          {t('ui.lab.override.after')}
        </p>
      )}
    </div>
  );
}
