# Used Repositories Only Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A real-environment run requires, checks and connects to only the repositories its selected scenarios use.

**Architecture:**
- **Lazy database pools:** each database's pool opens no connection until first use.
- **Optional SOAP connectors:** the runner doesn't need the SOAP connector beans, and a `roles.*`/`resources.*` step without its connector fails cleanly.
- **`ScenarioNeeds`:** one pass that works out what the selected scenarios use.
- **Guards fed from it:** guard 2 becomes conditional, a new database guard is added, and guards 4 and 5 use the same needs.
- **An end-to-end test** runs a real-environment context with only LDAP configured.

**Tech Stack:** Java 21, Spring Boot 3.5.6, HikariCP, JUnit 5, AssertJ, UnboundID (embedded LDAP), H2.

**Spec:** `docs/specs/2026-09-27-used-repositories-only-design.md` (commit d304156). **Ticket:** ITF-30.

## Global Constraints

- **Unused repositories are never checked by a guard or contacted.** Used ones must be configured and not a placeholder. There is no up-front connection probe.
- **`ScenarioNeeds` rules:**
  - It reads `steps:` **and** `cleanup:`.
  - Prefixes map as follows: `ldap.` → `ldap`, `db.` → `databases` (`database` parameter), `roles.` → `roles`, `resources.` → `resources`, `rest.` or `http.` → `restTargets` (`target` parameter).
  - A `null` value is skipped; any other value goes through `String.valueOf`; a value containing `${` is skipped.
  - The sets are sorted.
- **Guard order in `scenarios()`:**
  1. guard 1 (`requireNotEmbedded`)
  2. guard 3
  3. select the files
  4. load them once
  5. `ScenarioNeeds`
  6. guard 2 (`requireEndpoints`), guard 4, the DB guard (`requireDatabases`), guard 5
- **Guard 2 checks:**
  - the LDAP URL only if `ldap`
  - the SOAP URL only if `roles`
  - `idm.test.soap.resource-url` is **set**, only if `resources` (no placeholder compare)
  - its variables: `IDM_TEST_LDAP_URL`, `IDM_TEST_SOAP_URL`, `IDM_TEST_SOAP_RESOURCEURL`
- **DB guard placeholders:**
  - `sap`: `jdbc:postgresql://localhost:5432/sap`, `sapuser`/`sappass`
  - `oracle-hr`: `jdbc:oracle:thin:@localhost:1521:HRDB`, `hruser`/`hrpass`
  - `workday`: `jdbc:postgresql://localhost:5432/workday`, `wdayuser`/`wdaypass`
- **DB guard messages** name the property and `variableFor(property)`, never a credential value. An unconfigured database's message lists the configured names.
- **The absent-connector message is exactly** `<step name> cannot run: no Role service is configured (idm.test.soap.url)` for `roles.grant`/`roles.revoke`, and `<step name> cannot run: no Resource service is configured (idm.test.soap.resource-url)` for `resources.grant`/`resources.revoke`. It is returned as `StepOutcome.finalFailure`.
- **Unchanged:** the embedded suite (`YamlScenarioTest`, the Java tests, `BaseIntegrationTest`), `ContainerScenarios`' behaviour and `application.yml`.
- **The engine stays Spring-free** (`NoSpringDependencyTest`).
- **Commits** end with a blank line, `ITF-30`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. "Unresolved compilation problems" means stale classes: run `./mvnw clean test`. Green means 0 failures, errors and skips.

---

### Task 1: Lazy database pools

**Files:**
- Modify: `idm-connector-database/src/main/java/com/idm/test/connector/database/config/DatabaseConfiguration.java` (`createDataSource`)
- Test: `idm-connector-database/src/test/java/com/idm/test/connector/database/config/DatabaseConfigurationTest.java` (new)

**Interfaces:**
- Produces: `DatabaseConfiguration.jdbcTemplates(DatabaseProperties)`, unchanged in signature. Its pools now connect on first use.

- [ ] **Step 1: Write the failing test**

```java
package com.idm.test.connector.database.config;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("DatabaseConfiguration")
class DatabaseConfigurationTest {

    private static DatabaseProperties.DatabaseConfig h2(String url) {
        DatabaseProperties.DatabaseConfig config = new DatabaseProperties.DatabaseConfig();
        config.setJdbcUrl(url);
        config.setUsername("sa");
        config.setPassword("");
        config.setDriverClassName("org.h2.Driver");
        config.setMaxPoolSize(2);
        config.setMinIdle(1);
        config.setConnectionTimeout(2000L);
        config.setIdleTimeout(600000L);
        config.setMaxLifetime(1800000L);
        return config;
    }

    private static DatabaseProperties properties() {
        DatabaseProperties properties = new DatabaseProperties();
        properties.setDatabases(Map.of(
                "up", h2("jdbc:h2:mem:lazy-up;DB_CLOSE_DELAY=-1"),
                // Nothing listens on port 1: an eager pool fails right here, a lazy one does not.
                "down", h2("jdbc:h2:tcp://localhost:1/none")));
        return properties;
    }

    @Test
    @DisplayName("Pools are built without connecting, so an unreachable database does not stop startup")
    void testUnreachableDatabaseDoesNotFailStartup() {
        assertThatCode(() -> new DatabaseConfiguration().jdbcTemplates(properties()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A reachable database answers on first use; an unreachable one fails only when used")
    void testConnectsOnFirstUse() {
        Map<String, JdbcTemplate> templates = new DatabaseConfiguration().jdbcTemplates(properties());
        MultiDatabaseConnector connector = new MultiDatabaseConnector(templates);

        OperationResult<List<Map<String, Object>>> up = connector.executeQuery("up", "SELECT 1 AS one");
        assertThat(up.isSuccess()).as(up.getMessage()).isTrue();

        OperationResult<List<Map<String, Object>>> down = connector.executeQuery("down", "SELECT 1 AS one");
        assertThat(down.isFailure()).isTrue();
        assertThat(down.getErrorType()).contains(ErrorType.DATABASE_ERROR);
    }
}
```

Before running, check that `DatabaseProperties` has a public no-arg constructor and that `DatabaseConfig` is a public static class with these setters (`grep -n "public" idm-connector-database/src/main/java/com/idm/test/connector/database/config/DatabaseProperties.java`). Also check that `MultiDatabaseConnector` has a public `MultiDatabaseConnector(Map<String, JdbcTemplate>)` constructor and `executeQuery(String, String, Object...)`. Adjust only the setter argument types if they differ, for example `Integer` versus `int`.

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./mvnw test -pl idm-connector-database -am -Dtest=DatabaseConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: both tests fail, because building the `down` pool throws `RuntimeException: Failed to configure database: down`. The eager `HikariDataSource(config)` connects immediately.

- [ ] **Step 3: Make the pools lazy**

In `DatabaseConfiguration.createDataSource`, replace `return new HikariDataSource(hikariConfig);` with:

```java
        // Lazy on purpose: the no-arg HikariDataSource starts its pool on the first
        // getConnection(), so a database no scenario uses is never contacted, not even by the
        // min-idle housekeeper. A used database that is unreachable fails its first query, as a
        // DATABASE_ERROR, instead of stopping the whole context.
        HikariDataSource dataSource = new HikariDataSource();
        hikariConfig.copyStateTo(dataSource);
        return dataSource;
```

Update the class Javadoc ("Creates a HikariCP connection pool for each database") to say that each pool is created lazily, on first use.

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./mvnw test -pl idm-connector-database -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips.

- [ ] **Step 5: Falsification**

Restore `return new HikariDataSource(hikariConfig);` temporarily. `testUnreachableDatabaseDoesNotFailStartup` fails. Revert.

- [ ] **Step 6: Commit**

```bash
git add idm-connector-database
git commit -m "feat: open each database pool on first use, not at startup

ITF-30
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: SOAP steps fail cleanly without their connector

**Files:**
- Modify, in `idm-test-engine/src/main/java/com/idm/test/engine/steps/`:
  - `RolesGrantStep.java`
  - `RolesRevokeStep.java`
  - `ResourcesGrantStep.java`
  - `ResourcesRevokeStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/SoapStepsTest.java`

**Interfaces:**
- Produces: the four step constructors accept `null`. `execute` then returns the final failure given in the Global Constraints.

- [ ] **Step 1: Write the failing test**

Add to `SoapStepsTest`:

```java
    @Test
    @DisplayName("A SOAP step built without its connector fails finally, naming the property to set")
    void testAbsentConnectorIsFinalFailure() {
        Map<String, Object> grantParams = Map.of("user", "cn=u,ou=users", "roles", List.of("cn=r,ou=roles"));
        StepOutcome roleGrant = new RolesGrantStep(null).execute(grantParams, false);
        assertThat(roleGrant.success()).isFalse();
        assertThat(roleGrant.isFinal()).isTrue();
        assertThat(roleGrant.message())
                .isEqualTo("roles.grant cannot run: no Role service is configured (idm.test.soap.url)");

        StepOutcome roleRevoke = new RolesRevokeStep(null).execute(
                Map.of("assignments", List.of("cn=a")), false);
        assertThat(roleRevoke.isFinal()).isTrue();
        assertThat(roleRevoke.message())
                .isEqualTo("roles.revoke cannot run: no Role service is configured (idm.test.soap.url)");

        Map<String, Object> resourceParams = Map.of("user", "cn=u,ou=users", "resource", "cn=res");
        StepOutcome resourceGrant = new ResourcesGrantStep(null).execute(resourceParams, false);
        assertThat(resourceGrant.isFinal()).isTrue();
        assertThat(resourceGrant.message()).isEqualTo(
                "resources.grant cannot run: no Resource service is configured (idm.test.soap.resource-url)");

        StepOutcome resourceRevoke = new ResourcesRevokeStep(null).execute(resourceParams, false);
        assertThat(resourceRevoke.isFinal()).isTrue();
        assertThat(resourceRevoke.message()).isEqualTo(
                "resources.revoke cannot run: no Resource service is configured (idm.test.soap.resource-url)");
    }
```

Add `import java.util.List;` if it's missing.

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=SoapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, with a `NullPointerException` or a message mismatch.

- [ ] **Step 3: Implement**

Make the **first statement** of each step's `execute` the following, with its own name and property.

`RolesGrantStep`:
```java
        if (connector == null) {
            return StepOutcome.finalFailure(
                    "roles.grant cannot run: no Role service is configured (idm.test.soap.url)");
        }
```
`RolesRevokeStep`:
```java
        if (connector == null) {
            return StepOutcome.finalFailure(
                    "roles.revoke cannot run: no Role service is configured (idm.test.soap.url)");
        }
```
`ResourcesGrantStep`:
```java
        if (connector == null) {
            return StepOutcome.finalFailure(
                    "resources.grant cannot run: no Resource service is configured (idm.test.soap.resource-url)");
        }
```
`ResourcesRevokeStep`:
```java
        if (connector == null) {
            return StepOutcome.finalFailure(
                    "resources.revoke cannot run: no Resource service is configured (idm.test.soap.resource-url)");
        }
```

To each constructor's Javadoc (or a new one), add: `@param connector the SOAP connector, or null when the service is not configured; execute then fails without calling anything`.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips.

- [ ] **Step 5: Falsification**

Remove the null check from `ResourcesRevokeStep`. The test fails with an NPE. Revert.

- [ ] **Step 6: Commit**

```bash
git add idm-test-engine
git commit -m "feat: fail a SOAP step cleanly when its service is not configured

ITF-30
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `ScenarioNeeds`, conditional guards and the database guard

**Files:**
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioNeeds.java`
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioNeedsTest.java`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentGuardsTest.java`

**Interfaces:**
- Consumes (Task 2): the SOAP steps accept `null` connectors.
- Produces:
  - `record ScenarioNeeds(boolean ldap, Set<String> databases, boolean roles, boolean resources, Set<String> restTargets)`, with `static ScenarioNeeds of(List<Scenario>)` and `boolean soap()`
  - In `RealEnvironmentScenarios`:
    - `static void requireNotEmbedded(Environment)`
    - `static void requireEndpoints(Environment, ScenarioNeeds)`
    - `static void requireRealTarget(Environment, ScenarioNeeds)`, which calls both
    - `static void requireDatabases(Set<String>, DatabaseProperties)`
    - `record DatabasePlaceholder(String jdbcUrl, String username, String password)`
    - `static final Map<String, DatabasePlaceholder> DATABASE_PLACEHOLDERS`
  - `restTargets(List<Scenario>)` and `usesSoap(List<Scenario>)` remain, as delegates.

- [ ] **Step 1: Write `ScenarioNeedsTest` (failing)**

```java
package com.idm.test.scenarios.engine;

import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.model.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScenarioNeeds")
class ScenarioNeedsTest {

    private static ScenarioNeeds needs(String yaml) {
        Scenario scenario = ScenarioLoader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "t.yaml");
        return ScenarioNeeds.of(List.of(scenario));
    }

    @Test
    @DisplayName("Each step family sets its own need, and nothing else")
    void testEachFamily() {
        ScenarioNeeds ldap = needs("""
                name: l
                steps:
                  - ldap.delete:
                      dn: cn=x,ou=users
                """);
        assertThat(ldap).isEqualTo(new ScenarioNeeds(true, java.util.Set.of(), false, false, java.util.Set.of()));

        ScenarioNeeds all = needs("""
                name: all
                steps:
                  - db.execute:
                      database: workday
                      statement: DELETE FROM t
                  - db.verify:
                      database: sap
                      query: SELECT 1
                      expect: { rowCount: 1 }
                  - roles.grant:
                      user: cn=u
                      roles: [cn=r]
                  - resources.grant:
                      user: cn=u
                      resource: cn=res
                  - rest.request:
                      target: idm
                      method: GET
                      path: /x
                  - http.request:
                      target: roleservice
                      method: POST
                      path: /y
                """);
        assertThat(all.ldap()).isFalse();
        assertThat(all.databases()).containsExactly("sap", "workday");
        assertThat(all.roles()).isTrue();
        assertThat(all.resources()).isTrue();
        assertThat(all.soap()).isTrue();
        assertThat(all.restTargets()).containsExactly("idm", "roleservice");
    }

    @Test
    @DisplayName("Entries only in cleanup count")
    void testCleanupCounts() {
        ScenarioNeeds needs = needs("""
                name: c
                steps:
                  - set:
                      x: 1
                cleanup:
                  - db.execute:
                      database: sap
                      statement: DELETE FROM t
                  - resources.revoke:
                      user: cn=u
                      resource: cn=res
                """);
        assertThat(needs.databases()).containsExactly("sap");
        assertThat(needs.resources()).isTrue();
        assertThat(needs.roles()).isFalse();
    }

    @Test
    @DisplayName("Run-time names and nulls are skipped; non-strings are converted")
    void testLiteralRules() {
        ScenarioNeeds needs = needs("""
                name: r
                fixtures:
                  cfg:
                    values: { db: sap }
                steps:
                  - db.verify:
                      database: "${cfg.db}"
                      query: SELECT 1
                      expect: { rowCount: 1 }
                  - db.verify:
                      database: 1
                      query: SELECT 1
                      expect: { rowCount: 1 }
                  - rest.request:
                      method: GET
                      path: /x
                """);
        assertThat(needs.databases()).containsExactly("1");
        assertThat(needs.restTargets()).isEmpty();
    }

    @Test
    @DisplayName("A scenario with only set: needs nothing")
    void testNothing() {
        assertThat(needs("""
                name: n
                steps:
                  - set:
                      x: 1
                """)).isEqualTo(new ScenarioNeeds(false, java.util.Set.of(), false, false, java.util.Set.of()));
    }
}
```

`ScenarioLoader.load` doesn't validate parameters, so any step map loads. `rest.request` without `target` gives a `null` target, which is skipped.

- [ ] **Step 2: Create `ScenarioNeeds`**

```java
package com.idm.test.scenarios.engine;

import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the selected scenarios use: LDAP, named databases, the Role and Resource services, and
 * REST targets. The real-environment guards check, and the run contacts, only these. Read
 * from every scenario's steps and cleanup. A name containing {@code ${} is only known at run
 * time and is skipped; a null name is skipped; anything else is converted with
 * {@code String.valueOf}, as the steps themselves do.
 */
record ScenarioNeeds(boolean ldap, Set<String> databases, boolean roles, boolean resources,
                     Set<String> restTargets) {

    ScenarioNeeds {
        databases = Collections.unmodifiableSortedSet(new TreeSet<>(databases));
        restTargets = Collections.unmodifiableSortedSet(new TreeSet<>(restTargets));
    }

    static ScenarioNeeds of(List<Scenario> scenarios) {
        boolean ldap = false;
        boolean roles = false;
        boolean resources = false;
        Set<String> databases = new TreeSet<>();
        Set<String> targets = new TreeSet<>();
        for (Scenario scenario : scenarios) {
            List<StepSpec> entries = new ArrayList<>(scenario.steps());
            entries.addAll(scenario.cleanup());
            for (StepSpec step : entries) {
                String type = step.type();
                if (type.startsWith("ldap.")) {
                    ldap = true;
                } else if (type.startsWith("db.")) {
                    addLiteral(databases, step.params().get("database"));
                } else if (type.startsWith("roles.")) {
                    roles = true;
                } else if (type.startsWith("resources.")) {
                    resources = true;
                } else if (type.startsWith("rest.") || type.startsWith("http.")) {
                    addLiteral(targets, step.params().get("target"));
                }
            }
        }
        return new ScenarioNeeds(ldap, databases, roles, resources, targets);
    }

    /** Whether any SOAP service is used, which is when guard 5 checks SOAP credentials. */
    boolean soap() {
        return roles || resources;
    }

    private static void addLiteral(Set<String> into, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (!text.contains("${")) {
            into.add(text);
        }
    }
}
```

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=ScenarioNeedsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 4 tests. If `Scenario` has no `cleanup()` or `params()` returns another type, check `idm-test-engine/src/main/java/com/idm/test/engine/model/` and adapt the accessor names only.

- [ ] **Step 3: Write the failing guard tests**

In `RealEnvironmentGuardsTest`:

(a) Add a constant and import `com.idm.test.connector.database.config.DatabaseProperties`:

```java
    /** What the existing guard-2 tests assumed: LDAP and the Role service are used. */
    private static final ScenarioNeeds LDAP_AND_ROLES = new ScenarioNeeds(true, Set.of(), true, false, Set.of());
    private static final ScenarioNeeds NOTHING = new ScenarioNeeds(false, Set.of(), false, false, Set.of());
```

(b) Replace **every** `RealEnvironmentScenarios.requireRealTarget(X)` call in this file with `RealEnvironmentScenarios.requireRealTarget(X, LDAP_AND_ROLES)`. Those tests keep their meaning.

(c) Add these tests:

```java
    private static DatabaseProperties databases(String name, String url, String user, String password) {
        DatabaseProperties.DatabaseConfig config = new DatabaseProperties.DatabaseConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        DatabaseProperties properties = new DatabaseProperties();
        properties.setDatabases(Map.of(name, config));
        return properties;
    }

    @Test
    @DisplayName("Guard 2 ignores LDAP and SOAP URLs that no selected scenario uses, even placeholders or unset")
    void testUnusedEndpointsIgnored() {
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(
                target(RealEnvironmentScenarios.LDAP_URL_PLACEHOLDER, null, "dev"), NOTHING))
                .doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(
                target(null, RealEnvironmentScenarios.SOAP_URL_PLACEHOLDER, "dev"),
                new ScenarioNeeds(false, Set.of(), false, false, Set.of("idm"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Guard 1 still refuses the embedded profile when nothing is used")
    void testEmbeddedRefusedEvenWithNoNeeds() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(null, null, "test"), NOTHING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'test'");
    }

    @Test
    @DisplayName("Guard 2 checks the LDAP URL only when ldap.* is used, and the SOAP URL only when roles.* is")
    void testEndpointsCheckedOnlyWhenUsed() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(null, REAL_SOAP, "dev"),
                new ScenarioNeeds(true, Set.of(), false, false, Set.of())))
                .hasMessageContaining("IDM_TEST_LDAP_URL");
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, null, "dev"),
                new ScenarioNeeds(true, Set.of(), false, false, Set.of())))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(null, null, "dev"),
                new ScenarioNeeds(false, Set.of(), true, false, Set.of())))
                .hasMessageContaining("IDM_TEST_SOAP_URL");
    }

    @Test
    @DisplayName("Guard 2 requires the resource URL only when resources.* is used")
    void testResourceUrlOnlyWhenUsed() {
        ScenarioNeeds resources = new ScenarioNeeds(false, Set.of(), false, true, Set.of());
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(null, null, "dev"), resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.soap.resource-url")
                .hasMessageContaining("IDM_TEST_SOAP_RESOURCEURL");

        MockEnvironment configured = target(null, null, "dev");
        configured.setProperty("idm.test.soap.resource-url", "https://idm.example.org/IDMProv/resource/service");
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(configured, resources))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The DB guard refuses a used database that is not configured, listing the configured ones")
    void testUnconfiguredDatabaseRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("hr"),
                databases("sap", "jdbc:postgresql://db.example.org/sap", "svc", "real")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'hr'")
                .hasMessageContaining("[sap]");
    }

    @Test
    @DisplayName("The DB guard refuses a blank or placeholder URL and placeholder credentials, never echoing a credential")
    void testDatabasePlaceholdersRefused() {
        RealEnvironmentScenarios.DatabasePlaceholder sap = RealEnvironmentScenarios.DATABASE_PLACEHOLDERS.get("sap");

        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("sap"),
                databases("sap", " ", "svc", "real")))
                .hasMessageContaining("IDM_TEST_DATABASES_SAP_JDBCURL");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("sap"),
                databases("sap", sap.jdbcUrl(), "svc", "real")))
                .hasMessageContaining("idm.test.databases.sap.jdbc-url")
                .hasMessageContaining("placeholder");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("sap"),
                databases("sap", "jdbc:postgresql://db.example.org/sap", sap.username(), "real")))
                .hasMessageContaining("IDM_TEST_DATABASES_SAP_USERNAME");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("sap"),
                databases("sap", "jdbc:postgresql://db.example.org/sap", "svc", sap.password())))
                .hasMessageContaining("IDM_TEST_DATABASES_SAP_PASSWORD")
                .hasMessageNotContaining(sap.password());
    }

    @Test
    @DisplayName("The DB guard passes a real configuration and ignores databases no scenario uses")
    void testDatabaseGuardPasses() {
        DatabaseProperties placeholders = databases("sap",
                RealEnvironmentScenarios.DATABASE_PLACEHOLDERS.get("sap").jdbcUrl(), "sapuser", "sappass");
        assertThatCode(() -> RealEnvironmentScenarios.requireDatabases(Set.of(), placeholders))
                .doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireDatabases(Set.of("sap"),
                databases("sap", "jdbc:postgresql://db.example.org/sap", "svc", "real")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The database placeholders match the fallbacks application.yml declares")
    void testDatabasePlaceholdersMatchApplicationYml() throws IOException {
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));
        Map<String, String> prefixes = Map.of("sap", "SAP_DB", "oracle-hr", "HR_DB", "workday", "WORKDAY_DB");

        assertThat(RealEnvironmentScenarios.DATABASE_PLACEHOLDERS).containsOnlyKeys("sap", "oracle-hr", "workday");
        RealEnvironmentScenarios.DATABASE_PLACEHOLDERS.forEach((name, placeholder) -> assertThat(yaml)
                .contains("${" + prefixes.get(name) + "_URL:" + placeholder.jdbcUrl() + "}")
                .contains("${" + prefixes.get(name) + "_USER:" + placeholder.username() + "}")
                .contains("${" + prefixes.get(name) + "_PASSWORD:" + placeholder.password() + "}"));
    }
```

`target(ldap, soap, profiles...)` already exists and returns a `MockEnvironment`. Check that its return type is `MockEnvironment`; if it isn't, create the environment inline for `configured`.

- [ ] **Step 4: Run the tests and confirm they fail to compile**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentGuardsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR. `requireRealTarget(Environment, ScenarioNeeds)`, `requireDatabases` and `DATABASE_PLACEHOLDERS` don't exist yet.

- [ ] **Step 5: Implement in `RealEnvironmentScenarios`**

Add these imports:
- `com.idm.test.connector.database.config.DatabaseProperties`
- `org.springframework.beans.factory.ObjectProvider`

(a) **Placeholders**, next to the other placeholder constants:

```java
    /** application.yml's fallback URL and credentials per database. Pinned by RealEnvironmentGuardsTest. */
    record DatabasePlaceholder(String jdbcUrl, String username, String password) {
    }

    static final Map<String, DatabasePlaceholder> DATABASE_PLACEHOLDERS = Map.of(
            "sap", new DatabasePlaceholder("jdbc:postgresql://localhost:5432/sap", "sapuser", "sappass"),
            "oracle-hr", new DatabasePlaceholder("jdbc:oracle:thin:@localhost:1521:HRDB", "hruser", "hrpass"),
            "workday", new DatabasePlaceholder("jdbc:postgresql://localhost:5432/workday", "wdayuser", "wdaypass"));
```

(b) **Fields.**
- Replace the `RoleServiceConnector` and `ResourceServiceConnector` fields with:
  ```java
      @Autowired
      private ObjectProvider<RoleServiceConnector> roleServiceConnector;

      @Autowired
      private ObjectProvider<ResourceServiceConnector> resourceServiceConnector;
  ```
- Add:
  ```java
      @Autowired
      private DatabaseProperties databaseProperties;
  ```

(c) **`scenarios()`:** replace the guard lines and the registry call:

```java
        requireNotEmbedded(environment);
        String scenarioDir = scenarioDirectory();
        requireScenarios(YamlScenarioTest.scenarioFiles(scenarioDir), scenarioDir);
        // After guard 3, so an empty directory gets its README message and only a pattern
        // that matches nothing gets the filter's message.
        List<String> selected = YamlScenarioTest.selectedScenarioFiles(scenarioDir);
        // Everything below checks only what the selected scenarios use.
        ScenarioNeeds needs = ScenarioNeeds.of(loadForGuards(scenarioDir, selected));
        requireEndpoints(environment, needs);
        requireRestTargets(needs.restTargets(), restProperties);
        requireDatabases(needs.databases(), databaseProperties);
        requireCredentials(needs.restTargets(), needs.soap(), restProperties, soapProperties, environment);

        Map<String, StepType> registry = YamlScenarioTest.registry(
                edirectoryConnector, databaseConnector, roleServiceConnector.getIfAvailable(),
                resourceServiceConnector.getIfAvailable(), idmRestConnector, restProperties, restAuthenticator);
```

(d) **Split guards 1 and 2.** Replace the existing `requireRealTarget(Environment environment)` with:

```java
    /** Guards 1 and 2 together, as the guard tests use them. */
    static void requireRealTarget(Environment environment, ScenarioNeeds needs) {
        requireNotEmbedded(environment);
        requireEndpoints(environment, needs);
    }

    /** Guard 1. The embedded test configuration is never a real target. */
    static void requireNotEmbedded(Environment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains(EMBEDDED_PROFILE)) {
            throw new IllegalStateException("Active profiles " + Arrays.toString(environment.getActiveProfiles())
                    + " include '" + EMBEDDED_PROFILE + "', the embedded test configuration. Set IDM_TEST_PROFILE"
                    + " to the profile that names your real environment, for example IDM_TEST_PROFILE=dev.");
        }
    }

    /**
     * Guard 2. Refuses an LDAP or SOAP endpoint that the selected scenarios use but nobody
     * named: the LDAP URL when ldap.* is used, the Role service URL when roles.* is, and a
     * Resource service URL when resources.* is. application.yml has no placeholder for the
     * resource URL, so there it is only required to be set. Endpoints no scenario uses are
     * not checked.
     */
    static void requireEndpoints(Environment environment, ScenarioNeeds needs) {
        if (needs.ldap()) {
            requireNamed(environment, "idm.test.ldap.url", "IDM_TEST_LDAP_URL", LDAP_URL_PLACEHOLDER);
        }
        if (needs.roles()) {
            requireNamed(environment, "idm.test.soap.url", "IDM_TEST_SOAP_URL", SOAP_URL_PLACEHOLDER);
        }
        if (needs.resources()) {
            requireNamed(environment, "idm.test.soap.resource-url", "IDM_TEST_SOAP_RESOURCEURL", null);
        }
    }
```

The guard-1 message is copied unchanged from the old method. If the old method's text differs from the text above, keep the old text.

(e) **The DB guard**, after `requireRestTargetsIn`:

```java
    /**
     * Guard DB. For each database the selected scenarios use, refuses one that is not
     * configured, and one whose jdbc-url, username or password is still application.yml's
     * placeholder. Databases no scenario uses are not checked, and are never contacted, because
     * their pools open on first use. Messages name properties and variables, never a value.
     */
    static void requireDatabases(Set<String> databases, DatabaseProperties properties) {
        for (String name : new TreeSet<>(databases)) {
            DatabaseProperties.DatabaseConfig config = properties.getDatabases().get(name);
            if (config == null) {
                throw new IllegalStateException("Scenarios use database '" + name
                        + "', which is not configured under idm.test.databases. Configured databases: "
                        + new TreeSet<>(properties.getDatabases().keySet()));
            }
            String prefix = "idm.test.databases." + name + ".";
            DatabasePlaceholder placeholder = DATABASE_PLACEHOLDERS.get(name);
            requireNamedValue(prefix + "jdbc-url", config.getJdbcUrl(), variableFor(prefix + "jdbc-url"),
                    placeholder == null ? null : placeholder.jdbcUrl());
            if (placeholder != null && placeholder.username().equals(config.getUsername())) {
                refusePlaceholder(prefix + "username");
            }
            if (placeholder != null && placeholder.password().equals(config.getPassword())) {
                refusePlaceholder(prefix + "password");
            }
        }
    }
```

(f) **Delegates.** `restTargets(List<Scenario>)` returns `ScenarioNeeds.of(scenarios).restTargets()`, and `usesSoap(List<Scenario>)` returns `ScenarioNeeds.of(scenarios).soap()`. Move their old Javadoc rules into `ScenarioNeeds`, and leave a one-line Javadoc on each delegate. `requireCredentialsIn` computes `ScenarioNeeds needs = ScenarioNeeds.of(loadForGuards(...))` and calls `requireCredentials(needs.restTargets(), needs.soap(), ...)`.

(g) **Javadoc.** Update the class Javadoc and `loadForGuards`' Javadoc to mention that the guards check only what the selected scenarios use.

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='RealEnvironmentGuardsTest,ScenarioNeedsTest,RealEnvironmentScenariosStructureTest,ContainerScenariosStructureTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass, with 0 failures, errors and skips.

- [ ] **Step 7: Falsification**

Make each change below, re-run `RealEnvironmentGuardsTest`, confirm that the named test fails, then revert:
- Make `requireEndpoints` check LDAP unconditionally. `testUnusedEndpointsIgnored` fails.
- Drop the `resources` branch. `testResourceUrlOnlyWhenUsed` fails.
- Remove the password placeholder check. `testDatabasePlaceholdersRefused` fails.
- Change `"sappass"` in `DATABASE_PLACEHOLDERS`. `testDatabasePlaceholdersMatchApplicationYml` fails.

- [ ] **Step 8: Run the full suite and commit**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS, 0 failures, errors and skips; `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`.

```bash
git add idm-test-scenarios/src/test/java
git commit -m "feat: check only the repositories the selected real-environment scenarios use

ITF-30
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: An LDAP-only real-environment run, end to end, and documentation

**Files:**
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentLdapOnlyTest.java`
- Create: `idm-test-scenarios/src/test/resources/scenarios-ldap-only/ldap-only.yaml`
- Modify:
  - `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ContainerScenarios.java` (one comment)
  - `idm-test-scenarios/src/test/resources/scenarios-real/README.md` ("What the environment must provide")
  - `docs/reference/running-tests.md` ("Against a real lab")
  - `docs/architecture/capabilities.md` (the "Real-environment runner" row)

**Interfaces:**
- Consumes: `RealEnvironmentScenarios` (Task 3, with `scenarioDirectory()` overridable and `scenarios()` a `@TestFactory`), `ContainerScenarios.requireBoundTo(String, String)` (static, package-private), `EmbeddedLdapServer.start(baseDn, bindDn, password, port, ldif, schema)` and `close()`/`getUrl()`, and `IdmTestSchema.edirectoryLikeSchema()`.

- [ ] **Step 1: The scenario**

`idm-test-scenarios/src/test/resources/scenarios-ldap-only/ldap-only.yaml`:

```yaml
name: LDAP only, through the real-environment runner
description: The runner must start and run with only LDAP configured
fixtures:
  user:
    generate: random-user
steps:
  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      attributes:
        cn: ${user.userId}
        sn: ${user.lastName}
      as: createdDn
  - ldap.verify:
      dn: "${createdDn}"
      exists: true
cleanup:
  - ldap.delete:
      dn: "${createdDn}"
```

- [ ] **Step 2: The test class**

```java
package com.idm.test.scenarios.engine;

import com.idm.test.connector.ldap.config.LdapProperties;
import com.idm.test.scenarios.config.IdmTestSchema;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The real-environment runner with only LDAP configured: an embedded directory stands in for
 * the real one, and nothing else is supplied. No SOAP resource URL, so the Resource service
 * bean does not exist; the databases stay application.yml's unreachable placeholders; no REST
 * credentials. Before ITF-30 this context could not even start. It runs in the default suite
 * (Test suffix) to keep "only what the scenarios use" true.
 * <p>
 * Like ContainerScenarios, it sets system properties before the Spring context loads, restores
 * them afterwards, and dirties the context so no other class reuses it. Running the whole suite
 * with {@code -Dscenario} makes this class fail, because its one file won't match: pair
 * {@code -Dscenario} with {@code -Dtest}, as the docs say.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@DisplayName("Real-environment runner with only LDAP configured")
class RealEnvironmentLdapOnlyTest extends RealEnvironmentScenarios {

    static final String DIR = "scenarios-ldap-only";

    private static final String BASE_DN = "dc=test,dc=com";
    private static final String BIND_DN = "cn=admin,dc=test,dc=com";
    private static final String BIND_PASSWORD = "test";

    private static final Map<String, String> PREVIOUS = new LinkedHashMap<>();
    private static EmbeddedLdapServer ldap;

    @Autowired
    private LdapProperties ldapProperties;

    @BeforeAll
    static void startDirectory() throws Exception {
        ldap = EmbeddedLdapServer.start(BASE_DN, BIND_DN, BIND_PASSWORD, 0, "ldap-init.ldif",
                IdmTestSchema.edirectoryLikeSchema());
        set("idm.test.ldap.url", ldap.getUrl());
        set("idm.test.ldap.base-dn", BASE_DN);
        set("idm.test.ldap.user-dn", BIND_DN);
        set("idm.test.ldap.password", BIND_PASSWORD);
    }

    @AfterAll
    static void stopDirectory() {
        PREVIOUS.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
        PREVIOUS.clear();
        if (ldap != null) {
            ldap.close();
        }
    }

    @TestFactory
    @Override
    @DisplayName("The LDAP-only scenario runs with nothing else configured")
    Stream<DynamicTest> scenarios() throws Exception {
        ContainerScenarios.requireBoundTo(ldapProperties.getUrl(), System.getProperty("idm.test.ldap.url"));
        return super.scenarios();
    }

    @Override
    String scenarioDirectory() {
        return DIR;
    }

    private static void set(String key, String value) {
        if (!PREVIOUS.containsKey(key)) {
            PREVIOUS.put(key, System.getProperty(key));
        }
        System.setProperty(key, value);
    }
}
```

Check before running:
- `EmbeddedLdapServer.start(...)`'s six-argument overload and `close()`, in `idm-test-utils/.../ldap/EmbeddedLdapServer.java`.
- `ContainerScenarios.requireBoundTo` is `static` and not `private`.
- `LdapProperties` is `com.idm.test.connector.ldap.config.LdapProperties`.

If `RealEnvironmentScenariosStructureTest` or `ContainerScenariosStructureTest` asserts something about subclasses that this class breaks, report it rather than weakening that test.

- [ ] **Step 3: Run it**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentLdapOnlyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, and `idm-test-scenarios/target/real-environment-results.json` shows `ldap-only.yaml` passed with its cleanup `SUCCEEDED`.

- [ ] **Step 4: Falsification**

Make each change below, re-run, confirm that the test fails, then revert:
- In `RealEnvironmentScenarios`, change the `ResourceServiceConnector` field back to `@Autowired private ResourceServiceConnector resourceServiceConnector;` and pass it directly. The context fails to start: no bean.
- In `DatabaseConfiguration.createDataSource`, return `new HikariDataSource(hikariConfig)` (eager). The context fails to start, because `sap` at `localhost:5432` is unreachable. If a PostgreSQL happens to be listening on 5432 locally, say so in the report and skip this falsification.

- [ ] **Step 5: Documentation**

**(a) `scenarios-real/README.md`, "What the environment must provide":** replace the refusal bullets for LDAP/SOAP URLs and the paragraph that begins "The Spring context will not start unless the following are also true:", with its two bullets, with:

```markdown
The runner checks, and connects to, **only what the selected scenarios use**. A repository no
scenario touches can stay as `application.yml`'s placeholder, and it is never contacted.

| Your scenarios use | You must configure (and not leave as the placeholder) |
|---|---|
| `ldap.*` | `idm.test.ldap.url` (`IDM_TEST_LDAP_URL`), plus the bind DN and password |
| `db.*` with `database: <name>` | `idm.test.databases.<name>.jdbc-url`, `username`, `password` |
| `roles.*` | `idm.test.soap.url` (`IDM_TEST_SOAP_URL`) and SOAP credentials |
| `resources.*` | `idm.test.soap.resource-url` (`IDM_TEST_SOAP_RESOURCEURL`) and SOAP credentials |
| `rest.*` / `http.request` with `target: <name>` | `idm.test.rest.<name>.base-url` and its credentials |

Database placeholders refused: `sap` `jdbc:postgresql://localhost:5432/sap` (`sapuser`/`sappass`),
`oracle-hr` `jdbc:oracle:thin:@localhost:1521:HRDB` (`hruser`/`hrpass`), `workday`
`jdbc:postgresql://localhost:5432/workday` (`wdayuser`/`wdaypass`). A database really at that
exact localhost URL must be named another way (for example `127.0.0.1`).

Database pools open on first use: a used database that cannot be reached fails the first step
that touches it, not the whole run. A name written as `${...}` is only known at run time and is
not checked in advance.
```

Keep the existing guard-1 and guard-3 bullets (the embedded profile, an empty directory), and the REST and credentials text (guards 4 and 5).

**(b) `docs/reference/running-tests.md`, "Against a real lab":** replace the five-item list with:

```markdown
1. the embedded `test` profile
2. an empty scenario directory
3. for the LDAP and SOAP services the selected scenarios use: a URL that is unset or still the placeholder
4. for the REST targets and databases they use: one that is unconfigured, or still the placeholder
5. placeholder or half-given credentials for the REST targets and SOAP services they use

Nothing the selected scenarios don't use is checked or contacted.
```

**(c) `docs/architecture/capabilities.md`, the "Real-environment runner" row:** in its last cell, replace `Five guards refuse a run that would test nothing real (embedded profile, placeholder URLs, empty directory, placeholder REST targets, placeholder or half-given credentials).` with:

```markdown
Guards refuse a run that would test nothing real, checking only what the selected scenarios use (ITF-30): the embedded profile, an empty directory, and for used repositories an unset or placeholder LDAP/SOAP URL, REST target, database or credential. Unused repositories are never contacted; database pools open on first use.
```

**(d) `ContainerScenarios.java`:** its comment above `DATABASES` says every configured database must accept a connection for the context to start. That is no longer true. Replace it with: `// The corpus uses test-db; pointing all four at the one PostgreSQL keeps any db.* step working. oracle-hr here is a stand-in and says nothing about Oracle.` Do not change the list.

- [ ] **Step 6: Full suite and commit**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS, 0 failures, errors and skips; the corpus has 0 FAILED. Report the total from `grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc`. If `docker info` succeeds, also run `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false`, expecting 20/20; otherwise say Docker was unavailable.

```bash
git add idm-test-scenarios docs
git commit -m "test: run an LDAP-only real-environment scenario end to end; document used-only rules

ITF-30
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
