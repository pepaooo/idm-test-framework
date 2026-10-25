import { Badge } from '../components/Badge';
import { t } from '../i18n';
import { isValidatable, type Problem } from './types';

interface Props {
  path: string | null;
  problems: readonly Problem[];
  onGoTo(line: number, column: number): void;
  collapsed?: boolean;
  onCollapsed?(value: boolean): void;
}

/** The validator's results for the active file: errors red ✕, warnings amber !, each with a line link. */
export function ProblemsPanel({ path, problems, onGoTo, collapsed = false, onCollapsed = () => undefined }: Props) {
  return (
    <section className="problems" aria-labelledby="problems-title">
      <div className="pane-head">
        <span id="problems-title">{t('ui.problems.title')}</span>
        {problems.length > 0 && <span className="count">{problems.length}</span>}
        <button type="button" className="pane-icon" aria-label={t(collapsed ? 'ui.layout.expandProblems' : 'ui.layout.collapseProblems')} onClick={() => onCollapsed(!collapsed)}>{collapsed ? '▴' : '▾'}</button>
      </div>
      {collapsed ? null : path && !isValidatable(path) ? (
        <p className="muted problems-empty">{t('ui.problems.notChecked')}</p>
      ) : problems.length === 0 ? (
        <p className="muted problems-empty">{path ? t('ui.problems.none') : ''}</p>
      ) : (
        <ul className="problems-list">
          {problems.map((problem, index) => (
            <li key={`${problem.line}:${problem.column}:${index}`} className={`problem-${problem.severity}`}>
              <Badge
                status={problem.severity === 'error' ? 'error' : 'warn'}
                label={t(problem.severity === 'error' ? 'ui.problems.error' : 'ui.problems.warning')}
              />
              <span className="problem-text">{problem.message}</span>
              <button type="button" className="link" onClick={() => onGoTo(problem.line, problem.column)}>
                {t('ui.problems.line', { line: problem.line })}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
