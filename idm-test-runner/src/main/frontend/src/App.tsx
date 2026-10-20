import { useEffect, useState } from 'react';
import { api, ApiProblem, type Health, type OpenWorkspace, type ServerMessage } from './api/client';
import { Badge } from './components/Badge';
import { LanguageSelect } from './components/LanguageSelect';
import { initialLanguage, setLanguage, t, useLanguage } from './i18n';
import { StartPage } from './start/StartPage';
import { baseName } from './start/format';
import { ThemeSwitch } from './theme/ThemeSwitch';
import { Workbench } from './workbench/Workbench';
import './App.css';

/**
 * The workbench page: the start page while no workspace is open, else the main screen (which has
 * its own top bar). A minimal router: which one shows is state, not a URL.
 */
export function App() {
  useLanguage(); // re-render on a language switch
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

  if (workspace) {
    return <Workbench workspace={workspace} warning={warning} />;
  }

  return (
    <div className="app">
      <header className="top">
        <span className="logo">{t('ui.app.name')}</span>
        <span className="muted">{t('ui.app.subtitle')}</span>
        <span className="spacer" />
        <LanguageSelect />
        <ThemeSwitch />
      </header>
      {problem && (
        <p role="alert" className="problem banner">
          <Badge status="error" /> {problem}
        </p>
      )}
      {health && (
        <StartPage
          git={health.git}
          onOpened={(opened, why) => {
            setWorkspace(opened);
            setWarning(why);
          }}
        />
      )}
      {!health && !problem && <p className="muted loading">{t('ui.loading')}</p>}
      <footer className="foot">{health ? t('ui.footer', { version: health.version }) : ''}</footer>
    </div>
  );
}
