import { useState } from 'react';
import { api, ApiProblem, withQuery } from '../api/client';
import { Badge, type BadgeStatus } from '../components/Badge';
import { t } from '../i18n';
import type { CommandInfo, LabSystem, LabView } from './types';

interface Props {
  system: LabSystem;
  profile: string;
  readOnly: boolean;
  hidden: boolean;
  panelId: string;
  /** Approve answered with the lab as it is now. */
  onSaved(view: LabView): void;
  /** The definition changed since it was shown: read the lab again. */
  onStale(): void;
}

const APPROVAL_BADGE: Record<CommandInfo['approval'], BadgeStatus> = {
  approved: 'ok',
  changed: 'missing',
  'not-approved': 'missing',
  'not-registered': 'missing',
};

/** An argument as a command line shows it: quoted when empty or with whitespace. */
function shownArg(arg: string) {
  return arg === '' || /\s/.test(arg) ? `"${arg}"` : arg;
}

/**
 * A registered command in the lab panel (ADR 0008): its definition exactly as the settings write it,
 * read-only (definitions are shared through Git), and whether this machine approved it. **Approve on
 * this machine** approves the definition shown here, by its hash: if it changed meanwhile, the server
 * refuses (409) and the panel reads it again, so what is approved is always what was on screen. The
 * workbench never approves on its own.
 */
export function CommandPanel({ system, profile, readOnly, hidden, panelId, onSaved, onStale }: Props) {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const command = system.command ?? null;
  const name = system.id.slice('command:'.length);
  const approval = command?.approval ?? 'not-registered';

  const approve = async () => {
    if (!command?.hash) return;
    setBusy(true);
    setProblem(null);
    try {
      onSaved(await api.post<LabView>(withQuery('/api/lab/commands/approve', { profile }), { name, hash: command.hash }));
    } catch (e) {
      setProblem(e instanceof ApiProblem ? e.message : String(e));
      if (e instanceof ApiProblem && e.status === 409) onStale();
    } finally {
      setBusy(false);
    }
  };

  const rows: [string, string][] = command && approval !== 'not-registered'
    ? [
        [t('ui.lab.command.executable'), command.executable ?? ''],
        [t('ui.lab.command.args'), (command.args ?? []).map(shownArg).join(' ') || '—'],
        ...Object.entries(command.params ?? {}).map(([param, pattern]): [string, string] => [
          t('ui.lab.command.param', { name: param }),
          pattern,
        ]),
        ...(command.env && command.env.length ? [[t('ui.lab.command.env'), command.env.join(', ')] as [string, string]] : []),
        [t('ui.lab.command.workingDir'), command.workingDir ?? t('ui.lab.command.workspaceDir')],
        [
          t('ui.lab.command.limits'),
          t('ui.lab.command.limitsValue', {
            timeout: command.timeoutSeconds ?? 0,
            output: command.maxOutputBytes ?? 0,
            exitCodes: (command.exitCodes ?? []).join(', '),
          }),
        ],
        ...(command.encoding ? [[t('ui.lab.command.encoding'), command.encoding] as [string, string]] : []),
      ]
    : [];

  return (
    <div role="tabpanel" id={panelId} aria-label={system.name} hidden={hidden} className="lab-form-panel lab-command">
      <div className="lab-identity">
        <span>{t('ui.lab.identifier')}</span> <code>{system.id}</code>
      </div>
      {system.usedByScenarios.length === 0 ? (
        <p className="muted lab-used">{t('ui.lab.unused')}</p>
      ) : (
        <p className="muted lab-used">{t('ui.lab.usedBy', { names: system.usedByScenarios.join(', ') })}</p>
      )}
      <p className="lab-command-state" role="status">
        <Badge status={APPROVAL_BADGE[approval]} /> {t(`ui.lab.command.state.${approval}`)}
      </p>
      {rows.length > 0 && (
        <dl className="lab-command-definition">
          {rows.map(([label, value]) => (
            <div key={label} className="lab-row">
              <dt>{label}</dt>
              <dd>
                <code>{value}</code>
              </dd>
            </div>
          ))}
        </dl>
      )}
      <p className="lab-where">{t('ui.lab.command.readOnly')}</p>
      {approval !== 'approved' && approval !== 'not-registered' && (
        <div className="lab-actions">
          <p className="lab-note">{t('ui.lab.command.approveHint')}</p>
          <span className="spacer" />
          <button type="button" className="primary" disabled={readOnly || busy || !command?.hash} onClick={() => void approve()}>
            {busy ? t('ui.lab.command.approving') : t('ui.lab.command.approve')}
          </button>
        </div>
      )}
      {problem && (
        <p role="alert" className="lab-error">
          <Badge status="error" /> {problem}
        </p>
      )}
    </div>
  );
}
