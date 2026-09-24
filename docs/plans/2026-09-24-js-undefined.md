# `undefined` in `js:{}` Results Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `js:{}` result containing `undefined` anywhere becomes an error that names its path, instead of silently becoming `null`.

**Architecture:** There is one change, in `JsValues.fromJs`, which is the only place a `js:{}` result is converted back to Java. It gains a path parameter (`result`, `result.dn`, `result.items[2]`) and throws `JsConversionException` on `Undefined` or `Scriptable.NOT_FOUND`. Java/JS `null` still returns `null`. Nothing else changes: `ExpressionEvaluator` already wraps the error as `ExpressionException` with the expression text, and the engine already turns that into a failed step, a FAILED cleanup entry, or a failed `set:`.

**Tech Stack:** Java 21, Rhino 1.8.0, JUnit 5, AssertJ.

**Spec:** `docs/specs/2026-09-24-js-undefined-design.md`. **Ticket:** ITF-20.

## Global Constraints

- **The exact message:** `<path> is undefined — a property that does not exist reads as undefined in JavaScript. Check the name, or write null if no value is intended.` The top-level path is `result`, an object property appends `.<key>`, and an array element appends `[<index>]`.
- **Explicit JS `null`** (and a Java `null`) still converts to `null`, at any depth.
- **No change** to `ExpressionEvaluator`, `Interpolator`, `ScenarioEngine`, `set:`, `as:` or `${...}` handling.
- **Every existing conversion error message stays byte-identical.** Those are the depth, BigInt, cycle, array-size and non-plain-object messages.
- Every refusal is falsification-tested.
- `-pl` is always paired with `-am`. Single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`.
- If you see "Unresolved compilation problems", that is stale IDE output. Run `./mvnw clean test` and do not change code.
- Branch: `feat/js-undefined` off `main`.
- Commit messages end with:
  ```
  ITF-20

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

Paths use `E` = `idm-test-engine/src/main/java/com/idm/test/engine` and `ET` = `idm-test-engine/src/test/java/com/idm/test/engine`.

---

### Task 0: Branch and baseline

- [ ] **Step 1:** `git checkout main && git checkout -b feat/js-undefined`
- [ ] **Step 2:** Run `./mvnw clean test`, then:

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
```

Expected: `BUILD SUCCESS` and **504**, with no non-zero `failures=`, `errors=` or `skipped=`.

---

### Task 1: Refuse `undefined` in `js:{}` results

**Files:**
- Modify: `E/expr/JsValues.java`
- Modify: `docs/guides/scenario-authoring.md`
- Test:
  - `ET/expr/JsValuesTest.java`
  - `ET/expr/ExpressionEvaluatorTest.java`
  - `ET/ScenarioEngineTest.java`

**Interfaces:**
- Consumes (existing):
  - `ExpressionEvaluator.evaluate(String, Map<String,Object>)`, which throws `ExpressionEvaluator.ExpressionException` whose message is `"could not evaluate js:{ <expr> }: <cause message>"`
  - `JsValues.JsConversionException` (package-private)
  - from `ScenarioEngineTest` (present since ITF-19): `recordingAction(String)`, which records `"run:<name>:<dn>"` and returns success; `withCleanup(List<StepSpec>, StepSpec...)`; `scenario(StepSpec...)`, with a `user` fixture from `random-user`; and the import of `CleanupStatus`
- Produces: no new API.

- [ ] **Step 1: Replace the test that pins the old behaviour, and add the conversion tests**

In `ET/expr/JsValuesTest.java`, replace the whole `testUndefinedBecomesNull` method, annotations included, with:

```java
    @Test
    @DisplayName("Should turn JS null and Java null into Java null")
    void testNullBecomesNull() {
        inContext((cx, scope) -> {
            Object jsNull = cx.evaluateString(scope, "null", "t", 1, null);

            assertThat(JsValues.fromJs(jsNull)).isNull();
            assertThat(JsValues.fromJs(null)).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("Should refuse JS undefined instead of turning it into null")
    void testUndefinedIsRefused() {
        inContext((cx, scope) -> {
            Object undefined = cx.evaluateString(scope, "undefined", "t", 1, null);

            assertThatThrownBy(() -> JsValues.fromJs(undefined))
                    .isInstanceOf(JsValues.JsConversionException.class)
                    .hasMessageStartingWith("result is undefined")
                    .hasMessageContaining("write null if no value is intended");
            return null;
        });
    }
```

Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` if it is missing.

In `ET/expr/ExpressionEvaluatorTest.java`, add these tests. `scope()` gives `user` with `firstName`, `lastName` and `startDate`, so `user.firstname` is a realistic typo:

```java
    @Test
    @DisplayName("Should refuse a result that is undefined, naming the expression")
    void testUndefinedResultRefused() {
        assertThatThrownBy(() -> evaluator.evaluate("user.firstname", scope()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessageContaining("js:{ user.firstname }")
                .hasMessageContaining("result is undefined");
    }

    @Test
    @DisplayName("Should refuse undefined nested in an object, naming the property")
    void testUndefinedPropertyRefused() {
        assertThatThrownBy(() -> evaluator.evaluate("({ dn: user.firstname })", scope()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessageContaining("result.dn is undefined");
    }

    @Test
    @DisplayName("Should refuse undefined nested in an array, naming the index")
    void testUndefinedElementRefused() {
        assertThatThrownBy(() -> evaluator.evaluate("[user.firstName, user.firstname]", scope()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessageContaining("result[1] is undefined");
    }

    @Test
    @DisplayName("Should refuse an array hole, which JavaScript reads as undefined")
    void testArrayHoleRefused() {
        assertThatThrownBy(() -> evaluator.evaluate("[1, , 3]", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessageContaining("result[1] is undefined");
    }

    @Test
    @DisplayName("Should still allow an explicit null, at the top level and nested")
    void testExplicitNullAllowed() {
        assertThat(evaluator.evaluate("null", Map.of())).isNull();

        Object result = evaluator.evaluate("({ a: null, b: [null] })", Map.of());
        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(map.containsKey("a")).isTrue();
        assertThat(map.get("a")).isNull();
        assertThat((List<?>) map.get("b")).containsExactly((Object) null);
    }

    @Test
    @DisplayName("Should convert a result with no undefined exactly as before")
    void testOrdinaryResultUnchanged() {
        Object result = evaluator.evaluate("({ name: user.firstName, tags: ['a', 'b'] })", scope());

        assertThat(result).isEqualTo(Map.of("name", "ada", "tags", List.of("a", "b")));
    }
```

Add imports if missing: `static org.assertj.core.api.Assertions.assertThatThrownBy`, `java.util.List`, `java.util.Map`.

In `ET/ScenarioEngineTest.java`, add:

```java
    @Test
    @DisplayName("A js: typo in a cleanup parameter is FAILED, not a silent success")
    void testJsTypoInCleanupFails() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1"))),
                new StepSpec("a", Map.of("dn", "js:{ user.userid }"))), "t.yaml");

        // Before this fix the cleanup action ran with dn=null and reported SUCCEEDED.
        assertThat(events).containsExactly("run:a:s1");
        assertThat(result.cleanup()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(CleanupStatus.FAILED);
            assertThat(c.message()).contains("userid").contains("undefined");
        });
        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("A js: typo in a step parameter fails that step")
    void testJsTypoInStepFails() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(
                scenario(new StepSpec("a", Map.of("dn", "js:{ user.userid }"))), "t.yaml");

        assertThat(events).isEmpty();
        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("userid").contains("undefined");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='JsValuesTest,ExpressionEvaluatorTest,ScenarioEngineTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: failures in `testUndefinedIsRefused`, the four undefined/hole tests, and the two engine typo tests. `testNullBecomesNull`, `testExplicitNullAllowed` and `testOrdinaryResultUnchanged` pass even now: they are the controls.

- [ ] **Step 3: Implement**

In `E/expr/JsValues.java`, replace the public entry point and the recursive method's opening lines.

Replace:

```java
    static Object fromJs(Object value) {
        return fromJs(value, Collections.newSetFromMap(new IdentityHashMap<>()), 0, new NodeBudget());
    }

    private static Object fromJs(Object value, Set<Object> ancestors, int depth, NodeBudget budget) {
        if (value == null || value instanceof Undefined || value == Scriptable.NOT_FOUND) {
            return null;
        }
```

with:

```java
    static Object fromJs(Object value) {
        return fromJs(value, "result", Collections.newSetFromMap(new IdentityHashMap<>()), 0, new NodeBudget());
    }

    /**
     * @param path where {@code value} sits in the result, e.g. {@code result.items[2]}, so an
     *             error can point at the exact spot
     */
    private static Object fromJs(
            Object value, String path, Set<Object> ancestors, int depth, NodeBudget budget) {
        // JavaScript reads a missing property as undefined rather than failing, so a typo like
        // user.userid would otherwise become a silent null — and a null parameter can make a
        // step, or a cleanup DELETE, quietly do nothing. An array hole reads as NOT_FOUND, which
        // JavaScript also treats as undefined. Explicit null is a deliberate value and passes.
        if (value instanceof Undefined || value == Scriptable.NOT_FOUND) {
            throw new JsConversionException(path + " is undefined — a property that does not exist "
                    + "reads as undefined in JavaScript. Check the name, or write null if no value "
                    + "is intended.");
        }
        if (value == null) {
            return null;
        }
```

In the same method's array branch, replace:

```java
                    list.add(fromJs(array.get(i, array), ancestors, depth + 1, budget));
```

with:

```java
                    list.add(fromJs(array.get(i, array), path + "[" + i + "]", ancestors, depth + 1, budget));
```

In the object branch, replace:

```java
                    map.put(key, fromJs(object.get(key, object), ancestors, depth + 1, budget));
```

with:

```java
                    map.put(key, fromJs(object.get(key, object), path + "." + key, ancestors, depth + 1, budget));
```

Update the javadoc of the public `fromJs(Object)`: in its `@throws JsConversionException` clause, add "contains `undefined` anywhere (including an array hole)" to the list of reasons. Change nothing else in the file.

- [ ] **Step 4: Run the tests to verify they pass**

Run the Step 2 command. Expected: all pass, `BUILD SUCCESS`.

- [ ] **Step 5: Falsify**

Temporarily change the new check back to the old behaviour. Replace the `throw new JsConversionException(path + " is undefined ...` block's body with `return null;`. Re-run the Step 2 command. These must fail:
- `testUndefinedIsRefused`
- `testUndefinedResultRefused`
- `testUndefinedPropertyRefused`
- `testUndefinedElementRefused`
- `testArrayHoleRefused`
- `testJsTypoInCleanupFails`
- `testJsTypoInStepFails`

Record the list, then revert and re-run to confirm everything passes.

- [ ] **Step 6: Document**

In `docs/guides/scenario-authoring.md`, directly after the sentence `Expression syntax is checked up front, but unknown JS variables can still fail at runtime.`, add this sentence to the same paragraph:

```markdown
A result that contains `undefined` anywhere — for example a misspelled property such as `user.userid` — is an error, not a silent `null`; the message names where it sits (`result.dn`, `result[1]`). Write `null` explicitly when no value is intended.
```

- [ ] **Step 7: Full suite**

Run `./mvnw clean test` and the Task 0 count command. Expected:
- `BUILD SUCCESS` and **513** (504 + 1 net in `JsValuesTest` + 6 in `ExpressionEvaluatorTest` + 2 in `ScenarioEngineTest`), with no non-zero failures, errors or skips.
- `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`. The corpus and fixtures, which use `js:` and `set:`, relied on nothing becoming null.
- If any corpus scenario or fixture now fails with `is undefined`, **stop and report it**. That is a real finding about the scenario; do not change the scenario or weaken the check.

- [ ] **Step 8: Commit**

```bash
git add idm-test-engine docs/guides/scenario-authoring.md
git commit -F - <<'EOF'
fix: refuse undefined in js:{} results instead of turning it into null

A misspelled property such as user.userid reads as undefined in
JavaScript and used to become a silent null, so a cleanup DELETE could
match nothing and report SUCCEEDED. undefined anywhere in a result is now
an error naming its path; explicit null is still allowed.

ITF-20

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```
