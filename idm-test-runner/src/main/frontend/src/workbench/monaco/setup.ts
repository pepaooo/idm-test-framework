import { loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor';
import { configureMonacoYaml } from 'monaco-yaml';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker.js?worker';
import YamlWorker from 'monaco-yaml/yaml.worker.js?worker';
import { api } from '../../api/client';

/**
 * Monaco from the app's own bundle — no CDN: @monaco-editor/react is pointed at the imported
 * monaco-editor, and the editor and YAML web workers are built by Vite, so the page works offline.
 * This module is only reached through a dynamic import (the editor chunk), keeping the start page light.
 * monaco-yaml gets the step-vocabulary schema from `/api/schema/scenario` for `scenarios/**`.
 *
 * monaco-editor stays on 0.54.x, the line monaco-yaml 5.5 is built against: its entry adapts
 * `editor.createWebWorker` to the options monaco-yaml passes; 0.57 no longer does, and the YAML
 * worker then never starts (no schema completion, hover or folding).
 */
self.MonacoEnvironment = {
  getWorker(_workerId: string, label: string) {
    return label === 'yaml' ? new YamlWorker() : new EditorWorker();
  },
};

loader.config({ monaco });

const yaml = configureMonacoYaml(monaco, {
  enableSchemaRequest: false,
  hover: true,
  completion: true,
  validate: true,
  schemas: [],
});

let schema: Promise<void> | null = null;

/** Loads the scenario schema once; editing works without it (no autocomplete) if it fails. */
export function loadScenarioSchema(): Promise<void> {
  if (!schema) {
    schema = api
      .get<Record<string, unknown>>('/api/schema/scenario')
      .then((json) => {
        yaml.update({
          schemas: [{ uri: 'idmtest://schema/scenario.json', fileMatch: ['scenarios/**/*.y*ml'], schema: json }],
        });
      })
      .catch(() => {
        schema = null;
      });
  }
  return schema;
}

/** The model URI of a workspace file: file:///scenarios/x.yaml, which the schema's glob matches. */
export function modelPath(path: string): string {
  return `file:///${path}`;
}

export { monaco };
