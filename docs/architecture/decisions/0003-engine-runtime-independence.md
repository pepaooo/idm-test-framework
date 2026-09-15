# 0003 — Keep runtime wiring outside the engine

**Status:** Accepted design, implemented with the limitations below.

**Recorded:** 2026-09-15, retrospectively from repository sources. This is the documentation date, not an invented original decision date.

**Provenance:** [Approved engine design](../../specs/2026-09-13-declarative-scenario-engine-design.md), dated 2026-09-13, §5.3 and §6.2–6.3. [Capability baseline](../capabilities.md) records current implementation evidence.

## Context

JUnit is available today; a future runner should not require rewriting execution.

## Decision

Supply engine dependencies by constructor. Keep Spring/JUnit imports and configuration loading out of engine main sources.

## Alternatives recorded in the design

The design defers a CLI and uses the existing JUnit scenario module as the adapter. No separate competing DI framework is selected.

## Consequences and implementation limits

The import boundary is tested, but core/connector dependencies can still bring Spring transitively. A CLI is enabled conceptually, not shipped.

## Implementation

See [source](../../../idm-test-engine/src/test/java/com/idm/test/engine/NoSpringDependencyTest.java) and the [architecture guide](../README.md).
