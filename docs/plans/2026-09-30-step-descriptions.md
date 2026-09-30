# Step descriptions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every step and cleanup entry may carry a `description:` (text, `${…}` resolved), recorded in the results and quoted in failure summaries; the scenario's own description is recorded too.

**Architecture:** `description` is a reserved key handled by `ScenarioValidator` and `ScenarioEngine` like `as`/`wait`: validated as text, removed before the step runs, resolved with the step's scope, and stored in new `description` components on `StepResult`, `CleanupResult` and `ScenarioResult`. No step type changes.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Jackson (the existing `JsonResultWriter`).

**Spec:** `docs/specs/2026-09-30-step-descriptions-design.md` (commit 149058b). **Ticket:** ITF-35.

## Global Constraints

- **Key:** `description`, optional, on any step (including `set:`) and any cleanup entry. It is never passed to a step and never appears in `resolvedParams`; on `set:` it binds no variable.
- **Validation messages, exactly** (after the usual `<file>, step N (<type>): ` / `<file>, cleanup entry N (<type>): ` prefix):
  - not a string (map, list, number, boolean, null): `'description' must be text`
  - a whole-value `js:{…}` (or a malformed `js:{`): `'description' must be text; js:{ … } is not allowed here`
  - unknown `${name}`: the existing unknown-reference messages, unchanged
- **Run time:** a step's description is resolved with the step's scope; if resolving it throws, the step fails with message `could not resolve 'description': <error message>`. A cleanup entry records its description resolved when all its `${…}` names are bound and resolving succeeds, otherwise **as written**; a SKIPPED entry records it as written.
- **Results:** `StepResult`, `CleanupResult` and `ScenarioResult` each gain a last component `String description` (null when absent). Each keeps a constructor with its current parameter list that passes `null`.
- **Failure summary:** a failing step or FAILED cleanup entry with a description shows it after its type: `(<type>: "<description>")`; without one, today's `(<type>)` exactly.
- **The engine stays Spring-free** (`NoSpringDependencyTest`). No step type changes.
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. Green means 0 failures, errors and skips.
- **Commits** end with a blank line, `ITF-35`, and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: Validate, run and record descriptions (engine module)

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/result/StepResult.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/result/CleanupResult.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/result/ScenarioResult.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioValidator.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioValidatorTest.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/result/JsonResultWriterTest.java`

**Interfaces:**
- Produces: `StepResult.description()`, `CleanupResult.description()`, `ScenarioResult.description()` (all `String`, nullable); the old constructors still compile.

- [ ] **Step 1: Write the failing validator tests**

Add to `ScenarioValidatorTest` (uses its existing `validator`, `scenario(...)`, `ACTION` = `ldap.create`, `ASSERTION` = `ldap.verify`; import `java.util.LinkedHashMap` if missing):

```java
    private static Scenario withCleanup(List<StepSpec> steps, StepSpec... cleanup) {
        return new Scenario("Test", null,
                Map.of("user", new FixtureSpec("random-user", Map.of())),
                steps, List.of(cleanup));
    }

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    @Test
    @DisplayName("A description is accepted on an action, an assertion, set: and a cleanup entry")
    void testDescriptionAccepted() {
        Scenario described = withCleanup(
                List.of(
                        new StepSpec("set", params("description", "Pick a DN", "dn", "cn=${user.userId},ou=users")),
                        new StepSpec("ldap.create", params("description", "Create ${user.userId}", "dn", "${dn}")),
                        new StepSpec("ldap.verify", params("description", "Check it", "dn", "${dn}",
                                "expect", Map.of("sn", "x")))),
                new StepSpec("ldap.create", params("description", "Tidy ${dn}", "dn", "${dn}")));

        assertThatCode(() -> validator.validate(described, "d.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A description must be text, not a map, a number or null")
    void testDescriptionMustBeText() {
        for (Object bad : new Object[]{Map.of("a", "b"), 42, null}) {
            Scenario scenario = scenario(new StepSpec("ldap.create", params("description", bad, "dn", "x")));

            assertThatThrownBy(() -> validator.validate(scenario, "d.yaml"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("step 1 (ldap.create): 'description' must be text")
                    .hasMessageNotContaining("js:");
        }
    }

    @Test
    @DisplayName("A description may not be a js:{} expression")
    void testDescriptionRejectsExpression() {
        Scenario scenario = scenario(new StepSpec("ldap.create",
                params("description", "js:{ user.userId }", "dn", "x")));

        assertThatThrownBy(() -> validator.validate(scenario, "d.yaml"))
                .hasMessageContaining("'description' must be text; js:{ … } is not allowed here");

        Scenario inCleanup = withCleanup(List.of(new StepSpec("ldap.create", params("dn", "x"))),
                new StepSpec("ldap.create", params("description", "js:{ 1 }", "dn", "x")));
        assertThatThrownBy(() -> validator.validate(inCleanup, "d.yaml"))
                .hasMessageContaining("cleanup entry 1 (ldap.create): 'description' must be text; js:{ … } is not allowed here");
    }

    @Test
    @DisplayName("A description's ${…} names must be known, with the usual scope rules")
    void testDescriptionReferences() {
        Scenario unknown = scenario(new StepSpec("ldap.create", params("description", "for ${nobody}", "dn", "x")));
        assertThatThrownBy(() -> validator.validate(unknown, "d.yaml"))
                .hasMessageContaining("references '${nobody...}'");

        Scenario laterBinding = withCleanup(
                List.of(new StepSpec("ldap.create", params("dn", "x", "as", "created"))),
                new StepSpec("ldap.create", params("description", "undo ${created}", "dn", "x")));
        assertThatCode(() -> validator.validate(laterBinding, "d.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("On set:, description is the step's description and binds no variable")
    void testDescriptionOnSetBindsNothing() {
        Scenario scenario = scenario(
                new StepSpec("set", params("description", "names", "dn", "x")),
                new StepSpec("ldap.create", params("dn", "${description}")));

        assertThatThrownBy(() -> validator.validate(scenario, "d.yaml"))
                .hasMessageContaining("step 2 (ldap.create)")
                .hasMessageContaining("references '${description...}'");

        Scenario onlyDescription = scenario(new StepSpec("set", params("description", "nothing")));
        assertThatThrownBy(() -> validator.validate(onlyDescription, "d.yaml"))
                .hasMessageContaining("a set: step needs at least one name to bind");
    }
```

If `ScenarioValidatorTest` already has a `withCleanup` or `params` helper with the same signature, reuse it instead of adding a duplicate.

- [ ] **Step 2: Write the failing engine and writer tests**

Add to `ScenarioEngineTest` (uses `engineWith`, `scenario`, `withCleanup`, `recordingAction`, `failingAction`; import `java.util.LinkedHashMap`, `com.idm.test.engine.result.StepResult` as needed):

```java
    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    /** Records the parameters it received. */
    private StepType paramsRecorder(String name, List<Map<String, Object>> seen) {
        return new StepType() {
            public String name() { return name; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                seen.add(Map.copyOf(params));
                return StepOutcome.success("ok");
            }
        };
    }

    @Test
    @DisplayName("A step's description is resolved into its result, and never reaches the step or resolvedParams")
    void testStepDescriptionRecorded() {
        List<Map<String, Object>> seen = new ArrayList<>();
        ScenarioEngine engine = engineWith(paramsRecorder("a", seen));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", params("description", "Create ${user.userId}", "dn", "x")),
                new StepSpec("a", params("dn", "y"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(seen.get(0)).doesNotContainKey("description");
        assertThat(result.steps().get(0).description()).startsWith("Create ").doesNotContain("${");
        assertThat(result.steps().get(0).resolvedParams()).doesNotContainKey("description");
        assertThat(result.steps().get(1).description()).isNull();
    }

    @Test
    @DisplayName("A set: step records its description and binds no 'description' name")
    void testSetDescription() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("set", params("description", "Pick ${user.userId}", "dn", "z")),
                new StepSpec("a", params("dn", "${dn}"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(result.steps().get(0).description()).startsWith("Pick ").doesNotContain("${");
        assertThat(result.steps().get(0).resolvedParams()).containsOnlyKeys("dn");
    }

    @Test
    @DisplayName("A description that can't be resolved fails the step, naming 'description'")
    void testUnresolvableDescriptionFailsStep() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", params("description", "for ${nobody.here}", "dn", "x"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).message()).startsWith("could not resolve 'description': ");
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("Cleanup entries record their description: resolved when they run, as written when skipped")
    void testCleanupDescription() {
        ScenarioEngine engine = engineWith(recordingAction("a"), recordingAction("tidy"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", params("dn", "x"))),
                new StepSpec("tidy", params("description", "Remove ${user.userId}", "dn", "x")),
                new StepSpec("tidy", params("description", "Undo ${never}", "dn", "${never}"))), "t.yaml");

        assertThat(result.cleanup().get(0).description()).startsWith("Remove ").doesNotContain("${");
        assertThat(result.cleanup().get(0).resolvedParams()).doesNotContainKey("description");
        assertThat(result.cleanup().get(1).status()).isEqualTo(CleanupStatus.SKIPPED);
        assertThat(result.cleanup().get(1).description()).isEqualTo("Undo ${never}");
    }

    @Test
    @DisplayName("A description that only names an unbound value does not skip a runnable cleanup entry")
    void testCleanupDescriptionDoesNotCauseSkip() {
        ScenarioEngine engine = engineWith(recordingAction("a"), recordingAction("tidy"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", params("dn", "x"))),
                new StepSpec("tidy", params("description", "Undo ${never}", "dn", "x"))), "t.yaml");

        assertThat(result.cleanup().get(0).status()).isEqualTo(CleanupStatus.SUCCEEDED);
        assertThat(result.cleanup().get(0).description()).isEqualTo("Undo ${never}");
    }

    @Test
    @DisplayName("The scenario's own description is recorded")
    void testScenarioDescriptionRecorded() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(new Scenario("Test", "What this checks",
                Map.of(), List.of(new StepSpec("a", params("dn", "x")))), "t.yaml");

        assertThat(result.description()).isEqualTo("What this checks");
    }

    @Test
    @DisplayName("The failure summary quotes a failing step's or cleanup entry's description, and is unchanged without one")
    void testFailureSummaryQuotesDescription() {
        ScenarioEngine engine = engineWith(failingAction("a"), failingAction("tidy"));

        ScenarioResult described = engine.run(withCleanup(
                List.of(new StepSpec("a", params("description", "Grant roles", "dn", "x"))),
                new StepSpec("tidy", params("description", "Remove it", "dn", "x"))), "t.yaml");
        assertThat(described.failureSummary())
                .isEqualTo("t.yaml, step 1 (a: \"Grant roles\"): a went wrong; "
                        + "t.yaml, cleanup entry 1 (tidy: \"Remove it\"): tidy went wrong");

        ScenarioResult plain = engine.run(scenario(new StepSpec("a", params("dn", "x"))), "t.yaml");
        assertThat(plain.failureSummary()).isEqualTo("t.yaml, step 1 (a): a went wrong");
    }
```

In `JsonResultWriterTest`, add a test that writes one `ScenarioResult` built with descriptions and asserts the JSON contains `"description" : "<scenario text>"` and the step's and cleanup entry's descriptions (use the file's existing write-then-read pattern). If the existing tests construct results with the old constructors, leave them unchanged.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='ScenarioValidatorTest,ScenarioEngineTest,JsonResultWriterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation fails (`description()` doesn't exist on the result records).

- [ ] **Step 4: Add `description` to the result records**

`StepResult.java`:

```java
/**
 * What one step did.
 *
 * @param resolvedParams parameters after interpolation, so a report shows the values that
 *                       were actually used rather than the references that produced them
 * @param attempts       how many times a retried assertion ran, or null if it did not wait
 * @param waitedMillis   how long a retried assertion waited, or null if it did not wait
 * @param description    the step's own description, resolved, or null when it has none
 */
public record StepResult(
        int index,
        String type,
        Map<String, Object> resolvedParams,
        boolean success,
        long durationMillis,
        String message,
        Integer attempts,
        Long waitedMillis,
        String description) {

    /** A result for a step without a description. */
    public StepResult(int index, String type, Map<String, Object> resolvedParams, boolean success,
                      long durationMillis, String message, Integer attempts, Long waitedMillis) {
        this(index, type, resolvedParams, success, durationMillis, message, attempts, waitedMillis, null);
    }
}
```

`CleanupResult.java`: add `@param description the entry's description: resolved when it ran and resolved cleanly, otherwise as written; null when it has none`, the component `String description` last, and:

```java
    /** A result for an entry without a description. */
    public CleanupResult(int index, String type, Map<String, Object> resolvedParams, CleanupStatus status,
                         long durationMillis, String message) {
        this(index, type, resolvedParams, status, durationMillis, message, null);
    }
```

`ScenarioResult.java`: add `@param description the scenario's own top-level description, or null`, the component `String description` last, keep the compact constructor, change the existing six-argument convenience constructor to pass `null` for description, and add:

```java
    /** A result with cleanup outcomes but no description. */
    public ScenarioResult(String name, String sourceName, boolean success, long durationMillis,
                          List<StepResult> steps, List<CleanupResult> cleanup, String failureDetail) {
        this(name, sourceName, success, durationMillis, steps, cleanup, failureDetail, null);
    }
```

In `failureSummary()`, replace `.append(" (").append(failed.type()).append("): ")` with `.append(" (").append(failed.type()).append(quoted(failed.description())).append("): ")`, replace `" (" + entry.type() + "): "` with `" (" + entry.type() + quoted(entry.description()) + "): "`, and add:

```java
    /** ': "<description>"' for a failure message, or "" when there is no description. */
    private static String quoted(String description) {
        return description == null ? "" : ": \"" + description + "\"";
    }
```

- [ ] **Step 5: Validate descriptions in `ScenarioValidator`**

Add:

```java
    private static final String DESCRIPTION = "description";

    /**
     * A step's or cleanup entry's description is text for people: ${…} references are fine, a
     * js:{ … } expression is not (a description must never compute anything).
     */
    private static void requireTextDescription(Map<String, Object> params, Locate locate) {
        if (!params.containsKey(DESCRIPTION)) {
            return;
        }
        if (!(params.get(DESCRIPTION) instanceof String text)) {
            throw locate.at("'description' must be text");
        }
        boolean expression;
        try {
            expression = Interpolator.expressionBody(text) != null;
        } catch (IllegalArgumentException malformed) {
            expression = true;
        }
        if (expression) {
            throw locate.at("'description' must be text; js:{ … } is not allowed here");
        }
    }
```

In `validate`, inside the step loop, first thing after `StepSpec step = …`:

```java
            requireTextDescription(step.params(),
                    message -> new ValidationException(sourceName, index, step.type(), message));
```

In the `set:` branch, check the description's references and exclude it from the names:

```java
            if (SET_STEP.equals(step.type())) {
                Map<String, Object> names = new LinkedHashMap<>(step.params());
                Object description = names.remove(DESCRIPTION);
                if (names.isEmpty()) {
                    throw new ValidationException(sourceName, i, step.type(),
                            "a set: step needs at least one name to bind");
                }
                if (description != null) {
                    requireKnownReferences(description, known, sourceName, i, step.type());
                }
                for (Map.Entry<String, Object> entry : names.entrySet()) {
                    // (the existing loop body, unchanged)
                }
                continue;
            }
```

For other steps, add `accepted.add(DESCRIPTION);` next to `accepted.add("as");`. The existing `requireWellFormedExpressions` and `requireKnownReferences(immediate, …)` calls already cover the description's `${…}` names.

In `validateCleanupEntry`, call `requireTextDescription(entry.params(), locate);` right after the unknown-type / assertion checks, and add `accepted.add(DESCRIPTION);` after `accepted.addAll(type.requiredParams());`. The existing reference check already covers it.

- [ ] **Step 6: Run and record descriptions in `ScenarioEngine`**

Add:

```java
    private static final String DESCRIPTION = "description";

    /** The description as written, or null. */
    private static String writtenDescription(StepSpec step) {
        Object value = step.params().get(DESCRIPTION);
        return value == null ? null : String.valueOf(value);
    }

    /** The description resolved with {@code scope}, or null. Throws when a reference can't be resolved. */
    private String resolvedDescription(StepSpec step, Map<String, Object> scope) {
        Object value = step.params().get(DESCRIPTION);
        return value == null ? null : String.valueOf(Interpolator.resolve(value, scope, evaluator));
    }

    private static Map<String, Object> withoutDescription(Map<String, Object> params) {
        Map<String, Object> copy = new LinkedHashMap<>(params);
        copy.remove(DESCRIPTION);
        return copy;
    }
```

Then:
- **`run(...)`:** the `catch (RuntimeException e)` around `runStep` builds its `StepResult` with the nine-argument constructor and `writtenDescription(step)` as the description. The final `new ScenarioResult(...)` passes `scenario.description()` as the last argument.
- **`runStep(...)`:** at the start (after `started`), resolve the description, failing the step if that throws:

  ```java
        String description;
        try {
            description = resolvedDescription(step, scope);
        } catch (RuntimeException e) {
            return new StepResult(index, step.type(), Map.of(), false,
                    System.currentTimeMillis() - started,
                    "could not resolve 'description': " + e.getMessage(), null, null, writtenDescription(step));
        }
  ```

  then pass `description` to `runSet(step, index, scope, started, description)`; build `immediate` from `withoutDescription(step.params())` instead of `step.params()`; and add `description` as the last argument of every other `new StepResult(...)` in `runStep` (unknown type, `wait` on an action, no-wait, and the waited result).
- **`runSet(...)`:** take the extra `String description` parameter, iterate `withoutDescription(step.params()).entrySet()`, and pass `description` as the last `StepResult` argument.
- **`runCleanup(...)`:** the `catch` builds its `CleanupResult` with `writtenDescription(entry)` as the last argument.
- **`runCleanupEntry(...)`:** compute the unbound names from `withoutDescription(entry.params())` (so a description never causes a skip); the SKIPPED result passes `writtenDescription(entry)`; then compute

  ```java
        String description = writtenDescription(entry);
        Object raw = entry.params().get(DESCRIPTION);
        if (raw != null && scope.keySet().containsAll(Interpolator.references(raw))) {
            try {
                description = resolvedDescription(entry, scope);
            } catch (RuntimeException e) {
                // recorded as written: a description must never change what cleanup does
            }
        }
  ```

  and pass `description` as the last argument of the unknown-type FAILED result and of the final result; resolve the parameters from `withoutDescription(entry.params())`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='ScenarioValidatorTest,ScenarioEngineTest,JsonResultWriterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass.

- [ ] **Step 8: Falsify**

One at a time, make the change, run the three test classes, confirm the named test fails, then revert:
- drop `accepted.add(DESCRIPTION)` for steps → `testDescriptionAccepted` fails (unknown parameter)
- skip `requireTextDescription` for steps → `testDescriptionMustBeText` and `testDescriptionRejectsExpression` fail
- build `immediate` from `step.params()` (not `withoutDescription`) → `testStepDescriptionRecorded` fails
- in `runSet`, iterate `step.params()` → `testSetDescription` fails
- compute unbound cleanup names from `entry.params()` → `testCleanupDescriptionDoesNotCauseSkip` fails
- make `quoted` always return `""` → `testFailureSummaryQuotesDescription` fails

Record each result in the report.

- [ ] **Step 9: Run the dependent modules**

Run: `./mvnw test -pl idm-test-engine,idm-test-scenarios -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips (the old record constructors keep `ScenarioSupport` and existing tests compiling).

- [ ] **Step 10: Commit**

```bash
git add idm-test-engine
git commit -m "feat: optional description on every step and cleanup entry, recorded in the results

ITF-35

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Documentation, two described scenarios, and the full suite

**Files:**
- Modify: `docs/reference/writing-scenarios.md`
- Modify: `idm-test-scenarios/src/test/resources/scenarios/provisioning-user-to-ldap.yaml`
- Modify: `idm-test-scenarios/src/test/resources/scenarios/database-provisioning.yaml`
- Modify (only if it recommends comments for step intent): `docs/guides/scenario-authoring.md`

**Interfaces:**
- Consumes (Task 1): the `description` key on steps and cleanup entries; `description` in `*-results.json` at scenario, step and cleanup-entry level; the failure-summary format `(<type>: "<description>")`.

- [ ] **Step 1: Describe steps in two bundled scenarios**

In `provisioning-user-to-ldap.yaml`, add a `description:` as the first key of each step and of the cleanup entry:
- `ldap.create`: `description: "Create ${user.userId} in the directory"`
- first `ldap.verify`: `description: Wait until the entry exists`
- second `ldap.verify`: `description: Check the entry carries the generated attributes`
- cleanup `ldap.delete`: `description: Remove the test entry`

In `database-provisioning.yaml`, add a short, accurate `description:` (read what each step does first) to at least two steps and to one cleanup entry, with one of them using a `${…}` reference.

- [ ] **Step 2: Document descriptions in the reference**

In `docs/reference/writing-scenarios.md`:

**"2. Values":** add this block at the end of the section:

````markdown
**Describing steps.** Any step or cleanup entry may say why it exists with `description:`.
It's text, `${…}` references are resolved, and `js:{ … }` is not allowed. It appears in the
results (`*-results.json`) and in a failing step's message, so a reader who never opens the
YAML still knows what the step was for. On `set:`, `description` is the step's description,
not a name to bind.

```yaml
- roles.grant:
    description: "Grant the Finance roles to ${user.userId}"
    user: "cn=${user.userId},ou=users,o=data"
    roles: [ ... ]
```
````

**"8. What validation rejects at load":** add two rows in the table's format:
- a `description` that isn't text: `demo.yaml, step 2 (roles.grant): 'description' must be text`
- a `description` that is a `js:{…}` expression: `demo.yaml, step 2 (roles.grant): 'description' must be text; js:{ … } is not allowed here`

Also update any example in that section that lists a step's accepted parameters (for example `ldap.create accepts: [as, attributes, directory, dn]`) to the validator's actual message now that `description` is accepted: `ldap.create accepts: [as, attributes, description, directory, dn]`. Check the exact list by reading `ScenarioValidator` (a sorted `TreeSet`).

**"9. Results":** add that each scenario, step and cleanup entry records its `description` (null when none), and that a failing step's summary shows it: `demo.yaml, step 3 (roles.grant: "Grant the Finance roles to test.user.179…"): <message>`.

**"10. A worked example":** add a `description:` to at least three of the example's steps and to its cleanup entry. `ScenarioReferenceTest` loads and validates this example, so it must stay valid.

**The authoring guide:** if `docs/guides/scenario-authoring.md` tells authors to explain steps with YAML comments, add one sentence pointing to **Describing steps** in the reference. Otherwise leave it.

- [ ] **Step 3: Run the full suite**

Run: `./mvnw -q clean test`
Expected: exit 0.

Then:
```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -h -o 'failures="[0-9]*"\|errors="[0-9]*"\|skipped="[0-9]*"' */target/surefire-reports/TEST-*.xml | grep -v '"0"' | wc -l
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json idm-test-scenarios/target/multi-directory-results.json
grep -c '"description" : "Remove the test entry"' idm-test-scenarios/target/scenario-results.json
```
Expected: a test count above 732; `0` non-zero attributes; both FAILED counts `0`; the last grep prints `1` (the described cleanup entry reached the results). Include the output in the report. If `EmbeddedLdapServerTest.testLdifImport` or `LdapStepsTest` fails with a closed-connection error (known intermittent flakes), re-run once and report both runs.

- [ ] **Step 4: Commit**

```bash
git add docs/reference/writing-scenarios.md docs/guides/scenario-authoring.md idm-test-scenarios/src/test/resources/scenarios
git commit -m "docs: describe step descriptions, and use them in two bundled scenarios

ITF-35

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
