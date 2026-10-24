import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Badge, type BadgeStatus } from '../components/Badge';
import { t } from '../i18n';
import { DefaultDirectoryForm } from './DefaultDirectoryForm';
import { DEFAULT_DIRECTORY_TAB } from './labState';
import { SystemForm } from './SystemForm';
import type { LabSystem, LabView } from './types';

interface Props {
  view: LabView | null;
  error: string | null;
  profile: string;
  readOnly: boolean;
  /** The tab to show (a system id), or null for the first. */
  selected: string | null;
  onSelect(systemId: string | null): void;
  /** Closes the panel (after asking, when something typed is not saved). */
  onClose(): void;
  onSaved(view: LabView): void;
  onTested(): void;
  /** Whether anything typed is not saved (so the chip can ask before it closes the panel). */
  onDirtyChange(dirty: boolean): void;
}

const STATUS_BADGE: Record<LabSystem['status'], BadgeStatus> = { ready: 'ok', untested: 'warn', missing: 'missing' };
const ADD = '+add';

/** An id usable in the DOM for a system id (which may hold ':'). */
function domId(systemId: string) {
  return systemId.replace(/[^A-Za-z0-9_-]/g, '-');
}

/**
 * The lab panel (spec §4.5, mockup "lab connection"): one tab per system, each its own form, all
 * kept mounted while the panel is open so switching tabs keeps what was typed; "+ add" for a
 * system no scenario uses yet. When steps without `directory:` have no directory, a first
 * "Default directory" tab asks which one they use. Closing (✕ or Escape) asks first when something
 * is not saved.
 */
export function LabPanel({ view, error, profile, readOnly, selected, onSelect, onClose, onSaved, onTested, onDirtyChange }: Props) {
  const systems = view?.systems ?? [];
  const defaultDirectory = view?.defaultDirectory ?? null;
  /** The tabs in order: the default directory's (when asked for), then the systems'. */
  const tabs: { id: string; name: string; status: LabSystem['status'] }[] = [
    ...(defaultDirectory ? [{ id: DEFAULT_DIRECTORY_TAB, name: t('ui.lab.defaultDirectory.title'), status: 'missing' as const }] : []),
    ...systems.map((system) => ({ id: system.id, name: system.name, status: system.status })),
  ];
  const adding = selected === ADD;
  const current = adding ? ADD : (tabs.find((tab) => tab.id === selected)?.id ?? tabs[0]?.id ?? (view ? ADD : null));
  const tabRefs = useRef(new Map<string, HTMLButtonElement>());
  const dirty = useRef(new Set<string>());
  const [addKey, setAddKey] = useState(0);

  const markDirty = useCallback(
    (id: string, isDirty: boolean) => {
      if (isDirty) dirty.current.add(id);
      else dirty.current.delete(id);
      onDirtyChange(dirty.current.size > 0);
    },
    [onDirtyChange],
  );
  const dirtyHandlers = useRef(new Map<string, (d: boolean) => void>());
  const onDirtyFor = (id: string) => {
    let handler = dirtyHandlers.current.get(id);
    if (!handler) {
      handler = (d: boolean) => markDirty(id, d);
      dirtyHandlers.current.set(id, handler);
    }
    return handler;
  };

  const close = () => {
    if (dirty.current.size > 0 && !window.confirm(t('ui.lab.confirmClose'))) {
      return;
    }
    onClose();
  };

  useEffect(() => {
    if (current && current !== ADD) {
      tabRefs.current.get(current)?.focus();
    }
  }, []); // only when the panel opens: focus starts on its tab

  const onTabKey = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    let next = -1;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    if (next >= 0) {
      event.preventDefault();
      onSelect(tabs[next].id);
      tabRefs.current.get(tabs[next].id)?.focus();
    }
  };

  return (
    <div
      id="lab-panel"
      className="lab-panel"
      role="dialog"
      aria-modal="false"
      aria-labelledby="lab-panel-title"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.stopPropagation();
          close();
        }
      }}
    >
      <div className="lab-head">
        <h2 id="lab-panel-title">
          {t('ui.lab.title')} · {t('ui.lab.profile')} <span className="lab-profile">{profile}</span>
        </h2>
        <button type="button" className="link lab-close" aria-label={t('ui.lab.close')} onClick={close}>
          ✕
        </button>
      </div>
      {readOnly && (
        <p className="notice lab-readonly">
          <Badge status="warn" /> {t('ui.lab.readOnly')}
        </p>
      )}
      {error && (
        <p role="alert" className="lab-error">
          <Badge status="error" /> {error}
        </p>
      )}
      {!view && !error && <p className="muted lab-loading">{t('ui.loading')}</p>}
      {view && (
        <>
          <div className="lab-tabs">
            <div role="tablist" aria-label={t('ui.lab.systems')}>
              {tabs.map((tab, index) => (
                <button
                  key={tab.id}
                  ref={(button) => {
                    if (button) tabRefs.current.set(tab.id, button);
                    else tabRefs.current.delete(tab.id);
                  }}
                  type="button"
                  role="tab"
                  id={`lab-tab-${domId(tab.id)}`}
                  aria-selected={current === tab.id}
                  aria-controls={`lab-tabpanel-${domId(tab.id)}`}
                  tabIndex={current === tab.id ? 0 : -1}
                  className={current === tab.id ? 'lab-tab on' : 'lab-tab'}
                  onClick={() => onSelect(tab.id)}
                  onKeyDown={(event) => onTabKey(event, index)}
                >
                  <Badge status={STATUS_BADGE[tab.status]} label={t(`ui.lab.status.${tab.status}`)} /> {tab.name}
                </button>
              ))}
            </div>
            <button
              type="button"
              id="lab-tab-add"
              className={adding ? 'lab-tab lab-add on' : 'lab-tab lab-add'}
              aria-pressed={adding}
              aria-controls="lab-tabpanel-add"
              disabled={readOnly}
              title={readOnly ? t('ui.lab.readOnly') : t('ui.lab.add.hint')}
              onClick={() => {
                if (addKey === 0) setAddKey(1);
                onSelect(ADD);
              }}
            >
              {t('ui.lab.add')}
            </button>
          </div>
          {tabs.length === 0 && !adding && <p className="muted lab-loading">{t('ui.lab.none')}</p>}
          {defaultDirectory && (
            <DefaultDirectoryForm
              block={defaultDirectory}
              profile={profile}
              readOnly={readOnly}
              hidden={current !== DEFAULT_DIRECTORY_TAB}
              panelId={`lab-tabpanel-${domId(DEFAULT_DIRECTORY_TAB)}`}
              onSaved={onSaved}
              onDirty={onDirtyFor(DEFAULT_DIRECTORY_TAB)}
            />
          )}
          {systems.map((system) => (
            <SystemForm
              key={system.id}
              system={system}
              existingIds={systems.map((s) => s.id)}
              profile={profile}
              readOnly={readOnly}
              hidden={current !== system.id}
              label={system.name}
              panelId={`lab-tabpanel-${domId(system.id)}`}
              onSaved={onSaved}
              onTested={onTested}
              onDirty={onDirtyFor(system.id)}
            />
          ))}
          {addKey > 0 && (
            <SystemForm
              key={`add-${addKey}`}
              system={null}
              existingIds={systems.map((s) => s.id)}
              profile={profile}
              readOnly={readOnly}
              hidden={!adding}
              label={t('ui.lab.add.title')}
              panelId="lab-tabpanel-add"
              onSaved={(saved, systemId) => {
                markDirty(ADD, false);
                onSaved(saved);
                onSelect(systemId);
                setAddKey((k) => k + 1); // the next "+ add" starts empty
              }}
              onTested={onTested}
              onDirty={onDirtyFor(ADD)}
            />
          )}
        </>
      )}
    </div>
  );
}
