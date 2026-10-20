import { useSyncExternalStore } from 'react';
import en from './en.json';
import es from './es.json';
import { readPreference, writePreference } from '../storage';

/** The workbench's languages. Server messages arrive already in the one sent as Accept-Language. */
export type Language = 'en' | 'es';
export const LANGUAGES: readonly Language[] = ['en', 'es'];

export type Args = Record<string, string | number>;

const CATALOGS: Record<Language, Record<string, string>> = { en, es };
const STORAGE_KEY = 'idmtest.lang';

let current: Language = 'en';
const listeners = new Set<() => void>();

function isLanguage(value: unknown): value is Language {
  return typeof value === 'string' && (LANGUAGES as readonly string[]).includes(value);
}

/**
 * The text of `key` in `lang`, with `{name}` filled in from `args`. With a numeric `count`, the
 * `<key>.one` or `<key>.other` text is used. An unknown key shows as itself.
 */
export function translate(lang: Language, key: string, args?: Args): string {
  const catalog = CATALOGS[lang];
  let text = catalog[key];
  if (args && typeof args.count === 'number') {
    text = catalog[`${key}.${args.count === 1 ? 'one' : 'other'}`] ?? text;
  }
  if (text === undefined) {
    return key;
  }
  return args ? text.replace(/\{(\w+)\}/g, (whole, name: string) => (name in args ? String(args[name]) : whole)) : text;
}

/** The text of `key` in the current language. */
export function t(key: string, args?: Args): string {
  return translate(current, key, args);
}

export function currentLanguage(): Language {
  return current;
}

/** The remembered choice, else the server's language (`idmtest ui --locale`), else English. */
export function initialLanguage(serverLocale: string | null | undefined): Language {
  const stored = readPreference(STORAGE_KEY);
  if (isLanguage(stored)) {
    return stored;
  }
  const server = serverLocale?.toLowerCase().split(/[-_]/)[0];
  return isLanguage(server) ? server : 'en';
}

/** Switches the page's language; `remember` keeps the choice for the next visit. */
export function setLanguage(lang: Language, remember = true): void {
  if (remember) {
    writePreference(STORAGE_KEY, lang);
  }
  document.documentElement.lang = lang;
  if (lang !== current) {
    current = lang;
    listeners.forEach((listener) => listener());
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** The current language for a component, which re-renders when it changes. */
export function useLanguage(): Language {
  return useSyncExternalStore(subscribe, currentLanguage);
}
