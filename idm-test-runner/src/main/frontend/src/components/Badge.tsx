import { t } from '../i18n';
import './Badge.css';

/**
 * ok ✓ green · warn ! amber-yellow · missing ✕ red (a setting to fill in) · finding ✕ amber
 * (the lab did not do what was expected) · error ✕ red (the scenario or setup needs fixing).
 */
export type BadgeStatus = 'ok' | 'warn' | 'missing' | 'finding' | 'error';

const SYMBOLS: Record<BadgeStatus, string> = {
  ok: '✓',
  warn: '!',
  missing: '✕',
  finding: '✕',
  error: '✕',
};

/** A small square with a symbol, never colour alone; read out by its label. */
export function Badge({ status, label }: { status: BadgeStatus; label?: string }) {
  return (
    <span className={`badge badge-${status}`} role="img" aria-label={label ?? t(`ui.badge.${status}`)}>
      {SYMBOLS[status]}
    </span>
  );
}
