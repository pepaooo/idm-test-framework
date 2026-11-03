# ITF-74 Registered Command Steps Implementation Plan

> Steps use checkbox (`- [ ]`) syntax for tracking. Work one task at a time; each task ends with its
> own tests green and a commit.

**Goal:** Add `exec.run` and `exec.verify`, which run commands registered by name in the workspace
settings, with pattern-checked parameters, no shell, a timeout and an output cap, and only after the
person on the machine has approved the exact definition (ADR 0008).

**Architecture:** A new connector module, `idm-connector-exec`, owns command definitions, argument
building, process execution with capped output and a killed process tree on timeout, and the
definition hash. Two engine steps use it, as `db.*` uses `MultiDatabaseConnector`. Approvals are read
through an interface in the wiring module, which the runner implements over
`~/.idmtest/command-approvals.json`; a guard refuses unapproved commands before a run. The runner adds
`idmtest commands`, and the workbench adds command lab systems with an Approve button.

**Tech Stack:** Java 25, `ProcessBuilder` and `ProcessHandle`, Spring Boot 4.1.1 configuration
properties (connector `config` package only), JUnit 5, AssertJ, Maven; React + TypeScript + Vitest;
GitHub Actions.

**Spec:** `docs/specs/2026-11-04-registered-command-steps-design.md` · **ADR:**
`docs/architecture/decisions/0008-registered-commands-and-machine-approval.md`

## Global Constraints

- **Nothing runs without an approval.** No code path starts a process for a definition whose current
  hash is not approved on this machine. The scenarios module's runners refuse every command unless a
  test supplies approvals.
- **No shell, ever.** No `sh -c`, `cmd /c`, `bash`, `powershell` or `Runtime.exec(String)`. A parameter
  value is exactly one argument and never reaches the executable, `env`, `working-dir`, a `${…}`
  reference or a secret.
- No secret value in any response, event, log, report, results file or DOM: arguments, environment
  values and output are masked, and secret-named parameters too.
- Every process gets a timeout, a capped output per stream and a closed stdin; on timeout the whole
  process tree is killed and the step never hangs.
- The engine stays Spring-free; `StepType.execute` never throws.
- Integration tests run real processes (`java -version`, and a small Java test program run with the
  current JVM), never mocks of our own code. Tests must pass on Windows, Linux and macOS.

## Lessons carried over from ITF-73

- `StepRegistry.of` has callers the compiler finds and one it does not:
  `ScenarioSupportIsolationTest` calls it by reflection. Update all of them in the wiring task.
- `ScenarioReferenceTest` fails until every registered step has a section in
  `docs/reference/writing-scenarios.md` with a matching parameter table, so the step sections are
  written with the wiring task.
- `CatalogTest` fails on a catalog key no code uses: add message keys with the code that uses them.
- `Evidence` is sealed and `SecretMask` and `EvidenceHtml` switch over it: `CommandEvidence` must be
  handled in both in the same task.
- Tests that check the order of checks must use ordered maps (YAML is ordered; `Map.of` is not).
- Spring's enum binding is lenient; check values the runner cannot bind with Spring's binder.
- The runner's test resources keep their own copies of corpus scenarios.

## Review Focus

- **Approval bypass:** a changed definition (any field except a secret's value), a definition for
  another profile or another workspace path, and a symlinked workspace path must all count as
  unapproved (Tasks 1, 5, 6).
- **Injection:** parameter values with spaces, quotes, `;`, `&&`, `|`, `$(...)`, `%PATH%`, `^` and a
  leading `-` arrive as one literal argument or are refused by their pattern; a `{name}` inside a
  longer argument is literal (Task 1).
- **Windows:** `.cmd`/`.bat` executables are refused (they run through `cmd.exe`, which reparses
  arguments); a double quote in any argument is refused (a value could otherwise become several
  arguments); `CreateProcess` quoting of spaces and backslashes; `\r\n` output (Task 1).
- **Hangs:** a command that never ends, one that fills stdout without stopping, one that leaves a child
  running: each ends within the timeout and its tree is killed (Task 1).
- **Masking:** a `${SECRET}` argument, a secret echoed on stdout, a secret-named parameter (Tasks 3, 7).

---

## File map

- `pom.xml`: module `idm-connector-exec`.
- `idm-connector-exec/`: `config/CommandProperties.java`, `config/CommandConfiguration.java`;
  `client/CommandConnector.java`, `client/CommandDefinition.java`, `client/CommandResult.java`,
  `client/CommandProblem.java`, `client/DefinitionHash.java`, `client/OutputBuffer.java`; tests and a
  test helper program `src/test/java/.../TestProgram.java` (prints, sleeps, exits with a code, spawns a
  child) run with the current JVM.
- `idm-test-engine/`: `steps/ExecRunStep.java`, `steps/ExecVerifyStep.java`,
  `steps/OutputExpectations.java`, `steps/CommandProblems.java`; `spi/CommandEvidence.java`;
  `spi/Evidence.java`; `result/EvidenceHtml.java`; both message catalogs.
- `idm-test-wiring/`: `CommandApprovals.java` (interface: is this definition approved here?),
  `StepRegistry.java`, `ScenarioNeeds.java` (commands used), `RealEnvironmentGuards.java`.
- `idm-test-scenarios/`: registry callers (including `ScenarioSupportIsolationTest`),
  `BaseIntegrationTest` (a test approval of a harmless command), `application-test.yml`
  (`idm.test.commands.java-version`), `scenarios/exec-harmless-command.yaml`.
- `idm-test-runner/`: `commands/ApprovalStore.java` (`~/.idmtest/command-approvals.json`),
  `commands/CommandsCommand.java` (`list`, `approve`, `revoke`), `IdmTest.java`, `CommandLine.java`,
  `run/RunService.java`, `SecretMask.java`, `ui/lab/LabSettings.java` (`command:<name>`),
  `ui/api/` (approve endpoint), front end (lab system with Approve, scenario marker, pre-run notice).
- `.github/workflows/ci.yml`: a job on `windows-latest` and `macos-latest` for the exec tests.
- Docs: authoring guide and reference, configuration reference, runner and workbench guides,
  capability matrix, README, `CLAUDE.md`.

---

### Task 1: `idm-connector-exec`

**Interfaces produced:**

```java
public final class CommandConnector {
    CommandConnector(Map<String, CommandDefinition> commands, Path defaultWorkingDir);
    Optional<CommandDefinition> command(String name);
    Set<String> commandNames();
    OperationResult<CommandResult> run(String name, Map<String, String> params);
    String hash(String name);                      // of the definition as written
}
record CommandDefinition(String executable, List<String> args, Map<String, String> params,
                         Map<String, String> env, String workingDir, Duration timeout,
                         long maxOutput, List<Integer> exitCodes, String encoding) {}
record CommandResult(int exitCode, String stdout, String stderr, long stdoutBytes,
                     long stderrBytes, long durationMillis, boolean timedOut,
                     List<String> argv) {}
```

`CommandProblem` kinds: `NOT_CONFIGURED`, `BAD_DEFINITION` (undeclared or unused `{name}`, bad
pattern, `.cmd`/`.bat` on Windows), `UNKNOWN_PARAM`, `MISSING_PARAM`, `PARAM_REJECTED` (no full
match), `NOT_FOUND` (executable), `NO_WORKING_DIR`, `START_FAILED` — all `CONFIGURATION_ERROR`;
`TIMED_OUT` — `TIMEOUT`.

- [x] **Step 1: Test program.** A small `TestProgram` main (in test sources) that, by argument, prints
  each argument on its own line, writes N bytes to stdout or stderr, sleeps, exits with a code, prints
  an environment variable, or starts a child that sleeps. Tests run it as
  `ProcessHandle.current().info().command()` + `-cp` + test classes, so they need no OS tools.
- [x] **Step 2: Failing tests** (`CommandConnectorTest`): argument placement; each parameter value
  arriving as one literal argument for every Review Focus injection string; `{x}` inside a longer
  argument staying literal; undeclared/unused/unknown/missing parameters; a value not matching in full
  (`^…$` implied); exit codes; stdout and stderr apart; `max-output` with the byte count kept;
  `encoding`; `env` added to the inherited environment; `working-dir` default and missing; a missing
  executable; timeout killing a sleeping child (assert the child is gone); a flood of output not
  blocking the process; `.cmd`/`.bat` refused on Windows only.
- [x] **Step 3: Hash tests** (`DefinitionHashTest`): stable across runs and map orders; changes with
  every field; unchanged when only a `${NAME}`'s value changes (hash the raw settings, not resolved
  values — `CommandProperties` keeps the raw text with references).
- [x] **Step 4: Implement** until green. Read both streams on their own threads; wait with the timeout;
  on timeout `descendants().forEach(destroyForcibly)` then `destroyForcibly`, then join the readers.
- [x] **Step 5: Binding test** (`CommandPropertiesTest`), including Spring's lenient binding of
  `timeout` and `max-output`.
- [x] **Step 6:** `./mvnw test -pl idm-connector-exec -am`; commit `feat(exec): command connector (ITF-74)`.

**As built:** `CommandConnector(Map<String, RegisteredCommand>, Path)`; a `RegisteredCommand` holds the
definition as it runs and the hash of the definition as written. A command that runs, whatever its exit
code, or that times out, is a successful `OperationResult` whose `CommandResult` says so (`timedOut`,
exit code `-1`): the steps decide what that means, so there is no `TIMED_OUT` problem kind. Settings are
bound twice in `CommandConfiguration.registered`: normally, to run, and with a binder that resolves no
placeholders, for the text fields the hash covers (executable, args, params, env, working-dir); the
other fields are hashed as bound. The working directory defaults to the property `idm.test.workspace`,
which the runner sets in Task 6. Tests set `LC_ALL=C.UTF-8` where non-ASCII arguments are passed (as the
CI runners have); stderr assertions count bytes, because a JVM started with `JAVA_TOOL_OPTIONS` prints a
notice there.

### Task 2: CI on Windows and macOS

- [x] Add a job `exec-on-os` to `ci.yml`, matrix `windows-latest`, `macos-latest`, Java 25 Temurin:
  `./mvnw -B test -pl idm-connector-exec -am -Dtest='Command*Test'` (only the exec tests, so a
  Windows quirk elsewhere cannot fail it), in `bash` on every runner. Keep the Ubuntu job unchanged.
- [x] Commit `ci: run the exec connector's tests on Windows and macOS (ITF-74)`; open a draft PR early so
  CI runs on all three systems before the steps are built on top.

**Found by the first Windows run:** `ProcessBuilder` on Windows does not escape a double quote inside an
argument, and the program re-parses its command line, so a value `a" "b` would arrive as two arguments.
The connector now refuses a double quote in a parameter value (`PARAM_UNSAFE`) or a fixed argument
(`BAD_DEFINITION`) on Windows. A Java program on Windows also reads its arguments in the system code
page, so the tests' non-ASCII values stay within it, and the test program writes UTF-8.

CI green on all three systems at `e33b071` (draft PR #3).

### Task 3: `CommandEvidence` and messages

- [x] **Failing tests:** `CommandEvidence.of(name, argv, result)` masks secret values passed in, and
  arguments that follow a secret-looking option (`-password`, `--token`, `/p:`), and secret-named
  parameters; caps output with a truncation note; is idempotent (as `FileEvidence`); renders in HTML
  and JSON; `SecretMask` masks it (runner test).
- [x] **Implement:** `Evidence` permits `CommandEvidence` (JSON kind `command`); `EvidenceHtml` and
  `SecretMask` handle it; only the `evidence.command.*` keys now.
- [x] Commit `feat(engine): command evidence (ITF-74)`.

**As built:** `CommandEvidence(command, argv, exitCode, durationMillis, timedOut, stdout, stderr,
stdoutBytes, stderrBytes)`, plain values only (the engine's evidence does not know connector types);
factories `ran(…, secretPositions, …)` and `notStarted(name)`. Masked: the argument after a
secret-looking option (`-password x`), an inline value (`--token=x`, `/ApiKey:x`), and the positions
a secret-named parameter filled; output cut at `MAX_BODY_CHARS` per stream. The front-end type comes
with Task 8.

### Task 4: The two steps

- [x] **Failing `ExecStepsTest`** over a real connector and the test program: every row of the spec's
  §5 failure table; `exec.run` capture; `exec.verify` `exitCode` (number or list), `stdout`/`stderr`
  `equals`/`contains`/`matches`/`ignoreCase`, `\r\n` and trailing line breaks normalised; the
  vacuous-`expect` safeguards; a wait through the engine that passes when the program's output changes
  (the program reads a file the test rewrites); cleanup allows `exec.run` only.
- [x] **Implement** `ExecRunStep`, `ExecVerifyStep`, `OutputExpectations`, `CommandProblems`, and the
  `step.exec.*` keys in both catalogs. The steps take an approval check (a function from command name
  and hash to yes/no) and refuse an unapproved command as a final setup failure, so a missed guard
  still cannot run anything.
- [x] Commit `feat(engine): exec.run and exec.verify (ITF-74)`.

**As built:** the approval check is `CommandApproval` (engine `spi`, `approved(command, hash)`, with
`NONE`), passed to both steps; Task 5 adapts the wiring's approvals to it. Both steps check `params` and
`expect` before the approval, so a malformed scenario is reported as such, not as unapproved, and before
anything runs. `matches` must match the whole normalised output and `ignoreCase` applies only to
`equals`/`contains`, as in `ldap.verify`. Found while testing: a tool may echo its arguments, so a
secret-named parameter's value (4 characters or more) is also masked in stdout and stderr.

### Task 5: Wiring, guard, reference docs

- [x] `CommandApprovals` interface (`approved(workspace, profile, command, hash)`), with
  `CommandApprovals.NONE` (approves nothing).
- [x] `StepRegistry.of` takes the connector and the approvals: update every caller, including the
  reflective one in `ScenarioSupportIsolationTest`; 19 step types.
- [x] `ScenarioNeeds` gains `commands`; the guard refuses an undefined or unapproved command, naming
  each and the `idmtest commands approve` line; tests for both and for a workspace without
  `idm.test.commands`.
- [x] Write the `exec.run` and `exec.verify` sections of `docs/reference/writing-scenarios.md`
  (`ScenarioReferenceTest`).
- [x] Commit `feat(wiring): register exec steps and the command guard (ITF-74)`.

**As built:** no second interface: the wiring takes the engine's `CommandApproval` (`approved(command,
hash)`), which the runner will bind to its workspace and profile in Task 6. Until then `RunService`
passes `CommandApproval.NONE`, as the scenario module's runners do; the embedded suite will approve its
harmless command in Task 7. The guard names every unapproved command at once. Verified locally: wiring
88, scenarios 104, runner 393 Java and 391 Vitest tests.

### Task 6: Approvals in the runner

- [x] **Failing tests:** `ApprovalStore` over a temp `user.home` (atomic write, a corrupt file read as
  "nothing approved" with a warning, never as "everything"); keys use the workspace's real path, so a
  symlink to an approved workspace is a different key; `idmtest commands list|approve|revoke`
  (interactive y/N per new or changed definition, `--yes`, exit codes, Spanish); `idmtest run` refused
  for an unapproved command and running after approval; a changed definition refused again.
- [x] **Implement**, and pass the store as `CommandApprovals` in `RunService`.
- [x] Commit `feat(runner): command approvals and idmtest commands (ITF-74)`.

**As built:** `ApprovalStore` in `~/.idmtest/command-approvals.json`, keyed by the workspace's real
path, the profile and the command, with the definition's hash; a corrupt file approves nothing.
`idmtest commands list|approve|revoke --profile <p> [--yes] [name …]` binds the profile's settings by
starting the same lazy Spring context a run uses (logging off), and shows each definition as written:
`RegisteredCommand` now keeps the definition as written beside the one that runs, so `${NAME}` shows
by name and no secret value is printed. `approve` asks y/N (also s/sí) per new or changed command; end
of input is no. `RunService` asks the store for its workspace and profile, and sets
`idm.test.workspace`, where a command without `working-dir` starts.

### Task 7: Default-suite scenario

- [ ] `application-test.yml` registers `java-version` (executable: the current JVM's `java`, set by
  `BaseIntegrationTest`; args `["-version"]`; `exit-codes: [0]`); `BaseIntegrationTest` approves it in
  a test approvals object.
- [ ] `scenarios/exec-harmless-command.yaml`: `exec.verify` that stderr contains `version` (Java prints
  its version on stderr) and `exec.run` capturing it; a test that the same scenario is refused with
  `CommandApprovals.NONE`. Copy it to the runner's test resources if a runner test uses it.
- [ ] Commit `test(scenarios): harmless registered command (ITF-74)`.

### Task 8: Workbench

- [ ] **Back end:** `command:<name>` lab systems (fields shown read-only from the settings, plus the
  approval state: approved, changed, not approved); `POST /api/lab/commands/approve` for one command
  and profile, approving the definition hash the panel showed (409 if it changed since); usage per
  scenario for the tree marker; the run start refused with the guard's message for unapproved commands.
- [ ] **Front end:** the lab system with the full definition and **Approve on this machine**; a marker
  on scenarios that use commands; the run panel's notice naming the commands before a run; English and
  Spanish; Vitest and controller tests (no secret value in any response).
- [ ] Commit `feat(workbench): command systems and approval (ITF-74)`.

### Task 9: Documentation

- [ ] Authoring guide; reference §7 and §8; configuration reference (`idm.test.commands`, every key,
  the process-list warning); runner guide (`idmtest commands`); workbench guide; capability matrix
  (with limits: local only, no shell, approvals per machine); README; `CLAUDE.md`; spec corrections.
- [ ] Commit `docs: command steps (ITF-74)`.

### Task 10: Final check

- [ ] CI green on Ubuntu (full build), Windows and macOS (exec tests).
- [ ] By hand: a workspace with a registered command — refused before approval, approved with
  `idmtest commands approve`, run, changed, refused again; the same in the workbench.
- [ ] Re-read the Review Focus list against the tests that cover it.
