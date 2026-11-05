import { useEffect, useId, useRef, useState } from 'react';
import { withWorkspace } from '../api/client';
import { t, useLanguage } from '../i18n';
import './Run.css';

/**
 * "Download PDF ▾": a button that opens two plain links, Summary and Detailed, to the run's
 * on-demand PDF. They are links, not fetches, so the browser downloads the file with the session
 * cookie and the workspace in the address. Escape or a click elsewhere closes it.
 */
export function PdfMenu({ runId }: { runId: string }) {
  useLanguage();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLSpanElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const menuId = useId();

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const onDown = (event: MouseEvent) => {
      if (!root.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  const link = (kind: 'summary' | 'detailed', label: string) => (
    <a
      href={withWorkspace(`/reports/${encodeURIComponent(runId)}/report.pdf?kind=${kind}`)}
      download
      onClick={() => setOpen(false)}
    >
      {label}
    </a>
  );

  return (
    <span
      className="pdf-menu"
      ref={root}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && open) {
          event.stopPropagation();
          setOpen(false);
          button.current?.focus();
        }
      }}
    >
      <button
        ref={button}
        type="button"
        className="secondary pdf-menu-button"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen(!open)}
      >
        {t('ui.run.downloadPdf')} ▾
      </button>
      {open && (
        <span className="pdf-menu-links" id={menuId}>
          {link('summary', t('ui.run.pdfSummary'))}
          {link('detailed', t('ui.run.pdfDetailed'))}
        </span>
      )}
    </span>
  );
}
