# 0007 — An end-user runner, then a local web workbench

**Status:** Accepted direction. Nothing is implemented yet; each part has its own spec, plan and tickets.

**Recorded:** 2026-10-15, decided with jangulo (ITF-53).

**Provenance:** ITF-53 brainstorm. Builds on [ADR 0003](0003-engine-runtime-independence.md) (the engine is
free of Spring and JUnit so another runner can wrap it) and on ITF-44 (per-profile configuration files)
and ITF-46 (report language).

## Context

The YAML tier is meant for IdM consultants who do not write Java (README). Today they cannot use it
without a developer's setup:

- scenarios are read only from `idm-test-scenarios`' **test classpath** (`scenarios/`, `scenarios-real/`);
- the runners exist only as **JUnit test classes** (`YamlScenarioTest`, `RealEnvironmentScenarios`,
  `ContainerScenarios`); there is no `main` program;
- connections and credentials come from Spring profiles and `IDM_*` environment variables;
- results land in `target/`.

A consultant has to clone the repository, write YAML inside its source tree, set environment variables
and run Maven. The wish is a Validator-style tool: write scenarios, configure the connections, run them
and read the results, without Maven or an IDE — eventually from a browser.

## Decision

Build it, in three ordered parts. Each part gets its own spec, plan and tickets.

### Part 1 — A runner for end users, over a workspace

- One Spring Boot application with a command line, reusing the engine and connectors as they are.
- It works on a **workspace folder** the user owns, outside the source tree:

  ```
  my-tests/
    scenarios/            YAML scenarios (any sub-folders)
    config/               connection settings per profile (application-<profile>.yml, ITF-44 layout)
    secrets.env           optional, local only, never committed (or environment variables)
    reports/<run>/        results JSON, HTML report, log of each run
  ```

- Commands: `idmtest validate` and `idmtest run [--profile <p>] [--scenario <glob>] [--locale en|es]`.
  Exit code 0 only when every selected scenario passed, so the runner also works in CI.
- The JUnit runners stay for development and the framework's own CI.

### Part 2 — A local web workbench

- The same application serves `idmtest ui` on `http://localhost:<port>`.
- A single-page app (Monaco editor; YAML language support driven by a JSON Schema of the step
  vocabulary, for autocomplete, inline errors and hover help) over a REST API: workspace files,
  validation, runs; run progress through Server-Sent Events.
- One screen in the style of the Cerbos playground: workspace file tree, editor, Problems (from
  `ScenarioValidator`), run output, the HTML report, and a form for connection settings.
- Security from the first release: listen on the loopback address only; a random per-session access
  token; secrets shown masked and never written into scenario files or reports (ITF-23).
- The UI uses the report language setting (ITF-46) and reuses its message catalogs.

### Part 3 — Collaboration

- **3a, first:** the workspace is a Git repository; the workbench shows changes and can commit, pull
  and push, so consultants share scenarios through Git.
- **3b, later and separately decided:** a shared server deployment — several users, SSO/OIDC sign-in,
  a shared credential store, a run queue and run history. Part 2's REST API is designed so it can
  serve this without a rewrite.

### Distribution

- A ZIP per operating system (macOS, Windows, Linux) with a `jlink` Java runtime, the application and
  launcher scripts (`idmtest`, `idmtest.cmd`); plus a plain executable jar for anyone with Java 25.
- The launcher uses `IDM_TEST_JAVA_HOME` when it is set — after checking it is Java 25 or newer, and
  stopping with a clear message otherwise — and the bundled runtime when it is not. It does not pick up
  `JAVA_HOME` on its own, because that often points at an older JDK used by other tools.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Server-rendered pages (Spring MVC, Thymeleaf, htmx) with CodeMirror | No JavaScript build, but schema-driven autocomplete and hover help are much harder, and an IDE-like screen with live run output is less fluid. |
| Desktop shell (Electron, Tauri) | Native window, but per-OS packaging and more to maintain, and it loses the "open it in your browser" model. |
| Executable jar only | Simplest to build, but consultants must install Java 25 first. Kept as the second artifact. |
| Docker image | No Java needed, but needs Docker, volume mounts, and container networking to labs reached over a VPN. Better suited to Part 3b. |
| Shared server first | Collaboration from day one, but brings authentication, shared lab credentials, network reach to every lab and concurrent runs before the basic flow exists. |
| Web app before the runner | The UI needs a runner that works on a user folder anyway; building the runner first also gives CI and terminal users value early. |

## Consequences

- Part 1 adds a `main` application module and a workspace contract that later parts depend on; the
  JUnit path is unchanged.
- Part 2 adds a JavaScript build (npm via `frontend-maven-plugin`) to the Maven build. Developers need
  Node at build time; end users do not.
- The engine stays free of Spring and JUnit (ADR 0003); the runner and the web API are new
  composition points, like the JUnit adapter.
- A local server that holds lab credentials makes loopback-only binding and a session token
  non-negotiable.
- ITF-44's per-profile configuration files become the workspace's `config/` layout.

## Revisit as it grows

- Whether a shared server (Part 3b) is needed, once several consultants use Git collaboration.
- Storing connection secrets in the operating system's keychain instead of `secrets.env`.
- Signing and notarising the ZIPs, and an auto-update channel.
- A visual (form-based) scenario editor on top of the YAML editor.
