# ITF-73 Delimited Text File Steps Implementation Plan

> Steps use checkbox (`- [ ]`) syntax for tracking. Work one task at a time; each task ends with its
> own tests green and a commit.

**Goal:** Add `file.write`, `file.verify` and `file.delete`, so a scenario can drop a CSV for the
NetIQ Delimited Text driver and check the files the driver writes, using named, access-controlled
folders from the lab settings.

**Architecture:** A new connector module, `idm-connector-file`, owns folder settings, path
containment, access checks, atomic writes, candidate selection and CSV reading and writing (Apache
Commons CSV). Three step classes in `idm-test-engine` use it, as `db.*` uses
`MultiDatabaseConnector`. `file.verify` needs the scenario's start time, so the step SPI gains a
small `StepContext`. Wiring, guards, runner settings, the lab panel and reports learn the new folder
kind the same way they know databases and REST targets.

**Tech Stack:** Java 25, Spring Boot 4.1.1 configuration properties (connector module only), Apache
Commons CSV, JUnit 5, AssertJ, Maven; React + TypeScript + Vitest for the lab panel.

**Spec:** `docs/specs/2026-11-03-delimited-text-file-steps-design.md`

## Global Constraints

- The engine stays Spring-free. Spring appears only in `idm-connector-file`'s `config` package and in
  the wiring and runner modules, exactly as for the database connector.
- No secret value, and no folder **path**, in evidence, events, logs or reports. Evidence names the
  folder by its settings name only.
- A scenario never supplies a path. Every file name is resolved against its folder and must stay
  inside it after resolving symlinks.
- No step overwrites an existing file. A temporary file is never left behind, including on failure.
- `StepType.execute` must never throw. Every I/O and parse error becomes a `StepOutcome`.
- Existing scenarios, messages and `db.verify` behaviour are unchanged. If extracting shared
  expectation logic from `DbVerifyStep` would change one of its messages or checks, copy instead.
- Integration tests use real temporary folders (`@TempDir`), never mocks of our own code.

## Spec refinements made while planning

The implementation follows the spec, with two clarifications that need no new decision:

1. **Scenario start time.** `StepType.execute(params, captureRequested)` gives a step no context, and
   step instances are shared between scenarios. Task 2 adds a `StepContext` record
   (`scenarioStartedAt`) and a default `execute(params, captureRequested, context)` overload that
   delegates to the existing method, so no existing step changes. The engine always calls the
   three-argument form.
2. **Configuration properties.** The spec says the file connector has no Spring dependency in its
   main code. The existing connectors bind their settings with `@ConfigurationProperties` in a
   `config` package, and the runner reads them from the Spring context. The file connector does the
   same; its `client` package (used by the engine) stays free of Spring types. Task 9 corrects that
   sentence in the spec.

## Review Focus

- **Path escape:** `../x.csv`, `/etc/passwd`, `sub/x.csv`, `C:\x.csv`, and a symlink inside the
  folder pointing outside are all refused, for write, verify and delete (Task 1).
- **Stale files:** a matching file older than the scenario start (less `clock-skew`) never makes
  `file.verify` pass, and a `.idmtest-tmp` file is never read (Tasks 1 and 4).
- **Vacuous assertions:** an empty `expect`, unknown keys, an empty `row`, a null expected value, an
  unknown `where` column: each fails before reading, as in `db.verify` (Task 4).
- **Access:** a `read` folder refuses write and delete; a `write` folder refuses verify; the guards
  refuse the run before any step does (Tasks 4 and 5).
- **Masking:** a `password` column and any known secret value are masked in JSON, HTML, workbench
  events and the finding panel (Tasks 3 and 8).

---

## File map

- `pom.xml`: new module `idm-connector-file`; `commons-csv` version in `dependencyManagement`.
- `idm-connector-file/`: `pom.xml`;
  `config/FileProperties.java` (`@ConfigurationProperties(prefix = "idm.test")`, map `files`),
  `config/FolderConfig.java`, `config/FileConfiguration.java`;
  `client/DelimitedFileConnector.java`, `client/FolderAccess.java` (enum), `client/CsvFormat.java`
  (record of delimiter, quote, encoding, line ending, header), `client/FileProblem.java`,
  `client/WrittenFile.java`, `client/ReadFile.java` (records).
- `idm-test-engine/src/main/java/com/idm/test/engine/spi/`: `StepContext.java`, `FileEvidence.java`;
  `Evidence.java` (permit `FileEvidence`); `EvidenceMasking.java` (row masking reused).
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/`: `FileWriteStep.java`,
  `FileVerifyStep.java`, `FileDeleteStep.java`, `FileFolders.java` (shared parameter parsing and
  format overrides), `RowExpectations.java` (shared with `DbVerifyStep` if extraction is safe).
- `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java`: pass `StepContext`.
- `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioValidator.java`: literal file-name rules.
- `idm-test-engine/src/main/java/com/idm/test/engine/result/EvidenceHtml.java`, `JsonResultWriter`:
  render and serialise `FileEvidence`.
- `idm-test-engine/src/main/resources/com/idm/test/engine/i18n/messages{,_es}.properties`: new keys.
- `idm-test-wiring/`: `StepRegistry.java`, `ScenarioNeeds.java` (`folders` plus needed access),
  `RealEnvironmentGuards.java`, `pom.xml`.
- `idm-test-scenarios/`: `pom.xml`, the Spring test configuration that builds the registry,
  `application.yml` / `application-test.yml` (`idm.test.files.scratch`),
  `scenarios/files-delimited-round-trip.yaml`, and the folder created before the suite runs.
- `idm-test-runner/`: `run/RunService.java`, `ui/lab/LabSettings.java` (new kind `folder:<name>`),
  `ui/lab/ConnectionTester.java`, `ui/lab/ProfileYamlEditor.java` if a new section needs it,
  `SecretMask.java`, runner messages; front end `src/run/types.ts`, the evidence view, the lab panel
  and their tests.
- Docs: `docs/guides/scenario-authoring.md`, `docs/reference/writing-scenarios.md`,
  `docs/reference/configuration.md`, `docs/architecture/capabilities.md`, `docs/guides/workbench.md`,
  `README.md`, `CLAUDE.md` (project structure and step count).

---

### Task 1: `idm-connector-file` module

**Files:** create the module and the `config`/`client` classes in the file map; register the module
and `commons-csv` in the parent `pom.xml`.

**Interfaces produced:**

```java
public final class DelimitedFileConnector {
    DelimitedFileConnector(Map<String, FolderConfig> folders);
    OperationResult<WrittenFile> write(String folder, String file, List<String> columns,
                                       List<List<String>> rows, CsvFormat overrides);
    OperationResult<ReadFile> readNewest(String folder, String pattern, Instant notBefore,
                                         CsvFormat overrides);   // empty data = no candidate yet
    OperationResult<Boolean> delete(String folder, String file); // false = already absent
    OperationResult<Void> probe(String folder);                  // exists, access as declared
    Optional<FolderConfig> folder(String name);
}
record WrittenFile(String folder, String name, int rows, long size) {}
record ReadFile(String name, List<String> header, List<Map<String, String>> rows, long size,
                int olderMatches) {}
```

Error mapping: unknown folder, missing directory, access refused, unsafe name → `CONFIGURATION_ERROR`
(final). I/O errors → `NETWORK_ERROR` and parse errors → `VALIDATION_ERROR` (retryable by `file.verify`).
Existing target on write → `CONFIGURATION_ERROR`.

- [ ] **Step 1: Write failing tests** in `DelimitedFileConnectorTest` against `@TempDir`:
  round trip with commas, quotes, embedded delimiter and newline; header-only file; CRLF vs LF;
  `ISO-8859-1` and `UTF-8`; UTF-8 BOM tolerated on read, never written; `delete` of present and absent
  files; refusal on existing target; access enforcement for each mode; every Review Focus path-escape
  case (skip the symlink case where the file system cannot create links); `readNewest` choosing by
  time, then name, ignoring files ending in `temp-suffix` and files older than `notBefore - clock-skew`,
  and reporting `olderMatches`; a glob never matching into a subfolder; `probe` on a missing folder, a
  read-only folder and a writable one (the probe file is removed).
- [ ] **Step 2: Atomic write test:** after a write whose serialisation fails midway (inject a row
  value that the CSV printer rejects, or a closed stream via a package-private seam), no temporary file
  remains and no final file exists. On `AtomicMoveNotSupportedException`, the result is a setup error
  and the temporary file is deleted.
- [ ] **Step 3: Implement** until green. Resolve the folder with `toRealPath()`, reject names that
  contain a separator, `..`, a drive or a root, then check `target.toRealPath()` (or its parent's for a
  not-yet-existing file) starts with the folder's real path.
- [ ] **Step 4: Binding test:** `FilePropertiesTest` binds the YAML of spec §5 and checks the
  defaults (`access: read`, `,`, `"`, UTF-8, CRLF, header, `.idmtest-tmp`, 5s).
- [ ] **Step 5:** `./mvnw test -pl idm-connector-file -am`; commit `feat(file): delimited file connector (ITF-73)`.

### Task 2: `StepContext` in the step SPI

**Files:** `spi/StepContext.java`, `spi/StepType.java`, `ScenarioEngine.java`, engine tests.

- [ ] **Step 1: Failing test** in the engine tests: a test step that overrides the three-argument
  `execute` receives the same `scenarioStartedAt` on every attempt of a wait, in cleanup, and a
  different one in the next scenario; an existing step type still runs through the default overload.
- [ ] **Step 2: Implement:** `record StepContext(Instant scenarioStartedAt)`; default
  `execute(params, captureRequested, context)` delegating to the two-argument method; the engine
  builds one context from its existing `started` time and uses it for steps, retries and cleanup.
  Document the rule in the `StepType` javadoc.
- [ ] **Step 3:** `./mvnw test -pl idm-test-engine -am`; commit `feat(engine): step context with scenario start (ITF-73)`.

### Task 3: `FileEvidence`, masking and messages

**Files:** `spi/FileEvidence.java`, `spi/Evidence.java`, `spi/EvidenceMasking.java`,
`result/EvidenceHtml.java`, the JSON writer, both message bundles; `EvidenceTest`,
`HtmlReportWriterTest`, `JsonResultWriterTest`.

- [ ] **Step 1: Failing tests:** `FileEvidence.written(folder, name, size, header, rows)`,
  `.read(...)` with selected rows, and `.failed(folder, file)`. Masks secret-named columns (the same
  name rule `SqlEvidence` applies to result columns) and the run's known secrets. Caps at 50 rows and
  64 KiB with the existing truncation note, and always keeps the selected rows. Never holds a path.
  HTML and JSON render it like SQL evidence.
- [ ] **Step 2: Implement**, reusing `EvidenceMasking` rather than copying. Add every `step.file.*`
  message key used in Tasks 4–5 to `messages.properties` and `messages_es.properties`; the existing
  bundle-completeness test must pass.
- [ ] **Step 3:** run the engine tests; commit `feat(engine): file evidence (ITF-73)`.

### Task 4: The three steps and their validation

**Files:** the step classes in the file map, `ScenarioValidator.java`; `FileStepsTest` and validator
tests.

- [ ] **Step 1: Shared expectations.** If `rejectMalformedExpect` and the row comparison in
  `DbVerifyStep` can move to `RowExpectations` with **identical** `db.verify` messages (the message
  keys are passed in), extract them and run `DbStepsTest` unchanged. Otherwise copy into
  `FileVerifyStep` and note why in its javadoc.
- [ ] **Step 2: Failing `FileStepsTest`** against a real `DelimitedFileConnector` over `@TempDir`,
  one test per row of spec §4.4, plus:
  - `file.write`: column order from `columns` and from the first row; extra key → final failure;
    missing key → empty field; null → empty; numbers and booleans as text; empty `rows` without
    `columns` → final failure; capture `{ folder, name, rows, size }`.
  - `file.verify`: `where` selection; no `where` = all rows; unknown `where` column → final failure;
    `rowCount`, `row` with exactly one row, case-insensitive columns, `checks` names as `db.verify`;
    no candidate → retryable failure whose message names the pattern and the older-match count;
    capture `{ name, rows }`; a wait that passes when another thread writes the file after a delay.
  - `file.delete`: present → deleted; absent → success "already absent"; read folder → final failure.
  - Format overrides accepted per step; `path`, `access`, `temp-suffix`, `clock-skew` rejected as
    unknown parameters.
- [ ] **Step 3: Validator rules** (failing tests first): `file.delete` with a literal `file`
  containing a glob character is rejected; a literal `file` with a separator, `..` or a root is
  rejected on all three steps; interpolated values are checked by the connector at run time.
- [ ] **Step 4: Implement** until green. `acceptedParams`: write `folder file rows columns delimiter
  quote encoding lineEnding header`; verify `folder file expect where wait delimiter quote encoding
  header`; delete `folder file`.
- [ ] **Step 5:** `./mvnw test -pl idm-test-engine -am`; commit `feat(engine): file.write, file.verify, file.delete (ITF-73)`.

### Task 5: Wiring and guards

**Files:** `StepRegistry.java`, `ScenarioNeeds.java`, `RealEnvironmentGuards.java`,
`idm-test-wiring/pom.xml`, their tests.

- [ ] **Step 1: Failing tests:** `StepRegistry.of(...)` and `forValidation()` list 17 types;
  `ScenarioNeeds` collects literal `folder` names with the access the steps need (write for
  `file.write`/`file.delete` in steps and cleanup, read for `file.verify`); the guards refuse an
  undeclared folder, a missing directory and a declared access weaker than needed, each with its own
  `runner.guard.*` message in both locales.
- [ ] **Step 2: Implement.** `StepRegistry.of` takes the `DelimitedFileConnector`; update every caller
  (`RunService`, the scenario module's configuration, `ValidateCommand` via `forValidation`).
- [ ] **Step 3:** `./mvnw test -pl idm-test-wiring -am`; commit `feat(wiring): register file steps and folder guards (ITF-73)`.

### Task 6: Default-suite scenario

**Files:** `idm-test-scenarios/pom.xml`, its test configuration, `application.yml` /
`application-test.yml`, `scenarios/files-delimited-round-trip.yaml`.

- [ ] **Step 1:** declare `idm.test.files.scratch` (`access: read-write`, `path:
  ${IDM_TEST_FILES_SCRATCH:target/scenario-files}`) and create and empty that directory before the
  suite runs, in the same place the embedded LDAP and H2 are prepared.
- [ ] **Step 2:** write the scenario: a `random-user` fixture, `file.write` of two rows (one with a
  comma and quotes in a value), `file.verify` with `where:` on the generated user id, `rowCount: 1`,
  `row:` on two columns and `wait: true`, then `file.delete` in `cleanup:`. Assert in `YamlScenarioTest`
  output that the folder is empty afterwards.
- [ ] **Step 3:** `./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false -Dscenario=files-delimited-round-trip.yaml`,
  then the whole default suite; `ContainerScenarios` must still pass where Docker is available (it
  does not need the folder unless a scenario uses it). Commit `test(scenarios): delimited file round trip (ITF-73)`.

### Task 7: Runner and workbench back end

**Files:** `RunService.java`, `LabSettings.java`, `ConnectionTester.java`, `ProfileYamlEditor.java`
(if needed), `SecretMask.java`, runner message bundles; `UiServerTest`, `LabSettings` and
`ConnectionTester` tests.

- [ ] **Step 1: Failing tests:** an `idmtest run` of a workspace scenario using a folder from
  `config/application-<profile>.yml`; the lab view lists `folder:<name>` systems with `path`,
  `access` and format fields, the scenarios that use them, and save/delete behaving as for databases
  (versioned save, 409 on a stale file); the connection test reports missing, not readable and not
  writable, and removes its probe file; no response, event or log contains the folder path except the
  lab field the user edits.
- [ ] **Step 2: Implement:** `Kind.FOLDER` with prefix `idm.test.files.<name>`; the connector bean
  from `FileProperties`; masking for `FileEvidence` in `SecretMask`.
- [ ] **Step 3: Schema:** `StepSchema` is generated from the registry; add a test asserting the three
  steps and their parameters appear and that `file.delete` is allowed in cleanup.
- [ ] **Step 4:** `./mvnw test -pl idm-test-runner -am`; commit `feat(runner): folder settings and file evidence (ITF-73)`.

### Task 8: Workbench front end

**Files:** `src/run/types.ts`, the evidence view in the finding panel, the lab panel's system list and
editor, the new-scenario template comment, i18n strings, their Vitest tests.

- [ ] **Step 1: Failing Vitest tests:** `FileEvidence` renders as a table with the masked values and
  the truncation note; the lab panel shows a **Folders** group, edits path, access (select) and
  format fields, and runs the connection test; the template's commented example mentions `file.write`.
- [ ] **Step 2: Implement**; `npm test` and `npm run build` from the front-end folder, then
  `./mvnw test -pl idm-test-runner -am`. Commit `feat(workbench): folders in the lab panel (ITF-73)`.

### Task 9: Documentation

- [ ] Authoring guide and `writing-scenarios.md`: the three steps, file selection rules (glob, start
  time, `clock-skew`, newest wins), the failure table, cleanup with `file.delete`, the Delimited Text
  driver example from the spec.
- [ ] `configuration.md`: `idm.test.files` with every key and default, and the guidance to match
  `temp-suffix` to the driver's ignore pattern.
- [ ] Capability matrix, workbench guide (Folders), README step list, `CLAUDE.md` (module list, "17
  step types").
- [ ] Spec §6: replace "no Spring dependency in its main code" with the configuration-properties
  arrangement above, and add `StepContext`.
- [ ] Commit `docs: file steps (ITF-73)`.

### Task 10: Final check

- [ ] `./mvnw clean install` green.
- [ ] Run the scenario from Task 6 through `idmtest run` and through the workbench; open the HTML
  report and confirm the file evidence, masking and absence of the folder path.
- [ ] Re-read the Review Focus list against the tests that cover it.
