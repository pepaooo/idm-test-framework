# REST Target Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before any scenario runs, `RealEnvironmentScenarios` refuses a run whose selected scenarios use a REST `target:` that is unknown, has a blank `base-url`, or still has its `application.yml` placeholder `base-url`.

**Architecture:** This is guard 4 in `RealEnvironmentScenarios`. A pure `restTargets(List<Scenario>)` collects the literal targets of `rest.*` steps. `requireRestTargets(Set<String>, RestProperties)` applies the refusals, reusing guard 2's message logic through a value-based helper. `scenarios()` loads the selected files and runs guard 4 after guard 3 and before `buildTests`.

**Tech Stack:** Java 21, JUnit 5, AssertJ, the engine's `ScenarioLoader` / `Scenario` / `StepSpec`, and `RestProperties` from `idm-connector-rest`.

**Spec:** `docs/specs/2026-09-23-rest-target-guard-design.md`. **Ticket:** ITF-15.

## Global Constraints

- **A run whose selected scenarios use no `rest.*` step makes no REST demand.** `ContainerScenarios` and the corpus must need no change.
- The placeholders are exactly: `idm` → `https://idm.company.com:8543/IDMProv`, `sap` → `https://sap.company.com/api/v1`, `workday` → `https://workday.company.com/api/v1`, `servicenow` → `https://instance.service-now.com/api`.
- The variable named in each message is `IDM_TEST_REST_<TARGET>_BASEURL`, where `<TARGET>` is the target upper-cased (`Locale.ROOT`) with `-` removed.
- Targets are checked in sorted order.
- Files that fail to load are skipped by guard 4, because `buildTests` reports them. Targets containing `${` are skipped.
- Only these files change: `RealEnvironmentScenarios.java`, `RealEnvironmentGuardsTest.java`, `scenarios-real/README.md`, and `docs/specs/2026-09-22-real-environment-runner-design.md`. The engine, `ContainerScenarios`, `YamlScenarioTest`, `BaseIntegrationTest` and the corpus YAML are unchanged.
- Every refusal, plus the no-demand pass, is falsification-tested.
- `-pl` is always paired with `-am`. Single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`.
- If you see "Unresolved compilation problems", that is stale IDE output. Run `./mvnw clean test` and do not change code.
- Branch: `feat/rest-target-guard` off `main`.
- Commit messages end with:
  ```
  ITF-15

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

### Task 0: Branch and baseline

- [ ] **Step 1:** `git checkout main && git checkout -b feat/rest-target-guard`
- [ ] **Step 2:** Run `./mvnw clean test`, then:

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
```

Expected: `BUILD SUCCESS` and **473**, with no non-zero `failures=`, `errors=` or `skipped=`.

---

### Task 1: Guard 4

**Files:**
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentGuardsTest.java`
- Modify: `idm-test-scenarios/src/test/resources/scenarios-real/README.md`
- Modify: `docs/specs/2026-09-22-real-environment-runner-design.md`

**Interfaces:**
- Consumes (existing):
  - `ScenarioLoader.load(InputStream yaml, String sourceName)` returns `Scenario` and throws `ValidationException` (a `RuntimeException`) on bad input, including a `null` stream.
  - `Scenario.steps()` returns `List<StepSpec>`.
  - `StepSpec.type()` / `StepSpec.params()`.
  - `RestProperties.getRest()` returns a mutable `Map<String, RestProperties.RestApiConfig>`.
  - `RestApiConfig.getBaseUrl()` / `setBaseUrl(String)`.
  - `YamlScenarioTest.scenarioFiles(dir)`, `YamlScenarioTest.selectedScenarioFiles(dir)`, and `YamlScenarioTest.buildTests(dir, files, validator, engine, results)`.
- Produces:
  - `static final Map<String, String> REST_URL_PLACEHOLDERS`
  - `static Set<String> restTargets(List<Scenario> scenarios)`
  - `static void requireRestTargets(Set<String> targets, RestProperties properties)`

- [ ] **Step 1: Write the failing tests**

In `RealEnvironmentGuardsTest.java`, add these imports:

```java
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.model.Scenario;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
```

Add these helpers and tests inside the class, before `testPlaceholdersMatchApplicationYml`:

```java
    private static Scenario scenario(String yaml) {
        return ScenarioLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "inline.yaml");
    }

    private static RestProperties restTarget(String target, String baseUrl) {
        RestProperties properties = new RestProperties();
        RestProperties.RestApiConfig config = new RestProperties.RestApiConfig();
        config.setBaseUrl(baseUrl);
        properties.getRest().put(target, config);
        return properties;
    }

    @Test
    @DisplayName("Guard 4 collects the literal targets of rest.* steps, and nothing else")
    void testRestTargetsCollected() {
        Scenario scenario = scenario("""
                name: rest targets
                steps:
                  - rest.request:
                      target: idm
                      method: GET
                      path: /users
                  - rest.verify:
                      target: sap
                      path: /accounts/1
                      expect:
                        status: 200
                  - roles.grant:
                      target: hr
                  - rest.request:
                      target: "${chosen}"
                      method: GET
                      path: /x
                  - rest.request:
                      target: idm
                      method: GET
                      path: /again
                """);

        // idm and sap from both REST step types, once each; hr is not a REST step; the
        // expression target is only known at run time.
        assertThat(RealEnvironmentScenarios.restTargets(List.of(scenario))).containsExactly("idm", "sap");
    }

    @Test
    @DisplayName("Scenarios with no REST steps use no REST targets")
    void testNoRestTargetsWithoutRestSteps() {
        Scenario scenario = scenario("""
                name: ldap only
                steps:
                  - ldap.create:
                      dn: cn=x,ou=users
                      attributes:
                        cn: x
                """);

        assertThat(RealEnvironmentScenarios.restTargets(List.of(scenario))).isEmpty();
    }

    @Test
    @DisplayName("Guard 4 refuses a REST target that is not configured")
    void testUnknownRestTargetRefused() {
        RestProperties properties = restTarget("idm", "https://idm.example.org/IDMProv");

        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRestTargets(Set.of("sapp"), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'sapp'")
                .hasMessageContaining("not configured")
                .hasMessageContaining("[idm]");
    }

    @Test
    @DisplayName("Guard 4 refuses a blank REST base-url")
    void testBlankRestBaseUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRestTargets(Set.of("idm"), restTarget("idm", "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.rest.idm.base-url is not set")
                .hasMessageContaining("IDM_TEST_REST_IDM_BASEURL");
    }

    @Test
    @DisplayName("Guard 4 refuses a missing REST base-url")
    void testMissingRestBaseUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRestTargets(Set.of("idm"), restTarget("idm", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.rest.idm.base-url is not set");
    }

    @Test
    @DisplayName("Guard 4 refuses each REST target's application.yml placeholder")
    void testRestPlaceholdersRefused() {
        assertThat(RealEnvironmentScenarios.REST_URL_PLACEHOLDERS).hasSize(4);

        RealEnvironmentScenarios.REST_URL_PLACEHOLDERS.forEach((target, placeholder) ->
                assertThatThrownBy(() -> RealEnvironmentScenarios.requireRestTargets(
                        Set.of(target), restTarget(target, placeholder)))
                        .as("target %s", target)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("placeholder")
                        .hasMessageContaining("IDM_TEST_REST_" + target.toUpperCase(Locale.ROOT) + "_BASEURL"));
    }

    @Test
    @DisplayName("Guard 4 accepts a REST target with a real base-url")
    void testRealRestBaseUrlPasses() {
        assertThatCode(() -> RealEnvironmentScenarios.requireRestTargets(
                Set.of("idm"), restTarget("idm", "https://idm.example.org/IDMProv")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A run that uses no REST target makes no REST demand, even with every placeholder configured")
    void testNoRestTargetsNoDemand() {
        RestProperties properties = new RestProperties();
        RealEnvironmentScenarios.REST_URL_PLACEHOLDERS.forEach((target, placeholder) ->
                properties.getRest().putAll(restTarget(target, placeholder).getRest()));

        assertThatCode(() -> RealEnvironmentScenarios.requireRestTargets(Set.of(), properties))
                .doesNotThrowAnyException();
    }
```

In `testPlaceholdersMatchApplicationYml`, add these lines after the existing `assertThat(yaml)...;` statement, before the closing brace:

```java

        Map<String, String> restVariables = Map.of(
                "idm", "IDM_REST_URL",
                "sap", "SAP_REST_URL",
                "workday", "WORKDAY_REST_URL",
                "servicenow", "SERVICENOW_REST_URL");
        assertThat(RealEnvironmentScenarios.REST_URL_PLACEHOLDERS.keySet())
                .containsExactlyInAnyOrderElementsOf(restVariables.keySet());
        RealEnvironmentScenarios.REST_URL_PLACEHOLDERS.forEach((target, placeholder) ->
                assertThat(yaml).contains("${" + restVariables.get(target) + ":" + placeholder + "}"));
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentGuardsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure, `cannot find symbol ... restTargets` (and `requireRestTargets`, `REST_URL_PLACEHOLDERS`).

- [ ] **Step 3: Implement guard 4**

In `RealEnvironmentScenarios.java`:

**(a)** Add these imports alongside the existing ones:

```java
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
```

**(b)** Add this constant below `SOAP_URL_PLACEHOLDER`:

```java

    /** application.yml's fallback base-url for each REST target. Pinned to that file by RealEnvironmentGuardsTest. */
    static final Map<String, String> REST_URL_PLACEHOLDERS = Map.of(
            "idm", "https://idm.company.com:8543/IDMProv",
            "sap", "https://sap.company.com/api/v1",
            "workday", "https://workday.company.com/api/v1",
            "servicenow", "https://instance.service-now.com/api");
```

**(c)** In `scenarios()`, replace:

```java
        String scenarioDir = scenarioDirectory();
        requireScenarios(YamlScenarioTest.scenarioFiles(scenarioDir), scenarioDir);
```

with:

```java
        String scenarioDir = scenarioDirectory();
        requireScenarios(YamlScenarioTest.scenarioFiles(scenarioDir), scenarioDir);
        // After guard 3, so an empty directory gets its README message and only a pattern
        // that matches nothing gets the filter's message.
        List<String> selected = YamlScenarioTest.selectedScenarioFiles(scenarioDir);
        requireRestTargets(restTargets(loadForGuards(scenarioDir, selected)), restProperties);
```

and replace:

```java
        // After guard 3, so an empty directory gets its README message and only a pattern
        // that matches nothing gets the filter's message.
        return YamlScenarioTest.buildTests(scenarioDir, YamlScenarioTest.selectedScenarioFiles(scenarioDir),
                validator, engine, RESULTS).stream();
```

with:

```java
        return YamlScenarioTest.buildTests(scenarioDir, selected, validator, engine, RESULTS).stream();
```

**(d)** Replace the existing `requireNamed` method with these two methods, so guard 2 and guard 4 share one message:

```java
    private static void requireNamed(Environment environment, String property, String variable, String placeholder) {
        requireNamedValue(property, environment.getProperty(property), variable, placeholder);
    }

    private static void requireNamedValue(String property, String value, String variable, String placeholder) {
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
```

**(e)** Add these methods after `requireScenarios`:

```java
    /**
     * Guard 4. Refuses a REST target the run is about to use when it is not configured, its
     * base-url is blank, or it is still application.yml's placeholder. Only targets the
     * selected scenarios use are checked, so a run with no REST steps makes no REST demand.
     * Checked in sorted order, so the message is deterministic.
     */
    static void requireRestTargets(Set<String> targets, RestProperties properties) {
        for (String target : new TreeSet<>(targets)) {
            RestProperties.RestApiConfig config = properties.getRest().get(target);
            if (config == null) {
                throw new IllegalStateException("Scenarios use REST target '" + target
                        + "', which is not configured. Configured targets: "
                        + new TreeSet<>(properties.getRest().keySet()));
            }
            requireNamedValue("idm.test.rest." + target + ".base-url", config.getBaseUrl(),
                    "IDM_TEST_REST_" + target.toUpperCase(Locale.ROOT).replace("-", "") + "_BASEURL",
                    REST_URL_PLACEHOLDERS.get(target));
        }
    }

    /**
     * The literal {@code target} of every {@code rest.*} step, sorted. A target containing
     * {@code ${} is only known at run time, so it is skipped; {@code RestTargets} still
     * refuses an unknown or blank one when the step runs.
     */
    static Set<String> restTargets(List<Scenario> scenarios) {
        Set<String> targets = new TreeSet<>();
        for (Scenario scenario : scenarios) {
            for (StepSpec step : scenario.steps()) {
                if (step.type().startsWith("rest.")
                        && step.params().get("target") instanceof String target
                        && !target.contains("${")) {
                    targets.add(target);
                }
            }
        }
        return targets;
    }

    /**
     * Loads the selected files for guard 4. A file that fails to load is skipped here on
     * purpose: {@code buildTests} turns it into its own failing test carrying the real
     * error, and reporting it twice would only add noise.
     */
    private static List<Scenario> loadForGuards(String scenarioDir, List<String> files) {
        List<Scenario> scenarios = new ArrayList<>();
        for (String file : files) {
            try (InputStream in = RealEnvironmentScenarios.class.getClassLoader()
                    .getResourceAsStream(scenarioDir + "/" + file)) {
                scenarios.add(ScenarioLoader.load(in, file));
            } catch (RuntimeException | IOException e) {
                // Reported by buildTests; see above.
            }
        }
        return scenarios;
    }
```

**(f)** In the class javadoc, change "The guards below run before any scenario." to "The four guards below run before any scenario."

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='RealEnvironmentGuardsTest,RealEnvironmentScenariosStructureTest,ContainerScenariosStructureTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: `RealEnvironmentGuardsTest` 21/21, `RealEnvironmentScenariosStructureTest` 5/5, `ContainerScenariosStructureTest` 10/10, and `BUILD SUCCESS`.

- [ ] **Step 5: Falsify**

Do each mutation one at a time. Re-run `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentGuardsTest -Dsurefire.failIfNoSpecifiedTests=false` after each, confirm the named tests fail, then revert:

1. In `restTargets`, replace `step.type().startsWith("rest.")` with `true`. `testRestTargetsCollected` must fail, because `hr` is collected.
2. Remove `&& !target.contains("${")`. `testRestTargetsCollected` must fail.
3. Delete the `if (config == null) { ... }` block. `testUnknownRestTargetRefused` must fail with a `NullPointerException`.
4. In `requireNamedValue`, replace `value == null || value.isBlank()` with `false`. `testBlankRestBaseUrlRefused` and `testMissingRestBaseUrlRefused` must fail. Guard 2's existing blank/unset tests fail too, which is expected, because the helper is shared.
5. In `requireNamedValue`, replace `value.equals(placeholder)` with `false`. `testRestPlaceholdersRefused` must fail, and guard 2's placeholder tests fail too.
6. In `requireRestTargets`, replace `new TreeSet<>(targets)` with `new TreeSet<>(properties.getRest().keySet())`, so it checks every configured target instead of the used ones. `testNoRestTargetsNoDemand` must fail.

Record each result.

- [ ] **Step 6: Documentation**

In `idm-test-scenarios/src/test/resources/scenarios-real/README.md`, replace the line:

```markdown
- this directory contains no `.yaml`
```

with:

```markdown
- this directory contains no `.yaml`
- a scenario about to run uses a REST `target:` that is not configured, or whose `base-url` is
  blank or still its `application.yml` placeholder. Only targets the selected scenarios use are
  checked: a run with no `rest.*` steps is never asked for REST addresses.
```

In the same file, directly after the paragraph that begins `To point at a different host without editing`, add:

```markdown

The same applies to REST targets. `prod` hardcodes every REST `base-url` to its placeholder
value, so a `prod` run whose scenarios use a REST target is refused until that target's
`IDM_TEST_REST_<TARGET>_BASEURL` is set, for example `IDM_TEST_REST_IDM_BASEURL`.
```

In `docs/specs/2026-09-22-real-environment-runner-design.md`, directly before the line `Each guard's message names what was wrong and what to set,`, add:

```markdown
4. **Require every REST target the run uses to be named** *(added by ITF-15; see
   `docs/specs/2026-09-23-rest-target-guard-design.md`)*. For the `target:` of each `rest.*`
   step in the selected scenarios, fail if the target is not configured, or its `base-url` is
   blank or still its `application.yml` placeholder. A run with no REST steps makes no REST
   demand.

```

- [ ] **Step 7: Live, and the full suite**

1. Run `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false`. Expect `BUILD SUCCESS` and **20** testcases in `idm-test-scenarios/target/surefire-reports/TEST-com.idm.test.scenarios.engine.ContainerScenarios.xml`. That proves no REST demand without REST steps, since `ContainerScenarios` publishes no REST address. This needs Docker; if Docker is not running, stop and report.
2. Run `./mvnw clean test` and the Task 0 count command. Expect `BUILD SUCCESS` and **481** (473 + 8), with no non-zero failures, errors or skips.
3. `git diff main --stat` lists only the four files in this task.

- [ ] **Step 8: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java \
        idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentGuardsTest.java \
        idm-test-scenarios/src/test/resources/scenarios-real/README.md \
        docs/specs/2026-09-22-real-environment-runner-design.md
git commit -F - <<'EOF'
feat: refuse REST targets a real-environment run uses but never named

Guard 4 collects the target of every rest.* step in the selected
scenarios and refuses an unconfigured target, a blank base-url, or an
application.yml placeholder. Runs with no REST steps make no REST demand.

ITF-15

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```
