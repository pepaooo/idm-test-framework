# 0006 — Restricted expressions for computed values

**Status:** Accepted design, implemented with the limitations below.

**Recorded:** 2026-09-15, retrospectively from repository sources. This is the documentation date, not an invented original decision date.

**Provenance:** [Approved engine design](../../specs/2026-09-13-declarative-scenario-engine-design.md), dated 2026-09-13, §5.4–5.5 and §7.4–7.5. [Capability baseline](../capabilities.md) records current implementation evidence.

## Context

Forbidding computation forces simple string/date transformations into Java workflows.

## Decision

Allow whole-value js:{ ... } expressions under Rhino with no Java class access, fresh scopes and an instruction budget. Steps retain connector actions.

## Alternatives recorded in the design

The design discusses forbidding expressions and using Spring Expression Language. Familiar JavaScript syntax motivates Rhino. The later expression plan removes the proposed helper object in favor of plain ES6.

## Consequences and implementation limits

Native value conversion is required to avoid exposing Java objects. Conversion limits and instruction observation reduce failure risks but are not OS isolation. Some regex literals confuse the outer expression parser.

## Implementation

See [source](../../../idm-test-engine/src/main/java/com/idm/test/engine/expr/ExpressionEvaluator.java) and the [architecture guide](../README.md).

The [2026-09-14 expressions plan](../../plans/2026-09-14-expressions-and-variables.md) records the no-helper-object amendment implemented in code.
