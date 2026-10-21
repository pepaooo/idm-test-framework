# Run Keeps Its Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While a run is going, the workbench refuses to open a different workspace, and the refused tab can stop that run and then open the workspace it asked for.

**Architecture:** The server makes "switch the session's workspace" and "start a run" mutually exclusive under one lock in `RunSessions`, and refuses an open of another workspace while a run is active with a 409 that names the running workspace. The start page turns that 409 into a notice with **Stop the run** (confirmed) and **Cancel**; after a stop it retries the open until the run has ended.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (`idm-test-runner`), React + TypeScript + Vitest (`idm-test-runner/src/main/frontend`).

**Spec:** ITF-62 (https://optima-cyber-dev.youtrack.cloud/issue/ITF-62). Small change; the design was agreed in chat on 2026-10-21 and is recorded in *Design* below. Background: `docs/specs/2026-10-19-local-workbench-design.md` (ITF-56), `docs/guides/workbench.md`.

## Design (agreed 2026-10-21)

- **Who it applies to:** a tab on the start page asking to open a workspace other than the one being run, by clicking a recent workspace, typing a path, or after creating one. Opening the workspace that is being run is always allowed; with no run going, everything is allowed.
- **Server:** refuses to switch, names the running workspace, never queues and never switches anyway.
- **Tab:** shows "A run is going in lab-tests. Wait for it to finish, or stop it." with **Stop the run** and **Cancel**.
  - **Stop the run** asks first, because Stop skips the scenario's cleanup: "Stop the run in lab-tests? Its cleanup will not run." It uses the existing Stop.
  - **Cancel** closes the notice.
- **Calls made by Claude, accepted:** after a successful Stop the tab opens the requested workspace by itself once the run has ended; creating a workspace during a run still creates the folder and only its open waits; `--workspace` at start-up is unaffected (no run exists then).

## Global Constraints

- New error key `ui.workspace.runGoing`, catalog key `runner.ui.workspace.runGoing`, en: `A run is going in {0}. Wait for it to finish, or stop it.`; es: `Hay una ejecución en curso en {0}. Espere a que termine o deténgala.` (`{0}` = the running workspace's folder name).
- Confirm text (front-end catalog `ui.start.runGoing.confirm`), en: `Stop the run in {name}? Its cleanup will not run.`; es: `¿Detener la ejecución en {name}? Su limpieza no se ejecutará.`
- Button labels: `ui.start.runGoing.stop` en `Stop the run` / es `Detener la ejecución`; `ui.start.runGoing.cancel` en `Cancel` / es `Cancelar`; while waiting `ui.start.runGoing.stopping` en `Stopping the run…` / es `Deteniendo la ejecución…`.
- The 409 body: `{key: "ui.workspace.runGoing", message, runningWorkspace: {path, name}}` — the same `ApiError.Body` shape plus the field.
- No secret value in any response or UI text (unchanged rule). Errors keep the `{key, message}` shape.
- Engine untouched; CLI (`idmtest run` / `validate` / `init`) unchanged.
- Commit trailer: a blank line, `ITF-62`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Build: `./mvnw -B verify` green and the YAML corpus 0 FAILED; front end through Maven or `npm test` in `idm-test-runner/src/main/frontend`.

## Review Focus

- A run started at the same moment another tab opens a different workspace: exactly one wins; never a run of X while the session shows Y — pinned in Task 1 (`testOpenAndStartRace`).
- The tab that gets the notice has no workspace (start page) or a different one; its Stop must still reach the run, which the workspace guard protects — pinned in Task 2 (`stops the run with the running workspace's header`).
- The run ends by itself while the notice is showing, or the Stop answers `ui.run.none`: the open must just go ahead — pinned in Task 2 (`opens once the run has ended`).
- Re-opening the workspace that is being run (a reload, or the second tab on the same workspace) must still work — pinned in Task 1 (`testSameWorkspaceOpens`).
- A run that never ends after Stop (stuck connector) must not leave the tab spinning forever — pinned in Task 2 (`gives up waiting after 30 s`).

---

### Task 1: Server — refuse switching while a run is going

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/RunSessions.java` (the `Run` record of state, `start`, new `switchWorkspace`, new exception)
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/api/WorkspacesController.java:125-140` (`open`)
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/api/RunsController.java` (passes the current workspace to `start`)
- Modify: runner catalogs `messages.properties` / `messages_es.properties` (`runner.ui.workspace.runGoing`)
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/api/ApiErrors.java` or `ApiError.java` only as needed to render the extra `runningWorkspace` field
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/ui/RunSessionsTest.java`, `idm-test-runner/src/test/java/com/idm/test/runner/ui/api/WorkspacesControllerTest.java`

**Interfaces:**
- Produces:
  - `RunSessions.Run` keeps `final Path workspace` (the real path the run was started for).
  - `public Path activeWorkspace()` — the running workspace, or null when idle.
  - `public void switchWorkspace(UiSession session, Path root)` — under the lock `start` also takes: when a run is active and `root` is not its workspace, throw `RunSessions.RunningElsewhereException(Path running)`; otherwise `session.open(root)`.
  - `start(Path workspace, String profile, List<String> scenarios, Locale locale, Supplier<Path> current)` — under the same lock, throws a new `RunSessions.SwitchedException` when `!workspace.equals(current.get())`; `RunsController` maps it to the existing 409 `ui.workspace.switched`. The old four-argument `start` is removed (update its callers and tests).
  - HTTP: `POST /api/workspaces/open` → 409 `{key:"ui.workspace.runGoing", message, runningWorkspace:{path, name}}` while a run of another workspace is active.

- [ ] **Step 1: Failing tests.**
  - `RunSessionsTest.testSwitchRefusedWhileRunning`: start a run on X with a runner that blocks on a latch; `switchWorkspace(session, Y)` throws `RunningElsewhereException` whose `running()` equals X; `session.workspace()` is still X.
  - `RunSessionsTest.testSameWorkspaceOpens`: during the same run `switchWorkspace(session, X)` succeeds.
  - `RunSessionsTest.testSwitchAfterRun`: after the latch is released and the run has finished, `switchWorkspace(session, Y)` succeeds.
  - `RunSessionsTest.testOpenAndStartRace`: 200 rounds; two threads race `start(X, …, session::workspace)` and `switchWorkspace(session, Y)` from a common start barrier; assert every round ends in one of exactly two states — (run started on X and session still X) or (start refused with switched and session Y) — never a run on X with session Y.
  - `WorkspacesControllerTest.testOpenRefusedWhileRunning`: over HTTP, with a run of X active (a blocking runner injected the way `RunsControllerTest` does), `POST /api/workspaces/open {path: Y}` → 409, JSON has exactly `key`, `message`, `runningWorkspace`; `key == "ui.workspace.runGoing"`; `runningWorkspace.name` is X's folder name; `message` contains that name; with `Accept-Language: es` the message is the Spanish text; then `/api/health` still reports workspace X.
- [ ] **Step 2:** run them → fail (`./mvnw -B test -pl idm-test-runner -am -Dtest='RunSessionsTest,WorkspacesControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`).
- [ ] **Step 3: Implement.** One private lock object in `RunSessions` guards both the CAS that installs a run in `start` and the check-and-open in `switchWorkspace`; compare real paths (`toRealPath()`, falling back to `normalize().toAbsolutePath()`). `WorkspacesController.open` calls `runs.switchWorkspace(session, workspace.root())` in place of `session.open(...)` and maps `RunningElsewhereException` to the 409; `remember(...)` (recent list) happens only after a successful switch.
- [ ] **Step 4:** the same command → pass; then `./mvnw -B verify` green, corpus 0 FAILED.
- [ ] **Step 5: Commit** — `fix: the workbench keeps its workspace while a run is going`.

### Task 2: Start page — the run-going notice with Stop, and the guide

**Files:**
- Create: `idm-test-runner/src/main/frontend/src/start/RunGoingNotice.tsx`
- Modify: `idm-test-runner/src/main/frontend/src/start/StartPage.tsx` (`openWorkspace` and the three callers: recent list, create, open by path)
- Modify: `idm-test-runner/src/main/frontend/src/api/client.ts` (a per-request workspace header override)
- Modify: `idm-test-runner/src/main/frontend/src/i18n/en.json`, `es.json`
- Modify: `docs/guides/workbench.md` (the start page section: one paragraph)
- Test: `idm-test-runner/src/main/frontend/src/start/StartPage.test.tsx` (or a new `RunGoingNotice.test.tsx`)

**Interfaces:**
- Consumes: Task 1's 409 body (`ApiProblem.key === 'ui.workspace.runGoing'`, `ApiProblem.data.runningWorkspace.{path,name}`); existing `POST /api/runs/current/stop` (202 / 409 `ui.run.none`), which sits behind the workspace guard.
- Produces: `api.post(path, body, { workspace?: string })` — when `workspace` is given it is sent as `X-Idmtest-Workspace` instead of the page's workspace (existing callers unchanged).

- [ ] **Step 1: Failing tests** (fake fetch, fake timers where waiting is involved):
  - `shows the run-going notice`: an open answered 409 `ui.workspace.runGoing` for `lab-tests` shows the server's message, **Stop the run** and **Cancel**, and no generic error banner.
  - `cancel closes it`: Cancel removes the notice and sends nothing.
  - `asks before stopping`: Stop the run calls `window.confirm` with `Stop the run in lab-tests? Its cleanup will not run.`; answering no sends no request.
  - `stops the run with the running workspace's header`: answering yes sends `POST /api/runs/current/stop` with `X-Idmtest-Workspace` = the encoded `runningWorkspace.path`, then shows `Stopping the run…`.
  - `opens once the run has ended`: after the stop, the open is retried every 500 ms; the first retry still 409 `runGoing`, the second succeeds → the workspace opens (`onOpened` called once). Same when the stop itself answers 409 `ui.run.none` (the run had already ended): the open is retried at once.
  - `gives up waiting after 30 s`: if every retry is still `runGoing`, after 30 s the notice comes back (Stop the run / Cancel) and retrying stops.
  - `create during a run`: create succeeds, its open gets `runGoing` → the same notice, and after Stop the created workspace opens.
  - Spanish: the button labels and confirm text in `es`.
- [ ] **Step 2:** run them → fail (vitest via the Maven-installed node, see `docs/reference/running-tests.md#the-workbench-front-end`).
- [ ] **Step 3: Implement.** `openWorkspace` catches `ApiProblem` with key `ui.workspace.runGoing` and hands it to `RunGoingNotice` (state in the start page, one notice at a time), passing a `retry` that repeats the same open. Exact copy from Global Constraints; `role="alert"` on the notice, buttons labelled; confirm through `window.confirm`.
- [ ] **Step 4:** tests pass; i18n key parity test passes; guide paragraph added under *The start page*: opening another workspace while a run is going shows the notice; Stop asks first and skips cleanup; the workspace then opens by itself; the workspace being run can always be opened. Then `./mvnw -B verify` green, corpus 0 FAILED.
- [ ] **Step 5: Commit** — `feat: the start page offers to stop a run that blocks opening another workspace`.
