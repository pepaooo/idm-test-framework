# PDF evidence reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a summary PDF and a detailed PDF of any finished run, on demand, from the workbench
(including a new run history view) and from `idmtest report`.

**Architecture:** Every run also writes `run.json` (its `RunInfo` and finish time). A new `RunFolder`
in `idm-test-engine` reads a run folder back into `RunInfo` and `List<ScenarioResult>`. A new
`PdfReportWriter` renders it with Apache PDFBox, sharing the labels and masking of `HtmlReportWriter`.
The CLI and a new `RunsController` endpoint both call these, and the workbench gets a Download PDF
menu and a Runs view.

**Tech Stack:** Java 25, Apache PDFBox 3.0.x, Jackson 3 (`tools.jackson`), Spring MVC (runner only),
React, TypeScript and Vitest (workbench).

**Spec:** `docs/specs/2026-11-05-pdf-evidence-reports-design.md`

## Global Constraints

- The engine stays Spring-free. All PDF and `RunFolder` code lives in `com.idm.test.engine.result`.
- PDFs are produced only on request. A run writes `results.json`, `report.html` and the new
  `run.json`, and no PDF.
- The workbench never writes a PDF into `reports/`. It streams the bytes.
- No secret value in any response, event, log, report or DOM. A PDF never shows a value the HTML
  masks: apply `HtmlReportWriter.mask` (`Secrets.maskByKey`) to resolved parameters.
- Labels come from `idm-test-engine/src/main/resources/com/idm/test/engine/i18n/messages.properties`
  and `messages_es.properties`. Reuse existing `report.*` and `evidence.*` keys, and add every new key
  to both files.
- Plain PDF, kept PDF/A-ready: every font embedded (subset), no transparency, and document info set
  (title, creator `idmtest <version>`, creation date).
- Font: DejaVu Sans, DejaVu Sans Bold and DejaVu Sans Mono 2.37, bundled with their `LICENSE`. Ask
  the user before downloading them.
- A4 portrait. Every page has the footer `pdf.secretsMasked` and `pdf.page` ("Page n of N"). Pages
  after the first carry the running header: title · run id · kind.
- File names: `<runId>-summary.pdf` and `<runId>-detailed.pdf`.
- Java style: records, no Lombok, `@DisplayName` on tests, AssertJ.

## Review Focus

1. **A run folder from before this change** (no `run.json`) must still produce both PDFs, with
   "Not recorded" fields. Tested in Task 1 (`readsOldFolderWithoutRunJson`).
2. **A huge evidence block** (a 2,000-row SQL result, or a large HTTP body) must continue across pages
   and keep its last line. Tested in Task 2 (`monospaceBlockContinuesAcrossPages`) and Task 4
   (`longSqlEvidenceKeepsLastRow`).
3. **Characters the font lacks** (emoji in an LDAP value) must become `?` instead of throwing. Tested in
   Task 2 (`unsupportedGlyphBecomesQuestionMark`).
4. **A scenario with zero steps** (failed in setup) and **a step without evidence** must render without
   errors. Tested in Task 4 (`scenarioWithoutStepsAndStepWithoutEvidence`).
5. **A run id with path tricks or a symlinked folder** in the PDF endpoint answers 404, like
   `report.html`. Tested in Task 6 (`pdfRejectsEscapingRunId`).

---

### Task 1: `run.json` and reading a run folder back

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/RunRecord.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/RunFolder.java`
- Modify: `idm-test-wiring/src/main/java/com/idm/test/wiring/RunRecorder.java`, to also write the
  `run.json`
- Modify: the result records (`ScenarioResult`, `StepResult`, `CleanupResult`, evidence types,
  `RunInfo`), only where the round trip fails
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/result/RunFolderTest.java`
- Test: `idm-test-wiring/src/test/java/com/idm/test/wiring/RunRecorderTest.java` (create)

**Interfaces:**
- Produces: `public record RunRecord(RunInfo run, Instant finishedAt)`
- Produces: `RunRecorder.runInfoPath(Path resultsJson) -> Path`. `results.json` gives `run.json`,
  `x-results.json` gives `x-run.json`, and any other name gets `.run.json` appended. Mirror
  `reportPath`.
- Produces: `public record RunFolder(String runId, RunInfo run, Instant finishedAt, List<ScenarioResult> results)`
  - `static RunFolder read(Path folder, Locale override, Locale fallback)` reads `results.json` and
    `run.json` from `folder`. It throws `RunFolder.Unreadable` (a `RuntimeException`) when
    `results.json` is missing or does not parse.
  - The `runId` is the folder name.
  - Locale: `override` if it is non-null, else `run.json`'s locale, else `fallback`, else English. The
    CLI passes `--locale` as `override` and English as `fallback`. The workbench passes `null` as
    `override` and the session locale as `fallback`.
  - When `run.json` is missing or unreadable, it falls back: start time and profile are parsed from
    a `yyyyMMdd-HHmmss-<profile>` name (system zone), `runner` is null, the filter is null, endpoints
    are empty, and `finishedAt` is the start plus the sum of the scenario durations.

- [ ] **Step 1: Write the failing tests** in `RunFolderTest`:
  - `roundTripsEveryEvidenceKind`: write a result list containing one step with each of `HttpEvidence`,
    `SqlEvidence`, `LdapEvidence`, `FileEvidence` and `CommandEvidence`, plus a cleanup entry, a
    `messageKey` with args and a stopped scenario, through `JsonResultWriter.write`. Read it back with
    `RunFolder.read`. Assert `results` equals `results.stream().map(Secrets::paramsMasked).toList()`.
  - `readsRunJson`: a folder with `run.json` gives back the same `RunInfo` (runner, startedAt,
    profiles, filter, endpoints, locale `es`) and `finishedAt`.
  - `readsOldFolderWithoutRunJson`: folder `20261104-153012-local` holding only `results.json` gives
    `runId` `20261104-153012-local`, profiles `[local]`, `startedAt` 2026-11-04T15:30:12 local time,
    `runner` null, endpoints empty, and `finishedAt == startedAt + sum(durationMillis)`.
  - `missingResultsIsUnreadable`: an empty folder throws `RunFolder.Unreadable`.
- [ ] **Step 2: Write the failing test** `RunRecorderTest.writesRunJsonBesideResults`: `RunRecorder.write`
  produces `run.json` next to `results.json`, and `RunFolder.read` reads it. `runInfoPath` maps
  `real-environment-results.json` to `real-environment-run.json`.
- [ ] **Step 3: Run the tests to make sure they fail.** Run
  `./mvnw test -pl idm-test-engine,idm-test-wiring -am -Dtest='RunFolderTest,RunRecorderTest' -Dsurefire.failIfNoSpecifiedTests=false`.
  Expected: a compilation failure (`RunFolder` and `RunRecord` do not exist).
- [ ] **Step 4: Implement `RunRecord`, `RunFolder` and `RunRecorder`.** Read with the same
  `JsonMappers.builder()` mapper as `JsonResultWriter`. Add record creators or defaults only where the
  round-trip test shows they're needed. In `RunRecorder.write`, take `finishedAt = Instant.now()` once
  and write `run.json` inside the same try block as the HTML report, so a failure is logged and never
  fatal. Pass the same instant into `HtmlReportWriter`, so the HTML and the PDF report the same
  duration: add a `write(RunInfo, List<ScenarioResult>, Instant now, Path)` overload.
- [ ] **Step 5: Run the tests to make sure they pass.** Use the command from Step 3. Expected: PASS.
  Then run `./mvnw test -pl idm-test-engine,idm-test-wiring` and expect PASS (no regressions).
- [ ] **Step 6: Commit** with `git commit -m "feat(report): record run.json and read run folders back"`.

### Task 2: PDFBox, fonts and the page layout helper

**Files:**
- Modify: `pom.xml`: add the `pdfbox.version` property (latest 3.0.x) and a `dependencyManagement`
  entry for `org.apache.pdfbox:pdfbox`
- Modify: `idm-test-engine/pom.xml`: add the `pdfbox` dependency
- Create: `idm-test-engine/src/main/resources/com/idm/test/engine/result/fonts/DejaVuSans.ttf`,
  `DejaVuSans-Bold.ttf`, `DejaVuSansMono.ttf` and `LICENSE`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/PdfPages.java` (package-private)
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/result/PdfPagesTest.java`

**Interfaces:**
- Produces: `final class PdfPages implements AutoCloseable`, package-private:
  - `PdfPages(String runningHeader, Locale locale)`: creates the A4 document and embeds the three
    fonts with `PDType0Font.load(doc, stream, true)`.
  - `void title(String text)`, `void heading(String text)`, `void paragraph(String text)` (wraps),
    `void muted(String text)`.
  - `void table(List<List<Cell>> rows, float... columnWidthFractions)`, where
    `record Cell(String text, Style style)` and `enum Style { NORMAL, BOLD, PASS, FAIL, BADGE, MUTED }`.
  - `void tiles(List<Map.Entry<String, String>> labelValue)`.
  - `void mono(String text)`: a monospace block that splits across pages line by line.
  - `void newPage()`, `void keepTogether(float height)`.
  - `void info(String title, String creator)`.
  - `byte[] finish()`: draws the footers ("Secrets masked" · "Page n of N") and the running header on
    pages after the first, saves, and returns the bytes.
- Glyphs a font cannot encode are replaced with `?` before drawing (check with `font.hasGlyph`, or
  catch the encode exception per code point).
- Colours are opaque RGB only. Badges are a filled rectangle with text, with no alpha.

- [ ] **Step 1: Write the failing tests** in `PdfPagesTest`. Extract the text of the produced bytes with
  `PDFTextStripper` in each:
  - `wrapsLongParagraph`: a 600-character paragraph extracts whole, on more than one line.
  - `monospaceBlockContinuesAcrossPages`: `mono` of 400 lines `line-0`…`line-399` gives more than one
    page, and the last page contains `line-399`.
  - `footerNumbersPages`: with 3 pages, the text contains `Page 1 of 3` and `Page 3 of 3` (English),
    and `Página 3 de 3` with locale `es`.
  - `runningHeaderOnlyAfterFirstPage`: the running header text appears on page 2 and not on page 1.
  - `nonAsciiExtracts`: `Pérez ñ ü ✔ ✘` extracts unchanged.
  - `unsupportedGlyphBecomesQuestionMark`: `ok 😀` extracts as `ok ?` and does not throw.
  - `fontsEmbeddedAndInfoSet`: every font in the document's resources is embedded, and the document
    info has the given title and creator.
- [ ] **Step 2: Add the labels** `pdf.page=Page {0} of {1}` / `pdf.page=Página {0} de {1}` and
  `pdf.secretsMasked=Secrets masked` / `Secretos enmascarados` to both catalogs.
- [ ] **Step 3: Run the tests to make sure they fail.** Run
  `./mvnw test -pl idm-test-engine -Dtest=PdfPagesTest`. Expected: a compilation failure.
- [ ] **Step 4: Add the dependency and the fonts, and implement `PdfPages`.** Ask the user before
  downloading the fonts from the official DejaVu 2.37 release. Keep a cursor `y`; when the next line
  doesn't fit above the bottom margin, start a new page. Run the footer pass in `finish()`, once the
  page count is known.
- [ ] **Step 5: Run the tests to make sure they pass.** Use the command from Step 3. Expected: PASS.
- [ ] **Step 6: Commit** with `git commit -m "feat(report): PDFBox page layout with embedded DejaVu fonts"`.

### Task 3: Summary PDF (the shared header)

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/PdfReportWriter.java`
- Modify: both message catalogs
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/result/PdfReportWriterTest.java`

**Interfaces:**
- Consumes: `PdfPages` (Task 2), `RunFolder` (Task 1), and `HtmlReportWriter.label`, `duration` and
  `text` (package-private).
- Produces:
  - `public enum PdfReportWriter.Kind { SUMMARY, DETAILED }`, with `static Kind parse(String)`, which
    accepts `summary`/`detailed` and throws `IllegalArgumentException` otherwise.
  - `public static byte[] render(RunFolder folder, Kind kind, String creator)`.
  - `public static String fileName(String runId, Kind kind)`, which returns `<runId>-summary.pdf` or
    `<runId>-detailed.pdf`.

- [ ] **Step 1: Add the labels.** In English: `pdf.kind.summary=Summary`, `pdf.kind.detailed=Detailed`,
  `pdf.notRecorded=Not recorded`, `pdf.runId=Run`, `pdf.environment=Environment`,
  `pdf.scenarios=Scenarios`. In Spanish: `Resumen`, `Detalle`, `No registrado`, `Ejecución`,
  `Entorno`, `Escenarios`.
- [ ] **Step 2: Write the failing tests** in `PdfReportWriterTest`, built on a fixed `RunFolder` of 3
  scenarios (one passed, one finding, one stopped-only). Extract the text of the summary PDF in each:
  - `summaryHasHeader`: it contains the run id, the start time formatted with `report.datePattern`,
    the profiles, the filter, and every endpoint label and URL.
  - `summaryTotalsMatchHtml`: the scenarios, passed, failed, findings, setup and stopped values equal
    the `data-tile` values from `HtmlReportWriter.render` for the same input.
  - `summaryListsScenarios`: each source name and description is present, and the finding scenario's
    `failureSummary(Locale.ENGLISH)` is present.
  - `summaryHasNoDetail`: no step type or evidence text appears.
  - `summaryInSpanish`: with locale `es`, it contains `Resumen` and the Spanish tile labels.
  - `notRecordedFields`: a `RunFolder` with a null runner and empty endpoints shows `Not recorded`
    and `report.noneRecorded`.
- [ ] **Step 3: Run the tests to make sure they fail.** Run
  `./mvnw test -pl idm-test-engine -Dtest=PdfReportWriterTest`. Expected: a compilation failure.
- [ ] **Step 4: Implement `render` for `SUMMARY`,** with the header sections in spec §3.1 order.
  Count totals exactly as `HtmlReportWriter.render` does. Extract that counting into a
  package-private `record Totals(...) static Totals of(List<ScenarioResult>)` that both writers
  use. Set the title to `report.title` (runner, or `pdf.notRecorded`) followed by " · " and the kind
  label. Use `HtmlReportWriter.started`'s format for the start time (make it package-private).
- [ ] **Step 5: Run the tests to make sure they pass.** Use the command from Step 3, then
  `-Dtest=HtmlReportWriterTest`. Expected: PASS.
- [ ] **Step 6: Commit** with `git commit -m "feat(report): summary PDF"`.

### Task 4: Detailed PDF (steps, parameters, evidence, cleanup)

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/EvidencePdf.java`
- Modify: `PdfReportWriter.java`
- Test: `PdfReportWriterTest.java`, `idm-test-engine/src/test/java/com/idm/test/engine/result/EvidencePdfTest.java`

**Interfaces:**
- Consumes: `PdfPages`, `EvidenceHtml.summary(Evidence, Locale)`, `HtmlReportWriter.mask` and `text`.
- Produces: `static void EvidencePdf.append(PdfPages pages, StepResult step, Locale locale)`, a
  `switch` over the sealed `Evidence` types. It shows the same fields, in the same order, as
  `EvidenceHtml`'s `appendHttp`, `appendSql`, `appendLdap`, `appendFile` and `appendCommand`.

- [ ] **Step 1: Write the failing tests:**
  - `detailedStartsWithSummaryHeader`: page 1 of `DETAILED` extracts the same header text as page 1
    of `SUMMARY`, except for the kind label.
  - `detailedHasEveryStep`: each step's number, type, description and message is present, and the
    failed step is followed by `report.notRun`.
  - `detailedHasAttempts`: a step with `attempts=5` and `waitedMillis` shows the
    `report.attempts.other` text and the `evidence.lastAttempt` note.
  - `parametersAreMasked`: a resolved parameter `{"password": "hunter2", "nested": {"apiToken": "abc"}}`
    never extracts `hunter2` or `abc`, and does extract `***`.
  - `cleanupListed`: a failed cleanup entry shows `report.status.failed` and the finding badge.
  - `eachScenarioStartsNewPage`: scenario 2's heading is the first heading line on its page.
  - `longSqlEvidenceKeepsLastRow`: SQL evidence with 2,000 rows extracts the last row's value.
  - `scenarioWithoutStepsAndStepWithoutEvidence`: both render, and the scenario's failure summary is
    present.
  - `EvidencePdfTest`: one test per evidence kind, asserting the key fields extract (HTTP method,
    URL, status and body; the SQL and its params; the LDAP DN and attributes; the file path and
    content; the command's argv, exit code and output).
- [ ] **Step 2: Run the tests to make sure they fail.** Run
  `./mvnw test -pl idm-test-engine -Dtest='PdfReportWriterTest,EvidencePdfTest'`. Expected: the new
  tests fail.
- [ ] **Step 3: Implement `DETAILED`:** after the header, `newPage()` per scenario, then the contents
  of spec §3.2 in `appendScenario`/`appendStep` order. Pretty-print the parameters with the same
  mapper as the HTML (`INDENT_OUTPUT`) into `mono`.
- [ ] **Step 4: Run the tests to make sure they pass.** Use the command from Step 2, then
  `./mvnw test -pl idm-test-engine`. Expected: PASS.
- [ ] **Step 5: Commit** with `git commit -m "feat(report): detailed PDF with steps, evidence and cleanup"`.

### Task 5: `idmtest report` CLI command

**Files:**
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/ReportCommand.java`
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/CommandLine.java`: accept `report`, a
  positional run folder, `--pdf` and `--out`
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/IdmTest.java`: dispatch
- Modify: both catalogs: `runner.cli.usage` gets a `report` line, plus the new `runner.report.*` keys
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/ReportCommandTest.java` and
  `CommandLineTest` (existing or create)

**Interfaces:**
- Consumes: `RunFolder.read`, `PdfReportWriter.render`, `fileName` and `Kind.parse`.
- Produces:
  - `CommandLine` gains the components `Path runFolder`, `String pdf` and `Path out`. Keep every
    existing constructor.
  - `static int ReportCommand.run(CommandLine line, PrintStream out, PrintStream err)`.
- Behaviour:
  - `--pdf` is required.
  - The default output is `<runFolder>/<fileName>`.
  - An existing target is overwritten only when its PDF document info creator starts with `idmtest`.
    Otherwise it is refused with `runner.report.notOurs`, naming the file.
  - The creator is `idmtest <version>`, taken from the same source as `idmtest --version`.
  - `RunFolder.read(folder, <--locale or null>, Locale.ENGLISH)`.
  - It prints `runner.report.written` with the path.
- Exit codes:
  - `ExitCodes.OK` when written.
  - `ExitCodes.CANNOT_START` for a usage error (`UsageException`: missing folder argument, missing or
    bad `--pdf`), a missing or unreadable `results.json` (`runner.report.noResults`), or a refused
    overwrite.

- [ ] **Step 1: Write the failing tests:**
  - `CommandLineTest.parsesReport`: `report reports/x --pdf detailed --out a.pdf --locale es` parses all
    four values.
  - `CommandLineTest.reportRejectsBadPdf`: `--pdf full` gives a `UsageException`.
  - `ReportCommandTest.writesDefaultPath`: it exits 0 and `<folder>/<runId>-summary.pdf` exists and
    starts with `%PDF`.
  - `ReportCommandTest.writesToOut`.
  - `ReportCommandTest.overwritesOwnPdf`: the second run exits 0.
  - `ReportCommandTest.refusesForeignFile`: a non-idmtest file at `--out` stays unchanged, the
    command exits 2, and stderr names the file.
  - `ReportCommandTest.missingResults`: it exits 2 with the `runner.report.noResults` text.
  - `ReportCommandTest.helpListsReport`: the usage output contains `report`.
- [ ] **Step 2: Run the tests to make sure they fail.** Run
  `./mvnw test -pl idm-test-runner -am -Dtest='CommandLineTest,ReportCommandTest' -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement it.** `report` reads no settings and no secrets, and starts no Spring
  context.
- [ ] **Step 4: Run the tests to make sure they pass.** Use the command from Step 2. Expected: PASS.
- [ ] **Step 5: Commit** with `git commit -m "feat(runner): idmtest report --pdf summary|detailed"`.

### Task 6: PDF endpoint and full run history

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/api/RunsController.java`
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ui/LastRuns.java`
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/ui/api/RunsControllerTest.java`

**Interfaces:**
- Produces:
  - `GET /reports/{runId}/report.pdf?kind=summary|detailed`. It responds `200 application/pdf`,
    `Cache-Control: no-store`, and
    `Content-Disposition: attachment; filename="<PdfReportWriter.fileName>"`.
  - The PDF endpoint answers 404 under the same conditions as `report.html`: a bad run id, a path
    outside `reports/`, or a missing or unreadable `results.json` (`RunFolder.Unreadable`).
  - It answers 409 when `runId.equals(runs.activeRunId())`, and 400 for a bad `kind`.
  - The handler calls `RunFolder.read(folder, null, <session locale>)`: the run's own locale, or the
    workbench's for an old run.
  - `GET /api/runs/history` without `scenario` returns up to `HISTORY_ALL = 200` entries, including
    unreadable run folders with `result: null`. With `scenario`, it keeps today's behaviour: 10
    entries, unreadable folders skipped.
  - `LastRuns.HistoryEntry.result` may be null only in the unfiltered form. Change
    `LastRuns.history`: when `scenario == null` and the file doesn't parse, add an entry with a null
    result instead of `continue`.
  - Folders with no `results.json` at all are not listed (`resultFiles` finds only `results.json`
    files). Say so in the Javadoc.

- [ ] **Step 1: Write the failing tests** in `RunsControllerTest`:
  - `pdfSummaryDownload`: check the status, content type, disposition file name, and that the body
    starts with `%PDF`.
  - `pdfDetailedDownload`.
  - `pdfRejectsEscapingRunId`: `..`, a bad pattern, and a symlinked folder pointing outside each give
    404.
  - `pdfMissingResults404`.
  - `pdfWhileRunning409`.
  - `pdfBadKind400`.
  - `pdfRequiresWorkspace`: without a workspace header or the `?workspace=` parameter, it is
    rejected exactly as `report.html` is.
  - `historyAllListsUnreadable`: a corrupt `results.json` appears with `result: null`.
  - `historyByScenarioUnchanged`: the limit is 10 and unreadable folders are skipped.
- [ ] **Step 2: Run the tests to make sure they fail.** Run
  `./mvnw test -pl idm-test-runner -am -Dtest=RunsControllerTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement it,** reusing the `report.html` handler's run-id and real-path checks. Factor
  them into a private `Path runFolder(String runId)` that both handlers call.
- [ ] **Step 4: Run the tests to make sure they pass.** Use the command from Step 2. Expected: PASS.
- [ ] **Step 5: Commit** with `git commit -m "feat(ui): PDF download endpoint and full run history"`.

### Task 7: Workbench Download PDF menu and Runs view

**Files:**
- Create: `idm-test-runner/src/main/frontend/src/run/PdfMenu.tsx` and `PdfMenu.test.tsx`
- Create: `idm-test-runner/src/main/frontend/src/run/RunsView.tsx`, `RunsView.test.tsx` and `Runs.css`
  (or extend `Run.css`)
- Modify: `idm-test-runner/src/main/frontend/src/run/RunPanel.tsx`: the end line, after the
  `ui.run.openReport` link
- Modify: `idm-test-runner/src/main/frontend/src/workbench/TopBar.tsx` and `Workbench.tsx`: a **Runs**
  button opening `RunsView` as a dialog or panel (match `LabPanel`'s pattern)
- Modify: `idm-test-runner/src/main/frontend/src/run/types.ts`: `HistoryEntry.result: string | null`
- Modify: `idm-test-runner/src/main/frontend/src/i18n/en.json` and `es.json`

**Interfaces:**
- Consumes: `GET /api/runs/history` and `GET /reports/{runId}/report.pdf?kind=` (Task 6), and
  `withWorkspace` from `api/client.ts`.
- Produces:
  - `PdfMenu({ runId }: { runId: string })` renders a "Download PDF" button with two links,
    `withWorkspace('/reports/<runId>/report.pdf?kind=summary')` and the same with `kind=detailed`,
    each with the `download` attribute.
  - `RunsView({ onClose }: { onClose: () => void })`.
- New i18n keys: `ui.run.downloadPdf`, `ui.run.pdfSummary`, `ui.run.pdfDetailed`, `ui.runs.title`,
  `ui.runs.button`, `ui.runs.empty`, `ui.runs.unreadable`, `ui.runs.openReport`. Spanish values:
  "Descargar PDF", "Resumen", "Detalle", "Ejecuciones", "Ejecuciones", "Todavía no hay ejecuciones",
  "No se pueden leer los resultados", "Abrir informe".

- [ ] **Step 1: Write the failing tests:**
  - `PdfMenu.test`: both links point to the right URLs, include the workspace parameter, and have
    `download`.
  - `RunPanel.test`:
    - `showsPdfMenuWhenFinished`: the menu is present once the outcome has a `reportUrl`.
    - `noPdfMenuWhileRunning`: no menu while the run is in progress.
  - `RunsView.test`:
    - It lists entries newest first, with run id, date, profile and result.
    - Each readable row has Open report and the PDF menu.
    - A `result: null` row shows `ui.runs.unreadable` and no PDF menu.
    - An empty list shows `ui.runs.empty`.
  - `i18n.test` keeps the English and Spanish keys in parity (existing test).
- [ ] **Step 2: Run the tests to make sure they fail.** Put the bundled Node on `PATH`
  (`export PATH="$PWD/idm-test-runner/target/node:$PATH"`), then run `npm test` in
  `idm-test-runner/src/main/frontend`.
- [ ] **Step 3: Implement it,** following the existing button, dialog and keyboard patterns.
- [ ] **Step 4: Run the tests to make sure they pass.** Run `npm test`, then
  `./mvnw test -pl idm-test-runner -am`. Expected: PASS. `UiServerTest` needs the built `index.html`,
  and the Maven build does that build.
- [ ] **Step 5: Commit** with `git commit -m "feat(workbench): Download PDF menu and Runs history view"`.

### Task 8: Docs, dist check, end-to-end

**Files:**
- Modify: `docs/guides/runner.md`: add the `report` command
- Modify: `docs/guides/workbench.md`: add the Runs view and the Download PDF menu
- Modify: `docs/architecture/capabilities.md`: add PDF evidence reports
- Modify: `README.md`: one line where reports are mentioned
- Modify: `docs/specs/2026-11-05-pdf-evidence-reports-design.md`: add a "§11 As built" section for any
  deviations

- [ ] **Step 1: Run the full build** with `./mvnw clean install`. Expected: BUILD SUCCESS.
- [ ] **Step 2: Build the dist ZIP** with `./mvnw -Pdist package -pl idm-test-runner -am -DskipTests`.
  Unzip it into a temporary folder and run its launcher with
  `report <an existing reports/<id> folder> --pdf detailed`. Expected: exit 0 and a PDF that opens.
- [ ] **Step 3: Run it end to end in the workbench:** run `idmtest ui` on a workspace that has runs,
  open **Runs**, and download both PDFs for an old run. Then run one scenario and download from the
  end line. Open each PDF in a viewer and check the header, the page numbers and Spanish accents. Check
  that `reports/<id>/` gained no PDF.
- [ ] **Step 4: Update the docs** listed above.
- [ ] **Step 5: Commit** with `git commit -m "docs: PDF evidence reports"`.
