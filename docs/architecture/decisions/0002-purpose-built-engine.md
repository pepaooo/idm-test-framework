# 0002 — A purpose-built scenario engine

**Status:** Accepted design, implemented with the limitations below.

**Recorded:** 2026-09-15, retrospectively from repository sources. This is the documentation date, not an invented original decision date.

**Provenance:** [Approved engine design](../../specs/2026-09-13-declarative-scenario-engine-design.md), dated 2026-09-13, §5.1. [Capability baseline](../capabilities.md) records current implementation evidence.

## Context

IdM assertions need polling across LDAP and JDBC as well as HTTP.

## Decision

Use a small engine with protocol-independent assertion waiting and a closed step vocabulary.

## Alternatives recorded in the design

The design evaluated Karate and generating Karate feature files from YAML. It records HTTP-focused retry behavior and the Spring bridge/generation overhead as reasons to reject those approaches.

## Consequences and implementation limits

The team owns parsing, diagnostics and runner maintenance. Each added step increases that maintenance scope.

## Implementation

See [source](../../../idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java) and the [architecture guide](../README.md).
