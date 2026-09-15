# Architecture diagrams

These diagrams describe the [reviewed capability baseline](../capabilities.md). Context and runtime views use C4 element labels; the module view describes code organization, and the sequence diagram describes runtime behavior.

| Diagram | Question answered |
|---|---|
| [System context](system-context.svg) | Who uses the framework, and which systems does it interact with? |
| [Runtime containers](runtime-containers.svg) | What runs together, and what crosses a process boundary? |
| [Modules](modules.svg) | Which code owns each responsibility? |
| [Scenario execution](scenario-execution.svg) | How do validation, polling, failure and cleanup work? |
| [Execution environments](execution-environments.svg) | What is tested locally, and what remains real-environment work? |

**Notation:** A container is an executing application/runtime boundary, not a Docker image or Maven module. Artifacts are files, not services. Solid relationships show current operations; dashed relationships show a target integration or explicitly labeled available API not used by the demonstrated workflows. In the module view, arrows indicate selected source dependencies/calls, not every transitive Maven dependency. JSON files describe YAML runs; Surefire reports also cover Java tests.

The current runtime view treats a Surefire test JVM as one container instance. Maven can launch separate JVMs for modules. The environment view focuses on the scenarios module; connector tests create their own fixtures. The diagram's SOAP endpoint is a WireMock role-service stub, not a running Identity Manager. A real-environment profile alone does not disable the test base's embedded overrides.

The sequence view shows the valid-file path. Each malformed file instead becomes a failed dynamic test and a scenario-level JSON result; it does not enter the engine. A successful poll exits its retry loop immediately. A non-retryable action runs once. `set` operates only on scope and does not call a connector.

## Editing and rendering

Edit the `.mmd` source beside each SVG. Mermaid CLI is a documentation-only tool and is not an application dependency. Render using the pinned CLI:

```bash
npx --yes --package @mermaid-js/mermaid-cli@11.12.0 mmdc \
  -i docs/architecture/diagrams/system-context.mmd \
  -o docs/architecture/diagrams/system-context.svg \
  -b white -w 1600
```

Repeat for all five sources. Review exports for wrapping and readable arrows before updating a slide or document. Keep source and export changes together.

The review machine used installed Google Chrome via a temporary Puppeteer configuration:

```json
{"executablePath":"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome","args":["--no-sandbox"]}
```

For that setup the exact CLI command above additionally used `-p /private/tmp/idm-docs-build/puppeteer.json`. Use your own Chrome path or Mermaid CLI's bundled browser on another machine; the path is not a project dependency. All five source files rendered successfully with Mermaid CLI 11.12.0 and were visually inspected.
