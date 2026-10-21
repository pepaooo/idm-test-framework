/**
 * The browser's localStorage, for this viewer's preferences only (theme, language): never secrets,
 * tokens or file content. Storage can be missing or throw (private windows, blocked site data), so
 * every access is guarded and the page works without it.
 */
export function readPreference(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function writePreference(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // not remembered: the choice still applies to this page
  }
}
