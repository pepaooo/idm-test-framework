import { useEffect, useState } from 'react';
import { t } from '../i18n';
import { chooseTheme, storedTheme, systemTheme, type Theme } from './theme';

/** Light/dark switch: shows the current mode; follows the system until it is used once. */
export function ThemeSwitch() {
  const [chosen, setChosen] = useState<Theme | null>(storedTheme);
  const [system, setSystem] = useState<Theme>(systemTheme);

  useEffect(() => {
    if (chosen || typeof window.matchMedia !== 'function') {
      return undefined;
    }
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const changed = () => setSystem(query.matches ? 'dark' : 'light');
    query.addEventListener('change', changed);
    return () => query.removeEventListener('change', changed);
  }, [chosen]);

  const theme = chosen ?? system;
  const other: Theme = theme === 'dark' ? 'light' : 'dark';
  return (
    <button
      type="button"
      className="pill"
      aria-label={t(other === 'dark' ? 'ui.theme.toDark' : 'ui.theme.toLight')}
      onClick={() => {
        chooseTheme(other);
        setChosen(other);
      }}
    >
      <span aria-hidden="true">◐ </span>
      {t(theme === 'dark' ? 'ui.theme.dark' : 'ui.theme.light')}
    </button>
  );
}
