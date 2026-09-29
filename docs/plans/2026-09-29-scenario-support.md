# ScenarioSupport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The real-environment and container runners reuse the scenario helpers without loading `BaseIntegrationTest`, so they no longer start the embedded LDAP servers and WireMock.

**Architecture:**
- **`ScenarioSupport`,** a new class that extends nothing, takes over `YamlScenarioTest`'s static helpers verbatim. Every caller switches to it; `YamlScenarioTest` keeps only its `@TestFactory`.
- **`ScenarioSupportIsolationTest`** proves the claim at run time: in a fresh class loader, the runners' helper path never loads `BaseIntegrationTest` or `YamlScenarioTest`, and neither runner's bytecode references them.

**Tech Stack:** Java 21, JUnit 5, AssertJ, `java.net.URLClassLoader`.

**Spec:** `docs/specs/2026-09-29-scenario-support-design.md` (commit e943d2b). **Ticket:** ITF-17.

## Global Constraints

- **No behaviour change:** the same selection rules, messages, results files and `-Dscenario` handling. Method bodies and javadoc move verbatim; only `YamlScenarioTest.class` references inside the moved code become `ScenarioSupport.class`.
- **`ScenarioSupport`:** package `com.idm.test.scenarios.engine` (test sources), `final`, extends nothing, private constructor, package-private static members with the exact names and signatures `YamlScenarioTest` had: `SCENARIO_PROPERTY`, `registry(...)`, `buildTests(...)` (both overloads), `selectedScenarioFiles(String)`, `select(List<String>, String, String)`, `scenarioFiles(String)`; private `resource(String, String)` and `stripYaml(String)`.
- **After the change,** no main or test source outside `YamlScenarioTest.java` itself calls `YamlScenarioTest.<member>`.
- **`YamlScenarioTest`** still extends `BaseIntegrationTest` and still runs the corpus in `scenarios/`.
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. "Unresolved compilation problems" means stale classes: run `./mvnw clean test`. Green means 0 failures, errors and skips.
- **Commits** end with a blank line, `ITF-17`, and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: `ScenarioSupport`, the callers, and the isolation test

**Files:**
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioSupport.java`
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioSupportIsolationTest.java`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java`
- Modify (callers): `RealEnvironmentScenarios.java`, `ScenarioFilterTest.java`, `ScenarioReferenceTest.java`, `YamlScenarioValidationTest.java`, `MultiDirectoryScenarioTest.java`, `RealEnvironmentGuardsTest.java` (all in `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/`)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenariosStructureTest.java` (javadoc pointer only)

**Interfaces:**
- Produces: `ScenarioSupport.SCENARIO_PROPERTY`, `ScenarioSupport.registry(LdapDirectories, MultiDatabaseConnector, RoleServiceConnector, ResourceServiceConnector, IdmRestConnector, RestProperties, RestAuthenticator) → Map<String, StepType>`, `ScenarioSupport.buildTests(String, ScenarioValidator, ScenarioEngine, Path)` and `buildTests(String, List<String>, ScenarioValidator, ScenarioEngine, Path) → List<DynamicTest>`, `ScenarioSupport.selectedScenarioFiles(String)`, `ScenarioSupport.select(List<String>, String, String)`, `ScenarioSupport.scenarioFiles(String) → List<String>`.

- [ ] **Step 1: Write the isolation test**

`ScenarioSupportIsolationTest.java`:

```java
package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real-environment and container runners reuse the scenario helpers without touching the
 * embedded test base. {@code BaseIntegrationTest}'s static initialisers start two embedded LDAP
 * servers and WireMock, so a runner that merely loaded it would start them too (ITF-17).
 * <p>
 * Checked in a fresh class loader, because in the shared test JVM other tests have usually
 * loaded {@code BaseIntegrationTest} already.
 */
@DisplayName("Scenario helpers load no embedded test base")
class ScenarioSupportIsolationTest {

    private static final String PACKAGE = "com.idm.test.scenarios.engine.";
    private static final String BASE = "com.idm.test.scenarios.base.BaseIntegrationTest";
    private static final String YAML_TEST = PACKAGE + "YamlScenarioTest";

    /** A class loader over the test classpath that shares no application class with this JVM's. */
    private static final class IsolatedLoader extends URLClassLoader {

        IsolatedLoader() {
            super(classpath(), ClassLoader.getPlatformClassLoader());
        }

        boolean hasLoaded(String name) {
            return findLoadedClass(name) != null;
        }

        private static URL[] classpath() {
            return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                    .filter(entry -> !entry.isBlank())
                    .map(entry -> {
                        try {
                            return Path.of(entry).toUri().toURL();
                        } catch (MalformedURLException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toArray(URL[]::new);
        }
    }

    @Test
    @DisplayName("The runners' helper path never loads BaseIntegrationTest or YamlScenarioTest")
    void testHelperPathLoadsNoEmbeddedBase() throws Exception {
        try (IsolatedLoader loader = new IsolatedLoader()) {
            Class.forName(PACKAGE + "RealEnvironmentScenarios", true, loader);
            Class.forName(PACKAGE + "ContainerScenarios", true, loader);

            Class<?> support = Class.forName(PACKAGE + "ScenarioSupport", true, loader);
            List<?> files = (List<?>) invoke(support, "scenarioFiles", new Class<?>[]{String.class}, "scenarios");
            invoke(support, "select", new Class<?>[]{List.class, String.class, String.class}, files, null, "scenarios");

            Class<?> restProperties = Class.forName("com.idm.test.connector.rest.config.RestProperties", true, loader);
            Class<?> authenticator = Class.forName("com.idm.test.connector.rest.client.RestAuthenticator", true, loader);
            Object properties = restProperties.getConstructor().newInstance();
            Object withoutOAuth = authenticator.getMethod("withoutOAuth").invoke(null);
            Method registry = Arrays.stream(support.getDeclaredMethods())
                    .filter(method -> method.getName().equals("registry"))
                    .findFirst().orElseThrow();
            registry.setAccessible(true);
            registry.invoke(null, null, null, null, null, null, properties, withoutOAuth);

            assertThat(files).as("the helper path really ran").isNotEmpty();
            assertThat(loader.hasLoaded(BASE)).as("BaseIntegrationTest loaded").isFalse();
            assertThat(loader.hasLoaded(YAML_TEST)).as("YamlScenarioTest loaded").isFalse();
        }
    }

    @Test
    @DisplayName("Positive control: loading YamlScenarioTest does load BaseIntegrationTest")
    void testCheckCanFail() throws Exception {
        try (IsolatedLoader loader = new IsolatedLoader()) {
            // initialize = false: loading resolves the superclass without running its static
            // initialisers, so no embedded server starts here.
            Class.forName(YAML_TEST, false, loader);

            assertThat(loader.hasLoaded(BASE)).isTrue();
        }
    }

    @Test
    @DisplayName("Neither runner, nor ScenarioSupport, refers to the embedded test base in its bytecode")
    void testBytecodeReferencesNoEmbeddedBase() throws IOException {
        for (String runner : List.of("RealEnvironmentScenarios", "ContainerScenarios", "ScenarioSupport")) {
            String bytecode = classBytes(PACKAGE + runner);

            assertThat(bytecode).as(runner)
                    .doesNotContain("com/idm/test/scenarios/base/BaseIntegrationTest")
                    .doesNotContain("com/idm/test/scenarios/engine/YamlScenarioTest");
        }
    }

    private static Object invoke(Class<?> type, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    /** A class file's bytes as ISO-8859-1 text, so constant-pool class names can be searched. */
    private static String classBytes(String className) throws IOException {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream in = ScenarioSupportIsolationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=ScenarioSupportIsolationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `ScenarioSupport` does not exist yet, so compilation fails. (If you want to see the runtime failure first, temporarily create an empty `ScenarioSupport` whose `scenarioFiles` delegates to `YamlScenarioTest.scenarioFiles`: `testHelperPathLoadsNoEmbeddedBase` then fails on "BaseIntegrationTest loaded". Delete that stub before Step 3.)

- [ ] **Step 3: Create `ScenarioSupport` by moving the helpers**

Create `ScenarioSupport.java` with this shape:

```java
package com.idm.test.scenarios.engine;

// imports: exactly those the moved members need (see below); none from com.idm.test.scenarios.base

/**
 * The scenario helpers every runner shares: the step registry, file discovery and
 * {@code -Dscenario} selection, and turning files into dynamic tests.
 * <p>
 * Extends nothing, on purpose. {@code BaseIntegrationTest} starts the embedded LDAP servers and
 * WireMock in its static initialisers, so helpers reached through a subclass of it would start
 * them for the real-environment and container runners too (ITF-17).
 * {@code ScenarioSupportIsolationTest} checks this.
 */
final class ScenarioSupport {

    private ScenarioSupport() {
    }

    // members moved from YamlScenarioTest
}
```

Then **move** (cut from `YamlScenarioTest`, paste into `ScenarioSupport`, bodies and javadoc unchanged) these members of `YamlScenarioTest`:
- `/** System property naming the scenario file, or glob of files, to run. */ static final String SCENARIO_PROPERTY = "scenario";`
- `static List<DynamicTest> buildTests(String scenarioDir, ScenarioValidator validator, ScenarioEngine engine, Path resultsPath)`
- `static List<DynamicTest> buildTests(String scenarioDir, List<String> files, ScenarioValidator validator, ScenarioEngine engine, Path resultsPath)`
- `static List<String> selectedScenarioFiles(String scenarioDir)`
- `static List<String> select(List<String> files, String pattern, String scenarioDir)`
- `private static String stripYaml(String file)`
- `static Map<String, StepType> registry(LdapDirectories ldapDirectories, ...)`
- `private static InputStream resource(String scenarioDir, String file)`
- `static List<String> scenarioFiles(String scenarioDir)`

In the moved code, replace every `YamlScenarioTest.class` with `ScenarioSupport.class` (in `resource` and `scenarioFiles`). Move the imports these members need (the step classes, the connector types, `ScenarioLoader`, `ScenarioValidator`, `ScenarioEngine`, `Scenario`, `JsonResultWriter`, `ScenarioResult`, `StepType`, `LdapDirectories`, `DynamicTest`, `IOException`, `InputStream`, `UncheckedIOException`, `URISyntaxException`, `FileSystems`, `Files`, `Path`, `PathMatcher`, `ArrayList`, `Collections`, `LinkedHashMap`, `List`, `Map`, `PatternSyntaxException`, `Stream`, and the static `assertThat`/`fail`).

`YamlScenarioTest` then keeps: its class javadoc (change `{@link #select}` to `{@link ScenarioSupport#select}`), `SCENARIO_DIR`, `RESULTS`, and `scenarios()`, which becomes:

```java
    @TestFactory
    @DisplayName("Each scenario file runs as its own test")
    Stream<DynamicTest> scenarios() throws Exception {
        Map<String, StepType> registry = ScenarioSupport.registry(
                ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioValidator validator = new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS);
        ScenarioEngine engine = new ScenarioEngine(
                registry,
                new FixtureFactory(testDataGenerator),
                testExecutor,
                executorProperties.getDefaultTimeout(),
                executorProperties.getDefaultPollInterval(),
                new ExpressionEvaluator());

        return ScenarioSupport.buildTests(SCENARIO_DIR, ScenarioSupport.selectedScenarioFiles(SCENARIO_DIR),
                validator, engine, RESULTS).stream();
    }
```

Remove the imports `YamlScenarioTest` no longer uses.

- [ ] **Step 4: Switch the callers**

Replace `YamlScenarioTest.` with `ScenarioSupport.` for every helper call in:
- `RealEnvironmentScenarios.java` (`scenarioFiles`, `selectedScenarioFiles`, `registry`, `buildTests`)
- `ScenarioFilterTest.java` (`select`, 9 calls)
- `ScenarioReferenceTest.java` (`registry`)
- `YamlScenarioValidationTest.java` (`registry` ×2, `buildTests`)
- `MultiDirectoryScenarioTest.java` (`registry`, `buildTests`)
- `RealEnvironmentGuardsTest.java` (`scenarioFiles`)
- any reference to `YamlScenarioTest.SCENARIO_PROPERTY`

Leave javadoc mentions of `YamlScenarioTest` as the embedded runner (for example "Deliberately unlike {@link YamlScenarioTest}" in `RealEnvironmentScenarios`) as they are; they describe the runner, not the helpers, and add no bytecode reference.

Then check nothing is left:

Run: `git grep -n "YamlScenarioTest\.[a-zA-Z]" -- idm-test-scenarios/src ':!idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java'`
Expected: no output (a `{@link YamlScenarioTest}` javadoc mention has no `.` and does not match).

- [ ] **Step 5: Point the structure test at the runtime proof**

In `RealEnvironmentScenariosStructureTest.java`, above `testNoEmbeddedServerReferences`, add:

```java
    /**
     * A source-level check. The run-time guarantee (the runner's helper path never loads
     * {@code BaseIntegrationTest}, so it starts no embedded server) is
     * {@link ScenarioSupportIsolationTest}.
     */
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='ScenarioSupportIsolationTest,ScenarioFilterTest,ScenarioReferenceTest,RealEnvironmentGuardsTest,RealEnvironmentScenariosStructureTest,ContainerScenariosStructureTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass.

- [ ] **Step 7: Falsify**

One at a time, make the change, run `ScenarioSupportIsolationTest`, confirm the named test fails, then revert:
- in `ScenarioSupport.scenarioFiles`, use `YamlScenarioTest.class.getClassLoader()` → `testHelperPathLoadsNoEmbeddedBase` ("YamlScenarioTest loaded") and `testBytecodeReferencesNoEmbeddedBase` fail
- in `RealEnvironmentScenarios.scenarios()`, call `YamlScenarioTest.scenarioFiles` instead of `ScenarioSupport.scenarioFiles` (make `YamlScenarioTest.scenarioFiles` a temporary delegating method to compile) → `testBytecodeReferencesNoEmbeddedBase` fails for `RealEnvironmentScenarios`
- make `IsolatedLoader.hasLoaded` always return `false` → `testCheckCanFail` fails

Record each result in the report.

- [ ] **Step 8: Run the module**

Run: `./mvnw test -pl idm-test-scenarios -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips; `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json idm-test-scenarios/target/multi-directory-results.json` prints `0` for both.

- [ ] **Step 9: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine
git commit -m "refactor: move the scenario helpers into ScenarioSupport, so the runners start no embedded server

ITF-17

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Documentation and the full suite

**Files:**
- Modify: `docs/guides/scenario-authoring.md` (line ~210, where to register a step)
- Modify: `docs/architecture/README.md` (line ~37, the JUnit adapter row)
- Modify: `docs/architecture/capabilities.md` (the real-environment and container rows; the `[adapter]` link stays)

**Interfaces:**
- Consumes (Task 1): `ScenarioSupport` at `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioSupport.java`, with `registry(...)`.

- [ ] **Step 1: Update the docs**

`docs/guides/scenario-authoring.md`: replace

```markdown
Add the step to [YamlScenarioTest.registry](../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java), which supplies the same registry to validation and execution.
```

with

```markdown
Add the step to [ScenarioSupport.registry](../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioSupport.java), which every runner uses and which supplies the same registry to validation and execution.
```

`docs/architecture/README.md`: in the "JUnit adapter" row, replace the source link cell with

```markdown
[YamlScenarioTest](../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java) (embedded run), [ScenarioSupport](../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioSupport.java) (shared helpers)
```

`docs/architecture/capabilities.md`:
- in the "Real-environment runner" row's last cell, after "Unused repositories are never contacted;", insert ` no embedded LDAP or WireMock server is started (ITF-17);`
- in the "Container execution" row's last cell, append `. Starts no embedded LDAP or WireMock server (ITF-17)`

- [ ] **Step 2: Check for stale references**

Run: `git grep -n "YamlScenarioTest\.\(registry\|buildTests\|select\|scenarioFiles\|selectedScenarioFiles\|SCENARIO_PROPERTY\)" -- ':!docs/specs' ':!docs/plans' ':!docs/superpowers'`
Expected: no output.

- [ ] **Step 3: Run the full suite**

Run: `./mvnw -q clean test`
Expected: exit 0.

Then:
```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -h -o 'failures="[0-9]*"\|errors="[0-9]*"\|skipped="[0-9]*"' */target/surefire-reports/TEST-*.xml | grep -v '"0"' | wc -l
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json idm-test-scenarios/target/multi-directory-results.json
```
Expected: 728 tests (725 plus the 3 in `ScenarioSupportIsolationTest`); `0` non-zero attributes; both FAILED counts `0`. If `EmbeddedLdapServerTest.testLdifImport` or `LdapStepsTest` fails with a closed-connection error (known intermittent flakes in untouched code), re-run once and report both runs.

If Docker is available (`docker info` succeeds), also run:
`./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 20 scenarios, 0 failures. If Docker isn't available, say so in the report.

- [ ] **Step 4: Commit**

```bash
git add docs/guides/scenario-authoring.md docs/architecture/README.md docs/architecture/capabilities.md
git commit -m "docs: point to ScenarioSupport, and note the runners start no embedded server

ITF-17

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
