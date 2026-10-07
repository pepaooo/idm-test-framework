# List Helpers for http.verify Checks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every `js:{ … }` expression gains `containsAll`, `excludesAll` and `sameMembers`; `http.verify` adds the helpers' notes (`containsAll: missing [VPN]`) to a failing check's message; the docs gain a "Checking lists" subsection for `http.verify` and SQL recipes for `db.verify`.

**Architecture:** `ExpressionEvaluator` evaluates a fixed JavaScript prelude (the helpers plus a per-evaluation `__checkNotes` array) into each evaluation's scope, and a new `evaluateExplained` returns the value with the notes as a `com.idm.test.engine.spi.Explained` record. `DeferredExpression` gains a default `explain(bindings)`, implemented by `ScopedExpression`. `JsReferences` treats the helper names as standard globals, and `ScenarioValidator` reserves them. `HttpVerifyStep` uses `explain` and appends notes to false checks.

**Tech Stack:** Java 21, Mozilla Rhino (ES6 mode), JUnit 5, AssertJ, WireMock.

**Ticket:** ITF-41. **Spec:** `docs/specs/2026-10-07-list-check-helpers-design.md`.

## Global Constraints

- Helpers: `containsAll(list, values)`, `excludesAll(list, values)`, `sameMembers(list, values)`; each argument a single value or an array; `null`/`undefined` is an empty list.
- Equality: canonical text — a primitive is `String(value)` (`42` equals `'42'`, case-sensitive); an object is its keys sorted, each with its value's canonical text; an array is its elements' canonical text in order.
- Notes on `false`: `containsAll: missing [VPN]`, `excludesAll: found [Finance]`, `sameMembers: missing [VPN]; unexpected [Finance]` (empty part left out); at most 10 values per list, then `… N more`; values shown as canonical text; notes in call order; none for a `true` result; nothing carries over between evaluations.
- Reserved names: a fixture, `set:` or `as:` named `containsAll`, `excludesAll` or `sameMembers` is rejected at validation with a message containing `'<name>' is a built-in helper name; choose another`.
- `http.verify` false check with notes: `<label> is false: <source> — <notes joined by "; "><existing status/body context><existing xml hint>`; the notes pass through `TokenFailureText.safe(…, null)`. Without notes, or for a true check, messages are exactly as today.
- Same sandbox (every Java class denied) and instruction budget for the prelude and the expression. `evaluate(...)` behaviour unchanged.
- `db.verify`: no code change.
- The engine stays Spring-free.
- Commit trailer: a blank line, `ITF-41`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven: always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. If `./mvnw` finds no JVM: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.11-zulu`.

---

## File Structure

| File | Responsibility |
|---|---|
| Create `idm-test-engine/src/main/java/com/idm/test/engine/spi/Explained.java` | A value plus the helpers' notes |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/expr/ExpressionEvaluator.java` | Prelude, `evaluateExplained`, `HELPERS` |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/spi/DeferredExpression.java` | Default `explain` |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/ScopedExpression.java` | `explain` |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/expr/JsReferences.java` | Helper names are globals |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioValidator.java` | Reserved names |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/steps/HttpVerifyStep.java` | Notes in messages |
| Tests: `expr/ExpressionEvaluatorTest`, `expr/JsReferencesTest`, `ScenarioValidatorTest`, `steps/HttpVerifyStepsTest` | |
| Docs: `docs/reference/writing-scenarios.md`, `docs/architecture/capabilities.md` | |

---

### Task 1: The helpers in the evaluator, and their names in validation

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/spi/Explained.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/expr/ExpressionEvaluator.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/spi/DeferredExpression.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/ScopedExpression.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/expr/JsReferences.java` (`standardGlobals`)
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioValidator.java` (fixture loop in `validate`; `requireFreshBinding`)
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/expr/ExpressionEvaluatorTest.java`, `idm-test-engine/src/test/java/com/idm/test/engine/expr/JsReferencesTest.java`, `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioValidatorTest.java`

**Interfaces:**
- Produces:
  - `public record Explained(Object value, List<String> notes)` in `com.idm.test.engine.spi`
  - `public Explained ExpressionEvaluator.evaluateExplained(String expression, Map<String, Object> variables)`; `public static final Set<String> ExpressionEvaluator.HELPERS = Set.of("containsAll", "excludesAll", "sameMembers")`
  - `default Explained DeferredExpression.explain(Map<String, Object> bindings)`, overridden in `ScopedExpression`

- [ ] **Step 1: Write the failing tests**

Add to `ExpressionEvaluatorTest` (imports `com.idm.test.engine.spi.Explained`, `java.util.ArrayList`, `java.util.List`, `java.util.Map` as needed):

```java
    private Explained explain(String expression, Map<String, Object> variables) {
        return evaluator.evaluateExplained(expression, variables);
    }

    @Test
    @DisplayName("containsAll: every value present, single values or lists, compared as text")
    void testContainsAll() {
        Map<String, Object> vars = Map.of("groups", List.of("HR", "VPN", 42));
        assertThat(evaluator.evaluate("containsAll(groups, ['VPN', 'HR'])", vars)).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll(groups, 'HR')", vars)).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll(groups, '42')", vars)).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll('HR', 'HR')", Map.of())).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll(groups, 'hr')", vars)).isEqualTo(false);

        Explained missing = explain("containsAll(groups, ['HR', 'Finance'])", vars);
        assertThat(missing.value()).isEqualTo(false);
        assertThat(missing.notes()).containsExactly("containsAll: missing [Finance]");
    }

    @Test
    @DisplayName("excludesAll: none present; an absent list excludes everything")
    void testExcludesAll() {
        Map<String, Object> vars = Map.of("groups", List.of("HR", "VPN"));
        assertThat(evaluator.evaluate("excludesAll(groups, ['Finance'])", vars)).isEqualTo(true);
        assertThat(evaluator.evaluate("excludesAll(null, 'X')", Map.of())).isEqualTo(true);
        assertThat(evaluator.evaluate("excludesAll(undefined, 'X')", Map.of())).isEqualTo(true);

        Explained found = explain("excludesAll(groups, ['Finance', 'VPN'])", vars);
        assertThat(found.value()).isEqualTo(false);
        assertThat(found.notes()).containsExactly("excludesAll: found [VPN]");
    }

    @Test
    @DisplayName("sameMembers: same values in any order, duplicates ignored")
    void testSameMembers() {
        Map<String, Object> vars = Map.of("groups", List.of("HR", "VPN", "HR"));
        assertThat(evaluator.evaluate("sameMembers(groups, ['VPN', 'HR'])", vars)).isEqualTo(true);

        Explained differs = explain("sameMembers(groups, ['HR', 'Finance'])", vars);
        assertThat(differs.value()).isEqualTo(false);
        assertThat(differs.notes()).containsExactly("sameMembers: missing [Finance]; unexpected [VPN]");

        assertThat(explain("sameMembers(groups, ['HR', 'VPN', 'X'])", vars).notes())
                .containsExactly("sameMembers: missing [X]");
    }

    @Test
    @DisplayName("Objects compare by content, whatever their key order; arrays element by element")
    void testObjectsByContent() {
        Map<String, Object> vars = Map.of("roles", List.of(
                Map.of("id", 1, "role", "HR"), Map.of("id", 2, "role", "VPN")));
        assertThat(evaluator.evaluate("containsAll(roles, [{role: 'HR', id: '1'}])", vars)).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll(roles, [{role: 'HR', id: 2}])", vars)).isEqualTo(false);
        assertThat(evaluator.evaluate("containsAll([[1, 2]], [['1', '2']])", Map.of())).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll([[1, 2]], [[2, 1]])", Map.of())).isEqualTo(false);
    }

    @Test
    @DisplayName("Notes show at most ten values, then how many more")
    void testNoteCap() {
        List<Object> twelve = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            twelve.add("v" + i);
        }
        Explained capped = explain("containsAll([], wanted)", Map.of("wanted", twelve));
        assertThat(capped.notes()).containsExactly(
                "containsAll: missing [v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, … 2 more]");
    }

    @Test
    @DisplayName("Notes: in call order, none when true, none carried into the next evaluation")
    void testNotesLifecycle() {
        Explained two = explain("containsAll(['a'], 'b') || excludesAll(['a'], 'a')", Map.of());
        assertThat(two.value()).isEqualTo(false);
        assertThat(two.notes()).containsExactly("containsAll: missing [b]", "excludesAll: found [a]");

        Explained ok = explain("containsAll(['a'], 'a')", Map.of());
        assertThat(ok.value()).isEqualTo(true);
        assertThat(ok.notes()).isEmpty();

        assertThat(explain("1 + 1", Map.of()).notes()).isEmpty();
        assertThat(evaluator.evaluate("1 + 1", Map.of())).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Helpers run inside the sandbox and the instruction budget")
    void testHelpersSandboxed() {
        ExpressionEvaluator tight = new ExpressionEvaluator(5_000);
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            many.add("v" + i);
        }
        assertThatThrownBy(() -> tight.evaluate("sameMembers(many, many)", Map.of("many", many)))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessageContaining("ran too long");
        assertThatThrownBy(() -> evaluator.evaluate("java.lang.System.exit(0)", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class);
    }
```

If the existing literal test shows numbers come back as `Double` for `1 + 1`, keep `2.0`; otherwise match what `evaluate` returns today for that expression (check an existing arithmetic test in the file) — the point is that `evaluate` is unchanged.

Add to `JsReferencesTest`:

```java
    @Test
    @DisplayName("The list helpers are built-ins, not references")
    void testHelpersAreGlobals() {
        assertThat(JsReferences.freeNames("containsAll(response.json.groups, ['HR'])"))
                .containsExactly("response");
        assertThat(JsReferences.freeNames("excludesAll(a, b) && sameMembers(a, b)"))
                .containsExactlyInAnyOrder("a", "b");
    }
```

Add to `ScenarioValidatorTest`:

```java
    @Test
    @DisplayName("A check calling the list helpers validates")
    void testHelpersInDeferredCheck() {
        Scenario valid = scenario(new StepSpec("probe.verify",
                Map.of("check", "js:{ containsAll(response.groups, ['HR']) }")));

        assertThatCode(() -> deferredValidator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A fixture, set: or as: named like a helper is rejected")
    void testHelperNamesReserved() {
        Scenario fixture = new Scenario("Test", null,
                Map.of("containsAll", new FixtureSpec("random-user", Map.of())),
                List.of(new StepSpec("ldap.create", Map.of("dn", "x"))));
        assertThatThrownBy(() -> validator.validate(fixture, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'containsAll' is a built-in helper name; choose another");

        Scenario set = scenario(new StepSpec("set", Map.of("sameMembers", "x")));
        assertThatThrownBy(() -> validator.validate(set, "bad.yaml"))
                .hasMessageContaining("'sameMembers' is a built-in helper name; choose another");

        Scenario as = scenario(new StepSpec("ldap.create", Map.of("dn", "x", "as", "excludesAll")));
        assertThatThrownBy(() -> validator.validate(as, "bad.yaml"))
                .hasMessageContaining("'excludesAll' is a built-in helper name; choose another");
    }
```

(import `static org.assertj.core.api.Assertions.assertThatCode` if missing.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='ExpressionEvaluatorTest,JsReferencesTest,ScenarioValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`Explained`, `evaluateExplained` missing).

- [ ] **Step 3: Implement**

`Explained.java`:

```java
package com.idm.test.engine.spi;

import java.util.List;

/**
 * An expression's value, plus the notes its list helpers recorded when they returned false
 * ({@code containsAll: missing [VPN]}), in call order.
 */
public record Explained(Object value, List<String> notes) {

    public Explained {
        notes = List.copyOf(notes);
    }
}
```

`ExpressionEvaluator` — add (imports `com.idm.test.engine.spi.Explained`, `java.util.ArrayList`, `java.util.List`, `java.util.Set`):

```java
    /** Built-in functions every expression can call; scenario names may not reuse them. */
    public static final Set<String> HELPERS = Set.of("containsAll", "excludesAll", "sameMembers");

    /**
     * The list helpers. Values compare by canonical text: a primitive as String(v), an object as
     * its sorted keys with their values' canonical text, an array element by element. A helper
     * that returns false records one note in __checkNotes, which evaluateExplained reads back.
     */
    private static final String PRELUDE = """
            var __checkNotes = [];
            function __canon(v) {
              if (v === null || v === undefined) return String(v);
              if (Array.isArray(v)) return '[' + v.map(__canon).join(',') + ']';
              if (typeof v === 'object') {
                return '{' + Object.keys(v).sort().map(function (k) {
                  return JSON.stringify(k) + ':' + __canon(v[k]);
                }).join(',') + '}';
              }
              return String(v);
            }
            function __list(v) {
              return v === null || v === undefined ? [] : (Array.isArray(v) ? v : [v]);
            }
            function __select(from, against, wantPresent) {
              var keys = against.map(__canon), seen = {}, out = [];
              from.forEach(function (v) {
                var c = __canon(v);
                if (seen[c]) return;
                seen[c] = true;
                if ((keys.indexOf(c) >= 0) === wantPresent) out.push(c);
              });
              return out;
            }
            function __show(values) {
              var shown = values.slice(0, 10);
              if (values.length > 10) shown.push('\\u2026 ' + (values.length - 10) + ' more');
              return '[' + shown.join(', ') + ']';
            }
            function containsAll(list, values) {
              var missing = __select(__list(values), __list(list), false);
              if (missing.length === 0) return true;
              __checkNotes.push('containsAll: missing ' + __show(missing));
              return false;
            }
            function excludesAll(list, values) {
              var found = __select(__list(values), __list(list), true);
              if (found.length === 0) return true;
              __checkNotes.push('excludesAll: found ' + __show(found));
              return false;
            }
            function sameMembers(list, values) {
              var a = __list(list), b = __list(values);
              var missing = __select(b, a, false), unexpected = __select(a, b, false);
              if (missing.length === 0 && unexpected.length === 0) return true;
              var parts = [];
              if (missing.length) parts.push('missing ' + __show(missing));
              if (unexpected.length) parts.push('unexpected ' + __show(unexpected));
              __checkNotes.push('sameMembers: ' + parts.join('; '));
              return false;
            }
            """;
```

(`'\\u2026'` in the Java text block is the JavaScript escape for `…`; the note must contain the real `…` character.)

Change `evaluate` to delegate and add `evaluateExplained`:

```java
    public Object evaluate(String expression, Map<String, Object> variables) {
        return evaluateExplained(expression, variables).value();
    }

    /**
     * Like {@link #evaluate}, plus the notes the list helpers recorded while the expression ran.
     *
     * @throws ExpressionException as {@link #evaluate} does
     */
    public Explained evaluateExplained(String expression, Map<String, Object> variables) {
        Context current = Context.getCurrentContext();
        if (current != null && current.getFactory() != contextFactory) {
            // (move the existing comment from evaluate here unchanged)
            throw new ExpressionException(
                    "cannot evaluate js:{ " + expression + " }: another Rhino Context is already "
                            + "open on this thread, so the instruction budget would not apply to it",
                    null);
        }
        return doEvaluate(expression, variables);
    }
```

In `doEvaluate` (now returning `Explained`), after `cx.setClassShutter(className -> false);`:

```java
            // The helpers are defined after the scenario's names, so they always mean the
            // built-ins; the validator refuses scenario names that would collide with them.
            cx.evaluateString(scope, PRELUDE, "helpers", 1, null);

            Object value = JsValues.fromJs(cx.evaluateString(scope, expression, "expression", 1, null));
            return new Explained(value, notesIn(scope));
```

and add:

```java
    private static List<String> notesIn(Scriptable scope) {
        Object notes = JsValues.fromJs(ScriptableObject.getProperty(scope, "__checkNotes"));
        List<String> result = new ArrayList<>();
        if (notes instanceof List<?> list) {
            list.forEach(note -> result.add(String.valueOf(note)));
        }
        return result;
    }
```

`DeferredExpression` — add (import `java.util.List`):

```java
    /**
     * Like {@link #evaluate}, plus the notes the list helpers recorded ({@code containsAll:
     * missing [VPN]}), so a step can say why a check was false. Implementations that cannot
     * collect notes return none.
     */
    default Explained explain(Map<String, Object> bindings) {
        return new Explained(evaluate(bindings), List.of());
    }
```

`ScopedExpression` — add:

```java
    @Override
    public Explained explain(Map<String, Object> bindings) {
        Map<String, Object> variables = new LinkedHashMap<>(scope);
        variables.putAll(bindings);
        return evaluator.evaluateExplained(body, variables);
    }
```

and make `evaluate` return `explain(bindings).value()`.

`JsReferences.standardGlobals()` — after `names.add("arguments");` add `names.addAll(ExpressionEvaluator.HELPERS);`.

`ScenarioValidator`:
- in the fixture loop in `validate`, before the generator check:
  ```java
            if (ExpressionEvaluator.HELPERS.contains(fixture.getKey())) {
                throw new ValidationException(sourceName, "fixture '" + fixture.getKey()
                        + "' is a built-in helper name; choose another");
            }
  ```
- at the start of `requireFreshBinding`:
  ```java
        if (ExpressionEvaluator.HELPERS.contains(name)) {
            throw new ValidationException(sourceName, index, stepType,
                    keyword + " '" + name + "' is a built-in helper name; choose another");
        }
  ```

(`ExpressionEvaluator` is in `com.idm.test.engine.expr`; import it if `ScenarioValidator` doesn't already.)

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures/errors/skips. If `testHelpersSandboxed`'s budget of 5 000 does not trip for 2 000 elements, raise the element count (not the budget) until it does, and say so.

- [ ] **Step 5: Commit**

```bash
git add idm-test-engine
git commit -m "feat: containsAll, excludesAll and sameMembers in every expression, with notes on false

ITF-41

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Notes in http.verify failures, docs

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/HttpVerifyStep.java` (the check loop in `judge`/`execute`)
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/HttpVerifyStepsTest.java`
- Docs: `docs/reference/writing-scenarios.md` (§`http.verify`, §`db.verify`), `docs/architecture/capabilities.md` (the `YAML http.request` row)

**Interfaces:**
- Consumes: `DeferredExpression.explain(Map<String, Object>)` → `Explained(Object value, List<String> notes)` (Task 1).

- [ ] **Step 1: Write the failing tests**

Add to `HttpVerifyStepsTest` (it has `run(params)`, `params(...)`, `stubJson(status, body)` and `stubBody(contentType, body)`):

```java
    @Test
    @DisplayName("A containsAll check passes, and a failing one names the missing values")
    void testContainsAllNote() {
        stubJson(200, "{\"groups\":[\"HR\",\"Finance\"]}");

        assertThat(run(params("check", "js:{ containsAll(response.json.groups, ['HR']) }")).success()).isTrue();

        StepResult step = run(params("check", "js:{ containsAll(response.json.groups, ['HR', 'VPN']) }"));
        assertThat(step.success()).isFalse();
        assertThat(step.message()).contains(
                "check 1 of 1 is false: js:{ containsAll(response.json.groups, ['HR', 'VPN']) } — containsAll: missing [VPN] (status 200, body: ");
    }

    @Test
    @DisplayName("A failing sameMembers check names missing and unexpected values")
    void testSameMembersNote() {
        stubJson(200, "{\"groups\":[\"HR\",\"Finance\"]}");

        StepResult step = run(params("check", "js:{ sameMembers(response.json.groups, ['HR', 'VPN']) }"));

        assertThat(step.message()).contains("— sameMembers: missing [VPN]; unexpected [Finance]");
    }

    @Test
    @DisplayName("Helpers read XML elements whether they occur once or several times")
    void testHelpersOnXml() {
        stubBody("application/xml", "<user><role>Admin</role><role>HR</role></user>");
        assertThat(run(params("check", "js:{ containsAll(response.xml.user.role, ['HR', 'Admin']) }")).success()).isTrue();

        stubBody("application/xml", "<user><role>HR</role></user>");
        assertThat(run(params("check", "js:{ containsAll(response.xml.user.role, 'HR') }")).success()).isTrue();
    }

    @Test
    @DisplayName("A false check without helpers keeps today's message")
    void testPlainFalseUnchanged() {
        stubJson(200, "{\"department\":\"HR\"}");

        StepResult step = run(params("check", "js:{ response.json.department === 'Finance' }"));

        assertThat(step.message()).contains("is false: js:{ response.json.department === 'Finance' } (status 200")
                .doesNotContain(" — ");
    }
```

If `stubBody`'s path differs from `/users/1`, use the same path `params(...)` targets (read the helper first).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=HttpVerifyStepsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the note assertions fail (no ` — ` part yet); the passing-helper and plain tests may already pass.

- [ ] **Step 3: Implement**

In `HttpVerifyStep`, inside the loop over checks (import `com.idm.test.engine.spi.Explained`), replace

```java
            Object value;
            try {
                value = check.evaluate(Map.of("response", bound.response()));
            } catch (RuntimeException e) {
```

with

```java
            Explained explained;
            try {
                explained = check.explain(Map.of("response", bound.response()));
            } catch (RuntimeException e) {
```

keep the catch body unchanged, then after the try/catch add `Object value = explained.value();`, and change the false branch to:

```java
            if (!passed) {
                String notes = explained.notes().isEmpty()
                        ? ""
                        : " — " + TokenFailureText.safe(String.join("; ", explained.notes()), null);
                return StepOutcome.failure(label + " is false: " + check.source() + notes + context(response) + hint);
            }
```

- [ ] **Step 4: Docs**

`docs/reference/writing-scenarios.md`, §`http.verify`, after the **Reading XML** example and before **Captures**, add:

~~~markdown
**Checking lists.** Three functions make list checks short, and say what was wrong when they fail:

| Function | True when |
|---|---|
| `containsAll(list, values)` | every one of `values` is in `list` (others allowed) |
| `excludesAll(list, values)` | none of `values` is in `list` |
| `sameMembers(list, values)` | both hold the same values, in any order |

- Either argument may be one value or a list; a missing list (`null`, `undefined`) is empty, so
  `excludesAll(response.json.groups, 'Finance')` passes when there are no groups.
- Values compare as text, so `42` matches `'42'`; case matters (lower-case both sides with
  `.toLowerCase()` to ignore it). Objects compare by content, whatever their key order.
- A failing check says why: `check 1 of 1 is false: js:{ … } — containsAll: missing [VPN]`.
- The names work in every `js:{ … }`, and are reserved: no fixture, `set:` or `as:` may use them.

```yaml
- http.verify:
    target: idm
    method: GET
    path: "/users/${user.userId}/groups"
    check:
      - "js:{ containsAll(response.json.groups, ['HR', 'VPN']) }"
      - "js:{ excludesAll(response.json.groups, 'Finance') }"
    wait: { timeout: 2m, poll: 5s }

- http.verify:
    target: idm
    method: GET
    path: "/users/${user.userId}.xml"
    check: "js:{ sameMembers(response.xml.user.role, ['Admin', 'HR']) }"
```
~~~

(Insert only the content between the `~~~` lines.)

§`db.verify`, after its examples, add:

~~~markdown
**Checking several rows.** Put the condition in the query: the database compares with its own
rules (collation, case, types) and returns only what the check needs.

| To check that the user… | Query | `expect` |
|---|---|---|
| has READ and WRITE (others allowed) | `SELECT COUNT(*) AS n FROM user_permission WHERE username = ? AND permission IN ('READ', 'WRITE')` | `row: { n: 2 }` |
| does not have ADMIN | `SELECT 1 FROM user_permission WHERE username = ? AND permission = 'ADMIN'` | `rowCount: 0` |
| has exactly READ and WRITE | `SELECT COUNT(*) AS total, SUM(CASE WHEN permission IN ('READ', 'WRITE') THEN 1 ELSE 0 END) AS wanted FROM user_permission WHERE username = ?` | `row: { total: 2, wanted: 2 }` |
| has three groups | `SELECT COUNT(*) AS n FROM user_group WHERE username = ?` | `row: { n: 3 }` |
| has a mail address like `jdoe@…` | `SELECT 1 FROM users WHERE username = ? AND mail LIKE 'jdoe@%'` | `rowCount: 1` |

`row:` matches column names ignoring case, so `n` matches a database that returns `N`.
~~~

`docs/architecture/capabilities.md`, the `YAML http.request` row's last column: append `; checks can use containsAll, excludesAll and sameMembers, which say what was missing when false (ITF-41)`.

- [ ] **Step 5: Run the full suite and the corpus**

Delete stale `idm-test-scenarios/target/surefire-reports/*ContainerScenarios.*` files if present. Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures/errors/skips. Then

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
grep -c '"status" : "FAILED"' idm-test-scenarios/target/multi-directory-results.json
```

Expected: a total, `0`, `0`. Known flakes: `EmbeddedLdapServerTest.testLdifImport`, `LdapStepsTest` closed connection, `SoapStepsTest` port clash — re-run once and report both runs.

- [ ] **Step 6: Commit**

```bash
git add idm-test-engine docs/reference/writing-scenarios.md docs/architecture/capabilities.md
git commit -m "feat: http.verify says which values a list check missed; document list checks and database recipes

ITF-41

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
