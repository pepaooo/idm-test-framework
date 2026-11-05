import { useEffect, useId, useRef, useState } from 'react';
import { api, withWorkspace } from '../api/client';
import { Badge, type BadgeStatus } from '../components/Badge';
import { t, useLanguage } from '../i18n';
import { whenLabel } from './copyFinding';
import { PdfMenu } from './PdfMenu';
import type { HistoryEntry } from './types';
import './Run.css';

const RESULT_BADGE: Record<string, BadgeStatus> = { passed: 'ok', finding: 'finding', setup: 'error', stopped: 'warn' };

/**
 * The workspace's earlier runs, newest first (`GET /api/runs/history`): each with its report and its
 * PDFs. A run whose results cannot be read says so and offers neither. A dialog: the focus starts on
 * Close and Escape closes it.
 */
export function RunsView({ onClose }: { onClose: () => void }) {
  useLanguage();
  const [runs, setRuns] = useState<HistoryEntry[] | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const close = useRef<HTMLButtonElement>(null);
  const titleId = useId();

  useEffect(() => {
    close.current?.focus();
  }, []);

  useEffect(() => {
    let live = true;
    api
      .get<HistoryEntry[]>('/api/runs/history')
      .then((entries) => live && setRuns(entries))
      .catch((error: unknown) => live && setProblem(error instanceof Error ? error.message : String(error)));
    return () => {
      live = false;
    };
  }, []);

  const now = new Date();
  return (
    <div className="modal-backdrop">
      <div
        className="modal runs-view"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.stopPropagation();
            onClose();
          }
        }}
      >
        <h2 id={titleId}>{t('ui.runs.title')}</h2>
        {problem && (
          <p role="alert" className="notice">
            <Badge status="warn" /> {problem}
          </p>
        )}
        {runs !== null && runs.length === 0 && <p className="muted">{t('ui.runs.empty')}</p>}
        {runs !== null && runs.length > 0 && (
          <ul className="runs-list">
            {runs.map((run) => (
              <li key={run.runId} className="runs-row">
                <span className="runs-id">{run.runId}</span>
                <span className="muted">{whenLabel(run.at, now)}</span>
                <span className="muted">{run.profile ?? ''}</span>
                {run.result === null ? (
                  <span className="muted">{t('ui.runs.unreadable')}</span>
                ) : (
                  <>
                    <span>
                      <Badge status={RESULT_BADGE[run.result] ?? 'warn'} label={t(`ui.run.state.${run.result}`)} /> {t(`ui.run.state.${run.result}`)}
                    </span>
                    <a href={withWorkspace(`/reports/${encodeURIComponent(run.runId)}/report.html`)} target="_blank" rel="noopener noreferrer">
                      {t('ui.runs.openReport')}
                    </a>
                    <PdfMenu runId={run.runId} />
                  </>
                )}
              </li>
            ))}
          </ul>
        )}
        <div className="actions">
          <span className="spacer" />
          <button ref={close} type="button" className="secondary" onClick={onClose}>
            {t('ui.runs.close')}
          </button>
        </div>
      </div>
    </div>
  );
}
