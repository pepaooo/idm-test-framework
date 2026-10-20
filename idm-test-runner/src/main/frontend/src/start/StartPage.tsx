import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { api, ApiProblem, withQuery, type OpenWorkspace, type RecentWorkspace, type ServerMessage } from '../api/client';
import { Badge } from '../components/Badge';
import { t, useLanguage } from '../i18n';
import { FolderPicker } from './FolderPicker';
import { baseName, runStatus, runSummary, when } from './format';
import './StartPage.css';

export type Opened = (workspace: OpenWorkspace, warning?: ServerMessage) => void;

function message(e: unknown): string {
  return e instanceof ApiProblem ? e.message : String(e);
}

/** Opens the workspace in `path` on the server (files, lab and runs then act on it). */
async function openWorkspace(path: string, onOpened: Opened, warning?: ServerMessage) {
  const opened = await api.post<{ path: string; profiles: string[] }>('/api/workspaces/open', { path });
  onOpened({ path: opened.path, name: baseName(opened.path), profiles: opened.profiles }, warning);
}

/** Spec §4.2: recent workspaces, create one, open one. `git`: whether git is installed. */
export function StartPage({ git, onOpened }: { git: boolean; onOpened: Opened }) {
  const lang = useLanguage();
  return (
    <main className="start">
      <RecentList lang={lang} onOpened={onOpened} />
      <div>
        <CreateForm git={git} onOpened={onOpened} />
        <OpenForm onOpened={onOpened} />
      </div>
    </main>
  );
}

function RecentList({ lang, onOpened }: { lang: ReturnType<typeof useLanguage>; onOpened: Opened }) {
  const [recent, setRecent] = useState<RecentWorkspace[] | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const ids = useId();

  useEffect(() => {
    let live = true;
    api
      .get<RecentWorkspace[]>('/api/workspaces/recent')
      .then((list) => live && setRecent(list))
      .catch((e: unknown) => {
        if (live) {
          setRecent([]);
          setProblem(message(e));
        }
      });
    return () => {
      live = false;
    };
  }, []);

  const remove = async (path: string) => {
    try {
      await api.del(withQuery('/api/workspaces/recent', { path }));
      setRecent((list) => list?.filter((w) => w.path !== path) ?? null);
    } catch (e) {
      setProblem(message(e));
    }
  };

  const open = async (path: string) => {
    try {
      setProblem(null);
      await openWorkspace(path, onOpened);
    } catch (e) {
      setProblem(message(e));
    }
  };

  const now = new Date();
  return (
    <section aria-labelledby={`${ids}-title`}>
      <h2 id={`${ids}-title`} className="section-title">
        {t('ui.start.recent')}
      </h2>
      {problem && (
        <p role="alert" className="problem">
          <Badge status="error" /> {problem}
        </p>
      )}
      {recent === null && <p className="muted">{t('ui.loading')}</p>}
      {recent?.length === 0 && !problem && <p className="muted">{t('ui.start.recent.empty')}</p>}
      <ul className="recent">
        {recent?.map((w, index) => {
          const pathId = `${ids}-${index}-path`;
          const resultId = `${ids}-${index}-result`;
          const result = w.exists ? (
            <span className="result" id={resultId} data-testid="last-result">
              {w.lastRun ? (
                <>
                  <Badge status={runStatus(w.lastRun)} />
                  {' '}
                  {runSummary(w.lastRun, lang)}
                  <span className="muted">
                    {' · '}
                    {when(w.lastRun.at, now, lang)}
                    {w.lastRun.profile ? ` · ${w.lastRun.profile}` : ''}
                  </span>
                </>
              ) : (
                <span className="muted">{t('ui.start.neverRun')}</span>
              )}
            </span>
          ) : null;
          return (
            <li key={w.path} aria-label={w.name} className={w.exists ? 'ws' : 'ws missing'}>
              {w.exists ? (
                <button
                  type="button"
                  className="ws-open"
                  aria-label={t('ui.start.recent.open', { name: w.name })}
                  aria-describedby={`${pathId} ${resultId}`}
                  onClick={() => void open(w.path)}
                >
                  <span className="ws-name">{w.name}</span>
                  {result}
                  <span className="ws-path mono" id={pathId}>
                    {w.path}
                  </span>
                </button>
              ) : (
                <div className="ws-open">
                  <span className="ws-name">{w.name}</span>
                  <span className="result">
                    <span className="not-found">{t('ui.start.recent.notFound')}</span>
                    <br />
                    <button type="button" className="link" onClick={() => void remove(w.path)}>
                      {t('ui.start.recent.remove')}
                    </button>
                  </span>
                  <span className="ws-path mono">{w.path}</span>
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/** A text field with Browse… beside it. */
function FolderField({
  id,
  label,
  value,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const [browsing, setBrowsing] = useState(false);
  const browse = useRef<HTMLButtonElement>(null);
  const close = () => {
    setBrowsing(false);
    browse.current?.focus();
  };
  return (
    <>
      <label htmlFor={id}>{label}</label>
      <div className="folder-field">
        <input
          id={id}
          className="mono"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          autoComplete="off"
          spellCheck={false}
        />
        <button ref={browse} type="button" className="secondary" onClick={() => setBrowsing(true)}>
          {t('ui.start.browse')}
        </button>
      </div>
      {browsing && (
        <FolderPicker
          start={value}
          onCancel={close}
          onChoose={(path) => {
            onChange(path);
            close();
          }}
        />
      )}
    </>
  );
}

function CreateForm({ git, onOpened }: { git: boolean; onOpened: Opened }) {
  const ids = useId();
  const [name, setName] = useState('');
  const [parent, setParent] = useState('');
  const [includeExample, setIncludeExample] = useState(true);
  const [gitInit, setGitInit] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    try {
      const created = await api.post<{ path: string; warning: ServerMessage | null }>('/api/workspaces', {
        name,
        parent,
        includeExample,
        gitInit: git && gitInit,
      });
      await openWorkspace(created.path, onOpened, created.warning ?? undefined);
    } catch (e) {
      setProblem(message(e));
      setBusy(false);
    }
  };

  return (
    <form className="card" aria-labelledby={`${ids}-title`} onSubmit={(event) => void submit(event)}>
      <h3 id={`${ids}-title`}>{t('ui.start.create.title')}</h3>
      <p className="muted">{t('ui.start.create.hint')}</p>
      <label htmlFor={`${ids}-name`}>{t('ui.start.create.name')}</label>
      <input
        id={`${ids}-name`}
        value={name}
        onChange={(event) => setName(event.target.value)}
        required
        autoComplete="off"
        spellCheck={false}
      />
      <FolderField id={`${ids}-where`} label={t('ui.start.create.where')} value={parent} onChange={setParent} />
      <label className="check">
        <input type="checkbox" checked={includeExample} onChange={(event) => setIncludeExample(event.target.checked)} />
        {t('ui.start.create.includeExample')}
      </label>
      {git && (
        <label className="check">
          <input type="checkbox" checked={gitInit} onChange={(event) => setGitInit(event.target.checked)} />
          {t('ui.start.create.gitInit')}
        </label>
      )}
      {problem && (
        <p role="alert" className="problem">
          <Badge status="error" /> {problem}
        </p>
      )}
      <div className="actions">
        <button type="submit" className="primary" disabled={busy}>
          {t('ui.start.create.submit')}
        </button>
      </div>
    </form>
  );
}

function OpenForm({ onOpened }: { onOpened: Opened }) {
  const ids = useId();
  const [path, setPath] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    try {
      await openWorkspace(path.trim(), onOpened);
    } catch (e) {
      setProblem(message(e));
      setBusy(false);
    }
  };

  return (
    <form className="card" aria-labelledby={`${ids}-title`} onSubmit={(event) => void submit(event)}>
      <h3 id={`${ids}-title`}>{t('ui.start.open.title')}</h3>
      <p className="muted">{t('ui.start.open.hint')}</p>
      <FolderField id={`${ids}-folder`} label={t('ui.start.open.folder')} value={path} onChange={setPath} />
      {problem && (
        <p role="alert" className="problem">
          <Badge status="error" /> {problem}
        </p>
      )}
      <div className="actions">
        <button type="submit" className="secondary" disabled={busy || path.trim() === ''}>
          {t('ui.start.open.submit')}
        </button>
      </div>
    </form>
  );
}
