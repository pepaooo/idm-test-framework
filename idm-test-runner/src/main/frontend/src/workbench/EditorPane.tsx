import { lazy, Suspense } from 'react';
import { Badge } from '../components/Badge';
import { t } from '../i18n';
import { ProblemsPanel } from './ProblemsPanel';
import { isDirty, type Doc } from './store';
import { languageOf } from './types';

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
}

const nameOf = (path: string) => path.split('/').pop() ?? path;

/** The editor column: a tab per open file, the active file's editor, and Problems underneath. */
export function EditorPane({ tabs, active, readOnly, onActivate, onClose, onChange, onSave, onGoTo }: Props) {
  const doc = tabs.find((tab) => tab.path === active) ?? null;
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
      <ProblemsPanel
        path={doc?.path ?? null}
        problems={doc?.problems ?? []}
        onGoTo={(line, column) => doc && onGoTo(doc.path, line, column)}
      />
    </section>
  );
}
