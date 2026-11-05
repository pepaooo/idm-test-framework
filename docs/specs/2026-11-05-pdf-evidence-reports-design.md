# PDF evidence reports — design

**Ticket:** to be assigned. **Decided with:** pepaoo, 2026-11-05.

## 1. Intent

A run's `report.html` is good for working with a run, but it does not work as **test evidence**. A
project has to keep proof that its test cases were run and what they found, as a document filed with
the project documentation, for example on SharePoint. Someone saves it there by hand. This design
adds two PDF versions of a run's report:

- **Summary PDF:** the run on one or two pages. What ran, where, and the outcome of each scenario.
- **Detailed PDF:** the same header, then everything the HTML report shows for each scenario:
  steps, parameters, evidence and cleanup.

The PDFs are produced **only when someone asks for one**: from the workbench for a finished run, or
from the CLI for an existing run folder. A normal run writes exactly what it writes today.

## 2. Goals and boundaries

- Two PDFs per run, `summary` and `detailed`, built from what the run already recorded. No new data is
  captured during scenario execution.
- On demand only, in two places: a workbench **Download PDF** menu on a run, and an
  `idmtest report` CLI command on a run folder.
- Same content rules as `report.html`: the same masking, the same trimmed evidence, the same language
  (English or Spanish, from the message catalogs), self-contained, works offline.
- Library: **Apache PDFBox** (Apache 2.0), used directly. No HTML-to-PDF converter. The user chose
  this; OpenHTMLtoPDF was rejected because it is barely maintained.
- The engine stays Spring-free. The PDF writer lives next to `HtmlReportWriter` in `idm-test-engine`.
- **PDF/A is not required for now.** Whether the project's records process needs it is not yet known,
  so plain PDF is built, but kept easy to make PDF/A-2b later: all fonts embedded, no transparency in
  badges or colours, and the document info set (§3.3). PDF/A itself (an sRGB output intent, XMP
  metadata via `xmpbox`, a veraPDF check) is a follow-up if needed.
- **Non-goals:** sending, uploading or integrating with SharePoint, Jira, ALM or any other tool;
  signatures, approval boxes, or project or tester fields; a PDF written automatically on every run;
  changes to `report.html`'s content.
- **Anti-goals (a failure even if it works):** a PDF that shows any value the HTML report masks; a PDF
  that silently drops or cuts evidence the HTML shows (long content goes onto more pages instead); a
  run that produces a different outcome, or fails, because of PDF code.

## 3. What each PDF contains

Labels come from the existing `report.*` and `evidence.*` message keys wherever one exists. New
labels (for example `pdf.summaryTitle`, `pdf.page`) are added to both catalogs.

### 3.1 Header (shared by both PDFs, first page)

The same information the HTML report's header shows, in this order:

1. Title: the report title (`report.title` with the runner name), plus "Summary" or "Detailed".
2. Run line: run id (the folder name), start time in `report.datePattern`, duration, profiles,
   scenario filter. Anything not recorded shows `report.none` / `report.noneRecorded`.
3. Totals: scenarios, passed, failed, findings, setup problems, stopped, total duration. These are
   counted exactly as `HtmlReportWriter.render` counts them (for example, `stoppedOnly` counts as
   stopped, not as a finding).
4. Environment: the endpoints table (label → sanitised URL), or "none recorded".
5. Scenario list: one row per scenario in run order, with ✔/✘, the kind badge (finding, setup or
   stopped), source name, name when it differs, description, and duration. A failed row also shows
   `failureSummary(locale)` on a second line.

The **summary PDF is this header and nothing else.**

### 3.2 Detail (detailed PDF only, after the header)

One section per scenario, passed ones included, in run order, each starting on a new page. It holds
everything `HtmlReportWriter.appendScenario` / `appendStep` and `EvidenceHtml` show:

- Scenario heading: ✔/✘, badge, source name, name, description, duration. If it failed, the failure
  summary.
- Steps, numbered from 1, each with:
  - type, description and result badge;
  - attempts and wait time, or its duration;
  - the message, rendered in the report's language (`HtmlReportWriter.text`);
  - the resolved parameters as indented JSON, masked with `Secrets.maskByKey` as the HTML masks them;
  - its evidence, with the same fields and order as `EvidenceHtml` for HTTP, SQL, LDAP, file and command
    evidence, and the "last attempt" note when there were several attempts.

  After a failed step, a "not run" line (`report.notRun`).
- Cleanup: each entry with its status (succeeded, failed or skipped), type, description, duration and
  message. A failed entry gets the finding badge.

In the HTML these parts are collapsible. In the PDF everything is expanded.

### 3.3 Page furniture

- A4 portrait, with consistent margins.
- Every page after the first carries a one-line running header: the report title, the run id, and
  "Summary" or "Detailed".
- Every page carries a footer: "Secrets masked" and "Page n of N".
- PDF document info is set: title, the creator (`idmtest` and its version), and the creation date.

## 4. Where the run information comes from

**Gap found:** `results.json` holds only the scenario results. `RunInfo` (runner, start time,
profiles, filter, endpoints, locale) is written only into `report.html`, and the duration is taken
from `Instant.now()` when the report is written. Producing a PDF later from a run folder therefore
needs the run information stored too.

**Decision (my call):** `RunRecorder.write` also writes a `run.json` next to `results.json`. It holds
`RunInfo` plus `finishedAt`, the same instant the HTML uses for its duration. It is written in the
same place and with the same error handling as the report: auxiliary, so a failure is logged and
never fatal. Endpoint URLs are already sanitised by `RunInfo`. Its name follows `RunRecorder.reportPath`:
`results.json` gives `run.json`, and `x-results.json` gives `x-run.json`. This way the JUnit runners,
which share `target/`, do not overwrite each other's file.

For a run folder without `run.json` (runs from before this change), the PDF is still produced:

- run id and profile from the folder name (`yyyyMMdd-HHmmss-<profile>`), as `LastRuns` already does;
- start time from the folder name;
- duration from the sum of scenario durations;
- endpoints, filter and runner shown as "not recorded";
- language from the request (the workbench language, or `--locale` in the CLI), defaulting to English.

Reading `results.json` back needs it to deserialize into `ScenarioResult`. Evidence already has Jackson
type info (`Evidence`'s `kind` property). The builder checks the records round-trip and adds what is
missing (creators or defaults), with a test that `JsonResultWriter` output reads back equal to what
was written, apart from masking. Nested `Message` arguments that read back as maps or strings must
still render. Where a message cannot be re-rendered, the recorded English text is shown instead,
as `HtmlReportWriter.text` already does when there is no detail.

**Masking.** `results.json` masks the `secrets.env` values. `report.html` additionally masks values
under secret-looking keys (`Secrets.maskByKey`). The PDF applies the HTML's rule to what it reads, so
it never shows more than the HTML. Evidence is stored already masked and trimmed, and is used as is.

## 5. How you get one

### 5.1 Workbench

Today the workbench links only to the run started in the current session: the run panel's end line.
The only history is the last 10 runs of one scenario, shown in `FindingPanel` as badges with no links,
and the start page shows each workspace's last run without a link. Earlier runs, and runs where
everything passed, cannot be reached. This design therefore adds a **run history view** (decided with
the user).

- **Download PDF menu.** It has **Summary** and **Detailed** and appears in two places: next to the
  report link in the run panel's end line, and on each row of the new run history view.
- **Run history view.** A workspace-level **Runs** view, opened from the workbench's existing
  navigation (the builder picks the entry point to match the current layout). It lists every run in
  `reports/`, newest first, each with run id, date, profile, result (passed, findings or setup
  problems), **Open report** and **Download PDF**.
  - Backend: `GET /api/runs/history` without `scenario=` returns every run, built from
    `LastRuns.history` with no scenario filter. The current 10-entry limit applies only to the
    per-scenario form; the full list is paged or capped at a generous number (builder's call, at least
    200).
  - A run folder whose `results.json` cannot be read is listed without the result and without
    Download PDF, instead of being hidden.
- Download links carry the workspace as the `?workspace=` query parameter that `WorkspaceGuard`
  already accepts, because a plain link cannot send the `X-Idmtest-Workspace` header.
- Endpoint: `GET /reports/{runId}/report.pdf?kind=summary|detailed`. It uses the same run-id check,
  workspace resolution and real-path containment as the existing `report.html` handler in
  `RunsController`, and the same session, Host/Origin/Sec-Fetch-Site and `X-Idmtest-Workspace`
  rules. It responds with `Content-Type: application/pdf` and
  `Content-Disposition: attachment; filename="<runId>-summary.pdf"` (or `-detailed.pdf`).
- The PDF is built in memory and streamed. **Nothing is written into `reports/`.**
- The language is the run's own (`run.json`), or the workbench language for an old run.
- A missing or unreadable run answers 404, as for `report.html`. A run still in progress has no
  download menu, and the endpoint answers 409.

### 5.2 CLI

```
idmtest report <run-folder> --pdf summary|detailed [--out <file>] [--locale en|es]
```

- `<run-folder>` is a `reports/<run-id>/` folder containing `results.json`.
- The default output is `<run-folder>/<run-id>-summary.pdf` (or `-detailed.pdf`). `--out` writes
  somewhere else. An existing file is overwritten only if it is a PDF this command wrote before,
  recognised by the document info's creator being `idmtest`. Otherwise the command refuses and names
  the file (my call, in keeping with "saves never overwrite silently").
- `--locale` overrides the language. Without it, the run's language from `run.json` is used, else
  English.
- It is added to `CommandLine`, the `IdmTest` dispatch and the help text, like the existing commands.
  It reads no settings and needs no secrets and no connection.
- Exit codes follow `ExitCodes`: 0 written; usage error for bad arguments; a distinct failure when the
  folder has no readable `results.json`.

The JUnit runners (`RealEnvironmentScenarios`, `ContainerScenarios`) are unchanged. Their output
folders can be passed to `idmtest report` if they contain `results.json`. That is not a goal and is
not tested.

## 6. How it is built

- **Dependency:** `org.apache.pdfbox:pdfbox` 3.0.x, added to `idm-test-engine`, with its version
  managed in the parent POM.
- **Font:** one open-licensed TTF family (regular, bold and monospace, for example DejaVu Sans and
  DejaVu Sans Mono) is bundled as a resource and embedded as a subset. Its licence file is shipped with
  it. This makes Spanish and other non-ASCII text render the same everywhere. Glyphs the font lacks
  are replaced with `?` instead of failing.
- **Classes in `com.idm.test.engine.result`:**
  - `PdfReportWriter`: the public entry point, `write(RunInfo, List<ScenarioResult>, Instant finishedAt,
    Kind, OutputStream)`, with `Kind { SUMMARY, DETAILED }`. It mirrors `HtmlReportWriter`, sharing its
    label, duration and masking helpers (package-private today) instead of copying them.
  - A small layout helper, kept package-private: a cursor that writes wrapped text, keeps lines
    together, breaks pages and draws the running header and footer, simple two- and four-column
    tables, badges, and monospace blocks that continue across pages.
  - `EvidencePdf`: the PDF counterpart of `EvidenceHtml`, a `switch` over the sealed `Evidence` types.
  - `RunFolder` (or similar): reads `run.json` and `results.json` from a folder, falling back as in §4.
    The CLI and `RunsController` share it.
- **jlink / dist:** confirm the `dist` ZIP still builds and that `idmtest report` works from it.
  `java.desktop`, which PDFBox uses, is already in the jlink module list.
- The builder decides the exact visual styling: fonts, sizes, colours for pass and fail, spacing. Keep
  it close to `report.html`'s look and suitable for black-and-white printing (✔/✘ and badges carry the
  meaning, not colour alone).

## 7. Verification

- **Engine unit tests** (`PdfReportWriterTest`), using PDFBox's text extraction on the generated
  bytes:
  - both kinds contain the header fields and totals, in English and Spanish;
  - the summary has no step or evidence text, and the detailed PDF has all of it;
  - a secret-keyed parameter and a `secrets.env` value never appear in the extracted text;
  - a long SQL result or HTTP body spans pages without losing its last line;
  - non-ASCII text (`Pérez`, `ñ`) extracts correctly;
  - page numbering is "n of N".
- **Round-trip test:** `JsonResultWriter` output reads back through `RunFolder`, including every
  evidence type.
- **Fallback test:** a folder with `results.json` and no `run.json` still produces both PDFs, with the
  "not recorded" fields.
- **Runner:**
  - `RunCommandTest`-style tests for `idmtest report`: default path, `--out`, refusal to overwrite a
    foreign file, missing folder, and bad `--pdf` value.
  - `RunsControllerTest` for the endpoint: headers, 404 for a bad or escaping run id, 409 while
    running, and the workspace guard.
- **Front end:** Vitest tests that the menu appears for finished runs only and links to both kinds,
  and that the run history view lists runs newest first, including an unreadable one without
  Download PDF. `RunsControllerTest` also covers the unfiltered history.
- **Manual:** open both PDFs from a real run in a PDF viewer and print one page in black and white.

## 8. Documentation

- `docs/guides/runner.md`: the `report` command and its options.
- `docs/guides/workbench.md`: the Download PDF menu.
- `docs/architecture/capabilities.md`: PDF evidence reports.
- `README.md`: a line where reports are mentioned.
- The `.gitignore` that `init` writes already ignores `reports/`, so PDFs written by the CLI stay out
  of Git.

## 9. Done means

- From the workbench, both PDFs can be downloaded for any finished run, old runs included, through
  the new run history view.
- `idmtest report <folder> --pdf summary|detailed` writes them, from the build and from the `dist` ZIP.
- No masked value appears in either PDF. The detailed PDF has everything the HTML shows.
- Normal runs only gain a `run.json`, and their outcomes are unchanged.
- The full build is green, and the docs in §8 are updated.

## 10. Calls made in this design (for review)

- `run.json` is added to every run folder, with a fallback for old runs (§4).
- The output file names and the CLI overwrite rule (§5.2).
- A run in progress gets 409 and no download menu (§5.1).
- Each scenario starts on a new page in the detailed PDF (§3.2).
- DejaVu as the embedded font (§6).
- `run.json` is named like `reportPath` names the report, so the JUnit runners don't collide (§4).
- If a message can't be re-rendered, the recorded English text is shown (§4).
- An old PDF is recognised by its document info's creator before it is overwritten (§5.2).
- The run history lists unreadable runs instead of hiding them, and is capped at no fewer than 200
  (§5.1).
- The CLI exit code for a missing `results.json` is left to the builder, within `ExitCodes` (§5.2).

## 11. As built

Where the build differs from, or adds to, the design above:

- **`Message.revived`.** A message argument that is itself a message is written to `results.json` as a
  map of `key` and `args`. `Message.revived(List)` turns it back into a `Message` when the records are
  read, and `StepResult`, `CleanupResult` and `ScenarioResult` apply it to their message arguments, so
  a nested message re-renders in the PDF's language (§4).
- **Emoji.** The design had a character the font lacks become `?`. DejaVu Sans does have emoji such as
  U+1F600, so in proportional text an emoji renders as itself. `?` remains for characters neither font
  has (CJK, for example) and for any non-ASCII-Latin character in DejaVu Sans Mono (evidence blocks).
- **SQL evidence is already capped.** `Evidence.MAX_ROWS` (20) cuts a SQL result when the evidence is
  recorded, so a result in `results.json` never has the 2,000 rows that Review focus 2 imagines. The
  block continuation across pages is still implemented and tested for long blocks (a large HTTP body).
- **409 reuses `runner.ui.run.busy`.** The PDF endpoint answers a run in progress with the existing
  message and no new key.
- **New key `ui.runs.close`** (en and es) labels the Runs dialog's close button; the other `ui.runs.*`
  and `ui.run.pdf*` keys are as designed.
- **`PdfReportWriter.Kind.parse` is public.** The CLI and the endpoint share it and it throws
  `IllegalArgumentException` for anything but `summary` or `detailed`.
- **Containment of the inputs.** The PDF endpoint checks that `results.json` and `run.json` are
  regular files inside the run folder (real path, no symlinks) as it does for the folder itself, not
  only the run id.
- **Owner-only files.** The CLI writes through a temp file beside the target (created with mode 0600 on
  POSIX) and moves it into place, so a PDF written by `idmtest report` is readable only by its owner.
