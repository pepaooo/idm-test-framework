import { afterEach, describe, expect, it } from 'vitest';
import en from './en.json';
import es from './es.json';
import { currentLanguage, initialLanguage, setLanguage, t, translate } from './index';

describe('i18n', () => {
  afterEach(() => setLanguage('en', false));

  it('English and Spanish have the same keys', () => {
    expect(Object.keys(es).sort()).toEqual(Object.keys(en).sort());
  });

  it('every key is a ui.* key with a non-empty text', () => {
    for (const catalog of [en, es] as Record<string, string>[]) {
      for (const [key, text] of Object.entries(catalog)) {
        expect(key).toMatch(/^ui\./);
        expect(text.trim()).not.toBe('');
      }
    }
  });

  it('fills in named arguments', () => {
    expect(translate('en', 'ui.footer', { version: '1.2.3' })).toBe('idmtest 1.2.3 · listening on this computer only');
    expect(translate('es', 'ui.footer', { version: '1.2.3' })).toBe('idmtest 1.2.3 · escuchando solo en este equipo');
  });

  it('picks one or other by count', () => {
    expect(translate('en', 'ui.result.findings', { count: 1 })).toBe('1 finding');
    expect(translate('en', 'ui.result.findings', { count: 2 })).toBe('2 findings');
    expect(translate('es', 'ui.result.findings', { count: 2 })).toBe('2 hallazgos');
  });

  it('shows the key of a text that does not exist', () => {
    expect(translate('en', 'ui.no.such.key')).toBe('ui.no.such.key');
  });

  it('t follows the chosen language, which is remembered', () => {
    setLanguage('es');
    expect(currentLanguage()).toBe('es');
    expect(t('ui.start.recent')).toBe('Espacios de trabajo recientes');
    expect(window.localStorage.getItem('idmtest.lang')).toBe('es');
    expect(document.documentElement.lang).toBe('es');
    expect(initialLanguage('en')).toBe('es');
  });

  it("without a remembered choice, the server's language is used", () => {
    expect(initialLanguage('es')).toBe('es');
    expect(initialLanguage('fr')).toBe('en');
    expect(initialLanguage(undefined)).toBe('en');
  });
});
