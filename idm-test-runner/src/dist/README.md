# idmtest

`idmtest` runs YAML test scenarios for Identity Management systems (LDAP, databases, REST and SOAP)
from a workspace folder. This ZIP contains everything it needs, including a Java 25 runtime.

## Install

1. Unzip the archive anywhere, for example `C:\Tools\idmtest` or `~/tools/idmtest`.
2. Add the `bin` folder to your `PATH` (optional; you can also call `bin/idmtest` by its full path).
3. Check it works:

   ```
   idmtest --version
   ```

### Windows: how to call it

Call `idmtest` without quotes, from a folder on `PATH` or as `C:\Tools\idmtest\bin\idmtest`. If the path
has spaces and needs quotes, include the extension: `"C:\My Tools\idmtest\bin\idmtest.cmd"`. A quoted
call without `.cmd` (`"idmtest"`) makes Windows report the wrong folder to the launcher, and it then
can't find its runtime.

### macOS: first start

The launcher is not signed. Once, after unzipping, remove the quarantine flag macOS puts on downloaded files:

```
xattr -dr com.apple.quarantine idmtest
```

(run it in the folder that contains the unzipped `idmtest` folder).

## First workspace

```
idmtest init my-tests
cd my-tests
idmtest validate
idmtest run --profile lab
```

- `init [<dir>]` writes a starter workspace: `scenarios/`, `config/application-lab.yml`, `secrets.env.example`.
- `validate` checks every scenario without connecting to anything.
- `run --profile <name>` runs the scenarios against the systems in `config/application-<name>.yml` and
  writes `reports/<run-id>/report.html`, `results.json` and `run.log`.

Common options: `--workspace <dir>`, `--scenario <glob>`, `--locale en|es`, `--verbose` (for `run`).
`idmtest --help` lists them all.

## The workbench

```bash
idmtest ui
```

`ui` opens the workbench in your browser: create or open a workspace, edit scenarios, fill in and test
the lab settings, run and read the findings, in one screen. It listens on this computer only
(`127.0.0.1`) and prints its address with a session token, in case the browser does not open. Options:
`--workspace <dir>` (open that workspace straight away), `--port <n>`, `--locale en|es`. Press Ctrl+C
in the console to stop it. No Node or other software is needed: the workbench is inside the jar.

Passwords and tokens go in `secrets.env` in the workspace (copy `secrets.env.example`), or in environment
variables. Do not commit `secrets.env` to Git.

Exit codes: `0` everything passed, `1` at least one scenario failed or did not validate, `2` the run could not start.

## JDBC drivers

PostgreSQL, Oracle (`ojdbc11`) and SQL Server (`mssql-jdbc`) drivers are included. For any other
database, put its driver jar in either:

- `lib/drivers/` in this install folder (for every workspace), or
- `drivers/` in the workspace (for that workspace only).

## Using another Java

By default `idmtest` uses the bundled `runtime/`. To use a different Java 25 or newer, set
`IDM_TEST_JAVA_HOME` to its folder (the one that contains `bin/java` and `release`).

## Licences

See `LICENSE-THIRD-PARTY` for the software included in this distribution and its licences.
