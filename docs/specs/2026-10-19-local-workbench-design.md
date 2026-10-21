# The local web workbench — design

**Ticket:** ITF-56 (epic). **ADR:** [0007](../architecture/decisions/0007-end-user-runner-and-local-workbench.md), Part 2.
**Builds on:** ITF-55, the `idmtest` runner and workspace ([spec](2026-10-15-end-user-runner-design.md),
[guide](../guides/runner.md)). **Decided with:** jangulo, 2026-10-19. Mockups from the session:
`.superpowers/brainstorm/27692-1792430292/content/` (local, not committed).

## 1. Intent

An IdM consultant who does not write Java opens one window in their browser and does the whole job
there: create or open a workspace, write a scenario, connect to their real lab, run, and read what the
lab did. No terminal, no Maven, no IDE. The workbench is the browser face of the `idmtest` runner: it
works on the same workspace folder, through the same code, so everything it saves is immediately usable
by `idmtest run`, CI and Git.

**The core of the framework is finding out whether the IdM provisioned what it should.** A failed step
is usually not a mistake in the scenario: it is a finding — the IdM drivers did not provision the
attribute, did it too late, or did something else. The workbench must present a failure as a fact about
the lab, help the consultant tell "never happened" from "too slow" from "the scenario is wrong", and
make it easy to rerun and to hand the finding to whoever owns the drivers.

**The first five minutes:** create a workspace, edit a scenario, run it against the real lab (they
already have one). Connecting to the lab is part of those five minutes.

## 2. Goals, non-goals, anti-goals

**Goals**
- Edit, validate, run and read results for a workspace in one screen.
- Connect to the lab from a form; test each connection before running.
- Present step failures as findings, with expected vs found, the waiting history, evidence and the
  scenario's recent runs.
- Same files as the CLI: no separate store, no import/export step.

**Non-goals (first version)** — decided by jangulo:
- No Git features (ITF-57, Part 3a).
- No multi-user sharing or server deployment (ITF-61, Part 3b). One user, one machine.
- No form-based ("visual") scenario builder; the YAML editor is the editor.

**Anti-goals — failure even if it works** (jangulo):
- **Losing YAML edits**: through a refresh, a crash, a second tab, or by silently overwriting a file
  that changed on disk.
- **Showing passwords** anywhere the workbench generates: connection form, run panel, finding panel,
  copied finding, API responses, reports, logs. Text the user typed into their own files is shown as
  written in the editor (masking it would break editing) — see §4.4 for how plain-text passwords are
  flagged and kept out of drafts. (Scope decided by jangulo, 2026-10-19.)

## 3. Constraints

- Runs only on the user's machine: listens on the loopback address; every request carries a per-session
  token (in the URL it opens, then a cookie). Refuses other hosts and other origins.
- Same workspace layout and rules as ITF-55 (`scenarios/`, `config/application-<p>.yml`, `secrets.env`,
  `drivers/`, `reports/<run-id>/`), same settings precedence, same guards before anything is contacted,
  same masking (ITF-23 and ITF-55's `SecretMask`), same driver loading.
- Ships inside the `idmtest` program (jar and per-OS ZIPs); started with `idmtest ui`. End users need no
  Node; developers do at build time.
- English and Spanish UI (ITF-46 catalogs). A run uses the workbench's chosen language (as `--locale`
  does), so the screen and the report match. *(My call.)*
- The engine stays Spring-free (ADR 0003).

## 4. What was decided, and how it should behave

### 4.1 Starting

- `idmtest ui [--workspace <dir>] [--port <n>] [--locale en|es]` starts the workbench and opens the
  default browser at `http://127.0.0.1:<port>/?token=<random>`. Without `--port`, a free port.
  The console prints the URL (in case the browser does not open) and "Press Ctrl+C to stop".
- Without `--workspace`: the **start page**. With it: straight into that workspace.

### 4.2 Start page (decided)

- **Recent workspaces**: name, folder, last result (e.g. "✕ 2 findings · today 14:32 · lab",
  "✓ 12 passed · yesterday · uat", "never run"). A folder that no longer exists is shown greyed with
  "Remove from list".
- **Create a workspace**: name, location (with **Browse…**), "Include the example scenario",
  "Start a Git repository here". Runs the same code as `idmtest init` (ITF-55 `InitCommand`); the
  result is an ordinary workspace folder. "Start a Git repository" runs the installed `git init` in
  the new folder and is hidden when `git` is not installed; `InitCommand` (the CLI) is unchanged.
  *(My call.)*
- **Open a workspace**: any folder containing `scenarios/`.
- Top bar: language (English / Español) and the light/dark switch. Footer: version and "listening on
  this computer only".
- **Browse…** is a folder picker served by the workbench (the local program lists folders on disk; a
  browser page cannot).
- The recent list is a small file of paths in the user's settings folder (e.g.
  `~/.idmtest/recent.json`); it never stores scenario content or secrets.

### 4.3 Main screen (decided: option B)

- **Top bar**: workspace name; **profile picker** (from `config/application-*.yml`); **lab chip**;
  unsaved indicator; **Save** (⌘S / Ctrl+S); **Run** split button.
- **Left — file tree** of the workspace. Scenario files show their last result badge; unsaved files
  show ●; `secrets.env` shows a lock and is never opened in the editor. "Add files" (copies `.yaml` into
  `scenarios/`) and drag-and-drop onto the tree do the same.
- **Middle — editor** (tabs per open file) with the **Problems** panel underneath.
- **Right — run panel**: the current or last run, step by step, then the finding panel.

### 4.4 Editing and saving (decided)

- **Save only when the user presses Save.** No autosave to disk.
- **Drafts**: unsaved edits are kept in the browser (per workspace and file) so a refresh or crash does
  not lose them; on reopening, the draft is restored and marked unsaved.
- **Changed on disk**: if a file changes on disk while open (another editor, `git pull`, the CLI), the
  workbench tells the user and offers "Keep mine" / "Load from disk" / "Compare"; it never overwrites
  silently. A Save checks the file has not changed since it was loaded; if it has, the same choice is
  offered.
- **A second tab** on the same workspace shows a banner: "This workspace is open in another tab" and
  opens read-only until the user takes over (prevents two tabs saving over each other).
- **Plain-text passwords in the user's own files** (decided): the editor shows files as written. The
  Problems panel flags a value under a secret-looking key (`password`, `userPassword`, `secret`,
  `token`, … — the engine's `Secrets` rule) written as a literal in a scenario or a `config/*.yml`
  file, as a warning: "a password is written in plain text — use `${IDM_TEST_…}` and put the value in
  secrets.env". A file with such a flagged literal is never stored as a browser draft (the user is told
  unsaved edits to it are not kept across a refresh). `config/*.yml` files can be opened in the editor;
  `secrets.env` cannot.
  *Decided during implementation (controller ruling, ITF-56 Task 7):* the quoted advice is right only
  for `config/*.yml`, whose `${…}` reads environment variables and secrets.env. A scenario's `${…}`
  reaches only its fixtures and bindings, so in a scenario the warning reads "a password is written in
  plain text — declare a fixture and reference it with `${…}` instead". Both keep the problem key
  `ui.lint.plainSecret`.
- **Problems panel** shows `ScenarioValidator` results for the open file as the user types (debounced),
  in red, with line links. The editor offers YAML autocomplete, hover help and inline errors from a
  JSON Schema of the step vocabulary (step types and their parameters, generated from the registered
  `StepType`s so it cannot drift).

### 4.5 Lab connection (decided)

- **Lab chip** in the top bar shows overall readiness, e.g. "Lab ready", "Lab: 2 of 3 ready",
  "Lab: HR database needs a password". Clicking opens the **lab panel**.
- **Lab panel**: one tab per system the workspace's scenarios use (directories, databases, REST
  targets, SOAP services), "+ add" for another. Each tab is a form for that system's settings:
  URL, base DN, bind DN, user, etc.; **password fields show dots only** — never the value again once
  saved — with a "Change" action.
- **Save** writes plain settings to `config/application-<profile>.yml` and secrets to `secrets.env`
  (named like `IDM_TEST_LDAP_PASSWORD`, referenced as `${…}` from the YAML), the files `idmtest run`
  reads. Values the user set through environment variables are shown as "from the environment" and
  not editable. *(My calls:)* it edits the profile file in place and keeps its comments and layout,
  uncommenting the matching line from `idmtest init`'s template when there is one; it writes overrides
  only to the profile file, never to the shared `config/application.yml`, and shows a value inherited
  from that file as "from application.yml"; an open editor tab on the file is treated as "changed on
  disk" (§4.4).
- **Test connection** per system (what it does is my call): LDAP binds with the bind DN; a database
  opens a connection (and a validation query); a REST target fetches its OAuth token when it uses
  OAuth, otherwise sends a GET to its base URL (any HTTP answer = reachable; 401/403 = credentials
  rejected); a SOAP service fetches its WSDL with its credentials. It tests the form's current values
  (saved or not), after the same placeholder checks the guards use, and reports
  "✓ Connected · bind OK · 112 ms" or the reason, masked. A pass counts as "ready" until that system's
  settings change or the workbench restarts.
- **Status badges** (decided: icon badges): ✓ ready (filled in and last test passed), ! not tested yet
  (Run still works), ✕ missing (something a selected scenario needs is absent → Run is blocked with that
  message and the lab panel opens on that tab).

### 4.6 Running (decided)

- **Run** (⌘↵) runs the scenario in the active editor tab. If it has unsaved changes the button reads
  **Save & Run** and saves first (runs always use saved files).
- The arrow next to Run: **Run folder** (the selected folder), **Run all scenarios** (⇧⌘↵), **Run only
  the ones that found something**.
- During a run: progress ("Running all · 3 of 5"), each scenario's state (passed / finding / running
  "step 2 of 4…" / waiting) in the run panel and the file tree, and **■ Stop**.
- **Stop stops immediately** (decided): the current scenario's cleanup does **not** run. The run panel
  says "Stopped — cleanup did not run" and lists the skipped cleanup entries with the values they would
  have used (e.g. the created user's DN), so the consultant knows what may be left in the lab.
  *(Listing them: my call.)*
- A run uses the same path as `idmtest run`: same guards, same driver check, same masked run log, same
  `reports/<run-id>/` folder. A guard refusal or missing setting appears as a red message (scenario or
  setup problem), not as a finding.

### 4.7 Results: two kinds of red, never mixed (decided)

- **Red = the scenario or setup needs fixing**: everything detected before running (invalid YAML,
  unknown parameter, bad expression, missing setting, guard refusal, missing driver), and, during a run,
  a system that **could not be reached** (connection refused, timeout before any answer, TLS failure) or
  that **refused our credentials** (bind rejected, 401/403 from a token endpoint or on login). Shown in
  the Problems panel, as a run-start message, or on the step with a "setup" marker.
- **Amber = a finding: the lab did not do what the scenario expects** (decided: amber for findings).
  Anything the **target system answered** — just not as expected: a check that read back a different
  value (`ldap.verify`, `db.verify`, `rest.verify`, `http.verify`), an action the system rejected or
  failed (`ldap.create` "entry already exists", a `rest.request` answered with HTTP 500 or 403 from the
  IdM itself, a SOAP fault), a role or resource request the IdM refused.
- **Cleanup failures** are amber, with "something may be left in the lab" and the entry's values.
- The engine records which kind each failure is (a failure category on the step outcome and in
  `results.json`), so the workbench, the HTML report and the CLI console can all show it. Rule of thumb
  for each step type: the system replied → finding; we could not talk to it or it would not let us in →
  setup. (Decided by jangulo, 2026-10-19.)

### 4.8 Finding panel (decided)

For a step with a finding, the right panel shows:
- **Headline**: "The lab did not do what this step expects" and "The scenario ran as written. <system>
  never reached the expected state within the wait."
- **Expected vs found**: each checked attribute/column/field with the expected value, the value found
  on the last try, and the ones that matched.
- **Waiting**: every try over the wait (e.g. "30 tries every 2 s over 60 s") and whether the value
  ever changed — reads as "never provisioned" vs "changed late / slow driver".
- **Evidence**: what the system returned on the last try (the existing ITF-37/40 evidence), secrets
  hidden.
- **This scenario's recent runs**, from `reports/` (e.g. "✓ Mon 09:10 · ✓ Tue 16:45 · ✕ today 11:02 ·
  ✕ today 14:32"), with a hint when it used to pass with an unchanged scenario ("something changed on
  the IdM side").
- Actions: **↻ Rerun as is** (primary), **Copy finding**, **Open full report**, **Edit scenario**
  (jumps to the step's lines in the editor; not the default).
- **Copy finding** puts a plain-text summary on the clipboard: workspace/scenario, profile, run id,
  system URL (credentials removed), step, expected vs found, waiting summary, history, report path —
  masked.
- The editor marks the failed step's lines; clicking the step jumps there.

### 4.9 Look (decided)

- **Theme: graphite & indigo**, dark and light; follows the operating system's setting, with a switch
  in the top bar (remembered per user). Our own palette — similar in spirit to Cerbos's playground, not
  identical.
- Status: ✓ green, ! amber-yellow, findings amber (#f2994a family), red only for broken scenarios/setup.
  No red-tinted backgrounds. Colours always paired with a symbol or word (readable for colour-blind
  users and in greyscale).
- Reference values from the mockups (dark / light): background `#111216` / `#fafafc`, surfaces `#17181e`
  / `#ffffff`, raised `#1e2028` / `#f0f1f6`, lines `#2a2d38` / `#e1e3ec`, text `#e1e3ea` / `#1c1e27`,
  muted `#9195a5` / `#5e6273`, accent `#7c8cff` / `#4f5fe8`, ok `#4cc38a` / `#1f9d63`, finding `#f2994a`
  / `#e07b25`, error `#f06a75` / `#d33b4a`.

### 4.10 Reports

- **Open full report** opens the run's existing `report.html` (ITF-36/46) in a new browser tab, served
  by the workbench. The run panel and finding panel are the in-app view; the HTML report stays the
  shareable artefact. *(My call.)*

## 5. How it is made (my calls; jangulo handed these to me)

- **Same program**: the workbench is part of `idm-test-runner`; `idmtest ui` starts Spring Boot with an
  embedded web server bound to `127.0.0.1`. The CLI commands keep working unchanged.
- **Front end**: React + TypeScript, built with Vite; Monaco editor with YAML language support and the
  generated step-vocabulary JSON Schema; theme via CSS variables (one set per mode). Built by
  `frontend-maven-plugin` during the Maven build and served as static files from the jar.
- **API**: a small REST API for the workbench only (workspaces and recent list, folder listing, files
  read/save with a version check, validation, lab settings read/save/test, profiles, runs start/stop,
  run history), plus Server-Sent Events for live run progress. All behind the session token; JSON never
  contains secret values (only "set" / "not set" / "from the environment").
- **Reuse**: workspace creation = `InitCommand`; running = the same flow as `RunCommand` (refactored into
  a reusable run service with progress callbacks, used by both the CLI and the workbench); validation =
  `EngineFactory.validator(StepRegistry.forValidation())`; masking = `SecretMask`; settings = ITF-55's
  workspace configuration.
- **One run at a time** per workbench. Concurrent runs against one lab are out of scope.

## 6. Left to the builder

- Exact API paths and payloads; front-end component structure; state management.
- How the step-vocabulary schema is generated (build step vs endpoint), as long as it comes from the
  registered `StepType`s.
- How the run service exposes per-step progress and the waiting history (each try's observed value) —
  the engine may need a progress listener; keep the engine Spring-free.
- Folder-picker details (starting folder, hidden folders).
- Draft storage mechanism in the browser (e.g. IndexedDB), as long as drafts survive a refresh/crash
  and are cleared after Save.
- Keyboard shortcuts on Windows/Linux (Ctrl instead of ⌘).
- Packaging and size of the front-end bundle within the existing ZIPs.

## 7. Done means

- A consultant can, from a fresh start page: create a workspace, fill in the lab panel, test the
  connection, edit the example scenario, run it against a real lab and read the result — without a
  terminal.
- A finding shows the headline, expected vs found, waiting history, evidence and recent runs; Rerun as
  is and Copy finding work; red and amber are never mixed.
- No secret appears in anything the workbench generates — UI panels, API responses, copied findings,
  reports, logs (tests assert it, as ITF-55 does); plain-text passwords in the user's files are flagged
  and kept out of drafts.
- Every failure is shown as either a finding (amber) or a setup problem (red) by the rule in §4.7,
  in the workbench, the report and the CLI.
- No edit is lost: refresh, crash, second tab and changed-on-disk cases are tested.
- Light and dark modes; English and Spanish.
- Files saved by the workbench work unchanged with `idmtest run` and CI.
