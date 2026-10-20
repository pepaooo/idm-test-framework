import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

afterEach(() => {
  cleanup();
  delete document.documentElement.dataset.theme;
  try {
    window.localStorage.clear();
  } catch {
    // no storage in this environment
  }
});
