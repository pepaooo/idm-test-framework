# Real-Environment Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the team run YAML scenarios against a real OpenText IdM / eDirectory by adding one standalone runner class, `RealEnvironmentScenarios`, without changing any existing test.

**Architecture:** A new `@SpringBootTest` class in `com.idm.test.scenarios.engine` that extends nothing, pins no profile and overrides no property, so every `idm.test.*` value comes from `application.yml` plus the active profile. It reuses `YamlScenarioTest`'s existing package-private statics `registry(...)`, `scenarioFiles(...)` and `buildTests(...)`. Three guards run before any scenario, and they fail loudly on configurations that would otherwise report green while testing nothing real. Surefire skips the class by default because its name has no `Test` suffix.

**Tech Stack:** Java 21, Spring Boot 3.5.6, JUnit 5 `@TestFactory`, AssertJ, Spring's `MockEnvironment` (spring-test, already on the classpath through `spring-boot-starter-test`).

**Spec:** `docs/specs/2026-09-22-real-environment-runner-design.md`. **Ticket:** ITF-12.

## Global Constraints

- **No existing file is modified.** In particular `BaseIntegrationTest`, its seven subclasses, `YamlScenarioTest`, `YamlScenarioValidationTest`, `application.yml`, every connector and every scenario YAML stay byte-for-byte unchanged. The only files this plan creates are listed under each task.
- `RealEnvironmentScenarios` declares **no** `@ActiveProfiles`, **no** `@DynamicPropertySource`, extends **nothing**, and references no embedded server type (`EmbeddedLdapServer`, `WireMockServer`).
- The class name must **not** end in `Test` or `Tests`. The parent POM's surefire `<includes>` are `**/*Test.java` and `**/*Tests.java`, and that is the only mechanism keeping it out of the default build.
- Do not use `@EnabledIfSystemProperty` or any other skip mechanism. In this project green means every test runs, with zero skips.
- Scenario directory: `idm-test-scenarios/src/test/resources/scenarios-real/`. It ships with **only** a `README.md` and no `.yaml`.
- Run command, exactly: `IDM_TEST_PROFILE=dev ./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentScenarios -Dsurefire.failIfNoSpecifiedTests=false`
- Guard 1: refuse when active profiles contain `test`. Guard 2: refuse when `idm.test.ldap.url` or `idm.test.soap.url` is blank or equals its `application.yml` fallback: `ldap://localhost:389` or `https://idm.company.com:8543/IDMProv/role/service`. Guard 3: refuse when `scenarios-real/` contains no `.yaml`. Each message names what was wrong and what to set.
- Every guard is stated positively, checked before any scenario runs, and falsification-tested: with the guard removed, its test must fail.
- `-pl` is always paired with `-am`. Single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`.
- If you see "Unresolved compilation problems", the cause is stale IDE `.class` files. Run `./mvnw clean test` and do not change code.
- Work on branch `feat/real-environment-runner` off `main`.
- Commit messages end with the ticket id on its own line, followed by the trailer:
  ```
  ITF-12

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

## File Structure

| File | Responsibility |
|---|---|
| `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java` (create) | The runner: Spring wiring, the three guards as package-private statics, and the `@TestFactory` |
| `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentGuardsTest.java` (create) | Plain unit tests for the three guards. No Spring context. Runs in the default build |
| `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenariosStructureTest.java` (create) | Structural test that stops the embedded wiring or a `Test` suffix from being added back. Runs in the default build |
| `idm-test-scenarios/src/test/resources/scenarios-real/README.md` (create) | Tells the operator what belongs in the directory and what the environment must provide |

---

### Task 0: Branch and baseline

- [ ] **Step 1: Branch off main**

```bash
git checkout main
git checkout -b feat/real-environment-runner
```

- [ ] **Step 2: Record the baseline test count**

Run: `./mvnw clean test 2>&1 | grep -E "Tests run:.*Fail" | tail -1` and also `./mvnw clean test 2>&1 | grep -E "BUILD (SUCCESS|FAILURE)"`
Expected: `BUILD SUCCESS`, and `Failures: 0, Errors: 0, Skipped: 0` on every module's summary line. Write down the total `Tests run` summed across modules (for example, run `grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc`). Task 2 compares against this number.

---

### Task 1: The runner and its guards

**Files:**
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java`
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentGuardsTest.java`
- Create: `idm-test-scenarios/src/test/resources/scenarios-real/README.md`

**Interfaces:**
- Consumes (existing, unchanged, in `YamlScenarioTest`, same package):
  - `static Map<String, StepType> registry(EdirectoryConnector, MultiDatabaseConnector, RoleServiceConnector, ResourceServiceConnector, IdmRestConnector, RestProperties)`
  - `static List<String> scenarioFiles(String scenarioDir) throws URISyntaxException`. It returns `List.of()` when the directory is missing, and otherwise the sorted `.yaml` file names only.
  - `static List<DynamicTest> buildTests(String scenarioDir, ScenarioValidator, ScenarioEngine, Path resultsPath) throws URISyntaxException`
- Produces (Task 2 relies on these exact names):
  - `RealEnvironmentScenarios.SCENARIO_DIR = "scenarios-real"`
  - `RealEnvironmentScenarios.LDAP_URL_PLACEHOLDER = "ldap://localhost:389"`
  - `RealEnvironmentScenarios.SOAP_URL_PLACEHOLDER = "https://idm.company.com:8543/IDMProv/role/service"`
  - `static void requireRealTarget(Environment environment)` throws `IllegalStateException` (guards 1 and 2)
  - `static void requireScenarios(List<String> scenarioFiles)` throws `IllegalStateException` (guard 3)

- [ ] **Step 1: Write the failing guard tests**

Create `RealEnvironmentGuardsTest.java`:

```java
package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guards in {@link RealEnvironmentScenarios} exist to stop a run that tests nothing real
 * from reporting green. Each one is checked here without a Spring context, so these run in
 * every build even though the runner itself does not.
 * <p>
 * {@link #testRealTargetPasses()} is the positive control. Without it, every refusal below
 * would still pass if the guards simply refused everything.
 */
@DisplayName("Real-environment runner guards")
class RealEnvironmentGuardsTest {

    private static final String REAL_LDAP = "ldap://edir.example.org:389";
    private static final String REAL_SOAP = "https://idm.example.org:8543/IDMProv/role/service";

    private static MockEnvironment target(String ldapUrl, String soapUrl, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        if (ldapUrl != null) {
            environment.setProperty("idm.test.ldap.url", ldapUrl);
        }
        if (soapUrl != null) {
            environment.setProperty("idm.test.soap.url", soapUrl);
        }
        return environment;
    }

    @Test
    @DisplayName("A named, non-embedded target passes")
    void testRealTargetPasses() {
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, REAL_SOAP, "dev")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The embedded 'test' profile is refused")
    void testEmbeddedProfileRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, REAL_SOAP, "test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'test'")
                .hasMessageContaining("IDM_TEST_PROFILE");
    }

    @Test
    @DisplayName("The embedded 'test' profile is refused even alongside another profile")
    void testEmbeddedProfileRefusedAmongOthers() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, REAL_SOAP, "dev", "test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'test'");
    }

    @Test
    @DisplayName("An unset LDAP URL is refused")
    void testUnsetLdapUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(null, REAL_SOAP, "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.url")
                .hasMessageContaining("IDM_LDAP_URL");
    }

    @Test
    @DisplayName("A blank LDAP URL is refused")
    void testBlankLdapUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target("  ", REAL_SOAP, "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.url");
    }

    @Test
    @DisplayName("The LDAP URL placeholder from application.yml is refused")
    void testLdapPlaceholderRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                target(RealEnvironmentScenarios.LDAP_URL_PLACEHOLDER, REAL_SOAP, "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.url")
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("An unset SOAP URL is refused")
    void testUnsetSoapUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, null, "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.soap.url")
                .hasMessageContaining("IDM_SOAP_URL");
    }

    @Test
    @DisplayName("The SOAP URL placeholder from application.yml is refused")
    void testSoapPlaceholderRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                target(REAL_LDAP, RealEnvironmentScenarios.SOAP_URL_PLACEHOLDER, "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.soap.url")
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("An empty scenario directory is refused")
    void testEmptyScenarioDirectoryRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireScenarios(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scenarios-real");
    }

    @Test
    @DisplayName("A scenario directory with at least one file passes")
    void testNonEmptyScenarioDirectoryPasses() {
        assertThatCode(() -> RealEnvironmentScenarios.requireScenarios(List.of("create-user.yaml")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The shipped scenarios-real directory is empty, so a first run hits the guard")
    void testShippedDirectoryHasNoScenarios() throws Exception {
        // Pins spec section 7: the directory ships with a README only. If someone copies the
        // embedded corpus in, which writes straight to LDAP and would create entries IdM does
        // not know about, this fails and makes them read the README first.
        assertThat(YamlScenarioTest.scenarioFiles(RealEnvironmentScenarios.SCENARIO_DIR)).isEmpty();
        assertThat(getClass().getClassLoader().getResource(RealEnvironmentScenarios.SCENARIO_DIR + "/README.md"))
                .as("scenarios-real/README.md must ship so the directory exists on the classpath")
                .isNotNull();
    }

    @Test
    @DisplayName("The placeholders match the fallbacks application.yml actually declares")
    void testPlaceholdersMatchApplicationYml() throws IOException {
        // If application.yml's fallbacks change and these constants don't, guard 2 would
        // silently compare against a value nothing resolves to and never fire again.
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));

        assertThat(yaml)
                .contains("${IDM_LDAP_URL:" + RealEnvironmentScenarios.LDAP_URL_PLACEHOLDER + "}")
                .contains("${IDM_SOAP_URL:" + RealEnvironmentScenarios.SOAP_URL_PLACEHOLDER + "}");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentGuardsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol ... RealEnvironmentScenarios`.

- [ ] **Step 3: Create the scenario directory README**

Create `idm-test-scenarios/src/test/resources/scenarios-real/README.md`:

````markdown
# Real-environment scenarios

YAML scenarios in this directory run against a **real** OpenText IdM / eDirectory, not the
embedded LDAP, WireMock and H2 the rest of the suite uses. They are run by
`RealEnvironmentScenarios`, which the default build never runs.

## Running

```bash
IDM_TEST_PROFILE=dev ./mvnw test -pl idm-test-scenarios -am \
  -Dtest=RealEnvironmentScenarios -Dsurefire.failIfNoSpecifiedTests=false
```

Results are written to `idm-test-scenarios/target/real-environment-results.json`.

## What belongs here

Scenarios that **drive IdM** and then verify what IdM provisioned downstream. For example, a
`rest.request` against the IdM REST API, followed by `ldap.verify` and `db.verify` with
`wait:` against the connected systems.

**Do not copy the scenarios from `../scenarios/` here.** Those write straight to LDAP and the
database to simulate IdM. Pointed at a real directory, they would create entries IdM does not
know about.

This directory ships empty on purpose. Running with no `.yaml` here fails with a message
pointing back to this file, because a run that finds nothing and reports success would be
worse than no run.

## What the environment must provide

The runner refuses to start, before any scenario runs, when:

- the active profile is `test` (the embedded configuration)
- `idm.test.ldap.url` or `idm.test.soap.url` is blank or still the `application.yml` placeholder
  (`ldap://localhost:389`, `https://idm.company.com:8543/IDMProv/role/service`)
- this directory contains no `.yaml`

The Spring context will not start unless the following are also true:

- **Every database configured under `idm.test.databases` accepts a connection.** That means
  `sap`, `oracle-hr` and `workday`, not only the ones your scenarios use. Each pool connects
  when it is created (`new HikariDataSource(config)` in `DatabaseConfiguration`).
- **`idm.test.soap.resource-url` is set** (for example with the environment variable
  `IDM_TEST_SOAP_RESOURCEURL`). Without it, the `ResourceServiceConnector` bean is not created
  and the context fails.

Credentials come from the environment variables `application.yml` already references:
`IDM_LDAP_URL`, `IDM_LDAP_USER_DN`, `IDM_LDAP_PASSWORD`, `IDM_SOAP_URL`, `IDM_SOAP_USER`,
`IDM_SOAP_PASSWORD`, `IDM_REST_URL`, `IDM_REST_USER`, `IDM_REST_PASSWORD`, `SAP_DB_*`,
`HR_DB_*` and `WORKDAY_DB_*`.

Scenarios name their database by its key: `sap`, `oracle-hr` or `workday`. `test-db` is the
embedded H2.
````

- [ ] **Step 4: Write the runner**

Create `RealEnvironmentScenarios.java`:

```java
package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.core.config.TestExecutorProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs the YAML scenarios under src/test/resources/scenarios-real against whatever
 * environment the active profile names: a real eDirectory, IdM and databases.
 * <p>
 * Deliberately unlike {@link YamlScenarioTest}. It extends nothing, pins no profile and
 * overrides no property, so every {@code idm.test.*} value comes from application.yml and
 * {@code IDM_TEST_PROFILE}. {@code BaseIntegrationTest} cannot be used here, because it pins
 * the embedded infrastructure three ways and a profile change defeats none of them.
 * {@code RealEnvironmentScenariosStructureTest} keeps it that way.
 * <p>
 * Named without a {@code Test} suffix so surefire's includes skip it in the default build.
 * Run it explicitly:
 * <pre>
 * IDM_TEST_PROFILE=dev ./mvnw test -pl idm-test-scenarios -am \
 *   -Dtest=RealEnvironmentScenarios -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * The guards below run before any scenario. A connection error is loud on its own. The
 * failure they prevent is the quiet one: a run against the embedded configuration, a
 * placeholder, or an empty directory that reports green.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class})
@DisplayName("Real-environment scenarios")
public class RealEnvironmentScenarios {

    static final String SCENARIO_DIR = "scenarios-real";
    static final String EMBEDDED_PROFILE = "test";
    static final String LDAP_URL_PLACEHOLDER = "ldap://localhost:389";
    static final String SOAP_URL_PLACEHOLDER = "https://idm.company.com:8543/IDMProv/role/service";

    private static final Path RESULTS = Path.of("target", "real-environment-results.json");

    @Autowired
    private Environment environment;

    @Autowired
    private EdirectoryConnector edirectoryConnector;

    @Autowired
    private MultiDatabaseConnector databaseConnector;

    @Autowired
    private RoleServiceConnector roleServiceConnector;

    @Autowired
    private ResourceServiceConnector resourceServiceConnector;

    @Autowired
    private IdmRestConnector idmRestConnector;

    @Autowired
    private RestProperties restProperties;

    @Autowired
    private TestExecutor testExecutor;

    @Autowired
    private TestExecutorProperties executorProperties;

    @Autowired
    private TestDataGenerator testDataGenerator;

    @TestFactory
    @DisplayName("Each real-environment scenario file runs as its own test")
    Stream<DynamicTest> scenarios() throws Exception {
        requireRealTarget(environment);
        requireScenarios(YamlScenarioTest.scenarioFiles(SCENARIO_DIR));

        Map<String, StepType> registry = YamlScenarioTest.registry(
                edirectoryConnector, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties);
        ScenarioValidator validator = new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS);
        ScenarioEngine engine = new ScenarioEngine(
                registry,
                new FixtureFactory(testDataGenerator),
                testExecutor,
                executorProperties.getDefaultTimeout(),
                executorProperties.getDefaultPollInterval(),
                new ExpressionEvaluator());

        return YamlScenarioTest.buildTests(SCENARIO_DIR, validator, engine, RESULTS).stream();
    }

    /**
     * Guards 1 and 2. Refuses the embedded profile, and refuses a directory or role-service
     * URL that nobody actually named.
     */
    static void requireRealTarget(Environment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains(EMBEDDED_PROFILE)) {
            throw new IllegalStateException("Active profiles " + Arrays.toString(environment.getActiveProfiles())
                    + " include '" + EMBEDDED_PROFILE + "', the embedded test configuration. Set IDM_TEST_PROFILE"
                    + " to the profile that names your real environment, for example IDM_TEST_PROFILE=dev.");
        }
        requireNamed(environment, "idm.test.ldap.url", "IDM_LDAP_URL", LDAP_URL_PLACEHOLDER);
        requireNamed(environment, "idm.test.soap.url", "IDM_SOAP_URL", SOAP_URL_PLACEHOLDER);
    }

    private static void requireNamed(Environment environment, String property, String variable, String placeholder) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is not set. Set " + variable
                    + " or activate a profile that defines it.");
        }
        if (value.equals(placeholder)) {
            throw new IllegalStateException(property + " is still application.yml's placeholder (" + placeholder
                    + "), so no real target was named. Set " + variable
                    + " or activate a profile that defines it.");
        }
    }

    /**
     * Guard 3. A run that discovers nothing, executes nothing and reports success is the
     * worst outcome this runner can have. The directory ships empty, so this is the most
     * likely thing to happen on a first attempt.
     */
    static void requireScenarios(List<String> scenarioFiles) {
        if (scenarioFiles.isEmpty()) {
            throw new IllegalStateException("No .yaml scenarios found in src/test/resources/" + SCENARIO_DIR
                    + ". Add a scenario there; see " + SCENARIO_DIR + "/README.md for what belongs in it.");
        }
    }
}
```

- [ ] **Step 5: Run the guard tests to verify they pass**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentGuardsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` for `RealEnvironmentGuardsTest`, and `BUILD SUCCESS`.

- [ ] **Step 6: Falsify each guard**

Do each of the following one at a time. Re-run the Step 5 command after each, check that the named tests fail, then revert the change before doing the next one:

1. In `requireRealTarget`, replace the `if (Arrays.asList(...).contains(EMBEDDED_PROFILE))` condition with `if (false)`. `testEmbeddedProfileRefused` and `testEmbeddedProfileRefusedAmongOthers` must fail.
2. In `requireNamed`, replace `value == null || value.isBlank()` with `false`. `testUnsetLdapUrlRefused`, `testBlankLdapUrlRefused` and `testUnsetSoapUrlRefused` must fail. With `value` null, the placeholder check's `value.equals` throws `NullPointerException` instead, so those tests fail on the exception type.
3. In `requireNamed`, replace `value.equals(placeholder)` with `false`. `testLdapPlaceholderRefused` and `testSoapPlaceholderRefused` must fail.
4. In `requireScenarios`, replace `scenarioFiles.isEmpty()` with `false`. `testEmptyScenarioDirectoryRefused` must fail.
5. Change `LDAP_URL_PLACEHOLDER` to `"ldap://localhost:10389"`. `testPlaceholdersMatchApplicationYml` must fail.

After reverting all five, check that `git diff` for `RealEnvironmentScenarios.java` is empty against your Step 4 content. Then re-run Step 5 and confirm 12 passing. Record in your report which tests failed under each mutation.

- [ ] **Step 7: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java \
        idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentGuardsTest.java \
        idm-test-scenarios/src/test/resources/scenarios-real/README.md
git commit -F - <<'EOF'
feat: add a runner for YAML scenarios against a real environment

RealEnvironmentScenarios extends nothing, pins no profile and overrides no
property, so the active profile decides where scenarios run. Three guards
refuse the embedded profile, a placeholder or unset target URL, and an
empty scenario directory before any scenario runs.

ITF-12

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Structural test, end-to-end smoke and full-suite check

**Files:**
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenariosStructureTest.java`

**Interfaces:**
- Consumes: the `RealEnvironmentScenarios` class from Task 1. Its path is `src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java`, relative to the `idm-test-scenarios` module, which is surefire's working directory. The existing `NoSpringDependencyTest` relies on that same working directory.
- Produces: nothing later tasks use.

- [ ] **Step 1: Write the structural test**

Create `RealEnvironmentScenariosStructureTest.java`:

```java
package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RealEnvironmentScenarios} is only useful while it lacks the three things that pin
 * {@code BaseIntegrationTest} to the embedded infrastructure. Adding any of them back would
 * look like a harmless tidy-up and would restore the exact bug ITF-12 fixed: a run that
 * appears to test a real environment while hitting the embedded one.
 * <p>
 * Stated as a test rather than a convention, in the spirit of {@code NoSpringDependencyTest},
 * because conventions erode.
 */
@DisplayName("Real-environment runner structure")
class RealEnvironmentScenariosStructureTest {

    private static final Class<?> RUNNER = RealEnvironmentScenarios.class;
    private static final Path SOURCE =
            Path.of("src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java");

    @Test
    @DisplayName("Extends nothing, so it inherits no embedded wiring")
    void testExtendsNothing() {
        assertThat(RUNNER.getSuperclass()).isEqualTo(Object.class);
    }

    @Test
    @DisplayName("Pins no profile")
    void testNoActiveProfiles() {
        assertThat(MergedAnnotations.from(RUNNER, SearchStrategy.TYPE_HIERARCHY).isPresent(ActiveProfiles.class))
                .as("@ActiveProfiles on RealEnvironmentScenarios would stop IDM_TEST_PROFILE reaching Spring")
                .isFalse();
    }

    @Test
    @DisplayName("Overrides no property")
    void testNoDynamicPropertySource() {
        assertThat(Arrays.stream(RUNNER.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(DynamicPropertySource.class))
                .map(Method::getName))
                .as("@DynamicPropertySource beats every profile, environment variable and system property")
                .isEmpty();
    }

    @Test
    @DisplayName("Starts no embedded server")
    void testNoEmbeddedServerReferences() throws IOException {
        String source = Files.readString(SOURCE);

        assertThat(source)
                .doesNotContain("EmbeddedLdapServer")
                .doesNotContain("WireMock");
    }

    @Test
    @DisplayName("Is named so the default build does not run it")
    void testNotPickedUpBySurefireDefaults() {
        // The parent POM's surefire includes are **/*Test.java and **/*Tests.java. That is
        // the only thing keeping this runner, which needs a real environment, out of CI.
        assertThat(RUNNER.getSimpleName())
                .doesNotEndWith("Test")
                .doesNotEndWith("Tests");
    }
}
```

- [ ] **Step 2: Run it and verify it passes**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentScenariosStructureTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

This test was written after the class it inspects, so it has to be falsified before it proves anything (Step 3).

- [ ] **Step 3: Falsify the structural test**

Do each of the following one at a time on `RealEnvironmentScenarios.java`. Re-run the Step 2 command after each, check that the named test fails, then revert:

1. Add `@ActiveProfiles("test")` (import `org.springframework.test.context.ActiveProfiles`) to the class. `testNoActiveProfiles` must fail.
2. Add this method, with imports `org.springframework.test.context.DynamicPropertySource` and `org.springframework.test.context.DynamicPropertyRegistry`:
   ```java
   @DynamicPropertySource
   static void pin(DynamicPropertyRegistry registry) {
       registry.add("idm.test.ldap.url", () -> "ldap://localhost:1");
   }
   ```
   `testNoDynamicPropertySource` must fail.
3. Add the comment `// WireMockServer` anywhere in the class body. `testNoEmbeddedServerReferences` must fail.
4. Change `extends` nothing to `extends com.idm.test.scenarios.base.BaseIntegrationTest`. `testExtendsNothing` must fail, and `testNoActiveProfiles` must fail too, because the search is `TYPE_HIERARCHY` and `BaseIntegrationTest` carries `@ActiveProfiles("test")`. Confirm both fail.

`testNotPickedUpBySurefireDefaults` cannot be falsified by an edit without renaming the class. Instead, satisfy yourself of its logic: `"RealEnvironmentScenariosTest"` ends with `"Test"`.

After reverting everything, `git status` must show only the new structural test as untracked, and `RealEnvironmentScenarios.java` must be unchanged. Re-run Step 2 and confirm 5 passing. Record which tests failed under each mutation in your report.

- [ ] **Step 4: End-to-end smoke. The runner is reachable and guard 1 fires in a real context**

This is the only check that the Spring wiring works, that `-Dtest` overrides the surefire includes, and that a guard fires inside a live context. It uses the `test` profile, whose databases are all H2, so the context can start on this machine. `idm.test.soap.resource-url` is supplied through Spring's relaxed binding of environment variables, because the `test` profile does not define it and `ResourceServiceConnector` would otherwise be missing.

Run:
```bash
IDM_TEST_PROFILE=test IDM_TEST_SOAP_RESOURCEURL=http://localhost:1/IDMProv/resource/service \
  ./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentScenarios -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `BUILD FAILURE`. `RealEnvironmentScenarios` shows `Tests run: 1, Failures: 0, Errors: 1` (or `Failures: 1`), and the error message in `idm-test-scenarios/target/surefire-reports/com.idm.test.scenarios.engine.RealEnvironmentScenarios.txt` contains `include 'test', the embedded test configuration`.

If instead the context fails to start, for example with `ApplicationContext failure threshold`, `Failed to configure database` or `No qualifying bean`, **stop and report the exact error. Do not change any code or configuration to work around it.** The smoke's purpose is to find that out.

- [ ] **Step 5: Full suite. Nothing existing changed and the runner stays out of the default build**

Run: `./mvnw clean test`
Expected:
- `BUILD SUCCESS`, and every module reports `Failures: 0, Errors: 0, Skipped: 0`.
- Total tests = Task 0 baseline + 17 (12 guard tests + 5 structural tests). Compute it the same way as Task 0.
- `ls idm-test-scenarios/target/surefire-reports | grep RealEnvironmentScenarios.txt` prints nothing. `RealEnvironmentScenarios` itself did not run. Only `...RealEnvironmentGuardsTest` and `...RealEnvironmentScenariosStructureTest` reports exist.
- `git diff main --stat -- ':!idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironment*' ':!idm-test-scenarios/src/test/resources/scenarios-real'` prints nothing. No existing file was touched.

- [ ] **Step 6: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenariosStructureTest.java
git commit -F - <<'EOF'
test: stop the embedded wiring returning to the real-environment runner

Asserts RealEnvironmentScenarios extends nothing, has no @ActiveProfiles
or @DynamicPropertySource, references no embedded server, and keeps a
name surefire's default includes skip.

ITF-12

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```
