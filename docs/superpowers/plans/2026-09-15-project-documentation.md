# Project Documentation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task. Steps use checkboxes for tracking. Execute inline unless the user requests delegation.

**Execution status:** All eight tasks completed on 2026-09-15. Documentation and presentation outputs are indexed in `docs/README.md`. Changes are on `codex/project-documentation`; no implementation behavior changed.

**Goal:** Produce an understandable coworker introduction, accurate architecture documentation, practical usage guides, decision records, and a reusable presentation.

**Architecture:** Maintain Markdown documentation in the repository, with shared diagram sources and rendered images. Establish the relationship between implemented behavior and approved design before writing the audience-facing documents; derive the presentation from those documents.

**Tech Stack:** Markdown, Mermaid for module/sequence diagrams, C4 notation for context/runtime views, SVG diagram exports, PowerPoint and PDF for the presentation. Select a C4-compatible renderer after checking available tooling; do not add application runtime dependencies for documentation.

**Spec:** User request in this conversation; `docs/reference/specification.md`; `docs/specs/2026-09-13-declarative-scenario-engine-design.md`. These repository sources require reconciliation against code and tests, not unconditional adoption.

## Global Constraints

- Scope is documentation; do not change framework behavior to make a documented example work.
- Write in English for a mixed audience of IdM consultants, test authors, developers, and technical reviewers.
- Distinguish implemented, approved but unimplemented, and future/uncommitted capabilities.
- Code and executed tests establish current behavior; approved designs establish intended behavior. Record differences rather than silently treating either as the other.
- Keep historical design and phase records intact; add a status note/link if a historical statement needs context.
- Reuse existing configuration and running guides instead of creating duplicate references.
- Show Maven modules as code organization, not independently deployed services.
- Keep all executable examples aligned with supported syntax. Never use real credentials or personal data.
- Label embedded framework verification separately from validation against a real IdM system.
- Record the reviewed Git revision and test date for capability and validation claims. Do not copy stale passing-test counts.
- The plan itself is the only deliverable created during planning. Execution produces the documents below.

## Deliverables and Reading Paths

| File | Responsibility |
|---|---|
| `docs/README.md` | Navigation for newcomers, test authors, and maintainers |
| `docs/overview.md` | 2–3-page introduction: problem, users, example, context, capabilities, limitations |
| `docs/architecture/README.md` | Approximately 6–10 pages of architecture explanation |
| `docs/architecture/capabilities.md` | Current/design status matrix with source evidence |
| `docs/architecture/diagrams/` | Editable diagram sources and SVG exports |
| `docs/guides/scenario-authoring.md` | YAML and Java authoring tutorial and language reference links |
| `docs/reference/specification.md` | Reconciled architectural specification with scope/status clarified |
| `docs/reference/configuration.md` | Correct configuration keys, profiles, precedence, and environment examples |
| `docs/reference/running-tests.md` | Verified execution instructions and troubleshooting |
| `docs/architecture/decisions/README.md` | ADR index and status conventions |
| `docs/architecture/decisions/0001-*.md` through `0006-*.md` | Six focused decision records |
| `docs/presentations/project-introduction.md` | Slide narrative, speaker notes, and demo instructions |
| `docs/presentations/project-introduction.pptx` | Editable 8-slide team presentation |
| `docs/presentations/project-introduction.pdf` | Shareable presentation export |
| `README.md` | Concise entry point linking the maintained documentation |

## Task 1: Establish the documentation baseline

**Status:** Complete, 2026-09-15. Baseline verified with 320 passing tests on Java 21; see `docs/architecture/capabilities.md`. Original specification preserved under `docs/history/`.

**Produces:** Capability matrix and an agreed factual vocabulary for every later task.

- [x] Record `git rev-parse HEAD` and `git status --short`; preserve existing user changes.
- [x] Read the parent/module POMs, README, both specifications, current plans, configuration resources, engine sources, connector sources, and scenario tests.
- [x] Inspect `ScenarioEngine`, `ScenarioLoader`, `ScenarioValidator`, `Interpolator`, `ExpressionEvaluator`, `StepType`, and the registered steps in `YamlScenarioTest`.
- [x] Inspect `BaseIntegrationTest` and connector configuration to establish which endpoints are embedded, stubbed, or externally configurable.
- [x] Create `docs/architecture/capabilities.md` with columns: capability, implementation status, approved intent, source evidence, limitation. Cover YAML/Java authoring, available steps, expressions, variables/capture, retry/cleanup, reporting, connectors, CLI/UI, and real-system execution.
- [x] Resolve known documentation conflicts: original no-orchestration principle versus the approved engine; README expression roadmap versus current code; running guide skipped-test claims versus embedded infrastructure; profile names/activation claims versus actual configuration.
- [x] Update the maintained specification to describe current architecture and explicitly identified target behavior. Link historical design rationale and the capability matrix.

**Acceptance:** Every feature claim has a code/test or approved-design reference; unsupported or uncertain behavior is identified explicitly. No declaration that real IdM integration is validated without evidence.

## Task 2: Create the shared architecture diagrams

**Consumes:** Task 1 capability matrix. **Produces:** Five reusable diagrams with captions and legends.

- [x] Create `system-context` showing consultant/developer users, the framework boundary, IdM, directories, databases, and API/service targets; label protocols and relationship purposes.
- [x] Create `runtime-containers` showing the JUnit/JVM execution process, external systems, and output artifacts. Represent files as artifacts and libraries as internal elements, not services.
- [x] Create `modules` showing Java tests and the JUnit YAML adapter, loader/validator, engine, expression evaluator, step implementations, core/utilities, connectors, and result adapters. Verify dependency arrows against POMs and imports.
- [x] Create `scenario-execution` as a sequence diagram: discover/load, validate, fixtures, resolve values, run actions/assertions, assertion polling, first failure, reverse cleanup, structured result, JUnit/JSON outputs. Verify exact ordering and failure branches from source.
- [x] Create `execution-environments` showing the embedded LDAP/H2/WireMock environment and a separately labeled real-system target environment. Mark any target setup that is not yet verified.
- [x] Save sources and matching SVGs under `docs/architecture/diagrams/`; document the exact renderer command/version used in a directory README.
- [x] Render and visually inspect every diagram for readable labels, unambiguous boundaries, arrow direction, and slide-sized readability.

**Acceptance:** The diagrams use consistent names and status conventions, match the implementation boundary, and remain understandable without reading Java classes. C4 containers denote execution/data boundaries, not Maven modules.

## Task 3: Write the project overview

**Consumes:** Tasks 1–2. **Produces:** `docs/overview.md`.

- [x] Explain the IdM testing problem and intended audiences in plain language; define IdM, provisioning, scenario, connector, and assertion at first use.
- [x] Introduce the two authoring tiers and their shared connectors.
- [x] Embed the context diagram and a small supported YAML example, using an actual scenario as the source.
- [x] Describe inputs, execution, and outputs; summarize available capabilities and link the full matrix.
- [x] State what the embedded demonstration proves, current limitations, and separately labeled future directions.
- [x] Finish with reading links for running a test, writing a scenario, and understanding the architecture.

**Acceptance:** A coworker can explain the project's purpose, who uses it, how a scenario runs, and what is available today after a five-minute read.

## Task 4: Write the architecture guide and ADRs

**Consumes:** Tasks 1–2. **Produces:** Architecture guide and six ADRs.

- [x] Organize `docs/architecture/README.md` into purpose/scope, context, building blocks, runtime behavior, environments/configuration, cross-cutting concerns, and limitations.
- [x] Embed the module/runtime/sequence/environment diagrams with short explanations; map major components to source paths.
- [x] Explain constructor-supplied dependencies, separation from Spring/JUnit, action versus assertion behavior, error/result handling, cleanup guarantees and limits, and extension points.
- [x] Describe expression access restrictions and resource limits only as verified in implementation/tests; distinguish intended guarantees from evidence.
- [x] Create an ADR index and six records: `0001-two-authoring-tiers.md`, `0002-purpose-built-engine.md`, `0003-engine-runtime-independence.md`, `0004-assertion-retries-and-cleanup.md`, `0005-structured-results.md`, and `0006-restricted-expressions.md`.
- [x] Give each ADR status, source/date provenance, context, decision, alternatives, consequences, and implementation references. Do not invent historical acceptance dates or claim unrecorded alternatives were formally evaluated.
- [x] Cross-link ADRs from relevant architecture sections and link the architecture guide from the specification.

**Acceptance:** A developer can locate the component to extend, explain dependency direction, and predict behavior when validation, a step, waiting, or cleanup fails. ADRs capture rationale without duplicating the guide.

## Task 5: Write the scenario authoring guide

**Consumes:** Task 1 verified language/step inventory. **Produces:** `docs/guides/scenario-authoring.md`.

- [x] Begin with one minimal runnable YAML scenario and its required file location and Maven command.
- [x] Explain fixtures, generated values, interpolation, supported expressions, variables/capture, ordered steps, assertions, and waiting according to verified support.
- [x] List only registered supported steps as available; link planned vocabulary to the capability matrix.
- [x] Explain validation errors, timeout diagnostics, cleanup behavior, and JSON/JUnit results using representative sanitized output.
- [x] Add a Java example from an existing test and explain when Java is appropriate.
- [x] Explain how developers add a step using the real `StepType` contract and registration path, linking source rather than copying large implementation blocks.
- [x] Execute the tutorial against embedded infrastructure; fix examples or flag framework limitations without changing product behavior.

**Acceptance:** A newcomer can copy the example, run it, interpret its result, and understand the boundary between YAML and Java.

## Task 6: Repair execution/configuration guidance and navigation

**Consumes:** Baseline evidence and verified tutorial. **Produces:** Updated existing references and documentation index.

- [x] Verify prerequisites from the build configuration and wrapper; document root-directory execution with `-pl`/`-am` where necessary.
- [x] Replace stale skipped-test/external-service assumptions in `docs/reference/running-tests.md` with observed embedded-suite behavior.
- [x] Document actual configuration resources, supported profile activation, endpoint properties, and credential environment variables in `docs/reference/configuration.md`; remove invented profile support.
- [x] Add a troubleshooting table for Java/Maven mismatch, reactor dependencies, configuration errors, LDAP DN issues, endpoint/authentication failures, validation failures, and assertion timeouts. Derive messages/remedies from code or observed output.
- [x] Separate tested local instructions from real-environment setup requirements; do not run examples against shared external systems as documentation validation.
- [x] Create `docs/README.md` with newcomer, test-author, and maintainer reading paths.
- [x] Update root README links and contradictory summaries; replace fragile test-count assertions with dated evidence or omit counts.

**Acceptance:** Commands work from the stated directory, configuration examples match property binding, and each subject has one maintained home.

## Task 7: Build the coworker presentation

**Consumes:** Completed overview, architecture guide, diagrams, and runnable tutorial. **Produces:** Markdown narrative, PPTX, and PDF.

- [x] Load the presentations skill and workspace dependency runtime before generating artifacts.
- [x] Write an eight-slide narrative: (1) problem and purpose, (2) users and two authoring tiers, (3) system context, (4) short scenario example, (5) architecture/modules, (6) execution and failure handling, (7) local demonstration/results and limitations, (8) current status and next steps.
- [x] Reuse the shared diagrams; simplify labels for slides without changing architecture semantics.
- [x] Add speaker notes explaining IdM terms and a short embedded demo command with expected behavior.
- [x] Produce editable PowerPoint and PDF exports; link back to maintained source documentation.
- [x] Render and inspect all slides and PDF pages for text overflow, contrast, diagram legibility, and consistent terminology.

**Acceptance:** The deck supports a 10–15-minute introduction and makes sense when shared without the presenter. No future feature is presented as delivered.

## Task 8: Final validation and handoff

- [x] Run `./mvnw clean test` against the embedded suite and record actual pass/fail/skip totals and environment. If execution is blocked, record the reason and avoid fresh passing claims.
- [x] Validate the tutorial command: `./mvnw -pl idm-test-scenarios -am test -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [x] Check all local Markdown links, referenced source paths, images, and diagram render commands.
- [x] Cross-check feature names and status across overview, specification, architecture, guides, ADRs, README, and slides.
- [x] Run `git diff --check`; inspect the final diff to confirm no unintended implementation changes or sensitive data.
- [x] Deliver a concise file index, suggested reading order, validation evidence, and remaining factual limitations.

**Definition of done:** All six document categories are delivered, diagrams have editable sources and readable exports, runnable examples are verified or explicitly marked unverified, and the specification/current implementation distinction is consistent throughout.

## Delivery Order

1. Baseline and diagrams establish accuracy.
2. Overview plus architecture guide provide the first useful coworker handoff.
3. Authoring, execution/configuration references, and ADRs support ongoing use and maintenance.
4. Presentation reuses the completed material.
5. Final validation checks the documentation set as a whole.

Execute this as one documentation effort with staged deliverables. No documentation portal, new framework feature, deployment, or publishing service is required.
