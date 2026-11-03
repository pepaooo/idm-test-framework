# Registered command steps — design

**Ticket:** ITF-74. **Decided with:** jangulo, 2026-11-04. **Security model:**
[ADR 0008](../architecture/decisions/0008-registered-commands-and-machine-approval.md).

## 1. Intent

Some IdM checks and setup can only be done with a command: `dxcmd` to start or stop a driver or read
its state, restarting a service, a site-specific script that prepares test data. Today a scenario can
do none of this.

This design adds two step types: `exec.run`, an action, and `exec.verify`, a retryable assertion. Both
run **registered** commands on the machine that runs the scenarios. The ticket is in phase 2 ("scale
and reuse") of the Professional Services roadmap.

The hard part is not running a process; it is that a scenario must not be able to run arbitrary code
on a consultant's machine. Section 3 is the core of this design.

## 2. Goals and boundaries

- A scenario runs a command **by name** and passes **named parameters**. It never gives an executable
  or a command line.
- Commands are defined in the workspace settings (`idm.test.commands.<name>`), which a team can share
  through Git, and run only after the person on each machine has **approved the exact definition**.
- No shell: the executable and its arguments go to the operating system as a list.
- Every command has a timeout and an output cap; stdin is closed.
- Output and command lines are masked like all other evidence: no secret value in any response,
  event, log, report or DOM.
- Windows, Linux and macOS, local commands only. Differences between operating systems (paths,
  `.exe`, encodings, exit codes) live in each machine's profile, never in scenarios. On Windows a
  batch file (`.cmd`, `.bat`) is refused (see §10).
- The engine stays Spring-free.
- **Out of scope:** commands on another host over SSH (a follow-up ticket: credentials from
  `secrets.env`, a pinned host key, its own design); interactive commands; shell features such as
  pipes, redirection, globbing and `&&` (register a script that does them); the Iterator action, which
  stays deferred because it would change ADR 0001's rule that the step list is the execution order.

## 3. Trust: registered commands, approved on each machine

A workspace's `config/application-<profile>.yml` is committed and shared (its `.gitignore` excludes
only `secrets.env` and `reports/`). A pulled commit can therefore add both a command definition and a
scenario that calls it. Registration in the settings alone, or an opt-in flag in the settings, would
not stop a harmful shared workspace: both arrive together.

So the decision to run a definition is made **outside the workspace**, on each machine:

- **Approvals** are kept in `~/.idmtest/command-approvals.json` (the runner's per-user folder, beside
  its recent-workspaces list). An approval is keyed by the workspace's real path, the profile and the
  command name, and holds the SHA-256 of the definition **as written**: executable, arguments,
  parameter patterns, environment, working directory, exit codes, timeout, output cap and encoding,
  with each `${NAME}` counted by its name, never its value. Rotating a secret does not ask again;
  changing anything else does.
- A definition with no approval, or whose hash differs from its approval, is **unapproved**. A run
  whose selected scenarios use an unapproved command is refused before any step runs (a
  real-environment guard), naming the commands and how to approve them.
- **`idmtest commands approve --profile <p>`** lists the workspace's definitions for that profile,
  exactly as they will run (executable, every argument with `${NAME}` shown as the name, each
  parameter and its pattern, environment names), marks the new and changed ones, and asks y/N for
  each. `--yes` approves without asking, for a pipeline whose operator has reviewed the definitions.
  `idmtest commands list --profile <p>` shows the same without changing anything; `revoke` removes
  approvals.
- **In the workbench**, each command is a lab system (`command:<name>`) showing its exact definition,
  its state (approved, changed since approval, not approved) and **Approve on this machine**. A
  scenario that uses commands carries a visible marker in the tree, and a run that includes one names
  the commands it will run before it starts. The workbench never approves on its own.
- A workspace with no `idm.test.commands` has the feature off: a run whose scenarios use the two
  steps is refused before any step (the command is not registered), like any unknown name.

Approval is per machine and per user, not per run: once approved, a definition runs until it changes
or is revoked.

## 4. Settings

```yaml
idm:
  test:
    commands:
      driver-state:
        executable: /opt/novell/eDirectory/bin/dxcmd
        args: ["-user", "admin.sa.system", "-password", "${IDM_DXCMD_PASSWORD}",
               "-getstate", "{driver}"]
        params:
          driver: "[A-Za-z0-9 ._=,-]+"
        env: {}
        working-dir: ""
        timeout: 30s
        max-output: 64KB
        exit-codes: [0]
        encoding: UTF-8
        label: Driver state
```

| Key | Default | Meaning |
|---|---|---|
| `executable` | — (required) | an absolute path, or a name resolved on `PATH` when the process starts |
| `args` | `[]` | the arguments, in order. An argument that is exactly `{name}` is replaced by the parameter `name`; braces anywhere else are literal. `${NAME}` reads `secrets.env` or the environment, as in all settings |
| `params` | `{}` | each parameter a scenario may pass, with a pattern (Java regular expression) its value must match **in full**. Every `{name}` in `args` must be declared here, and every declared name used |
| `env` | `{}` | extra environment variables; values may use `${NAME}` |
| `working-dir` | the workspace folder | where the process starts; must exist |
| `timeout` | `60s` | after this, the process and its descendants are killed |
| `max-output` | `64KB` | bytes kept of stdout and of stderr each; the rest is drained and counted, not kept |
| `exit-codes` | `[0]` | the exit codes `exec.run` treats as success |
| `encoding` | the platform's | how stdout and stderr are decoded |
| `label` | — | the name the workbench shows |

A parameter value can never reach the executable, `env`, `working-dir`, a `${…}` reference or a
secret: only an `args` entry that is exactly `{name}`. Secrets come from the settings only. An
argument is visible in the operating system's process list while the command runs; the docs say so
and recommend an environment variable or a password file where the tool allows one.

## 5. Steps

```yaml
- exec.run:
    command: driver-start
    params: { driver: "cn=HR,cn=DS,o=system" }
    as: started                       # { exitCode, stdout, stderr, durationMillis }

- exec.verify:
    command: driver-state
    params: { driver: "cn=HR,cn=DS,o=system" }
    expect:
      exitCode: 0
      stdout: { contains: "2" }       # equals | contains | matches, and ignoreCase
      stderr: { equals: "" }
    wait: { timeout: 2m, poll: 5s }
```

- **`exec.run`** (action): required `command`; optional `params`. Succeeds when the exit code is one of
  the definition's `exit-codes`; otherwise a finding, with the output as evidence. Captures
  `{ exitCode, stdout, stderr, durationMillis }`, each output masked and cut at `max-output`.
- **`exec.verify`** (retryable assertion): required `command`, `expect`; optional `params`, `wait`.
  Runs the command again on each attempt. `expect` takes `exitCode` (a number or a list), `stdout`
  and `stderr` (each `equals`, `contains` or `matches`, optionally `ignoreCase: true`). An empty
  `expect`, unknown keys, or a matcher with no condition are final failures before anything runs, as
  in `db.verify`. Every expectation is compared, so a finding shows each one. Output is compared with
  trailing line breaks removed and `\r\n` read as `\n`, so the same check works on Windows.
- Both are usable in `cleanup:` only as `exec.run` (an action), for example to stop a driver the
  scenario started.

**Failure classification:**

| Situation | Outcome |
|---|---|
| unknown command, unapproved or changed definition | refused before the run (guard); at step time a final setup failure |
| unknown parameter, missing parameter, value not matching its pattern | final setup failure |
| executable not found, not executable, working directory missing | final setup failure |
| timeout | setup failure; retried by `exec.verify` |
| exit code not in `exit-codes` (`exec.run`) | finding |
| `expect` not met (`exec.verify`) | finding; retried |

## 6. How it is built

- **New module `idm-connector-exec`:** `CommandProperties` and `CommandConfiguration` in `config`
  (Spring), `CommandConnector` in `client` (no Spring). It builds the argument list, starts the process
  with `ProcessBuilder` (stdin from the null device), reads stdout and stderr on their own threads
  into capped buffers, enforces the timeout with `Process.destroyForcibly` on the process and every
  `ProcessHandle` descendant, and returns `OperationResult` with a `CommandProblem` the engine words
  in any language. It computes a definition's hash for approvals.
- **`idm-test-engine`:** `ExecRunStep`, `ExecVerifyStep`, a `CommandEvidence` record (command name,
  argument list with secrets and secret-named parameters masked, exit code, duration, timed out,
  stdout and stderr masked and capped), messages in English and Spanish. The expectation checks
  follow `RowExpectations`' pattern.
- **`idm-test-wiring`:** registration in `StepRegistry` (19 step types); `ScenarioNeeds` gains the
  commands used; a guard refuses a run when a needed command is not defined or not approved. Approvals
  are read through a small interface the runner implements, so the scenarios module's runners (which
  have no user approvals) refuse every command unless a test supplies approvals.
- **`idm-test-runner`:** the approvals file; `idmtest commands list|approve|revoke`; command lab
  systems with Approve, the scenario marker and the pre-run notice in the workbench; `SecretMask`
  covers `CommandEvidence`.
- **CI:** a new job on `windows-latest` and `macos-latest` builds what the exec tests need and runs
  them against `java -version`, which every runner has; the Ubuntu job keeps the full build.

## 7. Verification

- **Connector tests** on each CI operating system: argument placement and patterns, no shell
  (an argument with `;`, `&&`, `|`, `$(...)` or `%PATH%` arrives unchanged), exit codes, stdout and
  stderr kept apart, output cap, timeout killing a child process, encodings, a missing executable or
  working directory, the hash ignoring secret values and changing with anything else.
- **Step tests:** each row of §5's failure table, the vacuous-`expect` safeguards, capture, a wait
  that passes when the output changes, masking of a secret argument and of secret output.
- **Guard and approval tests:** an unregistered command and an unapproved or changed definition
  refused before any step; a workspace without `idm.test.commands` refused; approve, list and revoke.
- **Workbench:** lab systems with Approve, the marker and the notice (Vitest and controller tests);
  no secret in any response or event.
- **Default suite:** a scenario that runs and verifies a harmless registered command (`java -version`,
  approved by the test setup).

## 8. Documentation

ADR 0008; the authoring guide and reference (the two steps, the failure table); the configuration
reference (`idm.test.commands`, the process-list warning); the runner guide (`idmtest commands`); the
workbench guide (command systems and approval); the capability matrix; README and `CLAUDE.md`.

## 9. Done means

- This design, ADR 0008 and a plan in `docs/plans/`.
- The two steps registered, validated and documented.
- A harmless registered command runs in tests on Windows, Linux and macOS CI, and an unregistered
  command and an unapproved definition are refused.

## 10. As built (corrections)

- **Windows arguments.** `ProcessBuilder` does not escape a double quote inside an argument, so a
  Windows program would split it differently from what was written. The connector refuses any
  argument or parameter value containing `"` on Windows, and refuses batch files (`.cmd`, `.bat`),
  which Windows runs through `cmd.exe` and would read again as a command line. A Java child on Windows
  receives its arguments in the ANSI code page; the configuration reference says so.
- **Secret output.** Besides masking the arguments a secret-named parameter filled, the evidence masks
  those values (four characters or more) wherever the command echoed them in stdout or stderr.
- **Approvals in the JUnit runners.** `RealEnvironmentScenarios` refuses every command. The embedded
  suite and `ContainerScenarios` register `java-version` and approve only the definition their context
  bound (`TestCommands`), so a test cannot approve a definition it did not register.
- **Workbench.** An unapproved, changed or unregistered command marks its lab entry missing, which
  blocks the runs that use it through the lab chip, before the guard would refuse them. The lab binds
  commands as a run does, and keeps the answer until a file under `config/` or `secrets.env` changes.
- **Timeout.** A timed-out command is a setup failure with exit code `-1` in the evidence; its message
  gives the timeout in seconds.
