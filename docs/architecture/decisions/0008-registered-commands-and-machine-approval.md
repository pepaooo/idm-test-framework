# 0008 — Registered commands, approved on each machine

**Status:** Accepted direction (ITF-74). Design:
[registered command steps](../../specs/2026-11-04-registered-command-steps-design.md).

**Recorded:** 2026-11-04, decided with jangulo (ITF-74).

**Provenance:** ITF-74 brainstorm. Builds on [ADR 0007](0007-end-user-runner-and-local-workbench.md)
(workspaces, the runner and the workbench) and on ITF-57 (workspaces shared through Git).

## Context

Scenarios need to run commands such as `dxcmd`. A workspace is shared through Git, and its settings
(`config/application-<profile>.yml`) are committed. A scenario that could run any command, or a
settings file that could both define and enable commands, would let anyone who can push to a shared
workspace run code on every consultant's machine that pulls it.

## Decision

1. **Registered commands only.** Commands are defined by name in the settings
   (`idm.test.commands.<name>`): executable, fixed arguments, and named parameters each with a pattern
   its value must match in full. A scenario names a command and passes parameters; it never gives an
   executable or a command line.
2. **No shell.** The executable and its arguments go to the operating system as a list. A parameter
   value is always exactly one argument, and never reaches the executable, the environment, the
   working directory or a secret.
3. **Approved on each machine.** A definition runs only after the person on the machine approves it.
   Approvals live outside the workspace (`~/.idmtest/command-approvals.json`), keyed by workspace,
   profile and command, and hold a hash of the definition as written (secret references by name, not
   value). Any change to a definition needs a new approval. The runner refuses a run that would use an
   unapproved command; the workbench shows each definition exactly and never approves by itself.
4. **Limits.** Every command has a timeout (its process tree is killed) and an output cap; stdin is
   closed. Output and command lines are masked like all evidence.
5. **Local only.** Commands on another host (SSH) are a separate decision and ticket.

## Alternatives recorded

- **An opt-in flag in the workspace settings:** rejected. The flag and the definitions arrive through
  Git together, so it does not stop a harmful shared workspace.
- **Definitions only in a file Git ignores** (like `secrets.env`): safe and simple, but teams could not
  share definitions. Approval on each machine keeps sharing and the same protection.
- **Free command lines in scenarios, or a shell:** rejected; this is exactly what the decision prevents.

## Consequences

- A teammate's change to a command definition stops working on other machines until each person
  approves it. This is intended: the change is code that will run on their machine.
- A pipeline must approve explicitly (`idmtest commands approve --yes`) after its operator has
  reviewed the definitions.
- The scenarios module's test runners have no user approvals; tests supply them.
- An argument is visible in the operating system's process list while a command runs; the docs
  recommend an environment variable or a password file for secrets where the tool allows one.
