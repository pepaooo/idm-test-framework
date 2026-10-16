# A runner for end users over a workspace folder — design

**Ticket:** ITF-55 (epic). **ADR:** [0007](../architecture/decisions/0007-end-user-runner-and-local-workbench.md), Part 1.
**Decided with:** jangulo, 2026-10-15.

## 1. The goal

An IdM consultant who does not write Java unzips one download, points it at a folder of their own
scenarios and connection settings, and runs `idmtest validate` / `idmtest run` — no repository clone, no
Maven, no IDE. They get the same results JSON, HTML report (English or Spanish) and log the JUnit runners
produce today. A scenario that passes under `RealEnvironmentScenarios` passes the same way from a
workspace, with the same guards. The ZIP runs on a machine without Java, and the exit code makes the
runner usable in CI.

## 2. Decisions

1. Connection settings are the existing Spring YAML (`idm.test.*` keys) in `config/application-<profile>.yml`.
2. Secrets: environment variables first, then an optional `secrets.env` in the workspace. `init` puts it
   in `.gitignore`; the runner warns when Git tracks it; secret values are never printed. No encryption,
   no OS keychain in this ticket.
3. Commands: `init`, `validate`, `run` (plus `--help`, `--version`).
4. Code: a new `idm-test-wiring` module (shared wiring moved out of `idm-test-scenarios`' test sources)
   and a new `idm-test-runner` module (the program). The JUnit runners become thin wrappers over
   `idm-test-wiring`.
5. Drivers: PostgreSQL, Oracle (`ojdbc11`) and SQL Server (`mssql-jdbc`) are bundled; any other JDBC driver
   goes into a `drivers/` folder.
6. Distribution: an executable jar, and one ZIP per OS with a `jlink` Java 25 runtime and launchers;
   `IDM_TEST_JAVA_HOME` selects the user's own Java 25+.
7. Not in this ticket, and no tickets for now: signing/notarization, installers, auto-update, publishing
   releases (CI produces the ZIPs; releasing stays manual).

## 3. Modules

**`idm-test-wiring`** (main code; depends on `idm-test-core`, `idm-test-engine`, the connector modules).
The engine stays Spring-free (ADR 0003); this module is the Spring composition point.

| Unit | Moved from | Purpose |
|---|---|---|
| `StepRegistry` | `ScenarioSupport.registry(...)` | builds `Map<String, StepType>` from Spring-managed connectors |
| `EngineFactory` | the repeated `new ScenarioValidator` / `new ScenarioEngine(...)` blocks | builds validator and engine in one place |
| `RealEnvironmentGuards` | the five guards in `RealEnvironmentScenarios` | embedded profile, placeholder URLs, empty directory, placeholder REST targets, half-set credentials; localized (ITF-46) |
| `ScenarioNeeds`, `RunInfos` | test sources | what the selected scenarios use; the report header |
| `ScenarioSource` (interface), `ClasspathScenarioSource`, `DirectoryScenarioSource` | `ScenarioSupport.resource/scenarioFiles/selectedScenarioFiles` | lists and opens scenario files from the classpath (JUnit) or a folder (workspace); glob selection |
| `RunRecorder` | `ScenarioSupport.writeResults` | writes the results JSON and, beside it, the HTML report after each scenario |

**`idm-test-runner`** (depends on `idm-test-wiring`):

| Unit | Purpose |
|---|---|
| `IdmTestApplication` | Spring Boot entry point, no web server; dispatches the command |
| `CommandLine` | hand-written parsing of the command and options (no picocli) |
| `Workspace` | resolves the folder, its layout and `reports/<run-id>/` |
| `SecretsEnvPropertySource` | reads `secrets.env` into a property source after real environment variables |
| `DriverLoader` | loads JDBC driver jars from `<workspace>/drivers/` and registers them with `DriverManager` (§4) |
| `InitCommand`, `ValidateCommand`, `RunCommand` | each returns an exit code |
| `ConsoleProgress` | one line per scenario, then a summary |

**`idm-test-scenarios`:** `YamlScenarioTest`, `RealEnvironmentScenarios`, `ContainerScenarios`,
`MultiDirectoryScenarioTest` use `idm-test-wiring`; their behaviour, properties (`-Dscenario`,
`IDM_TEST_PROFILE`) and results are unchanged.

## 4. Workspace and settings

**Workspace:** `--workspace <dir>`, else the current folder. It must contain `scenarios/`; otherwise:
`not a workspace: <dir> has no scenarios/ folder (run 'idmtest init <dir>' to create one)` (exit 2).

```
my-tests/
  scenarios/                 *.yaml / *.yml; sub-folders allowed; a scenario's name is its relative path (users/create.yaml)
  config/application-<p>.yml one per profile; optional config/application.yml shared by every profile
  secrets.env                optional; KEY=VALUE lines, '#' comments, blank lines; never committed
  drivers/                   optional extra JDBC driver jars
  reports/<run-id>/          one folder per run
  .gitignore, README.md      written by init
```

**Settings**, set before Spring starts:
- `spring.config.additional-location=optional:file:<workspace>/config/`
- `spring.profiles.active=<--profile>`; for `run`, `--profile` is required and
  `config/application-<p>.yml` must exist (`no settings for profile 'lab': config/application-lab.yml not found`, exit 2)
- precedence, highest first: command-line options (`--locale` → `idm.test.report.locale`) → real
  environment variables → `secrets.env` → `config/application-<p>.yml` → `config/application.yml` → the
  runner's built-in defaults
- the runner's built-in `application.yml` holds only harmless defaults (timeouts, pool sizes); no lab URLs
  or credentials. The corpus's embedded settings stay in `idm-test-scenarios`' test resources. A missing
  setting stays missing, and the guards report it.
- `secrets.env` keys are used as written (`IDM_TEST_LDAP_PASSWORD`), referenced as `${IDM_TEST_LDAP_PASSWORD}`.
  A malformed line: `secrets.env line 7 is not KEY=VALUE` (exit 2) — never the line's content.

**Protections:** `init` writes `.gitignore` with `secrets.env` and `reports/`. Before `run`, when `git` is on
the PATH and the workspace is in a Git repository, `git ls-files --error-unmatch secrets.env` succeeding
prints a warning (`secrets.env is tracked by Git; remove it with 'git rm --cached secrets.env'`) and the
run continues. Secret values never appear on the console, in `run.log` or in reports (ITF-23).

**Drivers:** two places, both optional:
- `<install>/lib/drivers/` — on the class path from launch: the jar is built with Spring Boot's ZIP layout
  (`PropertiesLauncher`), and the launcher passes `-Dloader.path=<install>/lib/drivers`. Plain-jar users
  can pass their own `-Dloader.path=<dir>`.
- `<workspace>/drivers/` — loaded by `DriverLoader` at startup (the workspace is only known once the
  options are parsed): every `*.jar` goes into a child class loader, and each `java.sql.Driver` it provides
  (found through `ServiceLoader`) is registered with `DriverManager` through a small delegating driver, the
  standard way to use a driver from a non-system class loader.

`run.log` lists the driver jars loaded from both places. A database URL with no matching driver: `no JDBC driver for 'jdbc:db2://…' (database 'hr');
put the driver jar in <workspace>/drivers/` (exit 2, checked before any scenario runs).

## 5. Commands

**`idmtest init [<dir>]`** (default: current folder): writes `scenarios/example.yaml` (an `ldap.create` /
`ldap.verify` pair with cleanup, commented), `config/application-lab.yml` (common `idm.test.*` keys,
commented, `${…}` placeholders, links to `configuration.md`), `secrets.env.example`, `drivers/README.md`,
`.gitignore`, `README.md`. Never overwrites: existing files are skipped and listed; exit 0.

**`idmtest validate [--scenario <glob>] [--profile <p>] [--locale en|es] [--workspace <dir>]`:** loads and
validates the selected scenarios and prints `✔ <name>` or `✖ <name> — <message>` (in the chosen
language), then a summary. It never connects: no LDAP, database, REST or SOAP client or pool is created.
With `--profile`, it also checks that the profile file exists.

**`idmtest run --profile <p> [--scenario <glob>] [--locale en|es] [--workspace <dir>] [--verbose]`:**
1. resolve workspace, settings, language (an unsupported `--locale` stops: ITF-46 message, exit 2);
2. load and validate the selected scenarios — an invalid file is a failed result, the others still run;
3. driver check, then the real-environment guards — a refusal stops before any scenario (exit 2);
4. run each scenario in name order; print `✔ users/create.yaml  2.1 s` or
   `✖ users/create.yaml  5.3 s — <failure summary in the run's language>`;
5. write `reports/<run-id>/results.json`, `report.html` (after each scenario) and `run.log`;
   `<run-id>` = `yyyyMMdd-HHmmss-<profile>`;
6. print `<n> scenarios: <p> passed, <f> failed · report: reports/<run-id>/report.html`.

`--scenario` globs match the relative path, with or without the extension (as `-Dscenario` does today).
No match: `no scenario matches '<glob>'` (exit 2).

**Exit codes:** `0` everything selected passed / validated; `1` at least one scenario failed or did not
validate; `2` the run could not start (bad options, not a workspace, missing profile file, unsupported
locale, malformed `secrets.env`, missing driver, guard refusal, no matching scenario).

**Console and log:** framework logging goes to `run.log` only; the console shows progress and errors.
`--verbose` also echoes the log to the console and prints stack traces for start-up errors. Start-up
errors are one line on stderr in the run's language.

## 6. Packaging and launcher

**Artifacts** (from `idm-test-runner`):
- `idm-test-runner-<version>.jar` — Spring Boot executable jar with all connectors and the three bundled
  drivers; `java -jar` on Java 25+.
- `idmtest-<version>-<os>-<arch>.zip` for macOS arm64, macOS x64, Windows x64, Linux x64:

  ```
  idmtest/
    bin/idmtest, bin/idmtest.cmd
    lib/idm-test-runner-<version>.jar
    lib/drivers/             empty, for extra JDBC drivers
    runtime/                 jlink image, Java 25
    README.md, LICENSE-THIRD-PARTY
  ```

**Runtime:** `jlink --add-modules java.base,java.sql,java.naming,java.xml,java.net.http,java.logging,
java.management,java.desktop,java.instrument,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.zipfs,jdk.dynalink,
jdk.net,java.security.jgss,jdk.security.jgss --strip-debug --no-header-files --no-man-pages --compress zip-6`. The list is verified by running the jar
on the image. jlink runs per OS: a GitHub Actions matrix (macOS, Windows, Ubuntu) builds the ZIPs and
attaches them as workflow artifacts; locally `./mvnw -Pdist package -pl idm-test-runner -am` builds the
current machine's ZIP. The default build does not run jlink.

**Launcher** (`idmtest` and `idmtest.cmd`, same logic):
1. Java = `$IDM_TEST_JAVA_HOME/bin/java` when set, else `<install>/runtime/bin/java`;
2. read `JAVA_VERSION` from that Java's `release` file; below 25: `IDM_TEST_JAVA_HOME points to Java 21
   (<path>); idmtest needs Java 25 or newer` (exit 2);
3. run `<java> -Dloader.path=<install>/lib/drivers -jar <install>/lib/idm-test-runner-<version>.jar "$@"`
   (the workspace's `drivers/` is added by the runner itself); works from any folder and through `PATH`.

macOS: the README documents the one-time `xattr -dr com.apple.quarantine idmtest` for the unsigned launcher.

## 7. Testing

- **`idm-test-wiring`:** the moved classes keep their tests; `DirectoryScenarioSource` — sub-folders,
  globs over relative paths, sorting, `.yml`/`.yaml`.
- **`idm-test-runner`:**
  - workspace detection; settings precedence (each layer overriding the next); `secrets.env` parsing
    (comments, blank lines, malformed line → line number only, values never echoed); the Git-tracked
    warning in a temporary Git repository;
  - `init` writes every file and never overwrites;
  - `validate` — English and Spanish output, exit codes, and no connection: a profile pointing at
    unreachable hosts validates quickly with exit 0;
  - `run` end to end on a temporary workspace against the embedded LDAP, H2 and WireMock fixtures the
    corpus uses: a passing and a failing scenario; `reports/<run-id>/` contents, console summary, exit
    codes 0, 1 and 2 (missing profile, guard refusal, no matching scenario);
  - drivers: a driver jar placed in `drivers/` is loaded (H2 copied there, absent from the runner's
    own class path in that test); a URL without a driver gives the exit-2 message;
  - packaging (under `-Pdist`): ZIP layout; launcher picks `IDM_TEST_JAVA_HOME` and rejects Java below 25
    (fake `release` file); the jlink runtime runs `--version` and `validate` on a sample workspace.
- **`idm-test-scenarios`:** the full suite and corpus pass unchanged after the move (1032 tests,
  0 failed scenarios).

## 8. Documentation

- New guide `docs/guides/runner.md`: install, `init`, settings and secrets, `validate` / `run`, reports,
  drivers, `IDM_TEST_JAVA_HOME`, the macOS quarantine note.
- README: "Getting started without Maven", pointing at the guide.
- `configuration.md`: workspace configuration and precedence.
- `capabilities.md`: a "Runner" row. ADR 0007: Part 1 marked implemented.

## 9. Out of scope

- The web workbench (ITF-56) and Git features (ITF-57).
- Signing/notarization, installers, auto-update, publishing releases.
- OS keychain or encrypted secrets.
- A `check` command for connectivity (the guards in `run` cover the refusals).
- ITF-44 (per-profile files in the repository); the workspace's `config/` works without it.

## 10. Refinements made during implementation

- **Profile at the command-line layer.** `--profile` reaches Spring as `--spring.profiles.active`, like
  `--locale` as `--idm.test.report.locale`, so both beat environment variables and every file. The
  language is `--locale`, then `IDM_TEST_LOCALE`, then `-Didm.test.locale`, then the profile's
  `idm.test.report.locale`, then English.
- **Logging during runs.** The masked `run.log` (and the console with `--verbose`) are the only log outputs;
  `logging.file.*` and `logging.config` in workspace settings are ignored, so no file can receive
  `secrets.env` values unmasked. Secret values shorter than 4 characters are not masked.
- **Names and files.** A profile name may not contain `/`, `\` or `..`; `secrets.env` must be UTF-8.
- **`DriverLoader` reads service files per jar.** Each jar's own `META-INF/services/java.sql.Driver` is read,
  and a provider the parent class loader already has (a built-in driver) is skipped, so the built-in one is used.
- **Pools closed with the context.** Database pools are closed when the run's context closes.
- **No `Locale` on `DriverCheck`.** It throws a `UsageException` carrying a message key; the caller renders it
  in the run's language.
- **Init example.** The example scenario's DN is relative to the base DN, and the example settings use
  `user-dn` for the LDAP bind.
- **CI runner.** The x64 macOS job runs on `macos-15-intel`.
- **jlink modules.** The runtime also needs `jdk.dynalink`, `jdk.net`, `java.security.jgss` and
  `jdk.security.jgss`, found by running the jar on the image.
