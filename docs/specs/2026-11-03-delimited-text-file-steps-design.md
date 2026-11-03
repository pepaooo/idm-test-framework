# Delimited text file steps — design

**Ticket:** ITF-73. **Decided with:** jangulo, 2026-11-03.

## 1. Intent

Many IdM projects bring identities in through the NetIQ Identity Manager Driver for Delimited Text,
for example a CSV exported by HR. Today a scenario can neither drop a file for the driver nor read a
file the driver writes, so the flow "file in, then check LDAP, the database or AD" (and the reverse:
"change in the vault, then check the file out") cannot be tested end to end.

This design adds three step types: `file.write`, `file.verify` and `file.delete`. They follow the
`db.execute` / `db.verify` pattern: an action, a retryable assertion, and an action for `cleanup:`.
The ticket is in phase 1 ("ready for deliveries") of the Professional Services roadmap.

## 2. Goals and boundaries

- A scenario writes a delimited file into a **named folder**, waits for the driver, and checks the
  connected systems. A scenario also checks the rows of a file the driver writes, retrying until the
  file appears or the wait times out.
- Folders are declared by name in the lab settings (`idm.test.files.<name>`), like databases and
  REST targets. A scenario never gives a free path, so a shared scenario cannot read or write
  anywhere else on the consultant's machine.
- Each folder declares what scenarios may do in it (`access`), and the steps enforce it.
- The first version covers local folders and mounted shares (NFS, SMB mounted by the OS).
- The engine stays Spring-free.
- **Out of scope:** SFTP or SMB access done by the framework itself; fixed-width files; files with
  more than one CSV section; driver-specific checks of the archive or error folders. Those folders
  can still be declared as ordinary `read` folders and verified.

## 3. Decisions

| Question | Decision | Rejected |
|---|---|---|
| What `file.verify` checks | Pick rows by key with `where:`, then assert `rowCount` / `row:` exactly as in `db.verify` | Whole-file comparison (fails when the output file is shared with other users' rows); both modes (more surface, not needed yet) |
| Which file `file.verify` reads | Glob or exact name; only files modified since the scenario started count; the newest match is read | Exact name only (drivers add timestamps or sequence numbers); searching every match (bigger evidence, unclear failures) |
| Removing written files | A third step, `file.delete`, used in `cleanup:` | An auto-delete flag on `file.write` (brings back the implicit undo that ITF-19 removed); no deletion |
| Half-written files | Write to a temporary name in the same folder, then rename it with an atomic move | Write the final name directly (the polling driver can read a partial file) |
| CSV handling | Apache Commons CSV | Our own parser (quoting and embedded newlines become our bugs); Jackson CSV (its schema-based API fits free-form rows poorly) |
| Folder access | Each folder declares `read`, `write` or `read-write`, enforced by the steps; when omitted, a folder is `read` | Every folder read-write |
| Masking | Same rules as SQL and LDAP evidence: secret-named columns plus every known secret value; size-limited | A per-folder mask list (can be added later); metadata only (makes a failed verify hard to diagnose) |

## 4. Scenario syntax

```yaml
fixtures:
  user:
    generate: random-user

steps:
  - file.write:
      folder: hr-in
      file: "hr-${user.userId}.csv"
      columns: [employeeId, givenName, surname, email]
      rows:
        - { employeeId: "${user.userId}", givenName: "${user.firstName}",
            surname: "${user.lastName}", email: "${user.email}" }
      as: written

  - ldap.verify:
      dn: "cn=${user.userId},ou=users"
      expect: { mail: "${user.email}" }
      wait: { timeout: 2m, poll: 5s }

  - file.verify:
      folder: hr-out
      file: "hr-out-*.csv"
      where: { employeeId: "${user.userId}" }
      expect:
        rowCount: 1
        row: { status: provisioned }
      wait: { timeout: 2m, poll: 5s }

cleanup:
  - file.delete:
      folder: hr-in
      file: "${written.name}"
```

### 4.1 `file.write` (action)

- **Required:** `folder`, `file`, `rows`.
- **Optional:** `columns`, and the format overrides `delimiter`, `quote`, `encoding`, `lineEnding`,
  `header`.
- `rows` is a list of maps. A literal list and a list taken from a fixture are both accepted.
  An empty list is allowed only together with `columns`; it writes a header-only file. This covers
  testing how the driver handles an empty export.
- `columns` sets the column order of the header and of every row. When it is absent, the column
  order is the key order of the first row. A row with a key that is not a column is a final
  failure, so a typo is never silently dropped. A missing key writes an empty field.
- Values are written as text. Numbers and booleans use their YAML text form. A null value writes an
  empty field.
- The step writes the file as `<file><temp-suffix>`, flushes and closes it, then moves it to `<file>`
  with `ATOMIC_MOVE`. If the file system does not support an atomic move, the step fails as a setup
  problem; it never falls back to a non-atomic rename silently.
- An existing file with the final name is a final failure (no overwrite). Run-unique names, such as
  one built from a fixture, avoid the clash, and so does a `file.delete` in a previous run's cleanup.
- Capture (`as:`): `{ folder, name, rows, size }`. `name` is the final file name; `rows` is the
  number of data rows; `size` is in bytes.

### 4.2 `file.verify` (retryable assertion)

- **Required:** `folder`, `file`, `expect`.
- **Optional:** `where`, `wait`, and the format overrides `delimiter`, `quote`, `encoding`, `header`.
- `file` is an exact name or a glob (`*`, `?`, `[...]`, as in `java.nio.file.FileSystem#getPathMatcher`
  with `glob:`). A glob matches names inside the folder only, never subfolders.
- **Candidate files** are matching regular files whose last-modified time is at or after the
  scenario's start time, less the folder's `clock-skew` (default 5s, because a mounted share takes its
  time stamps from the server's clock). Temporary files ending in the folder's `temp-suffix` are
  ignored. Of the candidates, the step reads the newest; if two have the same time, it takes the
  later name in lexical order, so the choice is deterministic.
- No candidate means the step failed and is retried. The message names the folder, the pattern and
  how many matching files were older than the scenario's start.
- The file must have a header row (`header: true`, the default). Rows are maps keyed by header name.
  Column names are compared case-insensitively, as `db.verify` does.
- `where` is a map of column to value. A row is selected when every listed column is equal under
  `ValueComparison`. Without `where`, every row is selected. A `where` column that is not in the
  header is a final failure, never an empty selection.
- `expect` accepts `rowCount` and `row`, with exactly the meaning and the safeguards of `db.verify`:
  an empty `expect`, unknown keys, an empty or non-map `row`, and a null expected value are all final
  failures before the file is read. `row` needs exactly one selected row.
- `checks` are recorded as in `db.verify` ("row count", then one per expected column).
- Capture (`as:`): the selected rows as a list of maps, plus the file name read:
  `{ name, rows: [ ... ] }`.

### 4.3 `file.delete` (action)

- **Required:** `folder`, `file`. `file` must be an exact name; a glob is rejected by the validator.
- A missing file counts as success, with the message "already absent". The driver usually moves or
  deletes its input after processing it, so this is the normal case for cleanup.
- The step is usable both in `steps:` and in `cleanup:`.

### 4.4 Failure classification

| Situation | Outcome |
|---|---|
| Folder not declared in the settings | final failure, setup problem |
| Folder path does not exist or is not a directory | final failure, setup problem |
| Folder `access` does not allow the step | final failure, setup problem |
| `file` escapes the folder (absolute path, `..`, a separator, a symlink resolving outside) | rejected by the validator when the value is a literal; otherwise a final failure, setup problem |
| Malformed `expect` / `where` / `rows` | final failure, setup problem |
| I/O error reading or writing (permission denied, share unavailable) | failure; retried by `file.verify` |
| No candidate file yet | failure; retried |
| A file exists but cannot be parsed (bad quoting, wrong encoding) | failure; retried, because the driver may still be writing it under its final name |
| Rows differ from the expectation | finding; retried |

## 5. Folder settings

```yaml
idm:
  test:
    files:
      hr-in:                       # Delimited Text driver input
        path: /mnt/idm/hr/in
        access: write              # read | write | read-write; default read
        delimiter: ","             # default ","
        quote: '"'                 # default '"'
        encoding: UTF-8            # default UTF-8; BOM tolerated on read, never written
        line-ending: CRLF          # CRLF | LF; default CRLF (write only)
        header: true               # default true
        temp-suffix: .idmtest-tmp  # default .idmtest-tmp; match the driver's ignore pattern
        clock-skew: 5s             # default 5s (file.verify only)
      hr-out:
        path: /mnt/idm/hr/out
        access: read
```

- A step may override the format keys for one call. It can never override `path`, `access`,
  `temp-suffix` or `clock-skew`.
- `file.write` and `file.delete` need `write` or `read-write`. `file.verify` needs `read` or
  `read-write`.
- `path` may use `${...}` environment placeholders, like the other settings. The folder is resolved
  to its real path once per step; the target file's real path must stay inside it.

## 6. How it is built

- **New module `idm-connector-file`:** the configuration record `FileFolderProperties`, and
  `DelimitedFileConnector`, which does path resolution and containment, access checks, the atomic
  write, listing and choosing candidates, and reading and writing with Commons CSV. It returns
  `OperationResult` and `ErrorType` like the other connectors, and has no Spring dependency in its
  main code, matching how the engine uses the other connectors.
- **`idm-test-engine`:** `FileWriteStep`, `FileVerifyStep`, `FileDeleteStep` in `steps/`; a
  `FileEvidence` record in `spi/`; message keys in the i18n bundles (English and the existing
  locales). Shared expectation checks with `db.verify` are extracted rather than copied, if that can
  be done without changing `db.verify`'s messages.
- **`idm-test-wiring`:** registration in `StepRegistry` and `EngineFactory`; `ScenarioNeeds` gains a
  `folders` set (keyed by `folder`); `RealEnvironmentGuards` refuses a run when a needed folder is
  undeclared, missing, or lacks the access the scenario's steps need.
- **`idm-test-runner`:** the settings loaded from the workspace `config/`; the lab panel gets a
  **Folders** section (path, access, format fields), and the connection test checks that the folder
  exists and is readable or writable as declared. Writability is checked by creating and deleting a
  `<temp-suffix>` probe file. The YAML schema used by the workbench editor and by
  `idmtest validate` learns the three steps.
- **Evidence (`FileEvidence`):** folder name (never the path), file name, size, row count, and the
  content as rows. Values are masked by the existing secret-name rules applied to column headers and
  by the run's known secret values. Content is capped at 50 rows and 64 KiB, with a truncation note.
  For `file.verify`, the selected rows are always included, even when beyond the cap. The HTML
  report and the workbench finding panel show it like SQL evidence.

## 7. Verification

- **Connector unit tests:** quoting, embedded delimiters and newlines, escaped quotes, BOM, CRLF and
  LF, encodings, header-only files, containment (`..`, absolute paths, symlinks outside), access
  enforcement, atomic write leaving no temporary file behind, candidate choice by time and name.
- **Step tests (`FileStepsTest`)** against a `@TempDir` folder: each row of §4.4, the vacuous-`expect`
  safeguards, capture values, and retry until a file appears (written by the test thread after a
  delay).
- **Default-suite scenario** `files-delimited-round-trip.yaml` under `scenarios/`: `file.write` into a
  `read-write` folder that points at a temporary directory set by the test profile, `file.verify`
  with `where:` and `wait:`, and `file.delete` in cleanup. It proves the settings, guards, evidence
  and report end to end without an external system.
- **Guard tests** for undeclared, missing and wrong-access folders.
- **Workbench:** Vitest coverage for the Folders section; `UiServerTest` for saving folder settings
  and the connection test, with no path or secret leaking into other responses.

## 8. Documentation

The authoring guide and `docs/reference/writing-scenarios.md` (the three steps, folder selection
rules, failure table), `docs/reference/configuration.md` (`idm.test.files`), the capability matrix,
the workbench guide (Folders section), and the README step list.

## 9. Done means

- This design and a plan in `docs/plans/`.
- The three steps registered, validated and documented.
- `files-delimited-round-trip.yaml` passes in the default suite.
