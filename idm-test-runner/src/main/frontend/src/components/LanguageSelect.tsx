import { LANGUAGES, setLanguage, t, useLanguage, type Language } from '../i18n';

/** English / Español, remembered. */
export function LanguageSelect() {
  const lang = useLanguage();
  return (
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
  );
}
