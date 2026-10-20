import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { initialLanguage, setLanguage } from './i18n';
import { applyTheme, storedTheme } from './theme/theme';
import './theme/tokens.css';

// Before the first paint: the remembered theme and language (the server's language follows from /api/health).
applyTheme(storedTheme());
setLanguage(initialLanguage(null), false);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
