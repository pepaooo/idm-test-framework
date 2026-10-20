import { fakeServer, type Answer } from './fakeServer';
import type { Problem, TreeNode } from '../workbench/types';

export const WORKSPACE = { path: '/home/ana/lab-tests', name: 'lab-tests', profiles: ['lab', 'uat'] };

function param(url: string, name: string): string {
  return new URL(url, 'http://127.0.0.1').searchParams.get(name) ?? '';
}

function kindOf(path: string): TreeNode['kind'] {
  if (path.toLowerCase().endsWith('secrets.env')) {
    return 'secrets';
  }
  if (path.startsWith('scenarios/') && /\.ya?ml$/.test(path)) {
    return 'scenario';
  }
  if (path.startsWith('config/')) {
    return 'config';
  }
  return 'other';
}

/** The tree of `paths` as the server sends it (folders first, then names, like WorkspaceFiles). */
export function treeOf(paths: string[], results: Record<string, TreeNode['lastResult']> = {}): TreeNode {
  const root: TreeNode = { name: 'lab-tests', path: '', dir: true, kind: null, lastResult: null, children: [] };
  for (const path of paths) {
    const parts = path.split('/');
    let node = root;
    parts.forEach((part, index) => {
      const sub = parts.slice(0, index + 1).join('/');
      const leaf = index === parts.length - 1;
      let child = node.children!.find((c) => c.name === part);
      if (!child) {
        child = leaf
          ? { name: part, path: sub, dir: false, kind: kindOf(sub), lastResult: results[sub] ?? null, children: null }
          : { name: part, path: sub, dir: true, kind: null, lastResult: null, children: [] };
        node.children!.push(child);
      }
      node = child;
    });
  }
  const sort = (node: TreeNode) => {
    node.children?.sort((a, b) => (a.dir === b.dir ? a.name.localeCompare(b.name) : a.dir ? -1 : 1));
    node.children?.forEach(sort);
  };
  sort(root);
  return root;
}

export interface WorkbenchServerOptions {
  files: Record<string, string>;
  validate?: (path: string, content: string) => Problem[] | Promise<Problem[]>;
  routes?: Record<string, Answer | ((request: { url: string; body: unknown }) => Answer | Promise<Answer>)>;
}

/**
 * A fake workbench server over an in-memory workspace: files with versions (v1, then v2, v3… on
 * each change), saves refused with 409 over another version, validation by a test function.
 */
export function workbenchServer({ files, validate, routes = {} }: WorkbenchServerOptions) {
  let counter = 1;
  const disk = new Map(Object.entries(files).map(([path, content]) => [path, { content, version: 'v1' }]));
  const server = fakeServer({
    'GET /api/files/tree': () => ({ body: treeOf([...disk.keys(), 'secrets.env']) }),
    'GET /api/files': (request) => {
      const path = param(request.url, 'path');
      if (path.endsWith('secrets.env')) {
        return { status: 403, body: { key: 'runner.ui.file.secrets', message: 'secrets.env is not opened' } };
      }
      const file = disk.get(path);
      return file
        ? { body: { ...file } }
        : { status: 404, body: { key: 'runner.ui.file.notFound', message: `not found: ${path}` } };
    },
    'PUT /api/files': (request) => {
      const path = param(request.url, 'path');
      const body = request.body as { content: string; version: string };
      const file = disk.get(path);
      if (file && file.version !== body.version) {
        return {
          status: 409,
          body: { key: 'runner.ui.file.changed', message: `${path} changed on disk`, currentVersion: file.version },
        };
      }
      counter += 1;
      const version = `v${counter}`;
      disk.set(path, { content: body.content, version });
      return { body: { version } };
    },
    'POST /api/validate': async (request) => {
      const body = request.body as { path: string; content: string };
      return { body: { problems: validate ? await validate(body.path, body.content) : [] } };
    },
    ...routes,
  });
  return {
    ...server,
    disk,
    /** Another program changed the file: new content, new version. */
    changeOnDisk(path: string, content: string) {
      counter += 1;
      disk.set(path, { content, version: `v${counter}` });
    },
    puts: () => server.sent.filter((r) => r.method === 'PUT'),
    validations: () => server.sent.filter((r) => r.url === '/api/validate'),
  };
}
