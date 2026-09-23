# Container Scenarios Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the 18-scenario corpus through `RealEnvironmentScenarios` against OpenLDAP, PostgreSQL and WireMock containers started by Testcontainers. This is an optional runner that the default build never touches.

**Architecture:** `ContainerScenarios extends RealEnvironmentScenarios`. A static `@BeforeAll` starts the containers and publishes their addresses as `idm.test.*` system properties, the same knobs an operator sets with `-D`, before Spring loads the context. A static `@AfterAll` restores the properties and stops the containers. The only change to the parent runner is that its scenario directory becomes an overridable method.

**Tech Stack:** Java 21, Spring Boot 3.5.6, JUnit 5, Testcontainers 1.21.4 (`GenericContainer`, `PostgreSQLContainer`), `osixia/openldap:1.5.0`, `postgres:16-alpine`, `wiremock/wiremock:3.10.0`.

**Spec:** `docs/specs/2026-09-23-container-scenarios-design.md`. **Ticket:** ITF-14.

## Global Constraints

- **The default `./mvnw test` must stay Docker-free and unchanged in outcome.** `ContainerScenarios` is named without a `Test` suffix, and nothing in the default build starts a container.
- Testcontainers version is exactly `1.21.4`. Testcontainers 1.20.4 fails on this Docker; 1.21.3 needs an API pin. Do not move to 2.x.
- Images, exactly: `osixia/openldap:1.5.0`, `postgres:16-alpine`, `wiremock/wiremock:3.10.0`. The last matches `wiremock.version` in the parent POM.
- `ContainerScenarios` publishes configuration **only** through system properties set in `@BeforeAll`. It uses no `@DynamicPropertySource`, `@TestPropertySource` or `@ActiveProfiles`.
- `@AfterAll` restores every system property it set to its previous value, clearing it if there was none, and stops every container it started.
- A missing Docker daemon fails with exactly: `Docker is not available. ContainerScenarios needs a running Docker daemon; the default suite does not.` It is never a skip.
- **Do not edit any corpus scenario YAML to make it pass against containers.** If a scenario fails against OpenLDAP or PostgreSQL, that is a finding. Stop and report it.
- `BaseIntegrationTest`, its subclasses, `YamlScenarioTest`, `YamlScenarioValidationTest` and the corpus YAML stay unchanged.
- Every refusal and structural assertion is falsification-tested.
- `-pl` is always paired with `-am`. Single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`.
- If you see "Unresolved compilation problems", that is stale IDE `.class` output. Run `./mvnw clean test` and do not change code.
- Branch: `feat/container-scenarios` off `main`.
- Commit messages end with:
  ```
  ITF-14

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

## File Structure

| File | Change |
|---|---|
| `pom.xml` | `testcontainers.version` 1.20.4 → 1.21.4 |
| `idm-connector-database/pom.xml` | Remove the unused `org.testcontainers:postgresql` |
| `idm-test-scenarios/pom.xml` | Add `org.testcontainers:postgresql`, `org.postgresql:postgresql` and `com.oracle.database.jdbc:ojdbc11`, all test scope |
| `idm-test-scenarios/src/test/resources/schema.sql` | Portable identity columns; drop `ON UPDATE CURRENT_TIMESTAMP` |
| `.../engine/RealEnvironmentScenarios.java` | `SCENARIO_DIR` → `DEFAULT_SCENARIO_DIR` plus an overridable `scenarioDirectory()`; `requireScenarios` takes the directory |
| `.../engine/RealEnvironmentGuardsTest.java` | Follow the rename and the new `requireScenarios` signature |
| `.../engine/ContainerScenarios.java` (create) | The container runner |
| `.../engine/ContainerScenariosStructureTest.java` (create) | Structural tests plus `requireDocker` unit tests. No Docker needed |
| `idm-test-scenarios/src/test/resources/container-ldap/idm-test-schema.ldif` (create) | OpenLDAP schema extension template |
| `idm-test-scenarios/src/test/resources/scenarios-real/README.md` | "Running against containers" and "Running against a lab" |
| `CLAUDE.md` | One command |

`...` = `idm-test-scenarios/src/test/java/com/idm/test/scenarios`.

---

### Task 0: Branch and baseline

- [ ] **Step 1:** Create the branch.

```bash
git checkout main
git checkout -b feat/container-scenarios
```

- [ ] **Step 2:** Confirm Docker is running with `docker version --format '{{.Server.Version}}'`. It should print a version. If it doesn't, stop: Task 2 cannot be verified.

- [ ] **Step 3:** Record the baseline. Run `./mvnw clean test`, then:

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
```

Expected: `BUILD SUCCESS` and **462**, with no non-zero `failures=`, `errors=` or `skipped=`.

---

### Task 1: Enablers. Version, dependencies, portable schema, overridable directory

**Files:** `pom.xml`, `idm-connector-database/pom.xml`, `idm-test-scenarios/pom.xml`, `schema.sql`, `RealEnvironmentScenarios.java`, `RealEnvironmentGuardsTest.java`.

**Interfaces:**
- Produces (Task 2 relies on these):
  - `RealEnvironmentScenarios.DEFAULT_SCENARIO_DIR = "scenarios-real"` (package-private `static final String`)
  - `String scenarioDirectory()`: package-private, non-static, non-final. Returns `DEFAULT_SCENARIO_DIR`.
  - `static void requireScenarios(List<String> scenarioFiles, String scenarioDir)`
  - Testcontainers 1.21.4 plus the PostgreSQL driver on `idm-test-scenarios`' test classpath.

- [ ] **Step 1: Update the guard tests first**

In `RealEnvironmentGuardsTest.java`, make these changes:

- Replace `RealEnvironmentScenarios.requireScenarios(List.of())` with `RealEnvironmentScenarios.requireScenarios(List.of(), RealEnvironmentScenarios.DEFAULT_SCENARIO_DIR)`.
- Replace `RealEnvironmentScenarios.requireScenarios(List.of("create-user.yaml"))` with `RealEnvironmentScenarios.requireScenarios(List.of("create-user.yaml"), RealEnvironmentScenarios.DEFAULT_SCENARIO_DIR)`.
- Replace both occurrences of `RealEnvironmentScenarios.SCENARIO_DIR` with `RealEnvironmentScenarios.DEFAULT_SCENARIO_DIR`.

Add this test after `testEmptyScenarioDirectoryRefused`:

```java
    @Test
    @DisplayName("The empty-directory message names the directory that was actually searched")
    void testEmptyScenarioDirectoryMessageNamesDirectory() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireScenarios(List.of(), "some-other-dir"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("src/test/resources/some-other-dir");
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentGuardsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure (`DEFAULT_SCENARIO_DIR` and the two-argument `requireScenarios` don't exist yet).

- [ ] **Step 3: Change `RealEnvironmentScenarios`**

Replace `static final String SCENARIO_DIR = "scenarios-real";` with:

```java
    static final String DEFAULT_SCENARIO_DIR = "scenarios-real";
```

In `scenarios()`, replace:

```java
        requireScenarios(YamlScenarioTest.scenarioFiles(SCENARIO_DIR));
```

with:

```java
        String scenarioDir = scenarioDirectory();
        requireScenarios(YamlScenarioTest.scenarioFiles(scenarioDir), scenarioDir);
```

and replace:

```java
        return YamlScenarioTest.buildTests(SCENARIO_DIR, YamlScenarioTest.selectedScenarioFiles(SCENARIO_DIR),
                validator, engine, RESULTS).stream();
```

with:

```java
        return YamlScenarioTest.buildTests(scenarioDir, YamlScenarioTest.selectedScenarioFiles(scenarioDir),
                validator, engine, RESULTS).stream();
```

Add this method directly after `scenarios()`:

```java
    /**
     * The classpath directory this runner reads scenarios from. {@code ContainerScenarios}
     * overrides it to run the embedded corpus against containers; configuration still comes
     * only from the environment, exactly as for a real target.
     */
    String scenarioDirectory() {
        return DEFAULT_SCENARIO_DIR;
    }
```

Replace `requireScenarios` with:

```java
    static void requireScenarios(List<String> scenarioFiles, String scenarioDir) {
        if (scenarioFiles.isEmpty()) {
            throw new IllegalStateException("No .yaml scenarios found in src/test/resources/" + scenarioDir
                    + ". Add a scenario there; see " + DEFAULT_SCENARIO_DIR + "/README.md for what belongs in it.");
        }
    }
```

- [ ] **Step 4: Run the guard and structure tests**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='RealEnvironmentGuardsTest,RealEnvironmentScenariosStructureTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: `RealEnvironmentGuardsTest` 13/13 and `RealEnvironmentScenariosStructureTest` 5/5. Then falsify the new test once: change `+ scenarioDir` in the message to `+ DEFAULT_SCENARIO_DIR`, confirm `testEmptyScenarioDirectoryMessageNamesDirectory` fails, and revert.

- [ ] **Step 5: Version and dependencies**

In `pom.xml`, change `<testcontainers.version>1.20.4</testcontainers.version>` to `<testcontainers.version>1.21.4</testcontainers.version>`.

In `idm-connector-database/pom.xml`, delete this block:

```xml
        <!-- Testcontainers for PostgreSQL tests -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
```

In `idm-test-scenarios/pom.xml`, add after the WireMock dependency:

```xml
        <!-- ContainerScenarios: real OpenLDAP, PostgreSQL and WireMock containers (ITF-14).
             Only that runner starts them; the default build never touches Docker. -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- JDBC drivers. idm-connector-database declares these optional, so they are not
             inherited. RealEnvironmentScenarios and ContainerScenarios need them to reach a
             real PostgreSQL or Oracle database. -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.oracle.database.jdbc</groupId>
            <artifactId>ojdbc11</artifactId>
            <scope>test</scope>
        </dependency>
```

Versions come from the parent POM's `dependencyManagement`. If `ojdbc11` has no managed version there, stop and report; do not invent one.

Verify:

```bash
./mvnw dependency:tree -pl idm-test-scenarios -am 2>/dev/null | sed -n '/idm-test-scenarios ---/,$p' | grep -E "postgresql|ojdbc11|testcontainers:testcontainers"
```

Expected: `org.postgresql:postgresql`, `ojdbc11` and `org.testcontainers:testcontainers:jar:1.21.4` all appear under `idm-test-scenarios`.

- [ ] **Step 6: Make `schema.sql` portable**

In `idm-test-scenarios/src/test/resources/schema.sql`, make two changes:

- Replace all three occurrences of `id INT AUTO_INCREMENT PRIMARY KEY,` with `id INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,`.
- Replace `updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` with `updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`.

Change nothing else.

- [ ] **Step 7: Full default suite**

Run `./mvnw clean test`, then the Task 0 count command.

Expected: `BUILD SUCCESS` and **463** (462 + 1 new guard test), with no non-zero failures, errors or skips. H2 runs the portable `schema.sql` at every context start, so this suite is its test.

- [ ] **Step 8: Commit**

```bash
git add pom.xml idm-connector-database/pom.xml idm-test-scenarios/pom.xml \
        idm-test-scenarios/src/test/resources/schema.sql \
        idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java \
        idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentGuardsTest.java
git commit -F - <<'EOF'
build: prepare the real-environment runner for containers

Testcontainers 1.21.4 clears the Docker API clash. The PostgreSQL and
Oracle drivers were optional in the database connector and so missing
from the scenarios classpath. schema.sql is now portable to PostgreSQL,
and the runner's scenario directory can be overridden.

ITF-14

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: `ContainerScenarios`

**Files:**
- Create: `.../engine/ContainerScenarios.java`, `.../engine/ContainerScenariosStructureTest.java`, `idm-test-scenarios/src/test/resources/container-ldap/idm-test-schema.ldif`
- Modify: `scenarios-real/README.md`, `CLAUDE.md`

**Interfaces:**
- Consumes (from Task 1): `RealEnvironmentScenarios` with package-private `String scenarioDirectory()`, which you override.
- Produces: `ContainerScenarios.CORPUS_DIR = "scenarios"`, `ContainerScenarios.DOCKER_UNAVAILABLE` (the exact message) and `static void requireDocker(boolean available)`.

- [ ] **Step 1: Write the failing tests**

Create `ContainerScenariosStructureTest.java`:

```java
package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ContainerScenarios} is worth running only because it reaches its containers through
 * {@link RealEnvironmentScenarios}' configuration path, not around it. These checks keep it
 * that way, and keep it out of the Docker-free default build. None of them needs Docker.
 */
@DisplayName("Container runner structure")
class ContainerScenariosStructureTest {

    private static final Class<?> RUNNER = ContainerScenarios.class;

    @Test
    @DisplayName("Is named so the default build does not run it")
    void testNotPickedUpBySurefireDefaults() {
        assertThat(RUNNER.getSimpleName()).doesNotEndWith("Test").doesNotEndWith("Tests");
    }

    @Test
    @DisplayName("Extends the real-environment runner, so its guards and wiring apply")
    void testExtendsRealEnvironmentRunner() {
        assertThat(RUNNER.getSuperclass()).isEqualTo(RealEnvironmentScenarios.class);
    }

    @Test
    @DisplayName("Pins no profile and injects no properties through Spring's test annotations")
    void testNoSpringPropertyOverrides() {
        MergedAnnotations annotations = MergedAnnotations.from(RUNNER, SearchStrategy.TYPE_HIERARCHY);
        assertThat(annotations.isPresent(ActiveProfiles.class)).isFalse();
        assertThat(annotations.isPresent(TestPropertySource.class)).isFalse();
        assertThat(Arrays.stream(RUNNER.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(DynamicPropertySource.class))
                .map(Method::getName))
                .as("configuration must arrive as system properties, the way an operator supplies it")
                .isEmpty();
    }

    @Test
    @DisplayName("Runs the embedded corpus")
    void testRunsTheCorpus() {
        assertThat(new ContainerScenarios().scenarioDirectory()).isEqualTo("scenarios");
    }

    @Test
    @DisplayName("Without Docker, fails with a plain message")
    void testDockerUnavailableRefused() {
        assertThatThrownBy(() -> ContainerScenarios.requireDocker(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Docker is not available. ContainerScenarios needs a running Docker daemon;"
                        + " the default suite does not.");
    }

    @Test
    @DisplayName("With Docker, does not refuse")
    void testDockerAvailablePasses() {
        assertThatCode(() -> ContainerScenarios.requireDocker(true)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenariosStructureTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure, `cannot find symbol ... ContainerScenarios`.

- [ ] **Step 3: Create the OpenLDAP schema extension template**

Create `idm-test-scenarios/src/test/resources/container-ldap/idm-test-schema.ldif`. `@INETORGPERSON_DN@` is replaced at runtime with the DN of the container's `inetorgperson` schema entry, because its `{n}` index is assigned by the server:

```
# Gives OpenLDAP the eDirectory-like additions IdmTestSchema gives the embedded server:
# startDate and loginDisabled (same OIDs and syntaxes), and startDate, loginDisabled and c
# as optional attributes of inetOrgPerson. The connector writes every entry with the fixed
# classes top/person/organizationalPerson/inetOrgPerson, so an auxiliary class cannot be used.
dn: @INETORGPERSON_DN@
changetype: modify
add: olcAttributeTypes
olcAttributeTypes: ( 1.3.6.1.4.1.99999.1.1 NAME 'startDate' DESC 'IdM provisioning start date' EQUALITY caseIgnoreMatch SUBSTR caseIgnoreSubstringsMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
olcAttributeTypes: ( 1.3.6.1.4.1.99999.1.2 NAME 'loginDisabled' DESC 'eDirectory account disabled flag' EQUALITY booleanMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.7 SINGLE-VALUE )
-
replace: olcObjectClasses
olcObjectClasses: ( 2.16.840.1.113730.3.2.2 NAME 'inetOrgPerson' DESC 'RFC2798: Internet Organizational Person' SUP organizationalPerson STRUCTURAL MAY ( audio $ businessCategory $ carLicense $ departmentNumber $ displayName $ employeeNumber $ employeeType $ givenName $ homePhone $ homePostalAddress $ initials $ jpegPhoto $ labeledURI $ mail $ manager $ mobile $ o $ pager $ photo $ roomNumber $ secretary $ uid $ userCertificate $ x500uniqueIdentifier $ preferredLanguage $ userSMIMECertificate $ userPKCS12 $ startDate $ loginDisabled $ c ) )
-
```

- [ ] **Step 4: Write `ContainerScenarios`**

```java
package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the embedded scenario corpus through {@link RealEnvironmentScenarios} against real
 * OpenLDAP, PostgreSQL and WireMock containers.
 * <p>
 * The containers' addresses are published as {@code idm.test.*} system properties in
 * {@code @BeforeAll}, before Spring loads the context. That is exactly how an operator points
 * the parent runner at a real environment with {@code -D}, so everything after it goes
 * through the parent's guards and configuration path unchanged. That path is what this
 * runner proves. It proves nothing about OpenText IdM: the SOAP services are WireMock stubs.
 * <p>
 * Optional. Named without a {@code Test} suffix, so the Docker-free default build never runs
 * it. Needs a running Docker daemon:
 * <pre>
 * ./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * {@code -Dscenario=<file or glob>} narrows the run, as with {@link YamlScenarioTest}.
 */
@DisplayName("Container scenarios")
public class ContainerScenarios extends RealEnvironmentScenarios {

    static final String CORPUS_DIR = "scenarios";
    static final String DOCKER_UNAVAILABLE =
            "Docker is not available. ContainerScenarios needs a running Docker daemon; the default suite does not.";

    private static final String LDAP_IMAGE = "osixia/openldap:1.5.0";
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String WIREMOCK_IMAGE = "wiremock/wiremock:3.10.0";

    private static final String LDAP_BASE_DN = "dc=test,dc=com";
    private static final String LDAP_ADMIN_DN = "cn=admin,dc=test,dc=com";
    private static final String LDAP_ADMIN_PASSWORD = "test";
    private static final int LDAP_PORT = 389;
    private static final int WIREMOCK_PORT = 8080;

    // Every configured database must accept a connection for the context to start, so all
    // four point at the one PostgreSQL. oracle-hr here is a stand-in and says nothing about Oracle.
    private static final List<String> DATABASES = List.of("test-db", "sap", "oracle-hr", "workday");

    private static final List<GenericContainer<?>> STARTED = new ArrayList<>();
    private static final Map<String, String> PREVIOUS = new LinkedHashMap<>();

    @BeforeAll
    static void startContainers() throws Exception {
        requireDocker(DockerClientFactory.instance().isDockerAvailable());

        GenericContainer<?> ldap = startLdap();
        PostgreSQLContainer<?> postgres = start(new PostgreSQLContainer<>(POSTGRES_IMAGE), "PostgreSQL");
        GenericContainer<?> wiremock = start(new GenericContainer<>(WIREMOCK_IMAGE)
                .withExposedPorts(WIREMOCK_PORT)
                .withCommand("--global-response-templating")
                .withCopyFileToContainer(MountableFile.forClasspathResource("wiremock/mappings"),
                        "/home/wiremock/mappings")
                .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200)), "WireMock");

        publish(ldap, postgres, wiremock);
    }

    /**
     * Restores every system property to what it was before, so nothing leaks into another
     * class in the same JVM. Then stops the containers in reverse order. JUnit runs this even
     * when {@code @BeforeAll} failed part-way, which is what cleans up a partial start.
     */
    @AfterAll
    static void stopContainers() {
        PREVIOUS.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
        PREVIOUS.clear();

        for (int i = STARTED.size() - 1; i >= 0; i--) {
            STARTED.get(i).stop();
        }
        STARTED.clear();
    }

    @Override
    String scenarioDirectory() {
        return CORPUS_DIR;
    }

    static void requireDocker(boolean available) {
        if (!available) {
            throw new IllegalStateException(DOCKER_UNAVAILABLE);
        }
    }

    private static GenericContainer<?> startLdap() throws IOException, InterruptedException {
        GenericContainer<?> ldap = start(new GenericContainer<>(LDAP_IMAGE)
                .withEnv("LDAP_ORGANISATION", "Test Organization")
                .withEnv("LDAP_DOMAIN", "test.com")
                .withEnv("LDAP_ADMIN_PASSWORD", LDAP_ADMIN_PASSWORD)
                .withEnv("LDAP_TLS", "false")
                .withExposedPorts(LDAP_PORT)
                .waitingFor(Wait.forLogMessage(".*slapd starting.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2))), "OpenLDAP");

        String inetOrgPersonDn = exec(ldap, "OpenLDAP schema lookup",
                "ldapsearch", "-Y", "EXTERNAL", "-H", "ldapi:///", "-LLL", "-b", "cn=schema,cn=config",
                "(cn=*inetorgperson)", "dn")
                .lines()
                .filter(line -> line.startsWith("dn: "))
                .map(line -> line.substring("dn: ".length()).strip())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "OpenLDAP schema lookup found no inetorgperson schema entry under cn=schema,cn=config"));

        String extension = classpathText("container-ldap/idm-test-schema.ldif")
                .replace("@INETORGPERSON_DN@", inetOrgPersonDn);
        ldap.copyFileToContainer(Transferable.of(extension), "/tmp/idm-test-schema.ldif");
        exec(ldap, "OpenLDAP schema extension",
                "ldapmodify", "-Y", "EXTERNAL", "-H", "ldapi:///", "-f", "/tmp/idm-test-schema.ldif");

        ldap.copyFileToContainer(MountableFile.forClasspathResource("ldap-init.ldif"), "/tmp/ldap-init.ldif");
        exec(ldap, "OpenLDAP initial entries",
                "ldapadd", "-x", "-H", "ldap://localhost", "-D", LDAP_ADMIN_DN, "-w", LDAP_ADMIN_PASSWORD,
                "-f", "/tmp/ldap-init.ldif");

        return ldap;
    }

    private static void publish(GenericContainer<?> ldap, PostgreSQLContainer<?> postgres, GenericContainer<?> wiremock) {
        String wiremockBase = "http://" + wiremock.getHost() + ":" + wiremock.getMappedPort(WIREMOCK_PORT);

        set("idm.test.ldap.url", "ldap://" + ldap.getHost() + ":" + ldap.getMappedPort(LDAP_PORT));
        set("idm.test.ldap.base-dn", LDAP_BASE_DN);
        set("idm.test.ldap.user-dn", LDAP_ADMIN_DN);
        set("idm.test.ldap.password", LDAP_ADMIN_PASSWORD);
        set("idm.test.soap.url", wiremockBase + "/IDMProv/role/service");
        set("idm.test.soap.resource-url", wiremockBase + "/IDMProv/resource/service");

        for (String database : DATABASES) {
            String prefix = "idm.test.databases." + database + ".";
            set(prefix + "jdbc-url", postgres.getJdbcUrl());
            set(prefix + "username", postgres.getUsername());
            set(prefix + "password", postgres.getPassword());
            set(prefix + "driver-class-name", "org.postgresql.Driver");
        }

        // The default profile waits up to 5 minutes, polling every 5 seconds; the corpus was
        // written against the test profile's 60 seconds and 1 second.
        set("idm.test.executor.default-timeout-seconds", "60");
        set("idm.test.executor.default-poll-interval-seconds", "1");
    }

    private static void set(String key, String value) {
        if (!PREVIOUS.containsKey(key)) {
            PREVIOUS.put(key, System.getProperty(key));
        }
        System.setProperty(key, value);
    }

    private static <C extends GenericContainer<?>> C start(C container, String name) {
        STARTED.add(container);
        try {
            container.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException(name + " container failed to start: " + e.getMessage(), e);
        }
        return container;
    }

    private static String exec(GenericContainer<?> container, String step, String... command)
            throws IOException, InterruptedException {
        ExecResult result = container.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(step + " failed (exit " + result.getExitCode() + "): "
                    + result.getStderr().strip());
        }
        return result.getStdout();
    }

    private static String classpathText(String resource) throws IOException {
        try (InputStream in = ContainerScenarios.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
```

- [ ] **Step 5: Run the structural tests**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenariosStructureTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 6/6 passing, and **no container started**. `docker ps -a -q | wc -l` should report the same number before and after.

- [ ] **Step 6: Falsify**

Do each mutation one at a time. Re-run Step 5 after each, confirm the named test fails, then revert:

1. In `requireDocker`, replace `!available` with `false`. `testDockerUnavailableRefused` must fail.
2. In `requireDocker`, replace `!available` with `true`. `testDockerAvailablePasses` must fail.
3. Remove the `scenarioDirectory()` override. `testRunsTheCorpus` must fail.
4. Add `@org.springframework.test.context.TestPropertySource(properties = "x=y")` to the class. `testNoSpringPropertyOverrides` must fail.

Record each result.

- [ ] **Step 7: Gate 1. Properties reach Spring, with the containers running**

Run:

```bash
./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false -Dscenario=provisioning-user-to-ldap.yaml
```

Expected: `BUILD SUCCESS`, and 1 testcase in `TEST-com.idm.test.scenarios.engine.ContainerScenarios.xml`. Success is the proof: guard 2 would have refused the placeholder LDAP URL, and the connector could only reach the container's entry through the published address.

If a container fails to start (`<name> container failed to start`, for example a wait-strategy timeout on OpenLDAP's `slapd starting` log line), **stop and report the message and the container's last log lines.** Do not loosen timeouts or wait strategies on a guess.

If instead the context started with placeholder values (guard 2's "placeholder" message, or a connection to `localhost:389`), then `@BeforeAll` ran after the context loaded. **Stop and report.** Do not switch to `@DynamicPropertySource`.

- [ ] **Step 8: Gate 2. The schema extension, with the containers running**

Run the Step 7 command with `-Dscenario=deprovisioning-user-disable.yaml`.

Expected: `BUILD SUCCESS` and 1 testcase. This scenario writes `loginDisabled`.

If the failure message contains `OpenLDAP schema extension failed`, OpenLDAP refused the runtime schema change. **Stop and report the exact message.**

- [ ] **Step 9: The whole corpus**

Run the Step 7 command without `-Dscenario`.

Expected: `BUILD SUCCESS` and **18** testcases, all passing.

If any scenario fails, **do not edit the scenario or weaken the runner.** Record the scenario, the failing step and the message in your report as a finding about OpenLDAP or PostgreSQL versus the embedded servers, and stop.

Afterwards, `docker ps -q --filter ancestor=osixia/openldap:1.5.0 | wc -l` should print `0`. The containers were stopped.

- [ ] **Step 10: Docker-unavailable, live**

Run:

```bash
TESTCONTAINERS_DOCKER_CLIENT_STRATEGY=org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy \
DOCKER_HOST=tcp://127.0.0.1:1 \
./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD FAILURE`, and `idm-test-scenarios/target/surefire-reports/com.idm.test.scenarios.engine.ContainerScenarios.txt` contains `Docker is not available. ContainerScenarios needs a running Docker daemon`.

If Testcontainers still finds Docker through another route, record what happened instead. Do not change code. The unit test covers the message.

- [ ] **Step 11: Documentation**

In `idm-test-scenarios/src/test/resources/scenarios-real/README.md`, append at the end:

````markdown

## Running against containers

`ContainerScenarios` runs the embedded corpus (`../scenarios/`) through this same runner,
against real OpenLDAP, PostgreSQL and WireMock containers that Testcontainers starts and stops.
It needs a running Docker daemon. The default build never runs it.

```bash
./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false
```

It proves this runner's configuration path reaches genuine LDAP and SQL servers. It proves
nothing about OpenText IdM: the SOAP services are WireMock stubs.

## Running against a lab

A team with its own eDirectory / IdM environment (containers, VMs or a shared lab) points
this runner at it with the `IDM_TEST_*` variables described above. No code change is needed.
````

In `CLAUDE.md`, directly after the two `-Dscenario` command lines, add:

```bash

# Run the YAML corpus against OpenLDAP/PostgreSQL/WireMock containers (optional; needs Docker)
./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 12: Full default suite**

Note `docker ps -a -q | wc -l`. Run `./mvnw clean test` and the Task 0 count command.

Expected:
- `BUILD SUCCESS`.
- **469** tests (463 + 6), with no non-zero failures, errors or skips.
- `ls idm-test-scenarios/target/surefire-reports | grep -c "ContainerScenarios.txt"` prints `0`. Only the structure test ran.
- The Docker container count is unchanged: the default build started nothing.
- `git diff main --stat` lists only the files in this plan's File Structure table.

- [ ] **Step 13: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ContainerScenarios.java \
        idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ContainerScenariosStructureTest.java \
        idm-test-scenarios/src/test/resources/container-ldap/idm-test-schema.ldif \
        idm-test-scenarios/src/test/resources/scenarios-real/README.md CLAUDE.md
git commit -F - <<'EOF'
feat: run the scenario corpus against OpenLDAP, PostgreSQL and WireMock containers

ContainerScenarios publishes container addresses as system properties
before Spring starts, so the corpus runs through RealEnvironmentScenarios'
own configuration path. Optional: the default build never starts Docker.

ITF-14

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```
