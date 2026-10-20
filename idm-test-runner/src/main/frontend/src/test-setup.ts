import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { installBrowserFakes, resetBrowserFakes } from './test/browserFakes';

installBrowserFakes();

afterEach(async () => {
  cleanup();
  // an unmounted workbench still writes its pending drafts: let that land before the fresh IndexedDB
  await new Promise((resolve) => setTimeout(resolve, 50));
  resetBrowserFakes();
  delete document.documentElement.dataset.theme;
  try {
    window.localStorage.clear();
  } catch {
    // no storage in this environment
  }
});
