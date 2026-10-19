# Local Web Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `idmtest ui` opens a local browser workbench where a consultant creates or opens a workspace, edits scenarios, connects to their lab, runs, and reads findings — on the same files the CLI uses.

**Architecture:** Three layers, built bottom-up. (1) The engine learns three things the UI needs: a failure *kind* (finding vs setup), per-step progress with cancellation and a waiting history, and structured expected-vs-found checks; the HTML report and CLI show the kind. (2) `idm-test-runner` gains a reusable run service (shared by `idmtest run` and the UI) and a loopback-only Spring MVC server with a token, exposing a small JSON API plus Server-Sent Events. (3) A React + TypeScript single-page app (Vite, Monaco) served from the jar.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (`spring-boot-starter-webmvc`), SnakeYAML 2 (Boot-managed, comment-preserving edits), the existing engine/wiring/runner; React 18, TypeScript, Vite, `@monaco-editor/react` + `monaco-yaml`, Vitest + Testing Library; `frontend-maven-plugin`.

**Spec:** `docs/specs/2026-10-19-local-workbench-design.md` (ITF-56). Mockups: `.superpowers/brainstorm/27692-1792430292/content/` (local).

## Global Constraints

- Listens on `127.0.0.1` only. Every API/static request needs the session token: `?token=<random>` on first load, then an `HttpOnly; SameSite=Strict` cookie. Requests with another `Host` than `127.0.0.1:<port>`/`localhost:<port>`, or a foreign `Origin`, get 403.
- `idmtest ui [--workspace <dir>] [--port <n>] [--locale en|es]`; without `--port` a free port; opens the default browser; prints the URL and `Press Ctrl+C to stop`.
- No API response, SSE event, UI text, copied finding, report or log contains a secret value. Secrets appear only as `"set"` / `"not set"` / `"environment"`.
- Same workspace files, settings precedence, guards, driver loading, masking and `reports/<run-id>/` as `idmtest run` (ITF-55). Files the UI saves work unchanged with `idmtest run`.
- Failure kinds: **SETUP** (red) = detected before running, system unreachable (connection refused, timeout before an answer, TLS), credentials refused, malformed parameters, unexpected exceptions; **FINDING** (amber) = the system answered, not as expected (verify mismatch, action rejected/failed by the system, HTTP error from the IdM, SOAP fault). Cleanup failures are FINDING with "may be left in the lab". Rule source: `ErrorType` — `NETWORK_ERROR`, `TIMEOUT`, `AUTHENTICATION_ERROR`, `CONFIGURATION_ERROR` → SETUP; all others → FINDING.
- Save only on explicit Save. Saves carry the version read; a changed file on disk is never overwritten silently (HTTP 409).
- One run at a time per workbench. Stop is immediate: the current scenario's cleanup does not run; skipped cleanup entries are reported with their resolved values (masked).
- Theme graphite & indigo, light and dark (follows the OS; switch remembered). Tokens (dark / light): bg `#111216`/`#fafafc`, surface `#17181e`/`#ffffff`, raised `#1e2028`/`#f0f1f6`, line `#2a2d38`/`#e1e3ec`, text `#e1e3ea`/`#1c1e27`, muted `#9195a5`/`#5e6273`, accent `#7c8cff`/`#4f5fe8`, ok `#4cc38a`/`#1f9d63`, finding `#f2994a`/`#e07b25`, error `#f06a75`/`#d33b4a`. Status badges ✓ / ! / ✕ as small squares; no red-tinted backgrounds; colour always with a symbol or word.
- UI copy from the spec verbatim where quoted (e.g. "The lab did not do what this step expects", "Stopped — cleanup did not run", "Lab ready", "Lab: 2 of 3 ready"). English and Spanish (`ui.*` keys in a front-end catalog; server messages keep using the ITF-46 catalogs).
- The engine stays Spring-free (ADR 0003, `NoSpringDependencyTest`).
- End users need no Node; developers build the front end through Maven (`frontend-maven-plugin` downloads Node into `target/`).
- Commit trailer: a blank line, `ITF-56`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Java build: `./mvnw -B verify`; single module with `-pl <m> -am`. Front end: `npm test` / `npm run build` inside `idm-test-runner/src/main/frontend` (also run by Maven).

## Review Focus

- A workspace with Windows line endings, a BOM, or tabs in YAML still loads, edits and saves byte-for-byte except the edited lines — pinned in Task 8 (`testSaveKeepsLineEndings`) and Task 9 (`testCommentsAndLayoutKept`).
- Two saves of the same file from two tabs, or a save after `git pull` changed it: the second gets 409 and the UI offers Keep mine / Load from disk / Compare — pinned in Task 8 (`testStaleVersionRejected`) and Task 12 (`changedOnDisk.test.tsx`).
- A run started while another is running is refused with a clear message, not queued silently — pinned in Task 10 (`testSecondRunRefused`).
- Folder browsing on paths with spaces, non-ASCII names, symlinks and unreadable folders lists what it can and never escapes to a 500 — pinned in Task 6 (`testBrowseOddFolders`).
- A secret typed into the lab form never comes back in any later API response, SSE event, or the page's HTML — pinned in Task 9 (`testSecretNeverReturned`) and Task 10 (`testEventsMasked`).

---

## Phase 1 — Engine and reports

### Task 1: Failure kind (finding vs setup) on outcomes, results, report and CLI

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/spi/FailureKind.java`
- Modify: `spi/StepOutcome.java`; `result/StepResult.java`, `result/CleanupResult.java`; `ScenarioEngine.java`; the step classes under `steps/` that return failures from a connector result; `result/HtmlReportWriter.java`; `idm-test-runner/.../ConsoleProgress.java`; both i18n catalogs
- Test: `spi/FailureKindTest.java`, `ScenarioEngineTest.java`, step tests (`LdapStepsTest`, `DbStepsTest`, `RestStepsTest`, `HttpStepsTest`, `SoapStepsTest`), `HtmlReportWriterTest.java`, `JsonResultWriterTest.java`, runner `ConsoleProgressTest`

**Interfaces:**
- Produces: `public enum FailureKind { FINDING, SETUP; public static FailureKind of(ErrorType type) }`; `StepOutcome.kind()` (null on success); factories `StepOutcome.failure(Message, ErrorType)` / `failure(String, ErrorType)`; existing `failure(...)` without a type → `FINDING`; `finalFailure(...)` → `SETUP`. `StepResult.failureKind` and `CleanupResult.failureKind` (`@JsonInclude(NON_NULL)`, last component, String enum name).

- [ ] **Step 1: Failing tests.** `FailureKindTest`: `of(NETWORK_ERROR|TIMEOUT|AUTHENTICATION_ERROR|CONFIGURATION_ERROR) == SETUP`; every other `ErrorType` → `FINDING`. `ScenarioEngineTest`: a step returning `failure(msg)` gives `failureKind "FINDING"`; `finalFailure(msg)` → `"SETUP"`; a step that throws → `"SETUP"`; a failing cleanup entry → `"FINDING"`; a passing step has no `failureKind` in JSON (`JsonResultWriterTest`, layout test unchanged for passing results). Step tests: LDAP verify mismatch → FINDING; LDAP create "entry already exists" → FINDING; LDAP read against a closed port → SETUP; LDAP bind rejected → SETUP; REST 500 from WireMock → FINDING; REST connection refused → SETUP; REST 401 on the token endpoint → SETUP; SOAP fault → FINDING.
- [ ] **Step 2:** Run them → fail.
- [ ] **Step 3: Implement.** Steps pass the connector result's `ErrorType` when failing from one (`result.getErrorType().orElse(UNKNOWN_ERROR)`). Engine: thrown exception → SETUP; carry `kind` through wait suffixes and into results.
- [ ] **Step 4: Report and console.** `HtmlReportWriter`: a FINDING step gets the finding colour and the label `report.kind.finding` ("Finding"); SETUP gets `report.kind.setup` ("Setup problem"); scenario summary counts "findings" vs "setup problems". `ConsoleProgress.failed` appends `· finding` / `· setup problem` (catalog keys, en/es). Tests assert both labels in English and Spanish.
- [ ] **Step 5:** `./mvnw -B verify` green; corpus 0 FAILED. **Commit** — `feat: every failure is a finding or a setup problem`.

### Task 2: Run listener, cancellation and waiting history in the engine

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/spi/RunListener.java`, `spi/WaitObservation.java`
- Modify: `ScenarioEngine.java`, `result/StepResult.java`, `result/ScenarioResult.java`
- Test: `ScenarioEngineTest.java`, `JsonResultWriterTest.java`

**Interfaces:**
- Produces:
  - `public interface RunListener { default void stepStarted(int index, String type, String description) {} default void attempt(int index, int attempt, long elapsedMillis, String message) {} default void stepFinished(StepResult result) {} default void cleanupFinished(CleanupResult result) {} RunListener NONE = new RunListener() {}; }`
  - `public record WaitObservation(int attempt, long elapsedMillis, String message)`.
  - `ScenarioEngine.run(Scenario, String sourceName, RunListener listener, BooleanSupplier stopRequested)`; the existing `run(Scenario, String)` delegates with `NONE` and `() -> false`.
  - `StepResult.waitHistory` — `List<WaitObservation>`, only the attempts whose message differed from the previous one (first attempt always), capped at 50 entries, each message ≤ 300 chars; null when the step did not wait. `@JsonInclude(NON_NULL)`, after `failureKind`.
  - `ScenarioResult.stopped` (boolean, `NON_DEFAULT`) and `ScenarioResult.skippedCleanup` (`List<CleanupResult>` with status `SKIPPED` and resolved params, `NON_EMPTY`).
- [ ] **Step 1: Failing tests.** `ScenarioEngineTest`: listener receives `stepStarted`/`stepFinished` for each step in order and `attempt` for each try under `wait:`; `waitHistory` of a value that never changes has 1 entry and `attempts == N`; of a value that changes on try 3 has 2 entries; cap at 50; `stopRequested` turning true during step 2's wait → step 2 ends failed with `failureKind SETUP` and message key `engine.run.stopped` ("Stopped — cleanup did not run"), later steps not run, cleanup **not** run, `stopped == true`, `skippedCleanup` lists the cleanup entries with resolved params (captured `${createdDn}` resolved). JSON layout for an ordinary run unchanged.
- [ ] **Step 2:** fail. **Step 3:** implement; check `stopRequested` before each step, before each attempt, and before cleanup. **Step 4:** green; `NoSpringDependencyTest` green. **Commit** — `feat: run progress, stop and waiting history in the engine`.

### Task 3: Structured expected-vs-found checks on verify steps

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/spi/Check.java`
- Modify: `spi/StepOutcome.java`, `result/StepResult.java`, `steps/LdapVerifyStep.java`, `steps/DbVerifyStep.java`, `steps/RestVerifyStep.java`, `steps/HttpVerifyStep.java`, `ScenarioEngine.java`
- Test: the verify steps' tests

**Interfaces:**
- Produces: `public record Check(String name, String expected, String found, boolean matched)`; `StepOutcome.withChecks(List<Check>)` and `checks()`; `StepResult.checks` (`NON_EMPTY`, after `waitHistory`). Values are rendered text, masked by the same rule as evidence (a secret-looking `name` gives `found = "••••"`); `found` is `null` when absent.
- [ ] **Step 1: Failing tests.** `ldap.verify` with `expect: { sn: X, groupMembership: { contains: cn=Finance,ou=groups } }` against an entry with `sn: X` and no group → checks `[sn, "X", "X", true]`, `[groupMembership, "contains cn=Finance,ou=groups", null, false]`. `db.verify` row/column checks likewise; `rest.verify` status and body-path checks; `http.verify` one check per `check:` expression (`name` = the expression, `found` = `false`/error text). A `userPassword` expectation is still refused (ITF-39) — no check ever carries a secret value.
- [ ] **Step 2–4:** fail → implement (each verify step builds its checks from what it already compares) → green. **Commit** — `feat: verify steps report expected and found per check`.

## Phase 2 — Runner service and web server

### Task 4: A reusable run service behind `idmtest run`

**Files:**
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/run/RunService.java`, `run/RunRequest.java`, `run/RunEvents.java`, `run/RunOutcome.java`
- Modify: `RunCommand.java` (becomes a thin CLI adapter over `RunService`)
- Test: `run/RunServiceTest.java`; existing `RunCommandTest` unchanged and green

**Interfaces:**
- Produces:
  - `public record RunRequest(Path workspace, String profile, List<String> scenarios /* empty = all */, Locale locale, Map<String,String> environmentOverride /* test hook, null in production */)`.
  - `public interface RunEvents extends RunListener { default void runStarted(String runId, List<String> scenarios) {} default void scenarioStarted(String name) {} default void scenarioFinished(ScenarioResult result) {} default void runFinished(RunOutcome outcome) {} default void refused(Message reason) {} }` — every String/Message delivered already masked.
  - `public record RunOutcome(String runId, Path folder, int total, int passed, int findings, int setupProblems, boolean stopped, int exitCode)`.
  - `public final class RunService { public RunOutcome run(RunRequest request, RunEvents events, BooleanSupplier stopRequested) }` — same order as today's `RunCommand` (settings, secrets, Git warning, run folder + `RunLog`, Spring context, select, load/validate, drivers, guards, run, record), exceptions mapped to `refused`/exit codes as today.
- [ ] **Step 1: Failing test** `RunServiceTest`: against the `RunCommandTest` fixtures (embedded LDAP, H2 file DB, WireMock), a recording `RunEvents` sees `runStarted`, `scenarioStarted`/`stepStarted`/`stepFinished`/`scenarioFinished` per scenario and `runFinished` with counts; a guard refusal arrives as `refused` with no scenario events; stopping during a wait gives `stopped == true` and the skipped cleanup in the scenario result; no event text contains a `secrets.env` value.
- [ ] **Step 2–4:** fail → extract the logic from `RunCommand` without changing its output → `RunCommandTest` (all 24) and `RunServiceTest` green. **Commit** — `refactor: a run service shared by the CLI and the workbench`.

### Task 5: `idmtest ui` — loopback server, token, static app

**Files:**
- Modify: `idm-test-runner/pom.xml` (`spring-boot-starter-webmvc`), `IdmTest.java`, `CommandLine.java` (command `ui`, options `--port`), catalogs (`runner.ui.*`)
- Create: `ui/UiCommand.java`, `ui/UiServerConfig.java`, `ui/TokenFilter.java`, `ui/BrowserOpener.java`, `src/main/resources/static/index.html` (placeholder until Task 12)
- Test: `ui/UiServerTest.java`, `CommandLineTest`

**Interfaces:**
- Produces: `UiCommand.run(CommandLine, PrintStream out, PrintStream err)`; a running server whose base URL and token are available to tests via `UiCommand.start(...)` returning `UiServer(int port, String token, AutoCloseable stop)`. All later controllers live under `/api/**` and are covered by `TokenFilter`. The CLI commands must not start a web server.
- [ ] **Step 1: Failing tests** `UiServerTest`: server binds `127.0.0.1` only (a connect to the machine's non-loopback address fails); `GET /` without token → 403; with `?token=` → 200, sets `idmtest_token` cookie (`HttpOnly`, `SameSite=Strict`) and redirects to `/` without the token; cookie works afterwards; wrong token → 403; `Host: evil.example` → 403; `Origin: http://evil.example` on a POST → 403; `GET /api/health` → `{"version":"…"}`. `CommandLineTest`: `ui --port 0 --workspace /w` parses; `--port abc` → usage error.
- [ ] **Step 2–4:** fail → implement (Spring Boot with `server.address=127.0.0.1`, `server.port` from `--port` or 0; token = 32 random bytes hex; `BrowserOpener` uses `java.awt.Desktop` when supported, else prints only) → green. `idmtest validate`/`run` still start no web server (assert in `RunCommandTest` that no `server.port` is bound). **Commit** — `feat: idmtest ui serves a token-protected local workbench`.

### Task 6: Workspaces API — recent list, create, open, browse

**Files:**
- Create: `ui/api/WorkspacesController.java`, `ui/RecentWorkspaces.java`, `ui/FolderBrowser.java`
- Modify: `InitCommand.java` only if needed to expose a non-printing `create(Path, boolean includeExample)` (the CLI output stays identical)
- Test: `ui/api/WorkspacesControllerTest.java`, `ui/RecentWorkspacesTest.java`, `ui/FolderBrowserTest.java`

**Interfaces:**
- Produces (JSON): `GET /api/workspaces/recent` → `[{name, path, exists, lastRun: {runId, at, profile, passed, findings, setupProblems} | null}]`; `POST /api/workspaces` `{name, parent, includeExample, gitInit}` → `{path}`; `POST /api/workspaces/open` `{path}` → `{path, profiles:[…]}` (400 `ui.workspace.notWorkspace` when no `scenarios/`); `DELETE /api/workspaces/recent?path=`; `GET /api/folders?path=` → `{path, parent, entries:[{name, path, isWorkspace}]}`. Recent file: `<user.home>/.idmtest/recent.json` (most recent first, max 20). `gitInit` runs the installed `git init`; `GET /api/health` gains `"git": true|false`.
- [ ] **Step 1: Failing tests:** create writes the same files `idmtest init` writes (compare with `InitCommandTest`'s list) and adds to recent; `gitInit:true` creates `.git` (skip without git); open refuses a folder without `scenarios/`; recent survives a restart, drops nothing automatically but reports `exists:false`; lastRun read from the newest `reports/*/results.json`; `testBrowseOddFolders` — spaces, non-ASCII, a symlink loop, an unreadable folder → 200 with what is readable, never 500; `user.home` is redirected to a temp dir in tests.
- [ ] **Step 2–4:** fail → implement → green. **Commit** — `feat: workbench workspaces — recent, create, open, browse`.

### Task 7: Validation API, step-vocabulary schema and plain-text password lint

**Files:**
- Create: `ui/api/ValidationController.java`, `ui/StepSchema.java`, `ui/PlainSecretLint.java`
- Test: `ui/StepSchemaTest.java`, `ui/PlainSecretLintTest.java`, `ui/api/ValidationControllerTest.java`

**Interfaces:**
- Produces: `POST /api/validate` `{path, content}` → `{problems:[{severity:"error"|"warning", line, column, message, key}]}` — errors from `ScenarioLoader`/`ScenarioValidator` (line from the YAML node when known, else the step's line), warnings from `PlainSecretLint`; never writes the file. `GET /api/schema/scenario` → a JSON Schema (draft 2020-12) generated from `StepRegistry.forValidation()`: top-level keys, `fixtures`, `steps`/`cleanup` items as single-key maps whose keys are the step types and whose properties are each type's `acceptedParams()` (required = `requiredParams()`), plus `description`, `as`, `wait`. `PlainSecretLint.check(String yaml) -> List<Problem>` flags a literal value under a secret-looking key (engine `Secrets` rule) that is not a `${…}` reference, message key `ui.lint.plainSecret` ("a password is written in plain text — use ${IDM_TEST_…} and put the value in secrets.env").
- [ ] **Step 1: Failing tests:** schema lists all 14 step types and `ldap.verify` has `dn` required and `expect`, `wait`, `directory` accepted; validating `expct:` returns an error on its line with the validator's message; `userPassword: Secret123` → warning; `userPassword: ${IDM_TEST_PW}` → none; `config/application-lab.yml` with `password: hunter2` → warning; the response never echoes the literal value.
- [ ] **Step 2–4:** fail → implement → green. **Commit** — `feat: live validation, a step schema for the editor, and a plain-password warning`.

### Task 8: Files API — tree, read, save with version, add, change notifications

**Files:**
- Create: `ui/api/FilesController.java`, `ui/WorkspaceFiles.java`, `ui/FileWatcher.java`
- Test: `ui/WorkspaceFilesTest.java`, `ui/api/FilesControllerTest.java`

**Interfaces:**
- Produces: `GET /api/files/tree` → nested `{name, path, dir, kind:"scenario"|"config"|"secrets"|"other", lastResult:"passed"|"finding"|"setup"|null}`; `GET /api/files?path=` → `{content, version}` (`version` = SHA-256 of the bytes); `PUT /api/files?path=` `{content, version}` → `{version}`, **409** `{currentVersion}` when the file changed; `POST /api/files/add` (multipart `.yaml`/`.yml` → `scenarios/`, never overwrites: `name (2).yaml`); `GET /api/files/events` (SSE) → `{type:"changed"|"deleted"|"created", path}` from a `WatchService` on the workspace. `secrets.env` is never readable or writable here (403). Paths are workspace-relative and may not escape it (400). Save writes atomically (temp file + move) and keeps the content exactly as sent.
- [ ] **Step 1: Failing tests:** `testStaleVersionRejected` (save with an old version → 409, file unchanged); `testSaveKeepsLineEndings` (CRLF + BOM content round-trips byte-for-byte); escape attempts `../x`, absolute paths, symlink out of the workspace → 400; `secrets.env` → 403; add never overwrites; an external write produces a `changed` event within 2 s; tree marks the last result from the newest report.
- [ ] **Step 2–4:** fail → implement → green. **Commit** — `feat: workbench file API with versioned saves and change events`.

### Task 9: Lab settings API — read, save (comments kept), test connection

**Files:**
- Create: `ui/api/LabController.java`, `ui/lab/LabSettings.java`, `ui/lab/ProfileYamlEditor.java`, `ui/lab/SecretsEnvEditor.java`, `ui/lab/ConnectionTester.java`
- Test: `ui/lab/ProfileYamlEditorTest.java`, `ui/lab/SecretsEnvEditorTest.java`, `ui/lab/ConnectionTesterTest.java`, `ui/api/LabControllerTest.java`

**Interfaces:**
- Produces: `GET /api/lab?profile=` → `{systems:[{id, kind:"ldap"|"database"|"rest"|"soap", name, usedByScenarios, fields:[{key, label, value | null, secret, state:"set"|"not set"|"environment"|"application.yml"}], status:"ready"|"untested"|"missing", lastTest:{ok, message, millis} | null}]}` — systems = those the workspace's scenarios use (`ScenarioNeeds`) plus any already configured; secret fields never carry `value`. `PUT /api/lab?profile=` `{systemId, values:{key: value}, secrets:{key: value}}` writes plain values to `config/application-<p>.yml` via `ProfileYamlEditor` and secrets to `secrets.env` via `SecretsEnvEditor`, referencing them as `${IDM_TEST_…}` (naming as ITF-55's docs); `POST /api/lab/test` `{systemId, profile, values, secrets}` tests the form values without saving. Status: `missing` when a needed field is empty; `untested` until a test passes in this server's lifetime for the current values; `ready` after.
- `ProfileYamlEditor.set(Path file, String dottedKey, String value)` edits in place keeping every comment and blank line; uncomments a matching commented template line (`#   url: …` from `idmtest init`) when present; never touches `config/application.yml`.
- `ConnectionTester.test(...)` → `{ok, message, millis}`: LDAP bind; database open + `SELECT 1` (Oracle `SELECT 1 FROM DUAL`); REST: OAuth token fetch when `use-oauth`, else GET base URL (any HTTP status = reachable; 401/403 = credentials rejected); SOAP: GET WSDL (`<url>?wsdl`) with credentials. Placeholder values are refused first with the guards' messages. Messages masked; 10 s timeout.
- [ ] **Step 1: Failing tests:** `testCommentsAndLayoutKept` (edit `url` in the `idmtest init` template → only that line changes, uncommented, comments intact, CRLF kept if present); a value inherited from `application.yml` shows `state:"application.yml"` and saving writes an override to the profile file only; `testSecretNeverReturned` (PUT a password, then GET lab, GET files tree, GET health, the static index → the value appears nowhere); `SecretsEnvEditor` keeps comments/order and updates in place; env-provided values show `"environment"` and PUT for them → 400; tester against embedded LDAP (good bind → ok; wrong password → "credentials rejected"), H2, WireMock REST (200, 401), WireMock WSDL; unreachable port → not ok within the timeout.
- [ ] **Step 2–4:** fail → implement with SnakeYAML only for reading; edit lines textually (key path → line) so formatting is preserved → green. **Commit** — `feat: lab settings API with comment-preserving saves and connection tests`.

### Task 10: Runs API — start, live events, stop, history

**Files:**
- Create: `ui/api/RunsController.java`, `ui/RunSessions.java`
- Test: `ui/api/RunsControllerTest.java`

**Interfaces:**
- Consumes: `RunService`, `RunEvents`, `RunOutcome` (Task 4); `StepResult.failureKind/waitHistory/checks`, `ScenarioResult.stopped/skippedCleanup` (Tasks 1–3).
- Produces: `POST /api/runs` `{profile, scenarios:[…] | "all" | {folder}, onlyFindings:boolean}` → `{runId}`; **409** `ui.run.busy` when a run is active; `GET /api/runs/current/events` (SSE): `runStarted`, `scenarioStarted`, `stepStarted`, `attempt`, `stepFinished` (full masked `StepResult` JSON), `scenarioFinished`, `refused` `{message}`, `runFinished` (`RunOutcome` minus the folder path, plus `reportUrl`); `POST /api/runs/current/stop`; `GET /api/runs/history?scenario=` → last 10 `{runId, at, profile, result}` from `reports/`; `GET /reports/{runId}/report.html` serves that run's report. Language of the run = the UI's current language (request field `locale`).
- [ ] **Step 1: Failing tests:** `testSecondRunRefused`; `testEventsMasked` (a secret from `secrets.env` and one typed into the lab form appear in no event); event order for a 2-scenario run; stop during a wait → `stepFinished` with the stopped message, `scenarioFinished` with `skippedCleanup`, `runFinished.stopped`; history lists runs newest first; report served only for existing run ids (path traversal → 404).
- [ ] **Step 2–4:** fail → implement (one `RunSessions` slot, a background thread, SSE emitters) → green. **Commit** — `feat: workbench runs with live events, stop and history`.

## Phase 3 — Front end

### Task 11: Front-end project, theme, i18n, API client and start page

**Files:**
- Create: `idm-test-runner/src/main/frontend/` (Vite React-TS app: `package.json`, `vite.config.ts`, `src/main.tsx`, `src/api/client.ts`, `src/theme/tokens.css`, `src/theme/ThemeSwitch.tsx`, `src/i18n/{en,es}.json`, `src/i18n/index.ts`, `src/start/StartPage.tsx`, `src/start/FolderPicker.tsx`, `src/components/Badge.tsx`)
- Modify: `idm-test-runner/pom.xml` (`frontend-maven-plugin`: install Node into `target/`, `npm ci`, `npm test`, `npm run build` → `target/classes/static`); remove the Task 5 placeholder
- Test: `src/start/StartPage.test.tsx`, `src/components/Badge.test.tsx`, `src/i18n/i18n.test.ts`; `UiServerTest` asserts `/` serves the built `index.html`

**Interfaces:**
- Produces: `api` client (`get/post/put/del`, `events(path, onEvent)` over `EventSource`) sending the cookie (same origin); `<Badge status="ok"|"warn"|"missing"|"finding"|"error" />` (✓ / ! / ✕ squares; finding amber, error red); `t(key, args)`; theme follows `prefers-color-scheme`, switch stored in `localStorage` (`idmtest.theme`).
- [ ] **Step 1: Failing tests:** `StartPage` renders recent workspaces with last result text ("✕ 2 findings · today 14:32 · lab"), greys a missing folder with "Remove from list", submits create with name/where/includeExample/gitInit (gitInit hidden when `/api/health.git` is false), opens a typed path, shows the server's not-a-workspace message; language switch changes visible text to Spanish; `i18n.test` — en and es have the same keys; `Badge` renders the right symbol and an accessible label per status.
- [ ] **Step 2–4:** fail → implement (tokens exactly from Global Constraints) → `npm test` green and `./mvnw -B verify -pl idm-test-runner -am` builds the app into the jar. **Commit** — `feat: workbench front end — theme, languages and start page`.

### Task 12: Main screen — layout, file tree, editor, Problems, saving

**Files:**
- Create: `src/workbench/Workbench.tsx`, `TopBar.tsx`, `FileTree.tsx`, `EditorPane.tsx`, `ProblemsPanel.tsx`, `drafts.ts`, `tabLock.ts`, `ChangedOnDiskDialog.tsx`
- Test: `FileTree.test.tsx`, `EditorPane.test.tsx`, `drafts.test.ts`, `tabLock.test.ts`, `changedOnDisk.test.tsx`

**Interfaces:**
- Consumes: Tasks 7–8 APIs. Produces: `openFile(path)`, `saveActive(): Promise<void>`, `markLines(path, fromLine, toLine)` for Task 14.
- Behaviour: layout per mockup B (top bar · tree · editor + Problems · right run panel slot). Monaco with `monaco-yaml` configured from `/api/schema/scenario`. Problems from `/api/validate` 400 ms after typing stops (errors red, warnings amber). ⌘S/Ctrl+S saves; ● on unsaved tabs and files; drafts in IndexedDB keyed by workspace+path, restored and marked unsaved on reload, cleared after save, **not stored** for a file whose last validation has a `ui.lint.plainSecret` warning (a notice says so); `secrets.env` shows a lock and cannot open; a second tab on the same workspace (BroadcastChannel) opens read-only with "This workspace is open in another tab" and a "Take over" button; a `changed` file event or a 409 on save opens Keep mine / Load from disk / Compare (Monaco diff). "Add files" and dropping `.yaml` files on the tree call `POST /api/files/add`.
- [ ] **Step 1: Failing tests** for each behaviour above (`drafts.test` uses fake-indexeddb; `tabLock.test` with two BroadcastChannel peers; `changedOnDisk.test` drives a 409 and a `changed` event).
- [ ] **Step 2–4:** fail → implement → green. **Commit** — `feat: workbench editor with live problems, safe saving and drafts`.

### Task 13: Lab chip and lab panel

**Files:**
- Create: `src/lab/LabChip.tsx`, `src/lab/LabPanel.tsx`, `src/lab/SystemForm.tsx`
- Test: `LabChip.test.tsx`, `LabPanel.test.tsx`

**Interfaces:**
- Consumes: Task 9 API. Produces: `labState()` (`ready|untested|missing` + the missing system id) used by Task 14 to block Run.
- Behaviour: chip text "Lab ready" / "Lab: 2 of 3 ready" / "Lab: HR database needs a password" with ✓ / ! / ✕; panel with one tab per system (badge per tab), "+ add", fields per kind, secret fields as dots with "Change", fields from the environment read-only with "from the environment", inherited ones marked "from application.yml"; **Test connection** shows "✓ Connected · bind OK · 112 ms" or the reason; Save writes and refreshes status; a footer line says where values are saved (`config/application-<p>.yml`, `secrets.env`).
- [ ] **Step 1: Failing tests:** chip text and badge for each state; a secret field never renders its value after save (the DOM has no such text); env fields not editable; test-connection result shown; the profile picker switches profile and reloads the lab.
- [ ] **Step 2–4:** fail → implement → green. **Commit** — `feat: lab chip and connection panel`.

### Task 14: Run controls, run panel and finding panel

**Files:**
- Create: `src/run/RunButton.tsx`, `src/run/RunPanel.tsx`, `src/run/FindingPanel.tsx`, `src/run/copyFinding.ts`
- Test: `RunButton.test.tsx`, `RunPanel.test.tsx`, `FindingPanel.test.tsx`, `copyFinding.test.ts`

**Interfaces:**
- Consumes: Task 10 events and history; Task 12 `saveActive`, `markLines`, `openFile`; Task 13 `labState`.
- Behaviour:
  - **Run** (⌘↵) runs the active scenario, reading **Save & Run** when unsaved; the menu: Run folder, Run all (⇧⌘↵), Run only the ones that found something. Run is blocked with the lab message when `labState` is `missing`; a 409 busy shows `ui.run.busy`.
  - Run panel: progress "Running all · 3 of 5", per-scenario state (passed / finding / setup problem / running "step 2 of 4…" / waiting), ■ Stop; after the run "5 scenarios: 3 passed, 2 findings · Open full report"; clicking a scenario shows its steps; the file tree badges update.
  - Finding panel for a FINDING step: headline "The lab did not do what this step expects" + "The scenario ran as written. <system> never reached the expected state within the wait."; expected-vs-found table from `checks` (matched rows shown too); waiting row from `waitHistory` ("30 tries every 2 s over 60 s — the value never changed" / "changed on try 3"); evidence (existing evidence rendering); recent runs from history with the "used to pass" hint when the last pass was with the same file version; actions **↻ Rerun as is** (primary), **Copy finding**, **Open full report**, **Edit scenario** (calls `markLines` + focus).
  - A SETUP step shows a red "Setup problem" panel with the message and a link to the lab panel when it is a connection/credentials problem.
  - Stopped run: "Stopped — cleanup did not run" with the skipped cleanup entries and their values.
- `copyFinding(result, run) -> string` produces the plain-text block from the spec §4.8 (workspace/scenario, profile, run id, system URL without credentials, step, expected vs found, waiting summary, history, report path).
- [ ] **Step 1: Failing tests** for each behaviour above, driving a fake event stream; `copyFinding` exact text for the spec's example; no secret in the DOM after a run whose events would contain one if unmasked (events come masked; assert the UI never decodes or shows `secrets.env`).
- [ ] **Step 2–4:** fail → implement → green. **Commit** — `feat: run controls, live run panel and finding panel`.

### Task 15: Docs and an end-to-end check

**Files:**
- Create: `docs/guides/workbench.md`
- Modify: `docs/guides/runner.md` (link), `README.md` (one paragraph + screenshot reference text, no image binary), `docs/architecture/capabilities.md` (Workbench row), ADR 0007 (Part 2 implemented), `docs/reference/running-tests.md` (front-end build/test commands)
- Test: `ui/WorkbenchSmokeTest.java` (Java, against a started `idmtest ui`): create a workspace through the API, save a scenario, save lab settings for an embedded LDAP, test the connection, start a run, collect SSE events to `runFinished`, fetch the report — all with the token; assert no secret anywhere in the collected responses.
- [ ] **Step 1:** write the smoke test; run → green.
- [ ] **Step 2:** the guide: start (`idmtest ui`, URL, Ctrl+C), start page, main screen, lab panel (where values are saved; passwords never shown), editing and saving (drafts, changed on disk, second tab, plain-password warning), running (one, folder, all, stop), findings vs setup problems (amber vs red with the rule), copy finding, reports, light/dark, language. Every command a fenced `bash` block.
- [ ] **Step 3:** full `./mvnw -B verify` green, corpus 0 FAILED; `./mvnw -Pdist package -pl idm-test-runner -am -DskipTests` builds a ZIP whose `idmtest ui` serves the app (manual check recorded in the report). **Commit** — `docs: the workbench guide and capability updates`.
