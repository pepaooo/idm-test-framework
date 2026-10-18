# Running scenarios with `idmtest`

`idmtest` runs the framework's YAML scenarios from a folder you own, with no repository clone, no Maven
and no IDE. It produces the same results JSON and HTML report (English or Spanish) as the JUnit runners,
and its exit code makes it usable in CI. The design is in the
[end-user runner design](../specs/2026-10-15-end-user-runner-design.md) and
[ADR 0007](../architecture/decisions/0007-end-user-runner-and-local-workbench.md).

To write scenarios, see [Writing scenarios](../reference/writing-scenarios.md). To see every connection
setting, see the [Configuration guide](../reference/configuration.md).

## Install

Download the ZIP for your system, `idmtest-<version>-<os>-<arch>.zip` (macOS arm64 or x64, Windows x64,
Linux x64), and unzip it anywhere. The ZIP carries its own Java 25 runtime, so the machine needs no Java.

```
idmtest/
  bin/idmtest, bin/idmtest.cmd
  lib/idm-test-runner-<version>.jar
  lib/drivers/             extra JDBC drivers for every workspace
  runtime/                 the bundled Java 25
  README.md, LICENSE-THIRD-PARTY
```

Put `idmtest/bin` on your `PATH`, then check it:

```bash
idmtest --version
```

The ZIPs are built by the `Runner distribution - idmtest ZIPs` workflow (`.github/workflows/runner-dist.yml`), one job per
operating system. To build the ZIP for your own machine:

```bash
./mvnw -Pdist package -pl idm-test-runner -am
```

See [Building the runner](../reference/running-tests.md#building-the-runner) for what the build produces,
the JDK it needs, and where to download the ZIPs that CI builds.

### macOS: first start

The launcher is not signed. Once, after unzipping, remove the quarantine flag macOS puts on downloaded
files. Run it in the folder that contains the unzipped `idmtest` folder:

```bash
xattr -dr com.apple.quarantine idmtest
```

### Windows: how to call it

Call `idmtest` without quotes, from a folder on `PATH` or by its path. If the path has spaces and needs
quotes, name the launcher with its extension:

```bash
"C:\My Tools\idmtest\bin\idmtest.cmd" --version
```

A quoted call without `.cmd` makes Windows report the wrong folder to the launcher, which then cannot
find its runtime.

### Your own Java

By default the launcher uses `runtime/` from the ZIP. To use a Java 25 or newer of your own, set
`IDM_TEST_JAVA_HOME` to its folder (the one that holds `bin/java` and `release`). `JAVA_HOME` is not read,
because it often points at an older JDK used by other tools. On Windows the value may be quoted.

```bash
export IDM_TEST_JAVA_HOME=~/.sdkman/candidates/java/25.0.3-zulu
idmtest --version
```

### The plain jar

Anyone with Java 25 can run the executable jar directly, with the same commands:

```bash
java -jar idm-test-runner-<version>.jar run --profile lab
```

To give the jar extra JDBC drivers, add `-Dloader.path=<folder with jars>` before `-jar`.

## Create a workspace

A workspace is the folder that holds your scenarios, settings and results. Create one:

```bash
idmtest init my-tests
```

`init` prints `created <file>` for each file it writes, and `skipped <file> (already exists)` for each one
it leaves alone; it never overwrites. It ends with `workspace ready: <dir>`. Without a folder name it
uses the current folder.

```
my-tests/
  scenarios/                 your scenarios: *.yaml and *.yml, sub-folders allowed
  config/application-lab.yml connection settings for the "lab" profile (every key commented out)
  secrets.env.example        the passwords the example settings refer to
  drivers/README.md          where extra JDBC drivers go
  .gitignore                 lists secrets.env and reports/
  README.md
```

`scenarios/example.yaml` creates a user in the directory, checks it and cleans up. A scenario's name is its
path under `scenarios/`, with `/` separators (`users/create.yaml`), and scenarios run sorted by that name.

Every command works on the current folder, or on another one with `--workspace <dir>`. A folder without
a `scenarios/` folder is not a workspace. A path with spaces is fine.

## Connect to your lab

The connection settings are the framework's usual `idm.test.*` keys, in one file per environment:
`config/application-<profile>.yml`. The profile name is yours: `lab`, `dev`, `uat`. Edit
`config/application-lab.yml`: for each system you use, replace the placeholder values and remove the
`# ` in front of its lines. The rest of the keys are described in the
[Configuration guide](../reference/configuration.md).

```yaml
idm:
  test:
    ldap:
      url: ldap://directory.example.com:389
      base-dn: o=data
      user-dn: cn=admin,o=data
      password: ${IDM_TEST_LDAP_PASSWORD}
```

Settings that every profile shares can go in an optional `config/application.yml`.

Never write a password in these files. Refer to it as `${NAME}` and keep the value in `secrets.env`, in
the workspace folder. Copy the example and fill it in:

```bash
cp secrets.env.example secrets.env
```

```
# One KEY=VALUE per line; '#' starts a comment; blank lines are fine.
IDM_TEST_LDAP_PASSWORD=the-real-password
```

`secrets.env` must be UTF-8. The key is used exactly as written, and everything after the first `=` is the
value, so a value may contain `=`.

Secret values are masked as `****` on the console, in `run.log`, in `results.json` and in `report.html`.
A secret value is each `secrets.env` value (and the environment variable that overrides it, the value the
run actually uses), and the value of every environment variable whose name contains `password`, `passwd`,
`pwd`, `secret`, `token`, `authorization`, `credential`, `apikey`, `api_key`, `api-key` or `cookie`, in
any case (except `PWD` and `OLDPWD`, which hold folders). Use real passwords: a value shorter than 4
characters is not masked.

Never commit `secrets.env`. `init` puts it, and `reports/`, in `.gitignore`, and `run` warns when Git
tracks it (see Troubleshooting).

When the same setting is given in several places, the first of these wins:

1. command-line options (`--profile`, `--locale`)
2. real environment variables
3. `secrets.env`
4. `config/application-<profile>.yml`
5. `config/application.yml`
6. the runner's built-in defaults (timeouts and pool sizes; no URLs and no credentials)

So an environment variable overrides `secrets.env`, which is how a CI job supplies its own passwords. A
setting that no layer gives stays missing, and `run` reports it before any scenario runs.

Settings come only from these layers. An `application.yml` or a `config/` folder in the folder you run
`idmtest` from is never read: `--workspace <dir>` uses that workspace's settings and no other's.

The language of the console and the report is, in the same first-wins order: `--locale`, the environment
variable `IDM_TEST_LOCALE`, `-Didm.test.locale`, the profile's `idm.test.report.locale`, then English.

During a run, the masked `run.log` is the only log output (the console shows it too with `--verbose`).
`logging.file.*` and `logging.config` in workspace settings are ignored, so no settings file can send the
log, or a secret in it, somewhere unmasked.

A profile name may not contain `/`, `\` or `..`.

## Validate and run

```bash
idmtest validate
```

`validate` checks every scenario (YAML shape, step names and parameters, expressions) and connects to
nothing. It prints `✔ <name>` or `✖ <name> — <problem>` for each scenario and a summary:
`<n> scenarios: <v> valid, <i> invalid`.

```bash
idmtest validate --scenario 'users/*' --locale es --profile lab
```

With `--profile`, `validate` also checks that the profile's settings file exists.

```bash
idmtest run --profile lab
```

`run` needs `--profile`. It checks the scenarios, checks that a JDBC driver exists for every database they
use, refuses a run that would test nothing real (a missing or placeholder URL or credential), and then
runs the scenarios in name order:

```
✔ users/create.yaml  2.1 s
✖ users/disable.yaml  5.3 s — <what failed, in the run's language>
2 scenarios: 1 passed, 1 failed · report: reports/20261016-093012-lab/report.html
```

An invalid scenario file counts as a failed scenario, and the others still run.

| Option | Meaning |
|---|---|
| `--workspace <dir>` | the workspace folder (default: the current folder) |
| `--profile <name>` | the settings profile: `config/application-<name>.yml` |
| `--scenario <glob>` | only scenarios whose name matches |
| `--locale <en\|es>` | the language of messages and reports |
| `--verbose` | `run`: also show the framework log on the console, and stack traces for start-up errors |
| `--help`, `-h` | the usage text |
| `--version` | the version |

`--scenario` takes a glob matched against the scenario's path, with or without the extension.
Quote it, so your shell does not expand it:

```bash
idmtest run --profile lab --scenario 'users/*'
idmtest run --profile lab --scenario 'roles.grant-v2'
```

Exit codes:

| Code | Meaning |
|---|---|
| `0` | every selected scenario passed (or validated) |
| `1` | at least one scenario failed or did not validate |
| `2` | the run could not start: bad option, not a workspace, missing profile, unsupported locale, malformed `secrets.env`, missing driver, a refusal from the checks, no matching scenario |

## Results

Every run writes its own folder, `reports/<run-id>/`, where `<run-id>` is `yyyyMMdd-HHmmss-<profile>`:

| File | Content |
|---|---|
| `report.html` | the report: open it in a browser |
| `results.json` | the structured results |
| `run.log` | the framework log, with secret values masked, and the JDBC driver jars that were loaded |

`results.json` and `report.html` are rewritten after each scenario, so a run that stops half-way still
leaves a readable report of the scenarios that ran.

## Other databases

PostgreSQL, Oracle (`ojdbc11`) and SQL Server (`mssql-jdbc`) drivers are included. A jar in `drivers/`
never replaces one of these; for those databases the included driver is used, and `run.log` says so.
For any other database (MySQL, DB2, ...), put its driver jar in one of two places:

- `<workspace>/drivers/`, for that workspace only
- `<install>/lib/drivers/`, for every workspace

```bash
cp mysql-connector-j-9.0.0.jar my-tests/drivers/
```

Then name the database as usual under `idm.test.databases` with its `jdbc-url`.

## Troubleshooting

Each message below stops the run with exit code 2. `<...>` stands for your value.

| Message | What to do |
|---|---|
| `not a workspace: <dir> has no scenarios/ folder (run 'idmtest init <dir>' to create one)` | You are in the wrong folder. Go to the workspace, pass `--workspace <dir>`, or run `idmtest init <dir>`. |
| `no settings for profile '<p>': config/application-<p>.yml not found` | Create the file in the workspace's `config/` folder, or check the spelling of `--profile`. |
| `run needs --profile <name>` | Add `--profile <name>` to `run`. |
| `profile name '<p>' may not contain '/', '\' or '..'` | Use a plain name such as `lab`. |
| `secrets.env line <n> is not KEY=VALUE` | Fix line `<n>`: `KEY=VALUE`, a `#` comment or a blank line. The line's content is never printed. |
| `secrets.env is not UTF-8 text; save it as UTF-8` | Re-save the file as UTF-8 in your editor. |
| `secrets.env is not a file` | Something other than a file is named `secrets.env`; remove it. |
| `no scenario matches '<glob>'` | The glob matches no scenario. The pattern is compared with the path under `scenarios/`, such as `users/create`. |
| `unsupported report locale '<x>'; supported: en, es` | Use `en` or `es` in `--locale`, `IDM_TEST_LOCALE` or `idm.test.report.locale`. |
| `no JDBC driver for '<url>' (database '<name>'); put the driver jar in <workspace>/drivers/` | Put that database's driver jar in `<workspace>/drivers/` or `<install>/lib/drivers/`. |
| `the run could not start: <reason>` | The settings could not be loaded; the reason names the problem. Add `--verbose` for the stack trace. |
| `IDM_TEST_JAVA_HOME points to Java <n> (<path>); idmtest needs Java 25 or newer` | Point `IDM_TEST_JAVA_HOME` at a Java 25 or newer, or unset it to use the bundled runtime. |
| `IDM_TEST_JAVA_HOME points to <path>, which is not a Java installation; idmtest needs Java 25 or newer` | The folder must hold `bin/java` and a `release` file. |
| `the bundled Java runtime in <path> is missing or broken; reinstall idmtest or set IDM_TEST_JAVA_HOME to a Java 25+` | The ZIP's `runtime/` folder is damaged: unzip `idmtest` again, or point `IDM_TEST_JAVA_HOME` at your own Java 25 or newer. |

Refusals from the checks before a run name the setting to change, for example `<setting> is not set. Set
<variable> or activate a profile that defines it.`, or that the settings are still the placeholder values.
They also tell you when the embedded `test` profile is active, which would test nothing real:
`Active profiles [test] include 'test', the embedded test configuration. Run with --profile set to the
profile that names your real environment, for example --profile lab.`

One message does not stop the run:

| Message | What to do |
|---|---|
| `secrets.env is tracked by Git; remove it with 'git rm --cached secrets.env'` | Run that command, then commit. Change any password that was already committed. |
