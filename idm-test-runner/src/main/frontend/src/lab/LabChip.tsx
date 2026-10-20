import { useCallback, useRef, useSyncExternalStore } from 'react';
import { Badge, type BadgeStatus } from '../components/Badge';
import { t, useLanguage } from '../i18n';
import { useWorkbench } from '../workbench/context';
import { LabPanel } from './LabPanel';
import type { LabStore } from './labStore';
import './Lab.css';

const BADGE: Record<'ready' | 'untested' | 'missing', BadgeStatus> = { ready: 'ok', untested: 'warn', missing: 'missing' };

/**
 * The lab chip in the top bar (spec §4.5): "Lab ready" ✓, "Lab: 2 of 3 ready" !, or
 * "Lab: HR database needs a password" ✕. Clicking it opens the lab panel below it — on the missing
 * system's tab when one is missing.
 */
export function LabChip({ store }: { store: LabStore }) {
  useLanguage();
  const lab = useSyncExternalStore(store.subscribe, store.getState);
  const { labState, profile, readOnly } = useWorkbench();
  const chip = useRef<HTMLButtonElement>(null);
  const panelDirty = useRef(false);
  const onDirtyChange = useCallback((dirty: boolean) => {
    panelDirty.current = dirty;
    store.setUnsaved(dirty);
  }, [store]);
  const state = labState();
  const known = lab.view !== null;

  const close = () => {
    panelDirty.current = false;
    store.closePanel();
    chip.current?.focus();
  };
  const toggle = () => {
    if (!lab.open) {
      store.openPanel(state.missingSystemId);
    } else if (!panelDirty.current || window.confirm(t('ui.lab.confirmClose'))) {
      close();
    }
  };

  return (
    <div className="lab-anchor">
      <button
        ref={chip}
        type="button"
        className={`pill lab-chip lab-chip-${known ? state.state : 'unknown'}${lab.open ? ' open' : ''}`}
        aria-label={state.message}
        aria-expanded={lab.open}
        aria-controls={lab.open ? 'lab-panel' : undefined}
        aria-haspopup="dialog"
        disabled={profile === null}
        onClick={toggle}
      >
        {known && <Badge status={BADGE[state.state]} />}
        {!known && lab.error !== null && <Badge status="error" />}
        <span>{state.message}</span>
        <span aria-hidden="true">{lab.open ? ' ▴' : ' ▾'}</span>
      </button>
      {lab.open && profile !== null && (
        <LabPanel
          key={profile}
          view={lab.view}
          error={lab.error}
          profile={profile}
          readOnly={readOnly}
          selected={lab.selected}
          onSelect={(id) => store.select(id)}
          onClose={close}
          onSaved={(view) => store.accept(view)}
          onTested={() => void store.reload()}
          onDirtyChange={onDirtyChange}
        />
      )}
    </div>
  );
}
