import { useEffect, useRef, useState } from 'react';
import { api, ApiProblem } from '../api/client';
import { Badge } from '../components/Badge';
import { t } from '../i18n';

/** The server's refusal to open another workspace while a run of `running` is going. */
export interface RunGoing {
  message: string;
  running: { path: string; name: string };
}

const RUN_GOING = 'ui.workspace.runGoing';
/** Stop's answers that mean the run is over already: none is going, or another tab opened another workspace since. */
const RUN_OVER = new Set(['ui.run.none', 'ui.workspace.switched']);
/** How often the open is tried again after a stop, and for how long. */
const RETRY_EVERY_MS = 500;
const WAIT_AT_MOST_MS = 30_000;

/** `e` as a run-going refusal, or null when it is something else. */
export function runGoing(e: unknown): RunGoing | null {
  if (!(e instanceof ApiProblem) || e.status !== 409 || e.key !== RUN_GOING) {
    return null;
  }
  const running = (e.data as { runningWorkspace?: { path?: unknown; name?: unknown } } | undefined)?.runningWorkspace;
  if (typeof running?.path !== 'string' || typeof running.name !== 'string') {
    return null;
  }
  return { message: e.message, running: { path: running.path, name: running.name } };
}

function text(e: unknown): string {
  return e instanceof ApiProblem ? e.message : String(e);
}

/**
 * "A run is going in lab-tests…" with Stop the run (asked first: Stop skips the cleanup) and Cancel.
 * After a stop, `retry` (the same open again) is tried every 500 ms until it works, for at most 30 s;
 * then the notice comes back. `retry` gets `isCurrent`: false once this notice is gone (replaced or
 * closed), so a late answer does not open its workspace. `onProblem` hears any other error, `onCancel` closes the notice.
 */
export function RunGoingNotice({
  notice,
  retry,
  onCancel,
  onProblem,
}: {
  notice: RunGoing;
  retry: (isCurrent: () => boolean) => Promise<void>;
  onCancel: () => void;
  onProblem: (message: string) => void;
}) {
  const [stopping, setStopping] = useState(false);
  const alive = useRef(true);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      if (timer.current !== null) {
        clearTimeout(timer.current);
      }
    };
  }, []);

  const waitForTheEnd = (deadline: number, delay: number) => {
    const attempt = async () => {
      timer.current = null;
      try {
        await retry(() => alive.current);
      } catch (e) {
        if (!alive.current) {
          return;
        }
        if (runGoing(e) === null) {
          setStopping(false);
          onProblem(text(e));
        } else if (Date.now() >= deadline) {
          setStopping(false);
        } else {
          timer.current = setTimeout(() => void attempt(), RETRY_EVERY_MS);
        }
      }
    };
    if (delay === 0) {
      void attempt();
    } else {
      timer.current = setTimeout(() => void attempt(), delay);
    }
  };

  const stop = async () => {
    if (!window.confirm(t('ui.start.runGoing.confirm', { name: notice.running.name }))) {
      return;
    }
    setStopping(true);
    const deadline = Date.now() + WAIT_AT_MOST_MS;
    try {
      await api.post('/api/runs/current/stop', {}, { workspace: notice.running.path });
    } catch (e) {
      if (!alive.current) {
        return;
      }
      if (e instanceof ApiProblem && RUN_OVER.has(e.key)) {
        waitForTheEnd(deadline, 0); // it had already ended
      } else {
        setStopping(false);
        onProblem(text(e));
      }
      return;
    }
    if (alive.current) {
      waitForTheEnd(deadline, RETRY_EVERY_MS);
    }
  };

  return (
    <div role="alert" className="problem notice run-going">
      <p>
        <Badge status="error" /> {notice.message}
      </p>
      {stopping ? (
        <p className="muted">{t('ui.start.runGoing.stopping')}</p>
      ) : (
        <div className="actions">
          <button type="button" className="secondary" onClick={onCancel}>
            {t('ui.start.runGoing.cancel')}
          </button>
          <button type="button" className="primary" onClick={() => void stop()}>
            {t('ui.start.runGoing.stop')}
          </button>
        </div>
      )}
    </div>
  );
}
