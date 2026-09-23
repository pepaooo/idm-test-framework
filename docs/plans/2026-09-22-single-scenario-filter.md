# Single-Scenario Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an author run one YAML scenario, or a glob-selected group, with `-Dscenario=<glob>` in both `YamlScenarioTest` and `RealEnvironmentScenarios`.

**Architecture:** Add a pure function `select(files, pattern, scenarioDir)` to `YamlScenarioTest`, plus `selectedScenarioFiles(scenarioDir)`, which is the only reader of the `scenario` system property. Also add an overload of `buildTests` that takes the file list. Both `@TestFactory` methods pass the selected list to that overload. `scenarioFiles(dir)` is untouched, so its other callers are never filtered.

**Tech Stack:** Java 21, JUnit 5 `@TestFactory`, AssertJ, `java.nio.file.PathMatcher` (`glob:` syntax).

**Spec:** `docs/specs/2026-09-22-single-scenario-filter-design.md`. **Ticket:** ITF-13.

## Global Constraints

- **With no `-Dscenario` set, behaviour is identical to today.** Every file is loaded, validated and run.
- The property name is exactly `scenario`. Only `YamlScenarioTest.selectedScenarioFiles` reads it.
- Matching: `FileSystems.getDefault().getPathMatcher("glob:" + pattern)`, tried against the file name **and** the file name without `.yaml`.
- Refusals throw `IllegalStateException` from the `@TestFactory`. There are three: no file matches (the message names the pattern and the directory, and lists the available names), a blank value, and an invalid glob (the message names the pattern).
- With a filter set, only the selected files are loaded and validated.
- `scenarioFiles(String)` must not change. It has unfiltered callers: `YamlScenarioValidationTest` (through `buildTests`) and `RealEnvironmentGuardsTest`.
- `YamlScenarioValidationTest`, `BaseIntegrationTest` and every scenario YAML stay unchanged.
- Every refusal is falsification-tested: with its check removed, its test must fail.
- `-pl` is always paired with `-am`. Single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`.
- If you see "Unresolved compilation problems", that is stale IDE `.class` output. Run `./mvnw clean test` and do not change code.
- Work on branch `feat/single-scenario-filter` off `main`.
- Commit messages end with:
  ```
  ITF-13

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

---

## File Structure

| File | Change |
|---|---|
| `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java` | Add `SCENARIO_PROPERTY`, `select`, `selectedScenarioFiles` and the `buildTests` overload; `scenarios()` uses the selection |
| `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioFilterTest.java` (create) | Pure unit tests for `select`. No Spring, no system-property mutation |
| `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java` | Use `selectedScenarioFiles` after guard 3 |
| `CLAUDE.md` | One command line |
| `idm-test-scenarios/src/test/resources/scenarios-real/README.md` | One paragraph |

---

### Task 0: Branch and baseline

- [ ] **Step 1:** Create the branch.

```bash
git checkout main
git checkout -b feat/single-scenario-filter
```

- [ ] **Step 2:** Record the baseline. Run `./mvnw clean test`, then:

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
```

Expected: `BUILD SUCCESS` and **453**, with no non-zero `failures=`, `errors=` or `skipped=` in any report.

---

### Task 1: The filter in `YamlScenarioTest`

**Files:**
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java`
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioFilterTest.java`

**Interfaces:**
- Consumes (existing, unchanged): `static List<String> scenarioFiles(String scenarioDir) throws URISyntaxException`, which returns sorted `.yaml` names, or `List.of()` if the directory is missing.
- Produces (Task 2 relies on these exact names):
  - `static final String SCENARIO_PROPERTY = "scenario";`
  - `static List<String> select(List<String> files, String pattern, String scenarioDir)`
  - `static List<String> selectedScenarioFiles(String scenarioDir) throws URISyntaxException`
  - `static List<DynamicTest> buildTests(String scenarioDir, List<String> files, ScenarioValidator validator, ScenarioEngine engine, Path resultsPath) throws URISyntaxException`
  - The existing `buildTests(String, ScenarioValidator, ScenarioEngine, Path)` stays and delegates to the new overload.

- [ ] **Step 1: Write the failing tests**

Create `ScenarioFilterTest.java`:

```java
package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code -Dscenario} narrows a run to the files an author is working on. A filter that
 * selects nothing must fail, never pass having run zero scenarios, so most of these tests
 * are about the refusals.
 * <p>
 * Tests {@link YamlScenarioTest#select} directly with the pattern as an argument, so nothing
 * here sets a system property that could leak into other tests.
 */
@DisplayName("Scenario filter (-Dscenario)")
class ScenarioFilterTest {

    private static final String DIR = "scenarios";

    // Includes the deprovisioning-* and *-provisioning names that a substring match on
    // "provisioning" would wrongly select, so the group test below proves the glob does not.
    private static final List<String> FILES = List.of(
            "database-provisioning.yaml",
            "deprovisioning-user-delete.yaml",
            "deprovisioning-user-disable.yaml",
            "provisioning-minimal-user.yaml",
            "provisioning-user-to-ldap.yaml",
            "roles-assign-multiple.yaml",
            "user-provisioning.yaml");

    @Test
    @DisplayName("No filter returns every file unchanged")
    void testNoFilterReturnsEverything() {
        assertThat(YamlScenarioTest.select(FILES, null, DIR)).containsExactlyElementsOf(FILES);
    }

    @Test
    @DisplayName("An exact file name selects that one file")
    void testExactNameSelectsOneFile() {
        assertThat(YamlScenarioTest.select(FILES, "provisioning-user-to-ldap.yaml", DIR))
                .containsExactly("provisioning-user-to-ldap.yaml");
    }

    @Test
    @DisplayName("A file name without .yaml selects the same file")
    void testNameWithoutExtensionSelectsFile() {
        assertThat(YamlScenarioTest.select(FILES, "provisioning-user-to-ldap", DIR))
                .containsExactly("provisioning-user-to-ldap.yaml");
    }

    @Test
    @DisplayName("A group glob selects its group and nothing that merely contains the word")
    void testGroupGlobIsPrecise() {
        assertThat(YamlScenarioTest.select(FILES, "provisioning-*", DIR))
                .containsExactly("provisioning-minimal-user.yaml", "provisioning-user-to-ldap.yaml");
    }

    @Test
    @DisplayName("A brace glob selects two groups")
    void testBraceGlobSelectsTwoGroups() {
        assertThat(YamlScenarioTest.select(FILES, "{provisioning-*,roles-*}", DIR))
                .containsExactly(
                        "provisioning-minimal-user.yaml",
                        "provisioning-user-to-ldap.yaml",
                        "roles-assign-multiple.yaml");
    }

    @Test
    @DisplayName("A filter that matches nothing fails, listing what is available")
    void testNoMatchFails() {
        assertThatThrownBy(() -> YamlScenarioTest.select(FILES, "provisoning-user-to-ldap.yaml", DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Dscenario=provisoning-user-to-ldap.yaml")
                .hasMessageContaining(DIR)
                .hasMessageContaining("provisioning-user-to-ldap.yaml")
                .hasMessageContaining("roles-assign-multiple.yaml");
    }

    @Test
    @DisplayName("A filter against an empty directory fails and says there is nothing to choose from")
    void testNoMatchInEmptyDirectoryFails() {
        assertThatThrownBy(() -> YamlScenarioTest.select(List.of(), "anything.yaml", DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Dscenario=anything.yaml")
                .hasMessageContaining("Available: none");
    }

    @Test
    @DisplayName("A blank filter fails rather than guessing")
    void testBlankFilterFails() {
        assertThatThrownBy(() -> YamlScenarioTest.select(FILES, "  ", DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is set but empty");
    }

    @Test
    @DisplayName("An invalid glob fails, naming the pattern")
    void testInvalidGlobFails() {
        assertThatThrownBy(() -> YamlScenarioTest.select(FILES, "provisioning-[", DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Dscenario=provisioning-[")
                .hasMessageContaining("not a valid glob");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=ScenarioFilterTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure, `cannot find symbol ... select`.

- [ ] **Step 3: Implement the filter in `YamlScenarioTest`**

Make five changes.

**(a)** Add these imports alongside the existing `java.nio.file` / `java.util` imports:

```java
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.regex.PatternSyntaxException;
```

**(b)** Replace the class javadoc's first sentence. Replace:

```java
 * Runs every YAML scenario under src/test/resources/scenarios as its own test.
```

with:

```java
 * Runs every YAML scenario under src/test/resources/scenarios as its own test, or, when
 * {@code -Dscenario=<glob>} is set, only the files it selects (see {@link #select}).
```

**(c)** Add the constant directly below `private static final Path RESULTS = ...;`:

```java
    /** System property naming the scenario file, or glob of files, to run. */
    static final String SCENARIO_PROPERTY = "scenario";
```

**(d)** In `scenarios()`, replace:

```java
        return buildTests(SCENARIO_DIR, validator, engine, RESULTS).stream();
```

with:

```java
        return buildTests(SCENARIO_DIR, selectedScenarioFiles(SCENARIO_DIR), validator, engine, RESULTS).stream();
```

**(e)** Replace the existing `buildTests(...)` method, its javadoc included, with the two overloads below. The body of the second is the existing body, except that the loop iterates `files` instead of calling `scenarioFiles(scenarioDir)`. Then add `select`, `selectedScenarioFiles` and `stripYaml`:

```java
    /**
     * Builds a test for every scenario file in {@code scenarioDir}, unfiltered.
     */
    static List<DynamicTest> buildTests(
            String scenarioDir, ScenarioValidator validator, ScenarioEngine engine, Path resultsPath)
            throws URISyntaxException {
        return buildTests(scenarioDir, scenarioFiles(scenarioDir), validator, engine, resultsPath);
    }

    /**
     * Loads and validates each of {@code files} (names within {@code scenarioDir}) up front,
     * before any dynamic test runs, so a malformed twentieth file is reported immediately
     * rather than after its predecessors have already spent minutes waiting on provisioning.
     * <p>
     * A file that loads and validates cleanly becomes a dynamic test that runs the engine.
     * A file that fails to load or validate still becomes a dynamic test — one that fails
     * immediately with that error — so a typo in one file never hides the scenarios that are
     * fine, and every file still ends up with a {@link ScenarioResult} in the JSON output.
     * <p>
     * Catches {@link RuntimeException} broadly, not just {@code ValidationException}:
     * {@code ScenarioLoader} and {@code ScenarioValidator} only ever throw the latter today,
     * but trusting that here would mean any other failure during load or validation escapes
     * this factory method entirely — aborting the whole {@code @TestFactory} with zero
     * dynamic tests and no results file, the exact failure shape this method exists to avoid.
     */
    static List<DynamicTest> buildTests(
            String scenarioDir, List<String> files, ScenarioValidator validator, ScenarioEngine engine,
            Path resultsPath) throws URISyntaxException {

        List<ScenarioResult> collected = Collections.synchronizedList(new ArrayList<>());
        List<DynamicTest> tests = new ArrayList<>();

        for (String file : files) {
            DynamicTest test;
            try {
                Scenario scenario = ScenarioLoader.load(resource(scenarioDir, file), file);
                validator.validate(scenario, file);

                test = DynamicTest.dynamicTest(file, () -> {
                    ScenarioResult result = engine.run(scenario, file);
                    collected.add(result);
                    JsonResultWriter.write(List.copyOf(collected), resultsPath);

                    assertThat(result.success())
                            .as(result.failureSummary())
                            .isTrue();
                });
            } catch (RuntimeException e) {
                String message = e.getMessage();
                test = DynamicTest.dynamicTest(file, () -> {
                    ScenarioResult result = new ScenarioResult(file, file, false, 0L, List.of(), message);
                    collected.add(result);
                    JsonResultWriter.write(List.copyOf(collected), resultsPath);
                    fail(message);
                });
            }
            tests.add(test);
        }
        return tests;
    }

    /**
     * The scenario files in {@code scenarioDir} selected by {@code -Dscenario}, or all of them
     * when it is not set. The only place that reads the property.
     */
    static List<String> selectedScenarioFiles(String scenarioDir) throws URISyntaxException {
        return select(scenarioFiles(scenarioDir), System.getProperty(SCENARIO_PROPERTY), scenarioDir);
    }

    /**
     * Narrows {@code files} to those matching {@code pattern}, a glob tried against each file
     * name with and without its {@code .yaml} extension. A {@code null} pattern means no
     * filter was asked for and returns {@code files} unchanged.
     * <p>
     * Every other outcome that would select nothing throws instead: a blank pattern, an
     * invalid glob, or a pattern matching no file. A filtered run that ran zero scenarios
     * and reported green would be the vacuous success this project keeps closing.
     * {@code scenarioDir} is used only in messages.
     */
    static List<String> select(List<String> files, String pattern, String scenarioDir) {
        if (pattern == null) {
            return files;
        }
        if (pattern.isBlank()) {
            throw new IllegalStateException("-D" + SCENARIO_PROPERTY + " is set but empty. Name a scenario file or"
                    + " glob, for example -D" + SCENARIO_PROPERTY + "=provisioning-user-to-ldap.yaml, or leave it"
                    + " unset to run every scenario in " + scenarioDir + ".");
        }

        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException("-D" + SCENARIO_PROPERTY + "=" + pattern
                    + " is not a valid glob: " + e.getDescription(), e);
        }

        List<String> selected = files.stream()
                .filter(file -> matcher.matches(Path.of(file)) || matcher.matches(Path.of(stripYaml(file))))
                .toList();

        if (selected.isEmpty()) {
            throw new IllegalStateException("-D" + SCENARIO_PROPERTY + "=" + pattern + " matches no scenario in "
                    + scenarioDir + ". Available: " + (files.isEmpty() ? "none" : String.join(", ", files)));
        }
        return selected;
    }

    private static String stripYaml(String file) {
        return file.endsWith(".yaml") ? file.substring(0, file.length() - ".yaml".length()) : file;
    }
```

Leave `registry`, `resource` and `scenarioFiles` exactly as they are.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='ScenarioFilterTest,YamlScenarioValidationTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: `ScenarioFilterTest` 9/9 and `YamlScenarioValidationTest` 2/2, 0 failures/errors/skips, `BUILD SUCCESS`. `YamlScenarioValidationTest` proves the unchanged four-argument `buildTests` still works.

- [ ] **Step 5: Falsify each refusal**

Do each mutation one at a time. Re-run `./mvnw test -pl idm-test-scenarios -am -Dtest=ScenarioFilterTest -Dsurefire.failIfNoSpecifiedTests=false` after each, confirm the named tests fail, then revert:

1. Replace `pattern.isBlank()` with `false`. `testBlankFilterFails` must fail: the blank pattern now reaches the no-match refusal, whose message lacks "is set but empty".
2. Replace the `catch (PatternSyntaxException e) { ... }` body with `throw e;`. `testInvalidGlobFails` must fail on exception type.
3. Replace `selected.isEmpty()` with `false`. `testNoMatchFails` and `testNoMatchInEmptyDirectoryFails` must fail.
4. Replace `|| matcher.matches(Path.of(stripYaml(file)))` with nothing. `testNameWithoutExtensionSelectsFile` must fail.
5. Replace `return files;` in the null branch with `return List.of();`. `testNoFilterReturnsEverything` must fail.

Afterwards, `git diff` must show only your Step 3 changes. Re-run Step 4 and record the result of each mutation in your report.

- [ ] **Step 6: Live checks against the real corpus**

Each command below runs `YamlScenarioTest`. The count is the number of `<testcase` elements in `idm-test-scenarios/target/surefire-reports/TEST-com.idm.test.scenarios.engine.YamlScenarioTest.xml`, which you can get with `grep -c "<testcase"` on that file.

1. `./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false -Dscenario=provisioning-user-to-ldap.yaml`. Expect `BUILD SUCCESS` and **1** testcase.
2. The same command with `-Dscenario='provisioning-*'`. Expect `BUILD SUCCESS` and **3** testcases.
3. The same command with `-Dscenario=provisoning-user-to-ldap.yaml` (a deliberate typo). Expect `BUILD FAILURE`, and `idm-test-scenarios/target/surefire-reports/com.idm.test.scenarios.engine.YamlScenarioTest.txt` must contain `matches no scenario in scenarios. Available:`.
4. The same command with no `-Dscenario`. Expect `BUILD SUCCESS` and **20** testcases.

If any count differs, stop and report. Do not adjust the code to fit.

- [ ] **Step 7: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java \
        idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioFilterTest.java
git commit -F - <<'EOF'
feat: run a single YAML scenario or a glob-selected group with -Dscenario

With no property set, every file runs as before. A blank value, an
invalid glob or a pattern matching nothing fails the factory rather
than passing having run zero scenarios.

ITF-13

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Real-environment runner, docs and full suite

**Files:**
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java`
- Modify: `CLAUDE.md`
- Modify: `idm-test-scenarios/src/test/resources/scenarios-real/README.md`

**Interfaces:**
- Consumes (from Task 1, in `YamlScenarioTest`):
  - `static List<String> scenarioFiles(String scenarioDir)`
  - `static List<String> selectedScenarioFiles(String scenarioDir)`
  - `static List<DynamicTest> buildTests(String scenarioDir, List<String> files, ScenarioValidator, ScenarioEngine, Path)`
- Produces: nothing.

- [ ] **Step 1: Wire the filter into `RealEnvironmentScenarios`**

In `scenarios()`, keep `requireRealTarget(environment);` and `requireScenarios(YamlScenarioTest.scenarioFiles(SCENARIO_DIR));` exactly as they are. Guard 3 must still see the unfiltered listing, so an empty directory keeps its own message.

Replace:

```java
        return YamlScenarioTest.buildTests(SCENARIO_DIR, validator, engine, RESULTS).stream();
```

with:

```java
        // After guard 3, so an empty directory gets its README message and only a pattern
        // that matches nothing gets the filter's message.
        return YamlScenarioTest.buildTests(SCENARIO_DIR, YamlScenarioTest.selectedScenarioFiles(SCENARIO_DIR),
                validator, engine, RESULTS).stream();
```

In the class javadoc, directly after the `</pre>` line that closes the run command, add:

```java
 * Add {@code -Dscenario=<file or glob>} to run only matching files, as with
 * {@link YamlScenarioTest}.
```

- [ ] **Step 2: Run the runner's own tests**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='RealEnvironmentGuardsTest,RealEnvironmentScenariosStructureTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 12 + 5 = 17 passing. `testNoEmbeddedServerReferences` still passes, because the new lines mention no embedded server.

- [ ] **Step 3: Document the option**

In `CLAUDE.md`, under `### Build & Run`, directly after these two lines:

```bash
# Run specific test method
./mvnw test -Dtest=UserProvisioningTest#testCompleteProvisioningWorkflow
```

add:

```bash

# Run one YAML scenario, or a glob-selected group (quote patterns containing * or braces)
./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false -Dscenario=provisioning-user-to-ldap.yaml
./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false -Dscenario='provisioning-*'
```

In `idm-test-scenarios/src/test/resources/scenarios-real/README.md`, directly after the line that begins `Results are written to`, add a blank line and then this paragraph:

```markdown
To run one scenario or a group, add `-Dscenario=` with a file name or a glob, for example
`-Dscenario=create-user.yaml` or `-Dscenario='onboarding-*'`. Quote any pattern containing `*`
or braces. The `.yaml` extension is optional. A pattern that matches nothing fails and lists the
files that are available.
```

- [ ] **Step 4: Full suite**

Run `./mvnw clean test`. Then run the Task 0 count command.

Expected:
- `BUILD SUCCESS`.
- **462** tests (453 + 9), with no non-zero `failures=`, `errors=` or `skipped=` in any report.
- `ls idm-test-scenarios/target/surefire-reports | grep -c RealEnvironmentScenarios.txt` prints `0`.
- `git diff main --stat` lists only `YamlScenarioTest.java`, `ScenarioFilterTest.java`, `RealEnvironmentScenarios.java`, `CLAUDE.md` and `scenarios-real/README.md`.

- [ ] **Step 5: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java \
        CLAUDE.md idm-test-scenarios/src/test/resources/scenarios-real/README.md
git commit -F - <<'EOF'
feat: honour -Dscenario in the real-environment runner and document it

The filter applies after the empty-directory guard, so each failure
keeps its own message.

ITF-13

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```
