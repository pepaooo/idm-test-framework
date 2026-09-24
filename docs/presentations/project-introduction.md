# Coworker presentation

An eight-slide introduction intended for a 10–15-minute walkthrough. Use the [editable PowerPoint](project-introduction.pptx) or [PDF](project-introduction.pdf). The maintained [overview](../overview.md) and [architecture guide](../architecture/README.md) contain the full detail.

## Preparation

Use Java 21. Open the bundled YAML example and an output terminal before presenting. The live demonstration uses embedded fixtures; it does not connect to a real Identity Manager.

```bash
./mvnw -pl idm-test-scenarios -am test \
  -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected behavior: the example computes a display name, creates an entry, verifies its attributes and attempts cleanup. Inspect `idm-test-scenarios/target/scenario-results.json` and the JUnit result. The command was verified on 2026-09-15 with Java 21; the [baseline](../architecture/capabilities.md) records full-suite evidence.

The slide's YAML excerpt omits fixtures and create attributes for readability. For copy-and-run use the [complete authoring example](../guides/scenario-authoring.md), not the slide excerpt.

## Slide narrative and speaker notes

### 1. IdM Test Framework

Purpose: integration testing tools for Identity Management workflows. Provisioning means propagation of identity or access changes into target systems. A successful request is not necessarily proof that a target has the right state. Introduce the project in approximately one minute. Sources: docs/overview.md; docs/reference/specification.md.

### 2. Two ways to author a test

YAML is intended for consultants who want to describe a workflow as data. Java remains available for unsupported YAML operations and more complex control flow. Both reuse connectors, so connector fixes can help both paths. A scenario is an ordered workflow; a connector performs protocol operations; an assertion checks observed state. Allow two minutes. Sources: docs/overview.md; docs/architecture/decisions/0001-two-authoring-tiers.md.

### 3. System context

This is a simplified editable version of docs/architecture/diagrams/system-context.mmd. Rectangles identify persons/software systems; protocol labels identify boundaries. LDAP/JDBC/HTTP capabilities exist. The real Identity Manager integration is a target and has not been validated by the embedded suite. SOAP transport is generated JAX-WS, and REST OAuth/real endpoint wiring has limitations. Allow two minutes. Sources: docs/architecture/README.md sections 2–4; docs/architecture/capabilities.md.

### 4. A YAML scenario

Read the full runnable example at idm-test-scenarios/src/test/resources/scenarios/user-provisioning.yaml. This slide is an excerpt: the fixture and create attributes are omitted for readability. The authoring guide contains the complete scenario. Explain that created holds a relative distinguished name, or DN. The check waits for the expected attribute and the create step supplies deletion cleanup. Allow two minutes. Source: docs/guides/scenario-authoring.md.

### 5. Architecture and shared connectors

Simplified editable module view derived from docs/architecture/diagrams/modules.mmd. The engine contains parsing, validation, value handling and ordered execution. It uses LDAP steps today; Java callers have broader connector APIs. Core and utilities provide shared result, wait and data support. All are libraries in the test runtime, not microservices. The engine main sources do not import Spring/JUnit, although its dependencies bring Spring transitively. Source: docs/architecture/README.md sections 3–4. Allow two minutes.

### 6. Execution and failure handling

Simplifies the full sequence diagram at docs/architecture/diagrams/scenario-execution.mmd. Files are validated before dynamic test execution. Parameters resolve once per ordinary step. Only assertions with wait are polled; actions run once. The first failure stops later steps. Cleanup is an author-written top-level `cleanup:` block, run after the steps whether they passed or failed, with every entry attempted (ITF-19). Runtime result data is returned to the adapter, which writes JSON and reports JUnit success/failure. A failed cleanup entry fails the scenario; a step failure still leads the failure summary. Allow two minutes. Sources: docs/reference/specification.md sections 4 and 6; ADR 0004 and 0005.

### 7. Local demonstration and results

Run from the repository root with JDK 21. The full suite baseline on 2026-09-15 at revision 104e388a555f9e22d0c415290ff2edc606402a59 was 320 tests, zero failures/errors/skips. The selected tutorial command was also executed successfully. Show the console and scenario-results.json after the run, if time permits. The suite uses embedded UnboundID, H2 and WireMock; it validates framework behavior rather than a real Identity Manager. Results contain resolved values without redaction; use synthetic data. Sources: docs/reference/running-tests.md; docs/architecture/capabilities.md. Allow two minutes including demo.

### 8. Current scope and next steps

Available: YAML LDAP create/verify, fixtures, interpolation, expressions, variables/capture and structured results; broader Java connector APIs. Approved next work includes additional YAML operations and converting suitable Java scenarios. Real IdM validation needs separate wiring/authentication and actual provisioning workflows. CLI, frontend, HTML reports and parallel scenarios are deferred, not committed delivery promises. Invite coworkers to use the overview, authoring guide and architecture guide, then identify a first real use case for subsequent implementation. Sources: docs/architecture/capabilities.md; docs/README.md. Allow one minute.

## Sources and maintenance

- [Project overview](../overview.md).
- [Architecture and diagrams](../architecture/README.md).
- [Capability baseline](../architecture/capabilities.md).
- [Scenario authoring](../guides/scenario-authoring.md).
- [Running tests](../reference/running-tests.md).

Slides 3, 5 and 6 simplify the shared context, module and execution diagrams into editable slide shapes. The full diagrams retain details omitted from the presentation. Update the slide narrative and capability date when revising the PowerPoint; preserve the distinction between implemented and target behavior.
