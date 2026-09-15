# 0005 — Structured results before renderers

**Status:** Accepted design, implemented with the limitations below.

**Recorded:** 2026-09-15, retrospectively from repository sources. This is the documentation date, not an invented original decision date.

**Provenance:** [Approved engine design](../../specs/2026-09-13-declarative-scenario-engine-design.md), dated 2026-09-13, §6.4. [Capability baseline](../capabilities.md) records current implementation evidence.

## Context

Failures must be understandable to authors, and future report surfaces should share the same result data.

## Decision

Return ScenarioResult and StepResult. Use the JUnit adapter and JsonResultWriter to publish outcomes.

## Alternatives recorded in the design

The approved design defers HTML rendering and a frontend; it chooses structured data rather than embedding a presentation surface into execution.

## Consequences and implementation limits

The model is a compatibility surface. Current retries record attempts, waited time and the last message; there is no separate last-observation field. Resolved parameters are not redacted.

## Implementation

See [source](../../../idm-test-engine/src/main/java/com/idm/test/engine/result/ScenarioResult.java) and the [architecture guide](../README.md).
