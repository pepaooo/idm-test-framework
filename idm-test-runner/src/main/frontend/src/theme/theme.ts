import { readPreference, writePreference } from '../storage';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'idmtest.theme';

/** The switch's remembered choice, or null to follow the operating system. */
export function storedTheme(): Theme | null {
  const value = readPreference(STORAGE_KEY);
  return value === 'light' || value === 'dark' ? value : null;
}

export function systemTheme(): Theme {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches
    ? 'dark'
    : 'light';
}

/** Applies `theme` to the page (tokens.css reads `data-theme`); null follows the system. */
export function applyTheme(theme: Theme | null): void {
  if (theme) {
    document.documentElement.dataset.theme = theme;
  } else {
    delete document.documentElement.dataset.theme;
  }
}

/** The switch's choice: applied and remembered. */
export function chooseTheme(theme: Theme): void {
  writePreference(STORAGE_KEY, theme);
  applyTheme(theme);
}
