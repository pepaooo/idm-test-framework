import { lazy, Suspense, useEffect, useState } from 'react';
import { Badge } from '../components/Badge';
import { t } from '../i18n';
import { ProblemsPanel } from './ProblemsPanel';
import { isDirty, type Doc } from './store';
import { languageOf } from './types';
import { PanelSplitter } from './PanelSplitter';

/** Monaco is loaded on first use, in its own chunk (the start page stays light). */
const CodeEditor = lazy(() => import('./monaco/CodeEditor'));

interface Props {
  tabs: readonly Doc[];
  active: string | null;
  readOnly: boolean;
  onActivate(path: string): void;
  onClose(path: string): void;
  onChange(path: string, content: string): void;
  onSave(): void;
  onGoTo(path: string, line: number, column: number): void;
  problemsHeight?: number;
  problemsCollapsed?: boolean;
  onProblemsHeight?(value: number): void;
  onProblemsCollapsed?(value: boolean): void;
}

const nameOf = (path: string) => path.split('/').pop() ?? path;

/** The editor column: a tab per open file, the active file's editor, and Problems underneath. */
export function EditorPane({ tabs, active, readOnly, onActivate, onClose, onChange, onSave, onGoTo, problemsHeight = 180, problemsCollapsed = false, onProblemsHeight = () => undefined, onProblemsCollapsed = () => undefined }: Props) {
  const doc = tabs.find((tab) => tab.path === active) ?? null;
  const [narrow, setNarrow] = useState(() => window.matchMedia?.('(max-width: 760px)').matches ?? window.innerWidth <= 760);
  useEffect(() => {
    const media = window.matchMedia?.('(max-width: 760px)');
    const update = () => setNarrow(media?.matches ?? window.innerWidth <= 760);
    media?.addEventListener?.('change', update);
    window.addEventListener('resize', update);
    return () => {
      media?.removeEventListener?.('change', update);
      window.removeEventListener('resize', update);
    };
  }, []);
  return (
    <section className="editor-pane">
      {tabs.length > 0 && (
        <div className="tabs" role="tablist" aria-label={t('ui.editor.tabs')}>
          {tabs.map((tab) => (
            <div key={tab.path} className={`tab${tab.path === active ? ' tab-on' : ''}`}>
              <button
                type="button"
                role="tab"
                aria-selected={tab.path === active}
                title={tab.path}
                onClick={() => onActivate(tab.path)}
              >
                {nameOf(tab.path)}
                {isDirty(tab) && (
                  <span className="unsaved-dot" role="img" aria-label={t('ui.tree.unsaved')}>
                    {' ●'}
                  </span>
                )}
              </button>
              <button
                type="button"
                className="tab-close"
                aria-label={t('ui.editor.close', { name: nameOf(tab.path) })}
                onClick={() => onClose(tab.path)}
              >
                ×
              </button>
            </div>
          ))}
        </div>
      )}
      <div className="editor-body">
        {!doc && <p className="muted editor-empty">{t('ui.editor.empty')}</p>}
        {doc?.error && (
          <p role="alert" className="notice editor-notice">
            <Badge status="error" /> {doc.error}
          </p>
        )}
        {doc?.unkept && (
          <p role="status" className="notice editor-notice">
            <Badge status="warn" /> {t('ui.editor.unkept')}
          </p>
        )}
        {doc?.plainSecret && (
          <p role="status" className="notice editor-notice">
            <Badge status="warn" /> {t('ui.editor.plainSecret')}
          </p>
        )}
        {doc?.loaded && (
          <div className="editor-host">
            <Suspense fallback={<p className="muted editor-empty">{t('ui.editor.loading')}</p>}>
              <CodeEditor
                path={doc.path}
                value={doc.content}
                language={languageOf(doc.path)}
                readOnly={readOnly}
                label={t('ui.editor.label', { path: doc.path })}
                problems={doc.problems}
                marks={doc.marks}
                focus={doc.focus}
                onChange={(content) => onChange(doc.path, content)}
                onSave={onSave}
              />
            </Suspense>
          </div>
        )}
      </div>
      {!problemsCollapsed && !narrow && <div className="problems-divider"><PanelSplitter orientation="horizontal" label={t('ui.layout.resizeProblems')} value={problemsHeight} min={90} max={Math.max(90, window.innerHeight - 190)} onChange={onProblemsHeight} /></div>}
      <div className={`problems-slot${problemsCollapsed ? ' problems-slot-collapsed' : ''}`} style={{ height: problemsCollapsed ? 34 : problemsHeight }}>
        <ProblemsPanel
          path={doc?.path ?? null}
          problems={doc?.problems ?? []}
          collapsed={problemsCollapsed}
          onCollapsed={onProblemsCollapsed}
          onGoTo={(line, column) => doc && onGoTo(doc.path, line, column)}
        />
      </div>
    </section>
  );
}
