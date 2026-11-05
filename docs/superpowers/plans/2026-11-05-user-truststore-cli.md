# Per-user TLS Truststore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user import lab CA certificates once and have `idmtest run` and `idmtest ui` trust them without JVM options.

**Architecture:** A `UserTrustStore` service manages a combined PKCS12 file at `<user.home>/.idmtest/truststore.p12`, seeded from the current runtime's `cacerts`. The Java entry point selects it before dispatching a connecting command; explicit JVM truststore settings win. A CLI `trust` command imports certificates and refreshes the public roots.

**Tech Stack:** Java 25, Java `KeyStore` and `CertificateFactory`, existing CLI parser and JUnit/AssertJ tests.

**Spec:** `docs/superpowers/specs/2026-11-05-user-truststore-cli-design.md`

## Global Constraints

- No workbench certificate controls or workspace-specific store.
- Keep the store outside the ZIP and workspaces at `<user.home>/.idmtest/truststore.p12`.
- Preserve an existing store on malformed input, read failure, or write failure; never silently fall back to default trust when a store exists but is broken.
- Do not override an explicitly supplied `javax.net.ssl.trustStore`.
- Use Java APIs; do not require `keytool` in the bundled runtime.

## Review Focus

- A certificate file containing multiple PEM certificates imports all of them (Task 1 test).
- An already imported certificate remains a no-op even when its alias differs (Task 1 test).
- A corrupt existing store stays byte-for-byte unchanged after import or refresh (Task 1 test).
- Refresh keeps user imports but replaces old runtime roots with those from the active runtime (Task 1 test).
- A quoted path with spaces reaches `trust import` as one file argument (Task 2 parser test).

---

### Task 1: Per-user store operations

**Files:**
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/trust/UserTrustStore.java`
- Create: `idm-test-runner/src/test/java/com/idm/test/runner/trust/UserTrustStoreTest.java`

**Interfaces:**
- Produce: `UserTrustStore(Path userHome, Path javaHome)`; `ImportResult importCertificates(Path file)`; `RefreshResult refresh()`; `boolean exists()`; `Path path()`; `void selectForCurrentJvm()`.
- `selectForCurrentJvm()` does nothing if the explicit truststore system property is set; otherwise it validates the existing store and sets the JVM truststore path/type/password properties. It throws a domain exception with an actionable message for a broken store.
- Results expose imported, duplicate, and preserved counts plus each certificate's subject and SHA-256 fingerprint for CLI output.

- [ ] **Step 1: Write failing tests** for first import seeded with public roots, multi-PEM import, duplicate fingerprints, malformed input, unreadable/corrupt existing store left unchanged, refresh with changed runtime roots, and explicit JVM-property precedence.
- [ ] **Step 2: Run** `./mvnw -pl idm-test-runner -am test -Dtest=UserTrustStoreTest -Dsurefire.failIfNoSpecifiedTests=false`; expect failures for missing implementation.
- [ ] **Step 3: Implement** `UserTrustStore` with Java `KeyStore`/`CertificateFactory`; mark user aliases by SHA-256, stage output beside the target, then atomically replace it. Use an application-defined integrity password and restrict file permissions where supported.
- [ ] **Step 4: Run** the same focused test command; expect all tests to pass.
- [ ] **Step 5: Commit** the service and tests.

### Task 2: Trust CLI

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/CommandLine.java`
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/IdmTest.java`
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/trust/TrustCommand.java`
- Modify: `idm-test-runner/src/test/java/com/idm/test/runner/CommandLineTest.java`
- Create: `idm-test-runner/src/test/java/com/idm/test/runner/trust/TrustCommandTest.java`

**Interfaces:**
- Consume: Task 1's `UserTrustStore` API.
- Produce: `idmtest trust import <certificate-file>` and `idmtest trust refresh`; both operate without a workspace. No global options besides help/locale are required for this command.

- [ ] **Step 1: Write failing parser and command tests** for both forms, missing/extra arguments, a path with spaces, import output with subject/fingerprint, refresh output, and a malformed certificate returning exit code 2 without changing the store.
- [ ] **Step 2: Run** `./mvnw -pl idm-test-runner -am test -Dtest=CommandLineTest,TrustCommandTest -Dsurefire.failIfNoSpecifiedTests=false`; expect new cases to fail.
- [ ] **Step 3: Extend** `CommandLine.parse` and `IdmTest.dispatch`; implement `TrustCommand.run(CommandLine, PrintStream, PrintStream, Locale, UserTrustStore)` using existing localized message conventions.
- [ ] **Step 4: Run** the focused tests; expect all to pass.
- [ ] **Step 5: Commit** CLI changes and tests.

### Task 3: Startup selection and user documentation

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/IdmTest.java`
- Modify: `idm-test-runner/src/test/java/com/idm/test/runner/IdmTestTest.java`
- Modify: `idm-test-runner/src/dist/README.md`
- Modify: `docs/guides/runner.md`
- Modify: `docs/guides/workbench.md`
- Modify: `docs/reference/configuration.md`
- Modify: `idm-test-engine/src/main/resources/com/idm/test/engine/i18n/messages.properties`
- Modify: `idm-test-engine/src/main/resources/com/idm/test/engine/i18n/messages_es.properties`

**Interfaces:**
- Consume: Task 1's `selectForCurrentJvm()` and Task 2's command dispatch.
- Produce: automatic store selection for `run`, `ui`, and commands that check connections; no selection for help/version/import/refresh.

- [ ] **Step 1: Write failing entry-point tests** for default trust with no user file, automatic selection with a valid store, explicit JVM override, and failure on corrupt store before `ui` or `run` starts.
- [ ] **Step 2: Run** `./mvnw -pl idm-test-runner -am test -Dtest=IdmTestTest -Dsurefire.failIfNoSpecifiedTests=false`; expect new cases to fail.
- [ ] **Step 3: Call** `selectForCurrentJvm()` in `IdmTest.run` after parsing and before connecting dispatch; update help and docs with import, refresh, and override examples.
- [ ] **Step 4: Run** `./mvnw -B verify`; expect zero failures and errors.
- [ ] **Step 5: Commit** startup behavior, tests, and docs.

## Self-review

All spec sections map to Tasks 1–3. The five review-focus conditions have owning tests. The only state that survives a runtime update is the per-user store; `refresh` intentionally rebuilds it from current roots plus user imports.
