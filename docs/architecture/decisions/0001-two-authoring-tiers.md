# 0001 — Two authoring tiers

**Status:** Accepted design, implemented with the limitations below.

**Recorded:** 2026-09-15, retrospectively from repository sources. This is the documentation date, not an invented original decision date.

**Provenance:** [Approved engine design](../../specs/2026-09-13-declarative-scenario-engine-design.md), dated 2026-09-13, §1, §3 and §10. [Capability baseline](../capabilities.md) records current implementation evidence.

## Context

The original framework exposed Java libraries. Consultants need to describe repeatable workflows without writing Java.

## Decision

Keep YAML scenarios and explicit Java/JUnit workflows. Both consume the shared connectors.

## Alternatives recorded in the design

The approved design considered the library/tool mismatch and chose coexistence rather than replacing Java.

## Consequences and implementation limits

YAML is approachable but limited by its registered vocabulary. Java remains the route for unsupported operations and complex control flow.

## Implementation

See [source](../../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java) and the [architecture guide](../README.md).
