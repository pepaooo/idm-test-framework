import { emitNamedEvent, eventStreams } from './browserFakes';
import type { Answer } from './fakeServer';
import { labRoutes, directory } from './labServer';
import { treeOf, WORKSPACE, workbenchServer } from './workbenchServer';
import type { LabView } from '../lab/types';
import type { HistoryEntry, ScenarioResult, StepResult } from '../run/types';
import type { TreeNode } from '../workbench/types';

export const RUN_EVENTS = '/api/runs/current/events';
export const RUN_ID = '20261019-143205-lab';

/** Five scenarios, as in mockup run-all.html; each has the number of steps its name ends with. */
export const FILES: Record<string, string> = {
  'scenarios/users/create-user.yaml': 'name: Create\nsteps:\n  - ldap.create:\n      dn: cn=u123,ou=users\n  - ldap.verify:\n      dn: cn=u123,ou=users\n      expect: { groupMembership: { contains: cn=Finance,ou=groups } }\n      wait: { timeout: 60s, poll: 2s }\ncleanup:\n  - ldap.delete: { dn: cn=u123,ou=users }\n',
  'scenarios/users/disable-user.yaml': 'name: Disable\nsteps:\n  - ldap.update: { dn: a }\n',
  'scenarios/roles-grant.yaml':
    'name: Roles\nsteps:\n  - ldap.create: { dn: a }\n  - roles.grant: { user: a, roles: [r] }\n  - ldap.verify: { dn: a }\n  - roles.revoke: { user: a, roles: [r] }\n',
  'scenarios/resources-grant.yaml': 'name: Resources\nsteps:\n  - resources.grant: { user: a, resources: [x] }\n',
  'scenarios/hr-sync.yaml': 'name: HR\nsteps:\n  - db.verify: { database: hr, query: select 1 }\n',
};

export interface RunServerOptions {
  files?: Record<string, string>;
  results?: Record<string, TreeNode['lastResult']>;
  history?: HistoryEntry[];
  lab?: LabView;
  routes?: Record<string, Answer | ((request: { url: string; body: unknown }) => Answer | Promise<Answer>)>;
}

/** A workbench server with a lab, a tree with last results, and the runs API (202 for a start). */
export function runServer({ files = FILES, results = {}, history = [], lab, routes = {} }: RunServerOptions = {}) {
  const tree = { results };
  const server = workbenchServer({
    files,
    routes: {
      ...labRoutes({ views: { lab: lab ?? { systems: [directory('ready')] }, uat: lab ?? { systems: [directory('ready')] } } }),
      'GET /api/files/tree': () => ({ body: treeOf([...Object.keys(files), 'secrets.env'], tree.results) }),
      'POST /api/runs': { status: 202, body: { runId: RUN_ID } },
      'POST /api/runs/current/stop': { status: 202, body: { runId: RUN_ID } },
      'GET /api/runs/history': { body: history },
      ...routes,
    },
  });
  return {
    ...server,
    tree,
    runStarts: () => server.sent.filter((r) => r.method === 'POST' && r.url === '/api/runs'),
    treeReads: () => server.sent.filter((r) => r.url === '/api/files/tree').length,
  };
}

/** Sends a run event to the open run streams. */
export function emit(name: string, data: unknown) {
  emitNamedEvent(RUN_EVENTS, name, data);
}

export function openRunStreams() {
  return eventStreams(RUN_EVENTS);
}

export function step(index: number, type: string, extra: Partial<StepResult> = {}): StepResult {
  return {
    index,
    type,
    resolvedParams: { dn: 'cn=u123,ou=users' },
    success: true,
    durationMillis: 400,
    message: null,
    attempts: null,
    waitedMillis: null,
    description: null,
    ...extra,
  };
}

/** The finding of mockup finding-panel.html: groupMembership never arrived. */
export function findingStep(extra: Partial<StepResult> = {}): StepResult {
  return step(1, 'ldap.verify', {
    success: false,
    durationMillis: 60200,
    resolvedParams: { dn: 'cn=u123,ou=users', wait: { timeout: '60s', poll: '2s' } },
    message: 'groupMembership: expected contains cn=Finance,ou=groups, found nothing',
    attempts: 30,
    waitedMillis: 60200,
    failureKind: 'FINDING',
    waitHistory: [{ attempt: 1, elapsedMillis: 15, message: 'groupMembership absent' }],
    checks: [
      { name: 'groupMembership', expected: 'contains cn=Finance,ou=groups', found: null, matched: false },
      { name: 'loginDisabled', expected: 'FALSE', found: 'FALSE', matched: true },
    ],
    evidence: {
      kind: 'ldap',
      directory: null,
      dn: 'cn=u123,ou=users',
      operation: 'read',
      attributes: { cn: 'u123', loginDisabled: 'FALSE', userPassword: '***' },
      present: true,
    },
    ...extra,
  });
}

export function scenario(sourceName: string, steps: StepResult[], extra: Partial<ScenarioResult> = {}): ScenarioResult {
  return {
    name: sourceName,
    sourceName,
    success: steps.every((s) => s.success),
    durationMillis: steps.reduce((sum, s) => sum + s.durationMillis, 0) + 100,
    steps,
    cleanup: [],
    failureDetail: null,
    description: null,
    ...extra,
  };
}

/** Runs one whole scenario through the events: started, its steps, finished. */
export function playScenario(result: ScenarioResult, failureLine: string | null = null) {
  emit('scenarioStarted', { name: result.sourceName });
  for (const s of result.steps) {
    emit('stepStarted', { index: s.index, type: s.type, description: s.description });
    emit('stepFinished', s);
  }
  emit('scenarioFinished', { result, failureLine });
}

export { WORKSPACE };
