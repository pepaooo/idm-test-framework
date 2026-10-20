/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The built app goes where Spring Boot serves static files from: classpath:/static/ of the runner jar.
// Monaco and its web workers are bundled (no CDN), in chunks loaded only when a file is opened.
export default defineConfig({
  plugins: [react()],
  base: '/',
  worker: {
    format: 'es',
  },
  build: {
    outDir: '../../../target/classes/static',
    emptyOutDir: true,
    sourcemap: false,
    chunkSizeWarningLimit: 6000,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
    css: false,
    // Times on the start page are shown in the machine's zone: the tests pin it.
    env: { TZ: 'UTC' },
  },
});
