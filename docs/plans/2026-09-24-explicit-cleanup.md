# Explicit Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the engine's inferred undo with an author-written top-level `cleanup:` block. Its outcomes are reported, and a cleanup failure fails the scenario.

**Architecture:** The work is split into three tasks, ordered so the build stays green after each:

1. **Task 1** adds `cleanup:` end to end: model, loader, validator, engine, result and report. Inferred undo still exists at this point.
2. **Task 2** migrates the corpus and fixtures to `cleanup:` blocks, and extends guard 4 to walk cleanup entries.
3. **Task 3** removes inferred undo everywhere: `StepOutcome`'s undo slot, the engine's unwind, the four steps, and `db.execute`'s `undo:`. It also updates the docs.

During Tasks 1–2, cleanup runs **before** the engine unwinds the old undos. The duplicate deletes this causes are harmless: `LdapTemplate.unbind` silently accepts a missing DN, and a `DELETE ... WHERE` affects 0 rows.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Jackson (records serialize as-is). The engine module must not import Spring.

**Spec:** `docs/specs/2026-09-24-explicit-cleanup-design.md`. **Ticket:** ITF-19.

## Global Constraints

- **Outcomes:** each cleanup entry ends in exactly one of `SUCCEEDED`, `FAILED` or `SKIPPED`.
  - **SKIPPED** only when a root name the entry references is not bound in scope. Any other resolution error is `FAILED`.
- **When cleanup runs:** always, after the steps (pass or fail), in declaration order. Every entry is attempted.
- **Pass/fail:** a scenario passes only if all steps passed **and** no cleanup entry is `FAILED`. `SKIPPED` never fails a scenario.
- **Failure summary:** the steps' own failure leads; cleanup failures follow it.
- **Load-time validation of cleanup entries:**
  - the step type must be known
  - it must be an action (not `retryable()`, and not `set`)
  - no `wait:` and no `as:`
  - required parameters present, and only accepted parameters
  - expressions well-formed
  - every reference names something bound **anywhere** in the scenario: fixtures, any step's `as:`, or any `set:`
- **Error wording:** validation errors for cleanup entries read `<source>: cleanup entry N (<type>): <message>`. Step errors keep their existing wording.
- **Compatibility constructors:**
  - `Scenario` keeps a 4-argument constructor (no cleanup).
  - `ScenarioResult` keeps its 6-argument constructor (no cleanup).
  
  This way callers that don't care about cleanup don't change.
- **No existing loader or validator error message for steps changes.**
- **After Task 3:** `grep -rn "undo" idm-test-engine/src/main` returns nothing, and no step registers teardown.
- **Corpus migration never edits a scenario's steps** except to add `as: createdDn` to an `ldap.create` and to remove `undo:` from `db.execute`.
- **No scenario may report a `FAILED` cleanup entry.**
- Every refusal is falsification-tested.
- `-pl` is always paired with `-am`. Single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`.
- If you see "Unresolved compilation problems", that is stale IDE output. Run `./mvnw clean test` and do not change code.
- Branch: `feat/explicit-cleanup` off `main`.
- Commit messages end with:
  ```
  ITF-19

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

Paths below use `E` = `idm-test-engine/src/main/java/com/idm/test/engine` and `ET` = `idm-test-engine/src/test/java/com/idm/test/engine`.

---

### Task 0: Branch and baseline

- [ ] **Step 1:** `git checkout main && git checkout -b feat/explicit-cleanup`
- [ ] **Step 2:** Run `./mvnw clean test`, then:

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
```

Expected: `BUILD SUCCESS` and **486**, with no non-zero `failures=`, `errors=` or `skipped=`.

---

### Task 1: `cleanup:` in the engine

**Files:**
- Modify:
  - `E/model/Scenario.java`
  - `E/ScenarioLoader.java`
  - `E/ValidationException.java`
  - `E/ScenarioValidator.java`
  - `E/ScenarioEngine.java`
  - `E/result/ScenarioResult.java`
- Create:
  - `E/result/CleanupResult.java`
  - `E/result/CleanupStatus.java`
- Test (modify):
  - `ET/ScenarioEngineTest.java`
  - `ET/ScenarioLoaderTest.java`
  - `ET/ScenarioValidatorTest.java`

**Interfaces produced:**
- `Scenario(String name, String description, Map<String, FixtureSpec> fixtures, List<StepSpec> steps, List<StepSpec> cleanup)`: `cleanup` is never null, and there is a 4-argument constructor with no cleanup.
- `enum CleanupStatus { SUCCEEDED, FAILED, SKIPPED }`
- `record CleanupResult(int index, String type, Map<String, Object> resolvedParams, CleanupStatus status, long durationMillis, String message)`
- `ScenarioResult(String name, String sourceName, boolean success, long durationMillis, List<StepResult> steps, List<CleanupResult> cleanup, String failureDetail)`: this is the new canonical order, plus a 6-argument compatibility constructor `(name, sourceName, success, durationMillis, steps, failureDetail)`.
- `static ValidationException ValidationException.forCleanup(String sourceName, int index, String stepType, String message)`

- [ ] **Step 1: Write the failing engine tests**

In `ET/ScenarioEngineTest.java`, add these imports:

```java
import com.idm.test.engine.result.CleanupStatus;
```

Add this helper after `failingAction`:

```java
    /** Records that it ran, registers nothing. */
    private StepType plainAction(String name) {
        return new StepType() {
            public String name() { return name; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                events.add("run:" + name + ":" + params.get("dn"));
                return StepOutcome.success("ok");
            }
        };
    }

    private static Scenario withCleanup(List<StepSpec> steps, StepSpec... cleanup) {
        return new Scenario("Test", null,
                Map.of("user", new FixtureSpec("random-user", Map.of())),
                steps, List.of(cleanup));
    }
```

Add these tests at the end of the class:

```java
    @Test
    @DisplayName("Cleanup runs after the steps, in declaration order")
    void testCleanupRunsAfterStepsInOrder() {
        ScenarioEngine engine = engineWith(plainAction("a"), plainAction("b"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1"))),
                new StepSpec("a", Map.of("dn", "c1")),
                new StepSpec("b", Map.of("dn", "c2"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(events).containsExactly("run:a:s1", "run:a:c1", "run:b:c2");
        assertThat(result.cleanup()).extracting(c -> c.status())
                .containsExactly(CleanupStatus.SUCCEEDED, CleanupStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("Cleanup still runs when a step failed")
    void testCleanupRunsAfterAFailedStep() {
        ScenarioEngine engine = engineWith(plainAction("a"), failingAction("f"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1")), new StepSpec("f", Map.of())),
                new StepSpec("a", Map.of("dn", "c1"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(events).containsExactly("run:a:s1", "run:f", "run:a:c1");
    }

    @Test
    @DisplayName("Every cleanup entry is attempted even after one fails, and the failure fails the scenario")
    void testAllCleanupEntriesAttempted() {
        ScenarioEngine engine = engineWith(plainAction("a"), failingAction("f"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1"))),
                new StepSpec("f", Map.of()),
                new StepSpec("a", Map.of("dn", "c2"))), "t.yaml");

        assertThat(events).containsExactly("run:a:s1", "run:f", "run:a:c2");
        assertThat(result.cleanup()).extracting(c -> c.status())
                .containsExactly(CleanupStatus.FAILED, CleanupStatus.SUCCEEDED);
        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("cleanup entry 1 (f)").contains("f went wrong");
    }

    @Test
    @DisplayName("A cleanup entry whose capture was never bound is SKIPPED, and a skip never fails a scenario")
    void testUnboundCaptureSkips() {
        ScenarioEngine engine = engineWith(plainAction("a"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1"))),
                new StepSpec("a", Map.of("dn", "${neverBound}"))), "t.yaml");

        assertThat(events).containsExactly("run:a:s1");
        assertThat(result.cleanup()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(CleanupStatus.SKIPPED);
            assertThat(c.message()).contains("neverBound");
        });
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("A cleanup entry can use a capture a step bound")
    void testCleanupSeesCaptures() {
        ScenarioEngine engine = engineWith(valueProducing("v", "cn=made"), plainAction("a"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("v", Map.of("dn", "x", "as", "created"))),
                new StepSpec("a", Map.of("dn", "${created}"))), "t.yaml");

        assertThat(events).containsExactly("run:a:cn=made");
        assertThat(result.cleanup()).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo(CleanupStatus.SUCCEEDED));
    }

    @Test
    @DisplayName("A bad field on a bound name is FAILED, not SKIPPED, so a typo cannot hide as a skip")
    void testBadFieldOnBoundNameFails() {
        ScenarioEngine engine = engineWith(plainAction("a"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1"))),
                new StepSpec("a", Map.of("dn", "${user.bogus}"))), "t.yaml");

        assertThat(result.cleanup()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(CleanupStatus.FAILED);
            assertThat(c.message()).contains("bogus");
        });
        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("A step failure leads the summary; cleanup failures follow it")
    void testStepFailureLeadsSummary() {
        ScenarioEngine engine = engineWith(failingAction("f"), failingAction("g"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("f", Map.of())),
                new StepSpec("g", Map.of())), "t.yaml");

        String summary = result.failureSummary();
        assertThat(summary).contains("f went wrong").contains("g went wrong");
        assertThat(summary.indexOf("f went wrong")).isLessThan(summary.indexOf("g went wrong"));
    }

    @Test
    @DisplayName("A scenario with no cleanup block reports an empty cleanup list")
    void testNoCleanupMeansEmptyList() {
        ScenarioEngine engine = engineWith(plainAction("a"));

        ScenarioResult result = engine.run(scenario(new StepSpec("a", Map.of("dn", "s1"))), "t.yaml");

        assertThat(result.cleanup()).isEmpty();
        assertThat(result.success()).isTrue();
    }
```

- [ ] **Step 2: Write the failing loader and validator tests**

In `ET/ScenarioLoaderTest.java`, add the imports `java.io.ByteArrayInputStream` and `java.nio.charset.StandardCharsets` if they are missing, then add:

```java
    private static InputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Should load a cleanup block after the steps")
    void testLoadsCleanup() {
        Scenario scenario = ScenarioLoader.load(yaml("""
                name: With cleanup
                steps:
                  - ldap.create:
                      dn: cn=x
                cleanup:
                  - ldap.delete:
                      dn: cn=x
                """), "c.yaml");

        assertThat(scenario.cleanup()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo("ldap.delete");
            assertThat(entry.params()).containsEntry("dn", "cn=x");
        });
    }

    @Test
    @DisplayName("Should default to an empty cleanup block")
    void testCleanupDefaultsToEmpty() {
        Scenario scenario = ScenarioLoader.load(yaml("""
                name: No cleanup
                steps:
                  - ldap.create:
                      dn: cn=x
                """), "c.yaml");

        assertThat(scenario.cleanup()).isEmpty();
    }

    @Test
    @DisplayName("Should reject a cleanup block that is not a list, naming the section")
    void testRejectsNonListCleanup() {
        assertThatThrownBy(() -> ScenarioLoader.load(yaml("""
                name: Bad cleanup
                steps:
                  - ldap.create:
                      dn: cn=x
                cleanup: nope
                """), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'cleanup' must be a list");
    }
```

If `assertThatThrownBy` or `ValidationException` are not already imported there, import them (`static org.assertj.core.api.Assertions.assertThatThrownBy`; `ValidationException` is in the same package).

In `ET/ScenarioValidatorTest.java`, add:

```java
    private static Scenario scenarioWithCleanup(List<StepSpec> steps, StepSpec... cleanup) {
        return new Scenario("Test", null,
                Map.of("user", new FixtureSpec("random-user", Map.of())),
                steps, List.of(cleanup));
    }

    private static final List<StepSpec> CREATE_AS_CREATED = List.of(
            new StepSpec("ldap.create", Map.of("dn", "cn=${user.userId}", "as", "created")));

    @Test
    @DisplayName("Cleanup may reference a name bound by any step")
    void testCleanupReferencesAnyBinding() {
        assertThatCode(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "${created}"))), "c.yaml"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Cleanup may not reference a name bound nowhere")
    void testCleanupRejectsUnknownReference() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "${nowhere}"))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cleanup entry 1 (ldap.create)")
                .hasMessageContaining("nowhere");
    }

    @Test
    @DisplayName("Cleanup rejects an assertion")
    void testCleanupRejectsAssertion() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.verify", Map.of("dn", "cn=x", "expect", Map.of()))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cleanup entry 1 (ldap.verify)")
                .hasMessageContaining("assertion");
    }

    @Test
    @DisplayName("Cleanup rejects wait:")
    void testCleanupRejectsWait() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "cn=x", "wait", true))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'wait' is not allowed on a cleanup entry");
    }

    @Test
    @DisplayName("Cleanup rejects as:")
    void testCleanupRejectsAs() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "cn=x", "as", "again"))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'as' is not allowed on a cleanup entry");
    }

    @Test
    @DisplayName("Cleanup rejects set:")
    void testCleanupRejectsSet() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("set", Map.of("x", "1"))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cleanup entry 1 (set)");
    }

    @Test
    @DisplayName("Cleanup rejects an unknown step type, a missing required parameter and an unknown parameter")
    void testCleanupRejectsBadEntries() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.nope", Map.of())), "c.yaml"))
                .hasMessageContaining("cleanup entry 1 (ldap.nope)").hasMessageContaining("unknown step type");
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of())), "c.yaml"))
                .hasMessageContaining("missing required parameter 'dn'");
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "cn=x", "bogus", 1))), "c.yaml"))
                .hasMessageContaining("unknown parameter 'bogus'");
    }
```

Import `assertThatCode` / `assertThatThrownBy` there if they are missing.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='ScenarioEngineTest,ScenarioLoaderTest,ScenarioValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure (`CleanupStatus`, the 5-argument `Scenario`, `cleanup()`).

- [ ] **Step 4: Model and result types**

Replace the record declaration in `E/model/Scenario.java` (keep its class javadoc and imports) with:

```java
public record Scenario(
        String name,
        String description,
        Map<String, FixtureSpec> fixtures,
        List<StepSpec> steps,
        List<StepSpec> cleanup) {

    public Scenario {
        cleanup = cleanup == null ? List.of() : cleanup;
    }

    /** A scenario with no cleanup block. */
    public Scenario(String name, String description, Map<String, FixtureSpec> fixtures, List<StepSpec> steps) {
        this(name, description, fixtures, steps, List.of());
    }
}
```

Create `E/result/CleanupStatus.java`:

```java
package com.idm.test.engine.result;

/** How one {@code cleanup:} entry ended. */
public enum CleanupStatus {
    /** The action ran and succeeded. */
    SUCCEEDED,
    /** The action ran and failed, or its parameters could not be resolved. Fails the scenario. */
    FAILED,
    /** It references a name that was never bound, so what it would tear down never happened. */
    SKIPPED
}
```

Create `E/result/CleanupResult.java`:

```java
package com.idm.test.engine.result;

import java.util.Map;

/**
 * What happened to one {@code cleanup:} entry.
 *
 * @param index          position in the cleanup block, from 0
 * @param type           the step type, e.g. {@code ldap.delete}
 * @param resolvedParams parameters after interpolation; empty when SKIPPED or when resolving failed
 * @param status         how the entry ended
 * @param durationMillis time spent on this entry
 * @param message        the step's own message, or why it was skipped or failed
 */
public record CleanupResult(
        int index,
        String type,
        Map<String, Object> resolvedParams,
        CleanupStatus status,
        long durationMillis,
        String message) {
}
```

Replace `E/result/ScenarioResult.java`'s record header and `failureSummary()` (keep the package, the imports and the class javadoc; add `import java.util.ArrayList;` if needed):

```java
public record ScenarioResult(
        String name,
        String sourceName,
        boolean success,
        long durationMillis,
        List<StepResult> steps,
        List<CleanupResult> cleanup,
        String failureDetail) {

    public ScenarioResult {
        cleanup = cleanup == null ? List.of() : cleanup;
    }

    /** A result with no cleanup outcomes. */
    public ScenarioResult(String name, String sourceName, boolean success, long durationMillis,
                          List<StepResult> steps, String failureDetail) {
        this(name, sourceName, success, durationMillis, steps, List.of(), failureDetail);
    }

    /**
     * @return a message naming the file and what went wrong, or null when successful. The
     * steps' own failure leads; any FAILED cleanup entries follow it.
     */
    public String failureSummary() {
        if (success) {
            return null;
        }

        List<String> parts = new ArrayList<>();

        StepResult failed = null;
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (!steps.get(i).success()) {
                failed = steps.get(i);
                break;
            }
        }

        if (failed != null) {
            StringBuilder step = new StringBuilder()
                    .append(sourceName)
                    .append(", step ").append(failed.index() + 1)
                    .append(" (").append(failed.type()).append("): ")
                    .append(failed.message());
            if (failed.attempts() != null) {
                step.append(" — gave up after ").append(failed.attempts())
                        .append(" attempts over ").append(failed.waitedMillis()).append("ms");
            }
            parts.add(step.toString());
        } else if (failureDetail != null) {
            parts.add(sourceName + ": " + failureDetail);
        }

        for (CleanupResult entry : cleanup) {
            if (entry.status() == CleanupStatus.FAILED) {
                parts.add(sourceName + ", cleanup entry " + (entry.index() + 1)
                        + " (" + entry.type() + "): " + entry.message());
            }
        }

        return parts.isEmpty()
                ? sourceName + ": scenario failed before any step ran"
                : String.join("; ", parts);
    }
}
```

- [ ] **Step 5: Loader**

In `E/ValidationException.java`, add:

```java
    /** For a {@code cleanup:} entry: "{source}: cleanup entry N (type): message". */
    public static ValidationException forCleanup(String sourceName, int index, String stepType, String message) {
        return new ValidationException(sourceName, "cleanup entry " + (index + 1) + " (" + stepType + "): " + message);
    }
```

In `E/ScenarioLoader.java`, change the `return new Scenario(...)` call to:

```java
        return new Scenario(
                name,
                asString(root.get("description")),
                readFixtures(root.get("fixtures"), sourceName),
                readSteps(root.get("steps"), sourceName, "steps"),
                readSteps(root.get("cleanup"), sourceName, "cleanup"));
```

Change `readSteps` as follows. The step wording must stay byte-identical, so the existing loader tests must pass unchanged.
- Signature: `private static List<StepSpec> readSteps(Object raw, String sourceName, String section)`.
- `throw new ValidationException(sourceName, "'steps' must be a list");` becomes `throw new ValidationException(sourceName, "'" + section + "' must be a list");`.
- In the "exactly one step type" error, replace the literal `"step " + (i + 1)` with `("steps".equals(section) ? "step " : "cleanup entry ") + (i + 1)`.
- Replace the "parameters must be a map" throw with:

```java
                String message = "parameters must be a map, not a " + params.getClass().getSimpleName();
                throw "steps".equals(section)
                        ? new ValidationException(sourceName, i, stepType, message)
                        : ValidationException.forCleanup(sourceName, i, stepType, message);
```

- [ ] **Step 6: Validator**

In `E/ScenarioValidator.java`:

**(a)** Add a private functional interface inside the class:

```java
    /** Builds the located error for one entry, so helpers work for steps and cleanup alike. */
    @FunctionalInterface
    private interface Locate {
        ValidationException at(String message);
    }
```

**(b)** Change `requireWellFormedExpressions`. Its signature becomes `private void requireWellFormedExpressions(Object value, Locate locate)`. Every `throw new ValidationException(sourceName, index, stepType, X)` inside it becomes `throw locate.at(X)`, and the recursive calls pass `locate`. Update its two existing call sites:
- In the `set:` loop, replace `requireWellFormedExpressions(entry.getValue(), sourceName, i, step.type());` with `requireWellFormedExpressions(entry.getValue(), message -> new ValidationException(sourceName, index, step.type(), message));`.
- In the step path, replace `requireWellFormedExpressions(step.params(), sourceName, i, step.type());` with `requireWellFormedExpressions(step.params(), message -> new ValidationException(sourceName, index, step.type(), message));`.
- Because `i` is not effectively final, add `int index = i;` as the first line of the `for` loop body, and use `index` in these two lambdas.

**(c)** At the end of `validate(...)`, after the steps loop, add:

```java
        // Cleanup runs after everything, so it may reference any name bound anywhere above.
        for (int i = 0; i < scenario.cleanup().size(); i++) {
            validateCleanupEntry(scenario.cleanup().get(i), i, known, sourceName);
        }
```

**(d)** Add this method:

```java
    /**
     * A cleanup entry is an action run once after the steps. It cannot wait, capture, bind or
     * assert, and it may reference any name the scenario binds anywhere.
     */
    private void validateCleanupEntry(StepSpec entry, int index, Set<String> known, String sourceName) {
        Locate locate = message -> ValidationException.forCleanup(sourceName, index, entry.type(), message);

        if (SET_STEP.equals(entry.type())) {
            throw locate.at("set: is not allowed in cleanup — nothing runs after cleanup to read a binding");
        }

        StepType type = registry.get(entry.type());
        if (type == null) {
            throw locate.at("unknown step type. Available: " + new TreeSet<>(registry.keySet()));
        }
        if (type.retryable()) {
            throw locate.at("cleanup entries must be actions; " + entry.type()
                    + " is an assertion and tears nothing down");
        }
        for (String reserved : List.of("wait", "as")) {
            if (entry.params().containsKey(reserved)) {
                throw locate.at("'" + reserved + "' is not allowed on a cleanup entry");
            }
        }

        Set<String> accepted = new LinkedHashSet<>(type.acceptedParams());
        accepted.addAll(type.requiredParams());

        for (String required : new TreeSet<>(type.requiredParams())) {
            if (!entry.params().containsKey(required)) {
                throw locate.at("missing required parameter '" + required + "'");
            }
        }
        for (String supplied : entry.params().keySet()) {
            if (!accepted.contains(supplied)) {
                throw locate.at("unknown parameter '" + supplied + "'. " + entry.type()
                        + " accepts: " + new TreeSet<>(accepted));
            }
        }

        requireWellFormedExpressions(entry.params(), locate);

        for (String reference : Interpolator.references(entry.params())) {
            if (!known.contains(reference)) {
                throw locate.at("references '${" + reference + "...}' but nothing named '" + reference
                        + "' is declared or bound anywhere in the scenario");
            }
        }
    }
```

Add `import java.util.List;` if it is missing.

- [ ] **Step 7: Engine**

In `E/ScenarioEngine.java`:

**(a)** Add these imports:

```java
import com.idm.test.engine.result.CleanupResult;
import com.idm.test.engine.result.CleanupStatus;
import java.util.Set;
```

**(b)** In `run(...)`, declare `List<CleanupResult> cleanupResults = List.of();` next to `results`. Replace the `finally` block with:

```java
        } finally {
            // Author-written cleanup first, then the remaining inferred undos (removed in a
            // later change). Both always run, whether or not the steps passed.
            cleanupResults = runCleanup(scenario.cleanup(), scope);
            unwind(undos);
        }

        boolean cleanupFailed = cleanupResults.stream()
                .anyMatch(entry -> entry.status() == CleanupStatus.FAILED);

        return new ScenarioResult(scenario.name(), sourceName, success && !cleanupFailed,
                System.currentTimeMillis() - started, results, cleanupResults, failureDetail);
```

Delete the old `return new ScenarioResult(...)` line.

**(c)** Update the class javadoc's first sentence to: "Runs a scenario: interpolate each step's parameters, execute in order, retry assertions that declare a wait, stop at the first failure, then run the author's cleanup block."

**(d)** Add these methods:

```java
    /**
     * Runs every cleanup entry in order, whatever happened before. An entry that references a
     * name never bound is SKIPPED: what it would tear down never happened. Never throws.
     */
    private List<CleanupResult> runCleanup(List<StepSpec> cleanup, Map<String, Object> scope) {
        List<CleanupResult> results = new ArrayList<>();
        for (int i = 0; i < cleanup.size(); i++) {
            StepSpec entry = cleanup.get(i);
            long started = System.currentTimeMillis();
            CleanupResult result;
            try {
                result = runCleanupEntry(entry, i, scope, started);
            } catch (RuntimeException e) {
                result = new CleanupResult(i, entry.type(), Map.of(), CleanupStatus.FAILED,
                        System.currentTimeMillis() - started,
                        "cleanup '" + entry.type() + "' threw " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
            }
            results.add(result);
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private CleanupResult runCleanupEntry(
            StepSpec entry, int index, Map<String, Object> scope, long started) {

        Set<String> unbound = new TreeSet<>(Interpolator.references(entry.params()));
        unbound.removeAll(scope.keySet());
        if (!unbound.isEmpty()) {
            return new CleanupResult(index, entry.type(), Map.of(), CleanupStatus.SKIPPED,
                    System.currentTimeMillis() - started,
                    "skipped: " + unbound + " never bound, so what this would clean up never happened");
        }

        StepType type = registry.get(entry.type());
        if (type == null) {
            return new CleanupResult(index, entry.type(), Map.of(), CleanupStatus.FAILED,
                    System.currentTimeMillis() - started,
                    "unknown step type '" + entry.type() + "'. Available: " + new TreeSet<>(registry.keySet()));
        }

        Map<String, Object> resolved =
                (Map<String, Object>) Interpolator.resolve(entry.params(), scope, evaluator);
        StepOutcome outcome = type.execute(resolved, false);

        return new CleanupResult(index, entry.type(), resolved,
                outcome.success() ? CleanupStatus.SUCCEEDED : CleanupStatus.FAILED,
                System.currentTimeMillis() - started, outcome.message());
    }
```

`runCleanupEntry` deliberately ignores any undo on the outcome: cleanup registers nothing.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-engine -am -Dsurefire.failIfNoSpecifiedTests=false`

Expected: `BUILD SUCCESS`. All engine tests pass, the new ones included, and so do the existing loader and validator tests, unchanged.

- [ ] **Step 9: Falsify**

Do each mutation one at a time. Re-run Step 8's command after each, confirm the named test fails, then revert:

1. In `runCleanupEntry`, remove the `unbound` block. `testUnboundCaptureSkips` must fail.
2. In `run`, replace `success && !cleanupFailed` with `success`. `testAllCleanupEntriesAttempted` must fail.
3. In `runCleanup`, `break` after the first `FAILED` result. `testAllCleanupEntriesAttempted` must fail.
4. In the validator, remove the `type.retryable()` check. `testCleanupRejectsAssertion` must fail.
5. In the validator, remove the `wait`/`as` loop. `testCleanupRejectsWait` and `testCleanupRejectsAs` must fail.
6. In the validator, remove the reference loop in `validateCleanupEntry`. `testCleanupRejectsUnknownReference` must fail.
7. In `failureSummary`, add the cleanup parts before the step part. `testStepFailureLeadsSummary` must fail.

Record each result.

- [ ] **Step 10: The rest of the build**

Run `./mvnw clean test`. Expected: `BUILD SUCCESS`, and the total is 486 plus the new tests (8 engine, 3 loader, 7 validator = **504**), with 0 failures, errors or skips. The scenarios module is untouched and still passes; `ScenarioResult`'s 6-argument constructor keeps `YamlScenarioTest` compiling.

- [ ] **Step 11: Commit**

```bash
git add idm-test-engine
git commit -F - <<'EOF'
feat: add an author-written cleanup block to the scenario engine

cleanup: entries run after the steps, pass or fail, in order, and are
reported as SUCCEEDED, FAILED or SKIPPED. An entry referencing a name
that was never bound is skipped; a FAILED entry fails the scenario, but
the steps' own failure always leads the summary.

ITF-19

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Migrate the corpus and fixtures, extend guard 4

**Files:**
- Modify: every file under `idm-test-scenarios/src/test/resources/scenarios/` listed below
- Modify: `idm-test-scenarios/src/test/resources/scenarios-validation-fixture/valid-user.yaml`
- Modify: `idm-test-scenarios/src/test/resources/scenarios-vocabulary-fixture/full-vocabulary.yaml`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java` (`restTargets`)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentGuardsTest.java`

**Interfaces:**
- Consumes (Task 1):
  - `Scenario.cleanup()`
  - the YAML `cleanup:` block
  - `ScenarioResult.cleanup()` with `CleanupStatus`
- The JSON report now includes a `cleanup` array for each scenario.

**Migration rules.** Apply them mechanically, and change nothing else in any file.

- **R1 — `ldap.create`:** add `as: createdDn` to the `ldap.create` step, and a cleanup entry:
  ```yaml
    - ldap.delete:
        dn: "${createdDn}"
  ```
  Apply R1 to exactly these 12 files:
  - `deprovisioning-user-disable.yaml`
  - `integration-department-transfer.yaml`
  - `integration-new-hire-onboarding.yaml`
  - `modification-attribute-removal.yaml`
  - `modification-email-change.yaml`
  - `modification-user-in-ldap.yaml`
  - `provisioning-minimal-user.yaml`
  - `provisioning-user-to-ldap.yaml`
  - `roles-assign-multiple.yaml`
  - `roles-assign-to-ldap-group.yaml`
  - `roles-assign-with-database-access.yaml`
  - `user-provisioning.yaml`
  
  Do **not** apply it to `database-provisioning.yaml`, `deprovisioning-user-delete.yaml` or `integration-employee-termination.yaml`. They delete the entry in their own steps.
- **R2 — `db.execute` with `undo:`:** remove the `undo:` block from the step, and add a cleanup entry with the same `database`, the undo's `statement` and its `params`:
  ```yaml
    - db.execute:
        database: test-db
        statement: "DELETE FROM users WHERE user_id = ?"
        params: ["${user.userId}"]
  ```
  This applies to every `db.execute` that has `undo:`. There are 10 files and 13 steps: `database-provisioning`, `deprovisioning-access-revocation` (2), `deprovisioning-grace-period`, `deprovisioning-user-disable`, `integration-department-transfer`, `integration-new-hire-onboarding` (2), `modification-email-change`, `modification-user-in-database`, `provisioning-user-to-database` and `roles-assign-with-database-access` (2).
- **Order within `cleanup:`:** list entries in **reverse** order of the steps they tear down. The last-created thing is removed first, which mirrors how the old undos unwound.
- **Placement:** `cleanup:` is a top-level key placed after `steps:`, at the same indentation as `steps:`.

- [ ] **Step 1: Migrate the 20 corpus files per R1 and R2**

After editing, check:

```bash
cd idm-test-scenarios/src/test/resources/scenarios
grep -l "^\s*undo:" *.yaml | wc -l
grep -l "^cleanup:" *.yaml | wc -l
grep -l "as: createdDn" *.yaml | wc -l
cd -
```

Expected: `0`, then `17`, then `12`. The 17 is the 12 R1 files, plus `database-provisioning`, `deprovisioning-access-revocation`, `deprovisioning-grace-period`, `modification-user-in-database` and `provisioning-user-to-database`.

- [ ] **Step 2: Migrate the two fixtures**

- In `scenarios-validation-fixture/valid-user.yaml`, apply R1 (and R2 if it has any `db.execute undo:`).
- In `scenarios-vocabulary-fixture/full-vocabulary.yaml`, apply R1, using `as: createdDn` because `created` is already taken in that file. Apply R2 to any `db.execute undo:`. Its purpose is to cover the vocabulary, so the cleanup block exercises cleanup validation.
- Leave `broken-wait.yaml` untouched. It must still fail for its `wait` reason.

- [ ] **Step 3: Guard 4 walks cleanup entries (test first)**

In `RealEnvironmentGuardsTest.java`, add:

```java
    @Test
    @DisplayName("Guard 4 also collects REST targets used in cleanup")
    void testRestTargetsCollectedFromCleanup() {
        Scenario scenario = scenario("""
                name: rest in cleanup
                steps:
                  - ldap.create:
                      dn: cn=x,ou=users
                cleanup:
                  - rest.request:
                      target: servicenow
                      method: DELETE
                      path: /records/1
                """);

        assertThat(RealEnvironmentScenarios.restTargets(List.of(scenario))).containsExactly("servicenow");
    }
```

Run it and confirm it fails: `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentGuardsTest -Dsurefire.failIfNoSpecifiedTests=false`.

In `RealEnvironmentScenarios.restTargets`, change the inner loop's source from `scenario.steps()` to both lists:

```java
            List<StepSpec> entries = new ArrayList<>(scenario.steps());
            entries.addAll(scenario.cleanup());
            for (StepSpec step : entries) {
```

Keep the body unchanged, and add `cleanup:` to the method's javadoc. Re-run: pass. Falsify by reverting to `scenario.steps()` only; the new test must fail. Then restore.

- [ ] **Step 4: Run the corpus embedded, and check the report**

Run `./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false`.

Expected: `BUILD SUCCESS`, with 20 testcases. Then:

```bash
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
grep -c '"status" : "SUCCEEDED"' idm-test-scenarios/target/scenario-results.json
```

Expected: `0` FAILED and **25** SUCCEEDED. That is 12 LDAP deletes plus 13 DB statements. If Jackson formats the key differently (for example `"status":"FAILED"`), adjust the grep pattern to match, but the counts must hold. If any scenario fails, or any entry is FAILED, stop and report the scenario and the message. **Do not edit steps.**

- [ ] **Step 5: Run the corpus against containers**

Run `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false`. This needs Docker; stop and report if Docker is not running.

Expected: `BUILD SUCCESS` and 20 testcases. Then `grep -c '"FAILED"' idm-test-scenarios/target/real-environment-results.json` should print `0`.

- [ ] **Step 6: Full suite**

Run `./mvnw clean test`. Expected: `BUILD SUCCESS` and **505** (504 + 1), with 0 failures, errors or skips.

- [ ] **Step 7: Commit**

```bash
git add idm-test-scenarios
git commit -F - <<'EOF'
test: move corpus teardown into explicit cleanup blocks

Every ldap.create the scenario does not delete itself is captured with
as: createdDn and deleted in cleanup; every db.execute undo: statement
moves into cleanup in reverse order. Guard 4 now also checks REST
targets used in cleanup.

ITF-19

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 3: Remove inferred undo; docs

**Files:**
- Modify (main):
  - `E/spi/StepOutcome.java`
  - `E/ScenarioEngine.java`
  - `E/steps/LdapCreateStep.java`
  - `E/steps/LdapUpdateStep.java`
  - `E/steps/ResourcesGrantStep.java`
  - `E/steps/DbExecuteStep.java`
  - every main source that calls `StepOutcome.success(<msg>, null, <value>)`, or whose javadoc mentions undo
- Modify (tests):
  - `ET/ScenarioEngineTest.java`
  - `ET/steps/LdapStepsTest.java`
  - `ET/steps/DbStepsTest.java`
  - `ET/steps/SoapStepsTest.java`
- Modify (docs):
  - `docs/specs/2026-09-13-declarative-scenario-engine-design.md`
  - `docs/reference/scenario-conversion-findings.md`
  - `docs/reference/specification.md` (only if it describes inferred undo)
  - `idm-test-scenarios/src/test/resources/scenarios-real/README.md`

**Interfaces:**
- `record StepOutcome(boolean success, String message, Object value)`, with factories `success(String message)`, `success(String message, Object value)` and `failure(String message)`. The two factories that took a `Runnable` are removed.

- [ ] **Step 1: `StepOutcome`**

Replace the record in `E/spi/StepOutcome.java` with:

```java
/**
 * What happened when a step ran.
 *
 * @param success whether the step did what it was asked
 * @param message human-readable detail, shown in reports and failure messages
 * @param value   what {@code as:} binds into the scenario's variables, or {@code null} when the
 *                step produces nothing worth capturing. Use plain values — String, Number,
 *                Boolean, Map or List — so a scenario can reach into them by path.
 * <p>
 * A step never schedules teardown. Cleanup is written by the author in the scenario's
 * {@code cleanup:} block.
 */
public record StepOutcome(boolean success, String message, Object value) {

    public static StepOutcome success(String message) {
        return new StepOutcome(true, message, null);
    }

    /** @param value what {@code as:} binds; may be null */
    public static StepOutcome success(String message, Object value) {
        return new StepOutcome(true, message, value);
    }

    public static StepOutcome failure(String message) {
        return new StepOutcome(false, message, null);
    }
}
```

- [ ] **Step 2: The steps**

- **Every call** `StepOutcome.success(X, null, Y)` in `E/steps/` becomes `StepOutcome.success(X, Y)`. There are 6 today. Find them with `grep -rn "StepOutcome.success([^;]*, null," idm-test-engine/src/main`.
- **`LdapCreateStep`:** replace the `return StepOutcome.success("created " + dn, () -> { ... }, dn);` statement with `return StepOutcome.success("created " + dn, dn);`.
- **`LdapUpdateStep`:**
  - Delete the read-before block, the `priorView`, `restore` and `absentBefore` computation, and the `undo(...)` method.
  - The step validates `attributes`, calls `connector.update(dn, attributes)`, returns `StepOutcome.failure("ldap.update failed for " + dn + ": " + result.getMessage())` on failure, and on success returns `StepOutcome.success("updated " + dn + " " + attributes.keySet(), dn)`.
  - Replace the class javadoc with: "Replaces attribute values on an existing LDAP entry. Registers nothing: to restore prior values, capture them with `ldap.verify ... as:` and write an `ldap.update` in the scenario's `cleanup:` block."
  - Remove any imports that become unused.
- **`ResourcesGrantStep`:**
  - Its success returns `StepOutcome.success("granted " + resource + " to " + user, requestId)`.
  - Replace the javadoc paragraph about registering an undo with: "Registers nothing. To revoke afterwards, write `resources.revoke` in the scenario's `cleanup:` block."
  - Remove unused imports, such as `RequestResourceRevokeResponse`.
- **`DbExecuteStep`:**
  - `acceptedParams()` returns `Set.of("database", "statement", "params")`.
  - Delete all `undo` handling.
  - Success returns `StepOutcome.success(message, affected)`.
  - Replace the javadoc's undo paragraph with: "Registers nothing. Rows a scenario inserts are removed by a `db.execute` in its `cleanup:` block. 0 affected rows is still success, which is what makes a cleanup `DELETE ... WHERE` safe when the steps already removed the rows."
- **Javadoc sweep:** grep for any remaining mention, `grep -rn -i "undo" idm-test-engine/src/main`. Rewrite each one, for example in `LdapDeleteStep`, `RolesGrantStep`, `RolesRevokeStep`, `ResourcesRevokeStep` and `StepType`, so it no longer describes registering or withholding an undo. For instance, in `LdapDeleteStep`: "Deletes an LDAP entry. Registers nothing, like every step; recreating a deleted entry is the author's job, with an explicit `ldap.create`." **Target: that grep returns nothing.**

- [ ] **Step 3: The engine**

In `E/ScenarioEngine.java`:
- Delete the `undos` deque, `register(...)`, `unwind(...)` and the `Deque<Runnable> undos` parameter of `runStep`.
- Delete both `register(undos, outcome)` calls, and their comment in the wait loop.
- The `finally` block becomes `cleanupResults = runCleanup(scenario.cleanup(), scope);`, with its comment changed to: "The author's cleanup always runs, whether or not the steps passed."
- Remove the `ArrayDeque`/`Deque` imports and the now-unused `log` field (and the `Logger`/`LoggerFactory` imports) only if nothing else uses them.
- Update `bind`'s javadoc, which mentions `success(msg, undo)`, to say `success(msg)`.

- [ ] **Step 4: The tests**

`ScenarioEngineTest`:
- Change `recordingAction` to register nothing: `return StepOutcome.success("ok");`, with its javadoc saying "Records that it ran."
- Delete `plainAction`, and replace its uses with `recordingAction`.
- Delete `testCleanupRunsInReverse` and `testCleanupFailureDoesNotMaskOriginal`. They tested the removed unwind; Task 1's cleanup tests replace them.
- In `testUnresolvableReferenceFailsStepAndStillCleansUp` and `testThrowingStepFailsButDoesNotAbort`, change the expected events from `("run:a:x", "undo:a")` to `("run:a:x")`. Rename the first test to `testUnresolvableReferenceFailsStep`, with display name "Should fail the step when a param reference can't resolve", and delete its comment about cleanup.
- In `valueProducing`, change `StepOutcome.success("ok", null, value)` to `StepOutcome.success("ok", value)`.

`LdapStepsTest`:
- Delete `testCreateAndUndo`, `testCreateUndoRemovesEntry`, `testUpdateRestoresPriorValues` and `testUpdateUndoRemovesNewlyAddedAttribute`.
- In `testDeleteRegistersNoUndo`, `testFailureHasNoValue`, `testUpdateCaptureIsTheDn`, `testUpdateMissingEntryFails`, `testVerifyMatches` and `testVerifyMismatch`, remove every `.undo()` assertion or undo-related line. Delete a test only if nothing else remains in it. Rename `testDeleteRegistersNoUndo` to `testDeleteReturnsTheDn` if it still asserts something.
- If `testUpdateMissingEntryFails` asserts `could not read`, change the expectation to `ldap.update failed for`.
- Add:

```java
    @Test
    @DisplayName("ldap.create registers nothing: the entry is still there after the step")
    void testCreateLeavesTheEntry() {
        LdapCreateStep create = new LdapCreateStep(connector);
        String dn = "cn=ada.stays,ou=users";

        StepOutcome outcome = create.execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.stays", "sn", "Lovelace", "givenName", "Ada")), false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(dn);
        assertThat(connector.read(dn).isSuccess()).isTrue();

        connector.delete(dn);   // tidy the shared directory; nothing else will
    }
```

`DbStepsTest`:
- Delete `testExecuteRejectsNonMapUndo`, `testExecuteRejectsUndoWithoutStatement` and `testExecuteUndo`.
- In `testExecuteWithoutUndo`, rename it to `testExecuteReturnsAffectedRows` and drop the undo assertion.
- In `testExecuteInsert`, `testExecuteBadSqlFails` and `testExecuteUnknownDatabaseFails`, remove undo lines.
- Add:

```java
    @Test
    @DisplayName("db.execute no longer accepts undo:")
    void testExecuteDoesNotAcceptUndo() {
        assertThat(new DbExecuteStep(null).acceptedParams()).doesNotContain("undo");
    }

    @Test
    @DisplayName("0 affected rows is still success, so a cleanup DELETE of absent rows passes")
    void testZeroAffectedRowsSucceeds() {
        StepOutcome outcome = new DbExecuteStep(connector).execute(Map.of(
                "database", DB,
                "statement", "DELETE FROM sap_users WHERE username = ?",
                "params", List.of("nobody")), false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(0);
        assertThat(outcome.message()).contains("0 row(s) affected");
    }
```

`SoapStepsTest`:
- Delete `testResourceGrantRegistersRevokingUndo`.
- In `testGrantRegistersNoUndo` and `testResourceRevokeRegistersNoUndo`, remove the `.undo()` assertions. Rename them to `testGrantCapturesItsResult` and `testResourceRevokeCapturesItsResult` if they still assert something; otherwise delete them.
- Remove undo lines from `testGrantCapturesAssignmentDns`, `testGrantServiceErrorFails`, `testResourceGrantServiceErrorFails` and `testRevokeRejectsEmptyAssignments`.

After this step, `grep -rn "undo" idm-test-engine/src/test` must return nothing.

- [ ] **Step 5: Run the engine module**

Run: `./mvnw test -pl idm-test-engine -am -Dsurefire.failIfNoSpecifiedTests=false`. Expected: `BUILD SUCCESS`. Record the count.

Falsify:
- Put back a `connector.delete(dn)` inside `LdapCreateStep` after the create, simulating hidden teardown. `testCreateLeavesTheEntry` must fail.
- Add `"undo"` back to `DbExecuteStep.acceptedParams()`. `testExecuteDoesNotAcceptUndo` must fail.

Revert both.

- [ ] **Step 6: Docs**

- `docs/specs/2026-09-13-declarative-scenario-engine-design.md` §7.6: at the top of the section add `> **Superseded by ITF-19** (docs/specs/2026-09-24-explicit-cleanup-design.md): steps no longer register undos; teardown is the author's cleanup: block.`
- `docs/reference/scenario-conversion-findings.md`: at the top of §1 and of §2 add `> **Superseded by ITF-19** — see docs/specs/2026-09-24-explicit-cleanup-design.md.`
- `docs/reference/specification.md`: if `grep -n -i "undo" docs/reference/specification.md` finds a description of inferred undo, rewrite that sentence to describe the `cleanup:` block. Otherwise leave the file unchanged.
- `idm-test-scenarios/src/test/resources/scenarios-real/README.md`: after "## What belongs here" section's last paragraph, add:

````markdown

## Cleaning up

The engine never tears anything down on its own. Write teardown in a `cleanup:` block, which
runs after the steps whether they passed or not. Capture what a step created with `as:`, and
reference it in cleanup, so the entry is skipped if that step never ran:

```yaml
steps:
  - rest.request:
      target: idm
      method: POST
      path: /users
      body: { ... }
      as: created
cleanup:
  - rest.request:
      target: idm
      method: DELETE
      path: "/users/${created.body.id}"
```

A cleanup entry that fails turns the scenario red. A skipped one never does. Each entry's
outcome is in the JSON report.
````

- [ ] **Step 7: Full verification**

1. Run `grep -rn -i "undo" idm-test-engine/src`. It must print nothing.
2. Run `./mvnw clean test` and the Task 0 count command. Expect `BUILD SUCCESS` with 0 failures, errors or skips. Record the total, which is 505 minus the deleted tests plus the added ones.
3. `YamlScenarioTest` should show 20 testcases, and `grep -c '"FAILED"' idm-test-scenarios/target/scenario-results.json` should print `0`. With undo gone, cleanup is now the only teardown, so this is the real proof.
4. Run `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false`. Expect 20 testcases, and `0` FAILED in `real-environment-results.json`.

- [ ] **Step 8: Commit**

```bash
git add -A idm-test-engine idm-test-scenarios docs
git commit -F - <<'EOF'
feat: remove inferred undo; teardown is only the author's cleanup block

StepOutcome loses its undo slot and the engine its unwind. ldap.create,
ldap.update and resources.grant register nothing, and db.execute no
longer accepts undo:. The corpus already cleans up explicitly.

ITF-19

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```
