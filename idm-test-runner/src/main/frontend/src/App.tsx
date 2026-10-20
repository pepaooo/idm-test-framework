import { useEffect, useState } from 'react';
import { api, ApiProblem, type Health, type OpenWorkspace, type ServerMessage } from './api/client';
import { Badge } from './components/Badge';
import { initialLanguage, LANGUAGES, setLanguage, t, useLanguage, type Language } from './i18n';
import { StartPage } from './start/StartPage';
import { baseName } from './start/format';
import { ThemeSwitch } from './theme/ThemeSwitch';
import './App.css';

/**
 * The workbench page: the start page while no workspace is open, else the workspace (a placeholder
 * until the main screen exists). A minimal router: which one shows is state, not a URL.
 */
export function App() {
  const lang = useLanguage();
  const [health, setHealth] = useState<Health | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [workspace, setWorkspace] = useState<OpenWorkspace | null>(null);
  const [warning, setWarning] = useState<ServerMessage | undefined>();

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const answer = await api.get<Health>('/api/health');
        if (!live) {
          return;
        }
        setLanguage(initialLanguage(answer.locale), false);
        if (answer.workspace) {
          try {
            const opened = await api.post<{ path: string; profiles: string[] }>('/api/workspaces/open', {
              path: answer.workspace,
            });
            if (live) {
              setWorkspace({ path: opened.path, name: baseName(opened.path), profiles: opened.profiles });
            }
          } catch (e) {
            // e.g. --workspace names a folder without scenarios/: say why, and offer the start page
            if (live) {
              setProblem(e instanceof ApiProblem ? e.message : String(e));
            }
          }
        }
        if (live) {
          setHealth(answer);
        }
      } catch (e) {
        if (live) {
          setProblem(e instanceof ApiProblem ? e.message : String(e));
        }
      }
    })();
    return () => {
      live = false;
    };
  }, []);

  return (
    <div className="app">
      <header className="top">
        <span className="logo">{t('ui.app.name')}</span>
        <span className="muted">{workspace ? workspace.name : t('ui.app.subtitle')}</span>
        <span className="spacer" />
        <select
          className="pill"
          aria-label={t('ui.language')}
          value={lang}
          onChange={(event) => setLanguage(event.target.value as Language)}
        >
          {LANGUAGES.map((code) => (
            <option key={code} value={code}>
              {t(`ui.language.${code}`)}
            </option>
          ))}
        </select>
        <ThemeSwitch />
      </header>
      {problem && (
        <p role="alert" className="problem banner">
          <Badge status="error" /> {problem}
        </p>
      )}
      {health && !workspace && (
        <StartPage
          git={health.git}
          onOpened={(opened, why) => {
            setWorkspace(opened);
            setWarning(why);
          }}
        />
      )}
      {workspace && <WorkspacePlaceholder workspace={workspace} warning={warning} />}
      {!health && !problem && <p className="muted loading">{t('ui.loading')}</p>}
      <footer className="foot">{health ? t('ui.footer', { version: health.version }) : ''}</footer>
    </div>
  );
}

/** Stands in for the main screen (file tree, editor, run panel), which comes next. */
function WorkspacePlaceholder({ workspace, warning }: { workspace: OpenWorkspace; warning?: ServerMessage }) {
  return (
    <main className="workspace">
      <h1>{t('ui.main.open', { name: workspace.name })}</h1>
      <p className="mono muted">{workspace.path}</p>
      <p>
        {workspace.profiles.length
          ? t('ui.main.profiles', { profiles: workspace.profiles.join(', ') })
          : t('ui.main.noProfiles')}
      </p>
      {warning && (
        <p role="status" className="notice">
          <Badge status="warn" /> {warning.message}
        </p>
      )}
    </main>
  );
}
