# 0004 — Assertion retries and registered cleanup

> **Superseded by ITF-19** (docs/specs/2026-09-24-explicit-cleanup-design.md): steps no longer register undos; teardown is the author's cleanup: block, and a FAILED cleanup entry fails the scenario. The retry-polling decision below is unaffected and still stands. This record is kept as-is for history.

**Status:** Accepted design, implemented with the limitations below.

**Recorded:** 2026-09-15, retrospectively from repository sources. This is the documentation date, not an invented original decision date.

**Provenance:** [Approved engine design](../../specs/2026-09-13-declarative-scenario-engine-design.md), dated 2026-09-13, §7.2, §7.6 and §8.1. [Capability baseline](../capabilities.md) records current implementation evidence.

## Context

Provisioning is asynchronous and test-created data must be cleaned up after success or failure.

## Decision

The engine polls retryable assertions with resolved parameters and unwinds registered undo actions in reverse order. Actions execute once.

## Alternatives recorded in the design

The design makes waiting an assertion property rather than a separate wait step. It keeps polling policy out of individual step implementations.

## Consequences and implementation limits

Cleanup is best effort and only exists when a step supplies an undo. Cleanup exceptions are logged, not structured failures. Java test cleanup is separate and has incomplete role removal.

## Implementation

See [source](../../../idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java) and the [architecture guide](../README.md).
