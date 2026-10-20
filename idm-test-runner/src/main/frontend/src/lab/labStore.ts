import { api, ApiProblem, withQuery } from '../api/client';
import type { LabView } from './types';

export interface LabStoreState {
  profile: string | null;
  /** The lab as last read for `profile` (null before the first answer, or after a failed read). */
  view: LabView | null;
  /** Why the last read failed (the server's message), else null. */
  error: string | null;
  /** Whether the lab panel is open, and the tab it shows. */
  open: boolean;
  selected: string | null;
}

const RELOAD_DELAY = 250;

/**
 * The lab of the open workspace for the top bar's profile: read from `GET /api/lab`, read again
 * when the profile changes, after a save or a test, when the panel opens and when a settings or
 * scenario file changes on disk. Holds no secret: the server's view has none, and typed secrets
 * stay in the panel's inputs. Read through `useSyncExternalStore`.
 */
export class LabStore {
  private state: LabStoreState = { profile: null, view: null, error: null, open: false, selected: null };
  private listeners = new Set<() => void>();
  /** Answers older than the newest request are dropped (e.g. after a profile switch). */
  private sequence = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  readonly getState = () => this.state;

  private set(patch: Partial<LabStoreState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((listener) => listener());
  }

  /** Reads the lab for `profile` (a new profile drops the old view at once). */
  setProfile(profile: string | null): Promise<void> {
    if (profile !== this.state.profile) {
      this.set({ profile, view: null, error: null });
    }
    return this.reload();
  }

  async reload(): Promise<void> {
    const profile = this.state.profile;
    const ticket = ++this.sequence;
    if (profile === null) {
      this.set({ view: null, error: null });
      return;
    }
    try {
      const view = await api.get<LabView>(withQuery('/api/lab', { profile }));
      if (ticket === this.sequence) {
        this.set({ view, error: null });
      }
    } catch (e) {
      if (ticket === this.sequence) {
        this.set({ view: null, error: e instanceof ApiProblem ? e.message : String(e) });
      }
    }
  }

  /** The view a save answered with (`PUT /api/lab` answers as GET does). */
  accept(view: LabView) {
    this.sequence += 1;
    this.set({ view, error: null });
  }

  /** A file changed on disk: settings and scenarios decide what the lab needs, so read it again soon. */
  fileEvent(path: string) {
    const relevant =
      (path.startsWith('config/') && /\.ya?ml$/i.test(path)) || path === 'secrets.env' || path.startsWith('scenarios/');
    if (!relevant) {
      return;
    }
    if (this.timer) {
      clearTimeout(this.timer);
    }
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.reload();
    }, RELOAD_DELAY);
  }

  /** Opens the panel (on `systemId`, else the tab it showed last) and reads the lab again. */
  openPanel(systemId?: string) {
    this.set({ open: true, selected: systemId ?? this.state.selected });
    void this.reload();
  }

  closePanel() {
    this.set({ open: false });
  }

  select(systemId: string | null) {
    this.set({ selected: systemId });
  }

  /** Stops a pending reload (the workbench unmounts; under StrictMode it may mount again). */
  dispose() {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
