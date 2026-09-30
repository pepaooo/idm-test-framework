# HTML report for scenario runs — design

**Ticket:** ITF-36. **Depends on:** ITF-35 (step descriptions). **Follow-up:** ITF-37 (per-step
evidence: HTTP/SOAP exchanges, LDAP entries, SQL rows).

**References:** Karate's reports (<https://docs.karatelabs.io/running-tests/test-reports/>: an
HTML summary plus per-feature pages with every step and its HTTP detail, opt-in JSON Lines,
Cucumber JSON and JUnit XML) and Allure (summary → test → step tree with attachments).

## 1. The problem

A run writes `*-results.json` (per scenario, step and cleanup entry, with descriptions since
ITF-35) and Surefire's JUnit XML, but nothing a person can open, read and share. The JUnit view
shows only pass/fail and one failure line; the JSON is complete but not readable at a glance.

## 2. Decisions (jangulo, 2026-09-30)

1. **First sub-project: a report over what the results already hold.** Per-step evidence capture
   (Karate's request/response view) is ITF-37 and plugs into the same report later.
2. **Generated automatically by every runner, next to its JSON file,** rewritten after every
   scenario.
3. **One self-contained HTML file per run:** inline CSS and JavaScript, nothing loaded from
   outside, so it opens offline and can be attached or e-mailed as one file.
4. **One page with collapsible scenarios (Karate-like):** summary on top; failed scenarios open,
   passed ones collapsed; steps listed in order. (Chosen over a two-pane list/detail layout.)
5. **The header states the run and the endpoints it used,** never credentials.

## 3. Data

### `RunInfo`

A new record in `com.idm.test.engine.result` (engine module, Spring-free):

| Component | Meaning |
|---|---|
| `String runner` | `Real environment`, `Containers`, `Embedded`, or another runner's name |
| `Instant startedAt` | when the run started |
| `List<String> profiles` | the active Spring profiles (empty when none) |
| `String scenarioFilter` | the `-Dscenario` value, or `null` |
| `Map<String, String> endpoints` | label → sanitised URL, in label order |

- **Endpoints are those the selected scenarios use,** decided with `ScenarioNeeds` (ITF-30), so
  the header matches what the run could touch. Labels:
  - `LDAP <directory>` (the default directory resolved, per ITF-34)
  - `Database <name>`
  - `Role service`, `Resource service`
  - `REST <target>`
- **Every URL is sanitised when `RunInfo` is built** (`ReportUrls.sanitise(String)`, engine
  module, unit-tested):
  - the `user:password@` part is removed
  - the value of any query or `;`-separated parameter whose name contains `password`, `passwd`,
    `pwd`, `pass`, `secret`, `token`, `key`, `sig`, `auth` or `credential` (case-insensitive)
    becomes `***` (widened after security review)
  - an unparseable value is kept as text after the same parameter masking

### Where `RunInfo` comes from

- **A small factory in the scenarios module** (test sources, next to `ScenarioSupport`) builds
  it from the Spring `Environment`, `ScenarioNeeds` of the selected scenarios, and the bound
  properties (`LdapProperties`/`LdapDirectoriesProperties` for URLs, `SoapProperties`,
  `RestProperties`, `DatabaseProperties`).
- **Each runner passes its own:** `YamlScenarioTest` (`Embedded`), `RealEnvironmentScenarios`
  (`Real environment`), `ContainerScenarios` (`Containers`), `MultiDirectoryScenarioTest`
  (`Embedded (two directories)`); tests that build dynamic tests from fixtures may pass a minimal
  `RunInfo`.

## 4. Generation

- **`HtmlReportWriter.write(RunInfo run, List<ScenarioResult> results, Path path)`** in
  `com.idm.test.engine.result`, next to `JsonResultWriter`. Plain Java string building (no
  template engine, no new dependency); UTF-8; creates parent directories.
- **`ScenarioSupport.buildTests`** takes the `RunInfo` and, wherever it rewrites the JSON after a
  scenario, also rewrites the HTML at the JSON path with `-results.json` replaced by
  `-report.html`:
  - `target/scenario-report.html`
  - `target/real-environment-report.html`
  - `target/multi-directory-report.html`
- **The duration** in the header is `now − startedAt` at the moment of writing, so a report
  written mid-run shows the time so far.
- **A file that failed to load or validate** is shown as a failed scenario with its error, as in
  the JSON.

## 5. The page

- **Header:** "`<runner>` run", then start time, duration, profiles, filter, and a collapsible
  **Endpoints** list.
- **Summary tiles:** scenarios, passed, failed, total duration.
- **Controls:** Expand all, Collapse all, Only failed (a few lines of inline JavaScript; the page
  is fully readable without JavaScript, because sections use `<details>`).
- **Scenarios,** in run order, each a `<details>` section: open when failed, closed when passed.
  The heading shows ✔/✖, the file name, the scenario description (ITF-35) and the duration; a
  failed scenario also shows its failure summary.
- **Steps,** in order, as rows: status, number (1-based), type, *description*, duration,
  `attempts · waited` for `wait:` steps, and the message (highlighted when failed). Parameters
  open behind a ▸ as indented JSON. After a failed step: "Any later steps were not run."
- **Cleanup:** a sub-list with each entry's status (SUCCEEDED, FAILED, SKIPPED), type,
  description and message.
- **A reserved spot per step** (an empty container with a class name) where ITF-37's evidence will
  render.
- **Safety:**
  - every piece of text is HTML-escaped (descriptions, messages, parameters, names, URLs)
  - parameter values whose key (at any depth) contains `password`, `passwd`, `pwd`, `secret`, `token`,
    `authorization`, `credential`, `apikey`, `api_key`, `api-key` or `cookie` (case-insensitive) render as `***`
    (widened after security review); the JSON results are
    unchanged
  - no `src` or `href` points outside the file
- **Look:** light and dark themes follow `prefers-color-scheme`; `@media print` expands every
  section (and the JavaScript opens all `<details>` on `beforeprint`), so printing or saving as
  PDF gives the whole report.

## 6. Documentation

- `docs/reference/writing-scenarios.md` "Results": the report file, what it shows, the masking.
- `docs/reference/running-tests.md` and the real-environment README: where the report lands.
- README and the capability matrix: the report.

## 7. Testing

**`ReportUrlsTest`:** userinfo removed; `password=`, `api_key=`, `token=` and `;password=`
(JDBC style) masked; other parameters kept; a non-URL string masked the same way.

**`HtmlReportWriterTest`:**
- a failed scenario's `<details>` is `open`, a passed one's isn't
- step description, attempts and waited, message and parameters appear; "Any later steps were not
  run" follows a failed step
- cleanup entries with each status
- a load-failure result renders its error
- a `<script>` in a description or message is escaped
- a secret-looking parameter key (nested too) renders `***`
- header fields and endpoints appear; the file has no external `src`/`href`
- summary counts are right

**Runner level:**
- the embedded run writes `target/scenario-report.html` naming every corpus scenario
- `RealEnvironmentLdapOnlyTest` writes a report whose endpoints list its LDAP directory URL
- `RunInfo` factory: only used endpoints are listed; the default directory is resolved

**Also:** the full suite, the corpus (0 FAILED), and `ContainerScenarios` if Docker is available.

## 8. Out of scope

- Per-step evidence (ITF-37).
- A command to render an old JSON file, history and trends, tags, a parallel timeline.
- JUnit XML with step detail, Cucumber JSON, Allure results.
