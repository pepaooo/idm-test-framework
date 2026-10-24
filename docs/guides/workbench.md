# The workbench: `idmtest ui`

The workbench is the browser face of `idmtest`. In one window you create or open a workspace, write a
scenario, connect it to your lab, run it and read what the lab did — no terminal after the first
command, no Maven, no IDE. It works on the same workspace folder as `idmtest run`, through the same
code: everything it saves works unchanged with `idmtest run`, in CI and in Git.

The design is in the [workbench design](../specs/2026-10-19-local-workbench-design.md) and
[ADR 0007](../architecture/decisions/0007-end-user-runner-and-local-workbench.md), Part 2. For installing
`idmtest`, the workspace layout, settings and secrets, see [Running scenarios with `idmtest`](runner.md).

## Start it

```bash
idmtest ui
```

The workbench starts on a free port of this computer and opens your default browser there. The console
prints the address, in case the browser does not open:

```
The workbench is at http://127.0.0.1:<port>/?token=<random>
Press Ctrl+C to stop
```

Press Ctrl+C in that console to stop it. Closing the browser tab does not stop it.

| Option | Meaning |
|---|---|
| `--workspace <dir>` | open this workspace straight away; without it, the start page |
| `--port <n>` | listen on this port instead of a free one (not 80: browsers leave port 80 out of the address) |
| `--locale <en\|es>` | the language the workbench starts in (also `IDM_TEST_LOCALE`, as for `run`) |
| `--verbose` | the stack trace when the workbench cannot start |

```bash
idmtest ui --workspace my-tests --port 8123 --locale es
```

When the port is taken, the console says `cannot start the workbench: Port <n> is already in use` and
`idmtest` ends with exit code 2.

On a machine without a desktop session nothing opens: open the printed address yourself, in a browser
on the same computer.

### From a clone of the repository (no ZIP)

`idmtest` only launches the runner's main class, `com.idm.test.runner.IdmTest`, with `ui` as its first
argument. In a clone you can start the same workbench three ways; the options above work in all of them.

**From the IDE.** Create a run configuration for the main class `com.idm.test.runner.IdmTest` in the
`idm-test-runner` module, with program arguments such as `ui --workspace /path/to/my-tests`. Run or
debug it like any Java application; the IDE's stop button stops the workbench. The IDE does not build
the front end, so build it once first (a Maven build of `idm-test-runner`, or `npm run build`; see
[Running tests](../reference/running-tests.md#the-workbench-front-end)).

**From the built jar.** Build it, front end included (needs Java 25):

```bash
./mvnw package -pl idm-test-runner -am -DskipTests
```

Then start the workbench from it:

```bash
java -jar idm-test-runner/target/idm-test-runner-1.0.0-SNAPSHOT.jar ui --workspace /path/to/my-tests
```

Extra JDBC drivers go on the class path with `-Dloader.path=<folder>`, as for the jar's other commands.

**Through Maven.** Install the modules once, so the runner's dependencies come from your local
repository:

```bash
./mvnw install -DskipTests
```

Then start it with the Spring Boot plugin:

```bash
./mvnw -pl idm-test-runner spring-boot:run -Dspring-boot.run.arguments="ui --workspace /path/to/my-tests"
```

Re-run the install after changing another module. To stop the browser from opening, add
`-Dspring-boot.run.jvmArguments="-Djava.awt.headless=true"` and open the printed address yourself.

### Only this computer, only this session

- The workbench listens on `127.0.0.1` only; other machines cannot reach it.
- The address carries a random session token. The first request with it sets an `HttpOnly`,
  `SameSite=Strict` cookie for that port and reloads the page without the token, so the token is not
  left in the address bar. Every page and API request needs the cookie (or the token); without it the
  answer is 403. A new `idmtest ui` has a new token: open the address it prints.
- Requests whose `Host` is not `127.0.0.1:<port>` or `localhost:<port>`, or that come from another
  site (`Origin`, `Sec-Fetch-Site`), are refused with 403. The pages cannot be framed by another site.
- Two workbenches on different ports have separate cookies and do not disturb each other.

## The start page

Without `--workspace`, the workbench opens on the start page:

- **Recent workspaces**: each one's name, folder and last result, for example
  "✕ 2 findings · today 14:32 · lab", "✓ 12 passed · yesterday · uat", "! 1 stopped · today 09:05 · lab"
  or "never run". A folder that no
  longer exists is greyed, says "folder not found" and offers **Remove from list**. The list is a file of
  folder paths, `~/.idmtest/recent.json`; it never holds scenario content or secrets.
- **Create a workspace**: a name, where to put it (**Browse…** opens a folder picker served by the
  workbench), **Include the example scenario** (on by default) and **Start a Git repository here** (off
  by default; hidden when `git` is not installed). It writes the same files as `idmtest init`; the Git
  option runs `git init` in the new folder. The new workspace opens at once.
- **Open a workspace**: any folder with a `scenarios/` folder, typed or picked with **Browse…**,
  including folders made with `idmtest init`.

While a run is going, the workbench keeps its workspace: opening another one (from the list, by folder,
or after creating one) shows "A run is going in lab-tests. Wait for it to finish, or stop it." with
**Stop the run** and **Cancel**. Stop the run asks first ("Stop the run in lab-tests? Its cleanup will
not run."), because a stopped scenario skips its cleanup; the page then shows "Stopping the run…" and
opens the workspace you asked for by itself once the run has ended (if the run is still going after
30 seconds, the notice comes back). A workspace created during a run is created at once; only its
opening waits. Cancel closes the notice. The workspace being run can always be opened.

The top bar has the language and the light/dark switch; the footer shows the version and "listening on
this computer only".

## The main screen

- **Top bar**: the workspace name; the **Profile** picker (one entry per `config/application-<p>.yml`,
  the first one by name chosen at the start); the **lab chip**; "● N unsaved"; **Save**; the **Run**
  split button; language and theme.
- **Left, the file tree**: `scenarios/` and `config/` and the files at the top of the workspace.
  Scenarios show the badge of their last run that finished (✓ passed, amber ✕ finding, red ✕ setup
  problem); a scenario counted as stopped never changes it (a stop after a real finding shows that
  finding), and a scenario with no finished run has no badge; files
  with unsaved edits show ●; `secrets.env` shows a lock and never opens. **Add files**, or dropping
  `.yaml`/`.yml` files on the tree, copies them into `scenarios/` (up to 2 MB each); a file of the same
  name is never overwritten: the copy becomes `name (2).yaml`.
- **Middle, the editor**: one tab per open file, with YAML highlighting, autocomplete and hover help for
  the step vocabulary, and errors marked inline. The **Problems** panel underneath lists what
  `idmtest validate` would say about the open scenario, as you type, with a link to each line. Only
  files under `scenarios/` and `config/` are checked; for scenarios the error is the one
  `idmtest validate` reports, word for word.
- **Right, the run panel**: the current or last run, step by step, and the finding panel.

Below about 760 px wide the columns stack.

## Connect to the lab

The lab chip shows whether the systems the workspace's scenarios use are ready, for the selected
profile: "Lab ready", "Lab: 2 of 3 ready", or what is missing, such as "Lab: Directory needs a URL".
Click it to open the **lab panel**: one tab per system the scenarios use (directories, databases, REST
targets, the SOAP services), and **+ add** for one no scenario uses yet. Each tab's badge says ✓ ready,
! not tested yet, or ✕ missing.

Each tab is a form for that system's settings: URL, base DN, bind DN, user and so on.

- **Save** writes the plain settings to `config/application-<profile>.yml` and the passwords to
  `secrets.env`, the files `idmtest run` reads. The profile file is edited in place: its comments and
  layout stay, and a line of the `idmtest init` template is uncommented when there is one. A password is
  written to `secrets.env` and referenced from the profile file as `${IDM_TEST_…}`.
- **Passwords are never shown.** A saved password shows as dots with **Change**; the typed value is
  never sent back by the workbench, and the field is emptied after every Test and Save ("Cleared — type
  it again to save it."). Leaving it as dots keeps the saved one.
- **Secret names.** The name already used by the profile file is kept. Otherwise the template's name
  (`IDM_TEST_LDAP_PASSWORD`) or one derived from the setting (`IDM_TEST_DATABASES_HR_PASSWORD`) is
  used — unless another profile, `config/application.yml` or an existing value in `secrets.env` already
  uses that name. Then the workbench picks a name for this profile, `IDM_TEST_<PROFILE>_…` (for example
  `IDM_TEST_UAT_LDAP_PASSWORD`), so saving one profile never changes another profile's password.
- **Shared settings.** A value inherited from `config/application.yml` shows "from application.yml";
  changing it writes an override into the profile file only. `config/application.yml` is never
  written.
- **Environment variables win**, as in a run: a value set through an environment variable shows "from
  the environment" and cannot be changed here.
- **Refused saves**, with the reason and nothing written: a value containing `${` (the workbench writes
  the references itself), a value with a line break, a profile file or `secrets.env` that is a link
  pointing outside the workspace, and a profile file that is a link to `config/application.yml`.

**Default directory.** When scenarios have `ldap.*` steps without `directory:` and the workspace
names several directories but no `idm.test.default-directory` (or one that names none of them),
`idmtest run` cannot tell which directory those steps use and refuses the run. The lab panel then
opens on a first ✕ **Default directory** tab, and the chip reads "Lab: choose a default directory":
the tab names the scenarios with such steps, lists the configured directories, and **Save** writes
`default-directory` under `idm.test` in `config/application-<profile>.yml` (uncommenting the
template's line when there is one). An invalid value reads `default-directory is "nope", which is not
one of the configured directories.` A value set through `IDM_TEST_DEFAULTDIRECTORY` shows "from the
environment" and cannot be changed here. Until it is chosen, runs that include those scenarios are
blocked with the chip's message; an invalid value blocks every run, as `idmtest run` refuses it before
it starts.

**Test connection** tests the form's current values, saved or not, after the same checks a run makes
before it contacts anything (no placeholder URL, no in-memory database):

| System | What the test does |
|---|---|
| Directory | binds with the bind DN and password |
| Database | connects with the workspace's JDBC drivers and runs `SELECT 1` (`SELECT 1 FROM DUAL` on Oracle) |
| REST target | fetches its OAuth token when it uses OAuth; otherwise a GET to its base URL: any HTTP answer means reachable, 401/403 means the credentials were rejected |
| SOAP services | fetches the WSDL with the credentials |

The result reads "✓ Connected · bind OK · 112 ms", or the reason ("credentials rejected", "could not
reach …", "no answer"), with secrets masked. Each test gives up after 10 seconds. For a database the
test uses its own timeouts: timeout parameters in a PostgreSQL, MySQL, MariaDB or SQL Server JDBC URL
are left out of the test, and a URL whose timeout cannot be left out (an Oracle descriptor, for
example) is refused with "the JDBC URL sets a timeout of its own". The saved URL is not changed.

A system counts as ready once its last test passed with the values it has now; changing a setting, or
restarting the workbench, makes it "not tested yet" again. ! not tested yet does not stop a run. When
a system a scenario you are about to run needs is missing a setting (✕), that run is blocked: the run
panel shows the message and **Open the lab panel**, which opens on that system's tab. Only the
scenarios of the run count — the active scenario for **Run**, the folder's for **Run folder**, every
scenario for **Run all scenarios**, the ones with a finding or a setup problem for **Run only the ones
that found something** — so a system that only other scenarios use does not stop it; the blocked
entries of the Run menu say why when you point at them.

## Edit and save

- **Nothing is written until you press Save** (⌘S on macOS, Ctrl+S elsewhere). Saving writes the
  bytes as they are in the editor, line endings included.
- **Drafts.** Unsaved edits are kept in the browser (IndexedDB, per workspace and file) about a second
  after you stop typing, once the text has been checked. After a reload or a crash the draft comes back,
  marked unsaved. A draft is deleted when you save, load from disk, or undo back to the saved text.
  When the browser cannot keep drafts (a private window without IndexedDB, or a draft write that
  failed, e.g. a full quota), unsaved edits are not kept either, and the browser asks before you
  leave the page.
- **Plain-text passwords.** The editor shows your files as they are written. A literal value under a
  secret-looking key (`password`, `userPassword`, `secret`, `token`, …) is flagged in Problems as a
  warning. Keys that only say how a token is fetched are not secrets and are not flagged:
  addresses (`token-uri`, `authorization-uri`, any such key ending in `uri` or `url`) and settings such as
  `authorization-grant-type` and `client-authentication-method`, which the lab panel writes itself. In a `config/*.yml` file it reads "a password is written in plain text — use
  `${IDM_TEST_…}` and put the value in secrets.env". In a scenario, which cannot read environment
  variables, it reads "a password is written in plain text — declare a fixture and reference it with
  `${…}` instead". A file with such a warning is never kept as a draft: the editor says "Unsaved edits
  to this file are not kept if the page is reloaded", and the browser asks before you leave the page.
- **Changed on disk.** When an open file changes on disk (another editor, `git pull`, `idmtest`, or a
  lab panel save of the profile file) while it has unsaved edits, the workbench asks: **Keep mine**
  (then confirm the overwrite), **Load from disk**, or **Compare** (side by side). A file without
  edits simply reloads. Every Save carries the version that was read; if the file changed since, the
  save is refused and the same choice appears. Nothing is overwritten silently. Linux and Windows notice
  outside changes within a fraction of a second; macOS within about 2 seconds. When the system drops
  change notices (too many at once), or watching the files stops working for a moment, the workbench
  reads the tree and every open file again, with the same rules.
- **A second tab** on the same workspace shows "This workspace is open in another tab" and is
  read-only. **Take over** moves editing to it: the other tab keeps its edits as drafts first and becomes
  read-only. When the other tab has edits it could not keep as a draft (a file with a plain-text
  password, or text not yet checked), or does not say whether it has, Take over asks before it goes
  ahead; those edits stay in the other tab only, marked unsaved.
- **Another workspace in another tab.** One workbench has one open workspace. When another tab of the
  same workbench opens a different workspace from the start page, a tab still showing the first one
  stops acting on it: it shows "This workspace is no longer the one open in the workbench (another tab
  opened <name>)" over the page, with **Reload**. Nothing is saved, run or changed in the lab from that
  tab — its saves, lab saves and runs are refused instead of landing in the other workspace — and its
  unsaved edits stay where they were: in the tab, and in their drafts, which come back when you open
  that workspace again. Reload opens the workspace that is open now.

## Run

- **▶ Run** (⌘↵ / Ctrl+Enter) runs the scenario in the active tab, with the selected profile. When the
  file has unsaved edits the button reads **Save & Run** and saves first: runs always use saved files.
- The arrow next to Run offers **Run folder**, **Run all scenarios** (⇧⌘↵ / Ctrl+Shift+Enter) and
  **Run only the ones that found something** (the scenarios whose last run had a finding or a setup
  problem).
- **To run a folder**, click it in the tree (it is highlighted; Enter or Space selects it too, and
  the click still folds or unfolds it), then **Run ▾ → Run folder `<folder>/` (n)**: it runs every
  scenario in that folder and its subfolders. With no folder selected, Run folder is the active
  scenario's folder; opening a file from the tree selects no folder again, so Run folder follows the
  last click. `scenarios/` itself has no Run folder (Run all covers it), and folders outside
  `scenarios/` (`config/`, `reports/`) are not selected: the menu then offers the open scenario's
  folder. A folder without scenarios shows it disabled with (0), and the selection is not kept after a
  reload. Selecting a folder never changes what **▶ Run** runs: the scenario in the active tab.
- During a run the panel shows "Running all · 3 of 5", each scenario's state (passed, finding, setup
  problem, running "step 2 of 4…", waiting), and **■ Stop**. The tree marks every scenario of the
  run: ◌ running, ○ waiting, then its result badge as it finishes. One run at a time: a second start
  is refused while one is going.
- **Stop is immediate.** The current scenario's cleanup does not run. The panel says "Stopped —
  cleanup did not run" and lists the cleanup entries that were skipped, with the values they would have
  used, such as the DN of the user that was created, so you know what may be left in
  the lab. The values are masked as in `report.html`: the `secrets.env` values as `****`, and any value
  under a secret-looking key (`password`, `Authorization`, `token`, …) as `***`, which also hides a
  token the run obtained while it ran. The same masking applies to every step the run panel shows;
  `results.json` masks only the `secrets.env` values and keeps the other resolved parameters as they
  were, as for `idmtest run`. The interrupted scenario reads "stopped" with the warning ! (never the
  red of a setup problem), and scenarios the run never reached read "not run". The end line counts
  each on its own, for example "5 scenarios: 2 passed, 1 finding, 1 stopped, 1 not run"; a run stopped
  between two scenarios ends with "· stopped". A scenario whose step had already failed before the
  stop counts as that finding or setup problem, and still lists its skipped cleanup. A scenario
  counted as stopped never changes the tree's badge: it keeps the badge of its last run that
  finished, so **Run only the ones that found something** is not affected either.
- A workbench run is an `idmtest run`: the same workspace files, settings precedence, checks before
  anything is contacted, JDBC driver loading, masking and `reports/<run-id>/` folder (with
  `report.html`, `results.json` and `run.log`). A run uses the workbench's language. The differences are
  in `run.log` only:
  - the loggers of the workbench's own request handling stay at INFO, whatever the profile's
    `logging.level.*` says: `com.idm.test.runner.ui`, `org.springframework.web`,
    `org.springframework.http`, `org.springframework.security` and the embedded Tomcat's. So a
    workbench `run.log` has no DEBUG or TRACE lines from the REST client or the OAuth token client,
    which `idmtest run` would write;
  - the profile's `debug=`/`trace=`, `logging.pattern.*` and `logging.config` are ignored (`run.log`
    keeps its own pattern);
  - an invalid `logging.level` value is skipped, where `idmtest run` refuses to start.

## Read the result: findings and setup problems

A failed step is shown as one of two kinds, never mixed:

- **Amber, a finding: the lab did not do what the scenario expects.** The system answered, just not
  as expected: a check read back a different value (`ldap.verify`, `db.verify`, `rest.verify`,
  `http.verify`), the system rejected or failed an action (`ldap.create` "entry already exists", an HTTP
  error from the IdM, a SOAP fault), or the IdM refused a role or resource request. A cleanup entry that
  failed is a finding too, with "Something may be left in the lab:" and the entry's values.
- **Red, a setup problem: the scenario or the settings need fixing.** Everything found before the run
  (invalid YAML, an unknown parameter, a bad expression, a missing setting, a refusal from the checks, a
  missing driver), and during the run a system that could not be reached (connection refused, a timeout
  before any answer, TLS) or that refused the credentials. Its panel offers "Check the lab settings"
  when the problem looks like a connection or a login.

The rule comes from the connector's error type: `NETWORK_ERROR`, `TIMEOUT`, `AUTHENTICATION_ERROR` and
`CONFIGURATION_ERROR` are setup problems; every other failure is a finding. Malformed parameters and
unexpected errors are setup problems too. `results.json` records the kind of each failed step
(`failureKind`), so the HTML report and `idmtest run`'s console (`· finding`, `· setup problem`) show
the same split.

Click a failed scenario, then its failed step, to open the **finding panel**:

- the headline "The lab did not do what this step expects", and "The scenario ran as written.
  Directory never reached the expected state within the wait." (for a step that did not wait:
  "… answered, but not as this step expects.");
- **Expected vs found**: each checked attribute, column or field, with the expected value, the value
  found on the last try ("✕ differs" or "✓ matches");
- **Waiting**: "30 tries every 2 s over 60 s — the value never changed" (it was never provisioned) or
  "the value changed on try 3" (a slow driver may need a longer wait);
- **Evidence**: what the system returned on the last try, secrets hidden;
- **This scenario's recent runs**, from `reports/`, with a hint when it used to pass; a stopped run
  shows as "stopped" (!) and never counts as a pass or a failure;
- **↻ Rerun as is**, **Copy finding**, **Open full report**, **Edit scenario**.

The editor marks the failed step's lines by itself; **Edit scenario** opens the file there.

**Copy finding** puts a plain-text summary on the clipboard, to hand to whoever owns the drivers:

```
idmtest finding · lab-tests / users/create-user.yaml
Profile: lab · Run 20261019-143205-lab · eDirectory ldaps://edir.lab:636

Step 2 ldap.verify cn=u123,ou=users — the lab did not do what this step expects
  groupMembership: expected contains cn=Finance,ou=groups — found (not present)
  loginDisabled:   expected FALSE — found FALSE ✓
Waited 60 s (30 tries every 2 s); value never changed.
History: passed Mon 09:10, Tue 16:45; failed today 11:02, 14:32.
Report: reports/20261019-143205-lab/report.html
```

The system's URL is copied without the user and password in it, and without parameters such as
`password=` or `token=`; every value is masked as in the report.

## Reports

**Open full report** opens the run's `report.html` in a new browser tab, served by the workbench (still
behind the session cookie). It is the same report `idmtest run` writes, in
`reports/<run-id>/report.html`: the file to share. The run panel and the finding panel are the
in-app view of it.

## Look and language

- **Light and dark**: the workbench follows the operating system's setting until you use the switch in
  the top bar; the browser then remembers your choice. Every colour comes with a symbol or a word
  (✓, !, ✕, "finding", "setup problem").
- **English and Spanish**: the language picker is in the top bar. The browser remembers the choice;
  otherwise the workbench starts in the `--locale` language, or English. Runs started from the
  workbench write their messages and report in that language.

## What never shows a secret

No API answer, run event, panel, copied finding, report or log of the workbench contains a password,
token or `secrets.env` value: secret settings appear only as "set", "not set" or "from the
environment", and run output is masked as in `idmtest run`. `secrets.env` cannot be opened, read or
saved through the workbench. Text you write into your own files is shown as written in the editor
(masking it would break editing); plain-text passwords there are flagged and kept out of drafts, as
described above.
