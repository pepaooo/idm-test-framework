# No Spring Boot DataSource Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The scenario contexts (embedded suite, real-environment and container runners) stop creating Spring Boot's own unused embedded H2 DataSource and stop re-running `schema.sql` against it.

**Architecture:** Add `DataSourceAutoConfiguration.class` to the existing `@EnableAutoConfiguration(exclude = …)` on `BaseIntegrationTest` and `RealEnvironmentScenarios` (`ContainerScenarios` inherits it), delete the `test` profile's dead `spring.sql.init` block, correct `TestSchemaInitializer`'s javadoc, and prove the absence with context and structure tests.

**Tech Stack:** Java 21, Spring Boot 3.5.6, JUnit 5, AssertJ.

**Spec:** `docs/specs/2026-09-29-no-boot-datasource-design.md` (commit 88f0f13). **Ticket:** ITF-32.

## Global Constraints

- **Both annotations become exactly** `@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class, DataSourceAutoConfiguration.class})` on `BaseIntegrationTest` and `RealEnvironmentScenarios`. No other auto-configuration is excluded; Boot's JdbcTemplate, transaction-manager and SQL-init auto-configurations must back off on their own, and the tests prove it.
- **`TestSchemaInitializer`'s behaviour is unchanged**; it remains the only thing that runs `schema.sql`, against `test-db`.
- **No `@DynamicPropertySource`, `@TestPropertySource`, `@ActiveProfiles` or `@SpringBootTest(properties/args)`** is added to `RealEnvironmentScenarios` or `ContainerScenarios` (their structure tests forbid it).
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. "Unresolved compilation problems" means stale classes: run `./mvnw clean test`. Green means 0 failures, errors and skips.
- **Commits** end with a blank line, `ITF-32`, and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: Exclude Boot's DataSource, prove it, and document it

**Files:**
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/config/NoBootDataSourceTest.java`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/base/BaseIntegrationTest.java` (the `@EnableAutoConfiguration` line, ~61, and its import)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java` (the `@EnableAutoConfiguration` line, ~77, and its import)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenariosStructureTest.java` (one new test)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentLdapOnlyTest.java` (one new test)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/config/TestSchemaInitializer.java` (javadoc only)
- Modify: `idm-test-scenarios/src/test/resources/application.yml` (delete the `test` profile's `spring.sql.init` block, ~lines 275-280)
- Modify: `docs/architecture/capabilities.md` (two rows)

**Interfaces:** none new; this task only changes configuration and adds tests.

- [ ] **Step 1: Write the failing tests**

`NoBootDataSourceTest.java`:

```java
package com.idm.test.scenarios.config;

import com.idm.test.scenarios.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot must not build its own embedded DataSource in the scenario contexts (ITF-32). The
 * framework uses only its named pools (the {@code jdbcTemplates} map), and
 * {@link TestSchemaInitializer} creates the {@code test-db} schema; Boot's DataSource only ever
 * received a second, unused copy of {@code schema.sql}.
 */
@DisplayName("Spring Boot's own DataSource is excluded")
class NoBootDataSourceTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("No DataSource, JdbcTemplate, SQL-init initializer or transaction manager bean exists")
    void testNoBootDataSourceBeans() {
        assertThat(context.getBeanNamesForType(DataSource.class)).as("DataSource beans").isEmpty();
        assertThat(context.getBeanNamesForType(JdbcTemplate.class)).as("JdbcTemplate beans").isEmpty();
        assertThat(context.getBeanNamesForType(DataSourceScriptDatabaseInitializer.class))
                .as("spring.sql.init initializers").isEmpty();
        assertThat(context.getBeanNamesForType(PlatformTransactionManager.class))
                .as("transaction managers").isEmpty();
    }

    @Test
    @DisplayName("test-db still has the schema TestSchemaInitializer creates")
    @SuppressWarnings("unchecked")
    void testTestDbSchemaExists() {
        Map<String, JdbcTemplate> templates = (Map<String, JdbcTemplate>) context.getBean("jdbcTemplates");

        Integer users = templates.get("test-db").queryForObject("SELECT COUNT(*) FROM users", Integer.class);

        assertThat(users).isNotNull();
    }
}
```

In `RealEnvironmentScenariosStructureTest.java`, add (imports: `org.springframework.boot.autoconfigure.EnableAutoConfiguration`, `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`, `org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration`, `java.util.List`):

```java
    @Test
    @DisplayName("Excludes Spring Boot's LDAP and DataSource auto-configuration, for the container runner too")
    void testExcludesBootLdapAndDataSource() {
        for (Class<?> runner : List.of(RUNNER, ContainerScenarios.class)) {
            Class<?>[] excluded = MergedAnnotations.from(runner, SearchStrategy.TYPE_HIERARCHY)
                    .get(EnableAutoConfiguration.class)
                    .getClassArray("exclude");

            assertThat(excluded).as(runner.getSimpleName())
                    .contains(LdapAutoConfiguration.class, DataSourceAutoConfiguration.class);
        }
    }
```

In `RealEnvironmentLdapOnlyTest.java`, add (imports: `org.springframework.context.ApplicationContext`, `org.junit.jupiter.api.Test`, `javax.sql.DataSource`, and the static `assertThat` if missing):

```java
    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("The real runner's context creates no Spring Boot DataSource (ITF-32)")
    void testNoBootDataSource() {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='NoBootDataSourceTest,RealEnvironmentScenariosStructureTest,RealEnvironmentLdapOnlyTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `testNoBootDataSourceBeans` fails (a `dataSource` bean exists), `testExcludesBootLdapAndDataSource` fails (no `DataSourceAutoConfiguration` in the excludes), `RealEnvironmentLdapOnlyTest.testNoBootDataSource` fails; `testTestDbSchemaExists` passes.

- [ ] **Step 3: Exclude the DataSource auto-configuration**

In both `BaseIntegrationTest.java` and `RealEnvironmentScenarios.java`, change

```java
@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class})
```

to

```java
@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class, DataSourceAutoConfiguration.class})
```

and add `import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;`. Where a class javadoc explains the LDAP exclusion (for example `BaseIntegrationTest`'s "Excludes Spring Boot's LdapAutoConfiguration since we provide custom LDAP configuration."), extend it with: `and DataSourceAutoConfiguration, whose own embedded H2 the framework never used (ITF-32): databases are the named pools of DatabaseConfiguration.`

- [ ] **Step 4: Delete the dead `spring.sql.init` block**

In `idm-test-scenarios/src/test/resources/application.yml`, in the `test` profile document, delete these lines (the comment and the block), leaving `spring.config.activate.on-profile: test` in place:

```yaml

  # Initialize H2 databases with schema
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
      continue-on-error: false
```

- [ ] **Step 5: Correct `TestSchemaInitializer`'s javadoc**

Replace its first paragraph after the summary line:

```java
 * {@code spring.sql.init} cannot do this: DatabaseConfiguration builds its Hikari
 * DataSources privately inside the jdbcTemplates map bean, so no DataSource bean is
 * exposed for Spring Boot to initialize. schema.sql was configured but never ran,
 * leaving every scenario query failing with "bad SQL grammar".
```

with

```java
 * {@code spring.sql.init} cannot do this: DatabaseConfiguration builds its Hikari
 * DataSources privately inside the jdbcTemplates map bean, so no DataSource bean is
 * exposed for Spring Boot to initialize. When {@code spring.sql.init} was configured it ran
 * schema.sql against Spring Boot's own embedded H2, never against test-db; that
 * DataSource is now excluded (ITF-32), so this class is the only thing that runs schema.sql.
```

(Keep the rest of the javadoc and all code unchanged.)

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='NoBootDataSourceTest,RealEnvironmentScenariosStructureTest,ContainerScenariosStructureTest,RealEnvironmentLdapOnlyTest,ConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass.

If `testNoBootDataSourceBeans` still finds a `JdbcTemplate`, SQL-init initializer or transaction manager, find which auto-configuration or bean creates it and report it as a concern, rather than adding more excludes or weakening the assertion.

- [ ] **Step 7: Falsify**

One at a time, make the change, run the tests from Step 6, confirm the named test fails, then revert:
- remove `DataSourceAutoConfiguration.class` from `BaseIntegrationTest` → `NoBootDataSourceTest.testNoBootDataSourceBeans` fails
- remove it from `RealEnvironmentScenarios` → `testExcludesBootLdapAndDataSource` and `RealEnvironmentLdapOnlyTest.testNoBootDataSource` fail

Record each result in the report.

- [ ] **Step 8: Update the capability matrix**

In `docs/architecture/capabilities.md`:
- in the "Embedded scenario environment" row's last cell, append: `. Spring Boot's own DataSource is excluded (ITF-32); TestSchemaInitializer alone creates the test-db schema`
- in the "Real-environment runner" row's last cell, after `no embedded LDAP or WireMock server is started (ITF-17);`, insert ` and Spring Boot creates no embedded H2 database of its own (ITF-32);`

Then check no current doc still says `schema.sql` is applied through `spring.sql.init`:

Run: `git grep -n "sql.init" -- '*.md' ':!docs/history' ':!docs/specs' ':!docs/plans' ':!docs/superpowers'`
Expected: no output.

- [ ] **Step 9: Run the full suite**

Run: `./mvnw -q clean test`
Expected: exit 0.

Then:
```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -h -o 'failures="[0-9]*"\|errors="[0-9]*"\|skipped="[0-9]*"' */target/surefire-reports/TEST-*.xml | grep -v '"0"' | wc -l
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json idm-test-scenarios/target/multi-directory-results.json
```
Expected: 732 tests (728 plus 2 in `NoBootDataSourceTest`, 1 structure test and 1 `RealEnvironmentLdapOnlyTest` test); `0` non-zero attributes; both FAILED counts `0`. Include the command output in the report. If `EmbeddedLdapServerTest.testLdifImport` or `LdapStepsTest` fails with a closed-connection error (known intermittent flakes), re-run once and report both runs.

If `docker info` succeeds, also run `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false` and confirm 20 tests, 0 failures from `idm-test-scenarios/target/surefire-reports/TEST-com.idm.test.scenarios.engine.ContainerScenarios.xml`. Otherwise report that Docker isn't available.

- [ ] **Step 10: Commit**

```bash
git add idm-test-scenarios docs/architecture/capabilities.md
git commit -m "fix: exclude Spring Boot's own DataSource, so no stray H2 re-runs schema.sql

ITF-32

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
