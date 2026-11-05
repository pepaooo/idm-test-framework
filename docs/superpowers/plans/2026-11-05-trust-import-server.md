# Server Certificate Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user with only a server hostname and port inspect and explicitly import its presented leaf certificate into the per-user idmtest truststore.

**Architecture:** Extend the existing `trust` CLI with `import-server`. A focused TLS capture class makes a bounded direct connection, retrieves the leaf, and checks its validity and endpoint identity; `TrustCommand` displays the certificate and asks for confirmation; `UserTrustStore` imports the in-memory certificate through its existing store transaction.

**Tech Stack:** Java 25, JSSE, JUnit 5, AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-11-05-trust-import-server-design.md`

## Global Constraints

- Direct TLS only: no STARTTLS, HTTP proxy, or application data.
- Import only the presented leaf after explicit `y` or `yes`; no `--yes` bypass.
- Keep normal `run`/`ui` TLS verification and JVM truststore override precedence unchanged.
- Keep existing `trust import <file>` and `trust refresh` behavior.
- Add English and Spanish CLI text, help, bundled README, runner guide, and TLS reference.

## Review Focus

- A server that sends no X.509 peer certificate must fail without prompting or writing a store (Task 2 test).
- A TLS endpoint that stalls must time out rather than hang indefinitely (Task 2 test).
- IPv6 literals must parse only in bracketed `host:port` form (Task 1 test).
- A hostname mismatch or expired leaf must refuse import even if the user would answer yes (Task 2 test).
- EOF, blank input, and noninteractive input must leave the store unchanged (Task 3 test).

---

### Task 1: CLI shape and endpoint parsing

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/CommandLine.java`
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/trust/ServerEndpoint.java`
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/CommandLineTest.java`
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/trust/ServerEndpointTest.java`

**Interfaces:**
- Produces: `ServerEndpoint.parse(String): ServerEndpoint`, with `host(): String`, `port(): int`, and `toString()` preserving an unambiguous endpoint; invalid input raises `IllegalArgumentException`.
- Produces: `CommandLine.parse("trust", "import-server", "host:port")` with action `import-server` and one name; `--yes` remains invalid.

- [ ] **Step 1: Write failing tests** for DNS/IPv4/bracketed IPv6, invalid and missing ports, unbracketed IPv6, and CLI action/option validation.
- [ ] **Step 2: Run** `./mvnw -pl idm-test-runner -am -Dtest=CommandLineTest,ServerEndpointTest -Dsurefire.failIfNoSpecifiedTests=false test`; expect test failure.
- [ ] **Step 3: Implement** the parser and CLI validation without changing the `CommandLine` record shape.
- [ ] **Step 4: Run** the same test command; expect pass.
- [ ] **Step 5: Commit** parser and tests.

### Task 2: Bounded certificate capture and endpoint checks

**Files:**
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/trust/ServerCertificateFetcher.java`
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/trust/ServerCertificateFetcherTest.java`

**Interfaces:**
- Consumes: `ServerEndpoint` from Task 1.
- Produces: `ServerCertificateFetcher.fetch(ServerEndpoint): X509Certificate`, throwing `UserTrustStore.TrustException` for connection, TLS, validity, or identity failures.

- [ ] **Step 1: Write failing tests** using loopback TLS servers for self-signed leaf capture, hostname mismatch, expired leaf, no certificate/handshake failure, connection failure, and a stalled handshake.
- [ ] **Step 2: Run** the focused Maven test; expect failure.
- [ ] **Step 3: Implement** direct JSSE capture with connect/handshake timeouts, SNI for DNS names, chain retrieval, leaf validity, and HTTPS-compatible DNS/IP endpoint matching; close the socket after handshake.
- [ ] **Step 4: Run** the focused test; expect pass.
- [ ] **Step 5: Commit** fetcher and tests.

### Task 3: Confirm and import the captured certificate

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/trust/UserTrustStore.java`
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/trust/TrustCommand.java`
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/IdmTest.java`
- Modify: `idm-test-engine/src/main/resources/com/idm/test/engine/i18n/messages.properties`
- Modify: `idm-test-engine/src/main/resources/com/idm/test/engine/i18n/messages_es.properties`
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/trust/UserTrustStoreTest.java`
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/trust/TrustCommandTest.java`

**Interfaces:**
- Consumes: `ServerCertificateFetcher.fetch(ServerEndpoint)` from Task 2.
- Produces: `UserTrustStore.importCertificates(List<X509Certificate>): ImportResult`; the existing file overload delegates after parsing.
- Produces: a `TrustCommand.run` overload accepting an input stream and injectable fetcher for tests; the existing CLI entry delegates using `System.in`.

- [ ] **Step 1: Write failing tests** for yes/no/blank/EOF, duplicate import, invalid hostname/validity without prompt, fingerprint/details output, and store untouched on decline.
- [ ] **Step 2: Run** focused trust command/store tests; expect failure.
- [ ] **Step 3: Implement** the in-memory import, localized display and default-no prompt, and CLI input plumbing. Catch command errors as exit code 2.
- [ ] **Step 4: Run** focused tests; expect pass.
- [ ] **Step 5: Commit** import flow and tests.

### Task 4: User documentation and packaged verification

**Files:**
- Modify: `idm-test-runner/src/dist/README.md`
- Modify: `docs/guides/runner.md`
- Modify: `docs/reference/configuration.md`
- Modify: CLI help text in both message bundles
- Test: existing CLI help assertion in `idm-test-runner/src/test/java/com/idm/test/runner/IdmTestTest.java`

**Interfaces:** Documents `idmtest trust import-server lab.example:443`, its first-use trust limitation, fingerprint confirmation, and additive leaf renewal behavior.

- [ ] **Step 1: Write failing help assertion** for `trust import-server` and update usage text and docs.
- [ ] **Step 2: Run** `./mvnw -pl idm-test-runner -am test`; expect pass.
- [ ] **Step 3: Run** `./mvnw -B verify` and smoke-test the packaged launcher help/import-server syntax; expect pass.
- [ ] **Step 4: Commit** documentation and help.
