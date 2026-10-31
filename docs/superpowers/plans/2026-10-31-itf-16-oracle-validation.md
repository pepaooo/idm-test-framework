# ITF-16 Oracle Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reproduce Oracle 21c XE connection validation failure and verify JDBC validation with a repeatable integration test.

**Architecture:** An explicitly invoked integration test starts Oracle and exercises the production database configuration. Remove the hardcoded validation SQL and let Hikari use JDBC `Connection.isValid()`. Keep a Docker-free configuration regression test in the default suite.

**Tech Stack:** Java 25, Spring Boot 4.1.1, JUnit Jupiter, AssertJ, HikariCP, Oracle JDBC, Testcontainers, Maven wrapper.

**Spec:** `docs/superpowers/specs/2026-10-31-itf-16-oracle-validation-design.md`

## Global Constraints

- Use Oracle 21c XE, pinned to `gvenzl/oracle-xe:21.3.0-slim`.
- Name the explicit test `OracleConnectionValidationIT`; the default Maven suite stays independent of Docker.
- Preserve lazy pool creation, pool cleanup, and existing connection settings.
- Do not add per-database validation SQL settings or change scenario runners.
- An explicit run must fail clearly if Docker or Oracle cannot start; it must not silently skip.
- Close pools before stopping the container, including on failure.
- The available Docker daemon reports `aarch64`; actual Oracle verification requires a compatible x86 environment.
- Preserve unrelated working-tree changes in scenario resources and other files.

## Review Focus

- A container startup failure must be reported separately from an Oracle validation SQL failure (Task 1).
- An arbitrary host port must work without assuming localhost:1521 (Task 1).
- Invalid production validation SQL must fail despite valid application SQL (Tasks 1–2).
- Assertion failures must still close pools before container teardown (Task 1).
- An unused or unreachable database must retain lazy behavior (Task 2).

---

### Task 1: Add and run the Oracle reproduction

**Files:**
- Modify: `idm-connector-database/pom.xml` — add test-scoped container support.
- Create: `idm-connector-database/src/test/java/com/idm/test/connector/database/config/OracleConnectionValidationIT.java` — own the Oracle fixture and integration assertion.
- Create: `idm-connector-database/README.md` — document explicit execution and platform prerequisites.

**Interfaces:**
- Consumes: `DatabaseConfiguration.jdbcTemplates(DatabaseProperties): Map<String, JdbcTemplate>` and `DatabaseConfiguration.destroy(): void`.
- Produces: `OracleConnectionValidationIT.validatesOracleConnectionOnFirstUse(): void`, executable with Surefire's `-Dtest` override.

- [ ] **Step 1: Establish the execution environment.** Inspect the selected Docker daemon and Java version. Use an existing compatible x86 daemon/VM if available; obtain user input if its location is unknown. Do not provision a VM or change Docker contexts silently. If unavailable, continue implementation and default verification but mark real Oracle reproduction outstanding.
- [ ] **Step 2: Add the fixture dependency.** Add test-scoped `org.testcontainers:testcontainers` using Spring Boot dependency management, consistent with the existing Testcontainers generation. Use `GenericContainer<?>` so no Oracle-specific module or version override is needed.
- [ ] **Step 3: Write `validatesOracleConnectionOnFirstUse()`.** Start the pinned container with a disposable `ORACLE_PASSWORD`, expose 1521 dynamically, and wait for `DATABASE IS READY TO USE!` with a five-minute startup timeout. Connect to service `XEPDB1` using `system` and `oracle.jdbc.OracleDriver`. Derive the URL from `getHost()` and `getMappedPort(1521)`.

  Before exercising the pool, use `DriverManager.getConnection` to assert the server version is 21 and that direct `SELECT 1 FROM DUAL` returns 1. This distinguishes fixture and SQL errors from pool validation errors. Configure one named database through `DatabaseProperties`; then make this assertion through the production `JdbcTemplate`:

  ```java
  assertThat(template.queryForObject("SELECT 1 FROM DUAL", Integer.class)).isEqualTo(1);
  ```

  Put production configuration cleanup in an inner `finally`, with container cleanup in the outer resource scope, so failures close resources in the correct order. Start the container within its cleanup scope as well. Use JUnit assertions, not assumptions or disabled-without-Docker annotations.

- [ ] **Step 4: Run the integration test before changing production code.**

  ```sh
  ./mvnw test -pl idm-connector-database -am -Dtest=OracleConnectionValidationIT -Dsurefire.failIfNoSpecifiedTests=false
  ```

  Expected on a compatible environment: direct Oracle probe succeeds, production pool fails with an Oracle SQL error attributable to `SELECT 1`. Record the actual exception and Oracle error code; do not assume a code or count an architecture/connectivity failure as reproduction. If the production pool unexpectedly passes, investigate before applying the proposed fix.

- [ ] **Step 5: Document the fixture.** In the module README include the exact command above, Java 25, x86 Docker requirement, image pin, first-run download/startup cost, automatic cleanup, and how to use an already configured compatible Docker endpoint. Record observed before-fix evidence or explicitly state it remains unverified.
- [ ] **Step 6: Commit only Task 1 files.** Use `test: add explicit Oracle 21c XE validation reproduction`.

### Task 2: Switch validation to JDBC and verify

**Files:**
- Modify: `idm-connector-database/src/main/java/com/idm/test/connector/database/config/DatabaseConfiguration.java` — remove validation SQL override.
- Modify: `idm-connector-database/src/test/java/com/idm/test/connector/database/config/DatabaseConfigurationTest.java` — default-suite regression check.
- Modify: `idm-connector-database/README.md` — final reproduction and verification evidence.

**Interfaces:**
- Consumes: Task 1's `OracleConnectionValidationIT` and production configuration API.
- Produces: unchanged configuration API with `HikariDataSource.getConnectionTestQuery() == null`.

- [ ] **Step 1: Add `testUsesJdbcValidationWithoutStartingPool()`.** Build an H2-backed template with the existing fixture helper, retrieve its `HikariDataSource`, and assert both configuration properties before any query:

  ```java
  assertThat(dataSource.getConnectionTestQuery()).isNull();
  assertThat(dataSource.getHikariPoolMXBean()).isNull();
  ```

  Close the configuration in `finally`.

- [ ] **Step 2: Verify the regression test fails.**

  ```sh
  ./mvnw test -pl idm-connector-database -am -Dtest=DatabaseConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
  ```

  Expected: the new null-query assertion fails because the configured value is `SELECT 1`.

- [ ] **Step 3: Apply the minimal fix.** Remove `hikariConfig.setConnectionTestQuery("SELECT 1")` in `createDataSource(String, DatabaseProperties.DatabaseConfig): DataSource`. Add a brief comment explaining JDBC validation avoids database-specific validation SQL. Preserve all other pool settings and lifecycle code.
- [ ] **Step 4: Run the default connector tests and dependencies.** Run `./mvnw test -pl idm-connector-database -am`. Expected: all tests pass, including existing first-use/unreachable database and shutdown tests; `OracleConnectionValidationIT` does not run.
- [ ] **Step 5: Rerun Task 1's explicit Oracle command.** Expected: the same server version and direct probe pass, and the production query returns 1. Inspect Surefire results to verify the integration test actually ran. If the environment is unavailable, record that limitation and leave this step unchecked.
- [ ] **Step 6: Document results and review the diff.** Record the actual Oracle before/after outcome and commands in the module README. Confirm no unrelated changes or container credentials were included. Do not claim Oracle compatibility verified or mark ITF-16 resolved without the real run.
- [ ] **Step 7: Commit only Task 2 files.** Use `fix: use JDBC connection validation for database pools`.

## Self-review

The two tasks cover the approved scope: real Oracle reproduction, minimal JDBC validation fix, default regression coverage, lazy lifecycle preservation, explicit execution documentation, bounded readiness, and cleanup. The x86 execution environment remains an external prerequisite, and no H2 result substitutes for Oracle evidence.
