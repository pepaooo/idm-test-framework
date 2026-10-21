import { useEffect, useState } from 'react';
import { systemTheme } from '../../theme/theme';

function effective(): 'vs' | 'vs-dark' {
  const chosen = document.documentElement.dataset.theme;
  const theme = chosen === 'dark' || chosen === 'light' ? chosen : systemTheme();
  return theme === 'dark' ? 'vs-dark' : 'vs';
}

/** Monaco's theme for the page's light/dark mode, following the switch and the system. */
export function useMonacoTheme(): 'vs' | 'vs-dark' {
  const [theme, setTheme] = useState(effective);
  useEffect(() => {
    const update = () => setTheme(effective());
    const observer = new MutationObserver(update);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    const query = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-color-scheme: dark)') : null;
    query?.addEventListener('change', update);
    return () => {
      observer.disconnect();
      query?.removeEventListener('change', update);
    };
  }, []);
  return theme;
}
