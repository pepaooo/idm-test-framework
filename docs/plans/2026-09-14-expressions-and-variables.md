# Expressions and Variables Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a scenario compute values — a sandboxed `js:{ }` expression, a `set:` step for derived variables, and `as:` to capture a step's result — without ever letting a scenario perform an action outside a step.

**Architecture:** A `ExpressionEvaluator` wraps Rhino with every Java class denied and an instruction budget. Scenario variables are converted into **native** JS objects rather than wrapped Java objects, which is what makes a total sandbox possible. `Interpolator` gains one branch: a value that is entirely `js:{ ... }` is evaluated and returned with its native type. The engine binds `as:` and executes `set:`; the validator tracks which names exist at each step so a forward reference fails at load.

**Tech Stack:** Java 21, Mozilla Rhino 1.8.0, JUnit 5, AssertJ.

**Tracking:** ITF-7, subtask 2 of 4 under ITF-4.
**Source spec:** `docs/specs/2026-09-13-declarative-scenario-engine-design.md` §5.4, §5.5, §7.4, §7.5

## Global Constraints

- Java 21. Use records over Lombok; Lombok is not a dependency and must not be added.
- Run Maven as `./mvnw ...` — the wrapper is executable as of commit `527724f`.
- Build sibling modules with `-am`: `-pl <module>` alone fails to resolve `com.idm.test:*` dependencies. When combining `-am` with `-Dtest=`, add `-Dsurefire.failIfNoSpecifiedTests=false`.
- `idm-test-engine/src/main` must NOT import `org.springframework`. `NoSpringDependencyTest` enforces this and genuinely fails when violated.
- **The governing rule of this ticket (spec §5.4): expressions compute values, they never perform actions.** No connector access, no filesystem, no network, no Java class access. Steps remain the only things that act, which is what preserves the guarantee traded for amending the specification's "no orchestration layers" principle.
- **No helper object.** Expressions are plain sandboxed ES6. Standard JavaScript already covers date arithmetic, capitalisation, padding, conditionals and JSON. A `dates`/`strings` API is deliberately deferred until converting the 27 existing scenarios (ITF-5) shows which helpers earn their place.
- The engine renders nothing; it returns a result object.
- Every validation message names the source file, and the step when one is implicated.
- Commit messages reference `ITF-7` on its own line before the trailers. The trailer must be exactly `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` — use that exact text, never the name of whichever model is running.

## Verified before writing this plan

These were checked empirically against Rhino 1.8.0, not assumed. Do not redesign around them.

- `cx.setClassShutter(name -> false)` blocks `java.lang.System.getenv`, `new java.io.File(...)` and `Packages.java.lang.Runtime` — all three throw.
- `ContextFactory.observeInstructionCount` plus `setInstructionObserverThreshold` aborts `while(true){}`.
- **Wrapping scenario variables as a Java `Map` is incompatible with that shutter.** `Context.javaToJS` on a `Map` throws `Access to Java class "java.util.ImmutableCollections$Map1" is prohibited`. Variables must be converted into native `Scriptable` objects and arrays instead. This is why Task 1 has a recursive converter.
- Standard ES6 covers the helper cases: `new Date(s); d.setDate(d.getDate()+30); d.toISOString().slice(0,10)` yields `2026-02-14`; `padStart`, `charAt().toUpperCase()`, ternaries and `JSON.stringify` all work.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `idm-test-engine/src/main/java/com/idm/test/engine/expr/ExpressionEvaluator.java` | Evaluates one JS expression against a variable map, sandboxed and budgeted. Knows nothing about scenarios |
| `idm-test-engine/src/main/java/com/idm/test/engine/expr/JsValues.java` | Converts Java values to native JS and back. The reason the sandbox can deny every Java class |
| `idm-test-engine/src/test/java/com/idm/test/engine/expr/ExpressionEvaluatorTest.java` | Sandbox escape attempts, budget, syntax errors, scope access |
| `idm-test-engine/src/test/java/com/idm/test/engine/expr/JsValuesTest.java` | Round-tripping maps, lists, primitives and null |

**Modified:**

| File | Change |
|---|---|
| `pom.xml` | `rhino.version` property and a dependencyManagement entry |
| `idm-test-engine/pom.xml` | Depend on `org.mozilla:rhino` |
| `.../engine/Interpolator.java` | One branch: a value that is entirely `js:{ ... }` is evaluated; brace-matched, not regex |
| `.../engine/spi/StepOutcome.java` | Gains a `value` component so `as:` has something to bind |
| `.../engine/steps/LdapCreateStep.java` | Returns the created DN as its value |
| `.../engine/steps/LdapVerifyStep.java` | Returns the entry's attributes as its value, read once on success |
| `.../engine/ScenarioEngine.java` | Strips and honours `as:`; executes the reserved `set:` step |
| `.../engine/ScenarioValidator.java` | Accepts `as` on any step; tracks names bound in order; rejects forward references and a bad `set:` |

**Deliberately omitted:** any helper object; `js:` embedded inside a larger string; expression support in fixture definitions. All three are argued in the Global Constraints or in Task 2.

---

## Task 1: The sandboxed expression evaluator

Builds the evaluator in isolation, with no knowledge of scenarios. The security properties are the deliverable, so most of the tests are escape attempts.

**Files:**
- Modify: `pom.xml`
- Modify: `idm-test-engine/pom.xml`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/expr/JsValues.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/expr/ExpressionEvaluator.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/expr/JsValuesTest.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/expr/ExpressionEvaluatorTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces, relied on by Task 2:
  - `new ExpressionEvaluator()` — default instruction budget of 100,000
  - `new ExpressionEvaluator(int instructionBudget)`
  - `Object evaluate(String expression, Map<String, Object> variables)` — returns a Java value (String, Double, Boolean, Map, List or null); throws `ExpressionException` on syntax error, sandbox violation or budget exhaustion
  - `class ExpressionException extends RuntimeException` (nested in `ExpressionEvaluator`)

- [ ] **Step 1: Add the Rhino dependency**

In the parent `pom.xml`, add to `<properties>` immediately after the `h2.version` line:

```xml
        <rhino.version>1.8.0</rhino.version>
```

And to `<dependencyManagement><dependencies>`, immediately after the `commons-lang3` entry:

```xml
            <!-- Mozilla Rhino - sandboxed expression evaluation in scenarios -->
            <dependency>
                <groupId>org.mozilla</groupId>
                <artifactId>rhino</artifactId>
                <version>${rhino.version}</version>
            </dependency>
```

In `idm-test-engine/pom.xml`, add immediately after the `jackson-dataformat-yaml` dependency:

```xml
        <dependency>
            <groupId>org.mozilla</groupId>
            <artifactId>rhino</artifactId>
        </dependency>
```

- [ ] **Step 2: Write the failing JsValues test**

Create `idm-test-engine/src/test/java/com/idm/test/engine/expr/JsValuesTest.java`:

```java
package com.idm.test.engine.expr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsValues")
class JsValuesTest {

    /**
     * Runs a function inside a Rhino context, the way ExpressionEvaluator does.
     */
    private static <T> T inContext(BiFunction<Context, Scriptable, T> body) {
        Context cx = new ContextFactory().enterContext();
        try {
            return body.apply(cx, cx.initStandardObjects());
        } finally {
            Context.exit();
        }
    }

    @Test
    @DisplayName("Should pass primitives through unchanged")
    void testPrimitives() {
        inContext((cx, scope) -> {
            assertThat(JsValues.toJs(cx, scope, "ada")).isEqualTo("ada");
            assertThat(JsValues.toJs(cx, scope, 42)).isEqualTo(42);
            assertThat(JsValues.toJs(cx, scope, true)).isEqualTo(true);
            assertThat(JsValues.toJs(cx, scope, null)).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("Should convert a map into a native JS object, not a wrapped Java object")
    void testMapBecomesNativeObject() {
        inContext((cx, scope) -> {
            Object converted = JsValues.toJs(cx, scope, Map.of("lastName", "lovelace"));

            assertThat(converted).isInstanceOf(Scriptable.class);
            // A wrapped Java object would be NativeJavaObject, which the sandbox forbids.
            assertThat(converted.getClass().getName()).doesNotContain("NativeJavaObject");
            assertThat(((Scriptable) converted).get("lastName", (Scriptable) converted))
                    .isEqualTo("lovelace");
            return null;
        });
    }

    @Test
    @DisplayName("Should convert nested maps and lists recursively")
    void testNestedStructures() {
        inContext((cx, scope) -> {
            Object converted = JsValues.toJs(cx, scope,
                    Map.of("roles", List.of("admin", "user")));
            Scriptable outer = (Scriptable) converted;
            Scriptable roles = (Scriptable) outer.get("roles", outer);

            assertThat(roles.get(0, roles)).isEqualTo("admin");
            assertThat(roles.get(1, roles)).isEqualTo("user");
            return null;
        });
    }

    @Test
    @DisplayName("Should convert a JS result back into plain Java values")
    void testResultsComeBackAsJava() {
        inContext((cx, scope) -> {
            Object jsObject = cx.evaluateString(scope, "({a: 1, b: 'two'})", "t", 1, null);

            Object java = JsValues.fromJs(jsObject);

            assertThat(java).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) java;
            assertThat(map).containsEntry("b", "two");
            assertThat(map.get("a")).isEqualTo(1.0);   // JS numbers are doubles
            return null;
        });
    }

    @Test
    @DisplayName("Should convert a JS array back into a List")
    void testArrayComesBackAsList() {
        inContext((cx, scope) -> {
            Object jsArray = cx.evaluateString(scope, "['a','b']", "t", 1, null);

            assertThat(JsValues.fromJs(jsArray)).isEqualTo(List.of("a", "b"));
            return null;
        });
    }

    @Test
    @DisplayName("Should turn JS undefined and null into Java null")
    void testUndefinedBecomesNull() {
        inContext((cx, scope) -> {
            Object undefined = cx.evaluateString(scope, "undefined", "t", 1, null);

            assertThat(JsValues.fromJs(undefined)).isNull();
            assertThat(JsValues.fromJs(null)).isNull();
            return null;
        });
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
./mvnw -pl idm-test-engine -am test -Dtest=JsValuesTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure — `cannot find symbol: class JsValues`.

- [ ] **Step 4: Implement JsValues**

Create `idm-test-engine/src/main/java/com/idm/test/engine/expr/JsValues.java`:

```java
package com.idm.test.engine.expr;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between Java values and native JavaScript values.
 * <p>
 * This class is what makes a total sandbox possible. The obvious approach — handing Rhino a
 * Java {@code Map} via {@code Context.javaToJS} — fails once every Java class is denied:
 * wrapping the map itself requires access to {@code java.util.ImmutableCollections$Map1}.
 * Converting to native {@link Scriptable} objects instead means no Java class is ever exposed,
 * so the shutter can refuse all of them.
 */
final class JsValues {

    private JsValues() {
    }

    /**
     * Converts a Java value into a native JS value. Maps become JS objects, lists become JS
     * arrays, and everything else is passed through.
     */
    static Object toJs(Context cx, Scriptable scope, Object value) {
        if (value instanceof Map<?, ?> map) {
            Scriptable object = cx.newObject(scope);
            map.forEach((key, entry) ->
                    ScriptableObject.putProperty(object, String.valueOf(key), toJs(cx, scope, entry)));
            return object;
        }
        if (value instanceof List<?> list) {
            Object[] items = new Object[list.size()];
            for (int i = 0; i < list.size(); i++) {
                items[i] = toJs(cx, scope, list.get(i));
            }
            return cx.newArray(scope, items);
        }
        return value;
    }

    /**
     * Converts a JS result back into plain Java values, so nothing Rhino-specific escapes into
     * the rest of the engine. JS numbers arrive as {@link Double}; that is JavaScript's only
     * numeric type and is not hidden here.
     */
    static Object fromJs(Object value) {
        if (value == null || value instanceof Undefined || value == Scriptable.NOT_FOUND) {
            return null;
        }
        if (value instanceof NativeArray array) {
            List<Object> list = new ArrayList<>((int) array.getLength());
            for (int i = 0; i < array.getLength(); i++) {
                list.add(fromJs(array.get(i, array)));
            }
            return list;
        }
        if (value instanceof Scriptable object) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Object id : object.getIds()) {
                String key = String.valueOf(id);
                map.put(key, fromJs(object.get(key, object)));
            }
            return map;
        }
        return value;
    }
}
```

- [ ] **Step 5: Run it to verify it passes**

```bash
./mvnw -pl idm-test-engine -am test -Dtest=JsValuesTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 6: Write the failing evaluator test**

Create `idm-test-engine/src/test/java/com/idm/test/engine/expr/ExpressionEvaluatorTest.java`:

```java
package com.idm.test.engine.expr;

import com.idm.test.engine.expr.ExpressionEvaluator.ExpressionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExpressionEvaluator")
class ExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    private static Map<String, Object> scope() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("firstName", "ada");
        user.put("lastName", "lovelace");
        user.put("startDate", "2026-01-15");
        return Map.of("user", user);
    }

    @Test
    @DisplayName("Should evaluate a literal expression")
    void testLiteral() {
        assertThat(evaluator.evaluate("'ada'.toUpperCase()", Map.of())).isEqualTo("ADA");
    }

    @Test
    @DisplayName("Should read scenario variables")
    void testReadsVariables() {
        assertThat(evaluator.evaluate("user.lastName.toUpperCase()", scope()))
                .isEqualTo("LOVELACE");
    }

    @Test
    @DisplayName("Should do the date arithmetic that replaced a helper API")
    void testDateArithmetic() {
        String expression =
                "var d = new Date(user.startDate); d.setDate(d.getDate() + 30); "
                        + "d.toISOString().slice(0, 10)";

        assertThat(evaluator.evaluate(expression, scope())).isEqualTo("2026-02-14");
    }

    @Test
    @DisplayName("Should return native types, not only strings")
    void testReturnsNativeTypes() {
        assertThat(evaluator.evaluate("1 + 1", Map.of())).isEqualTo(2.0);
        assertThat(evaluator.evaluate("true", Map.of())).isEqualTo(true);
        assertThat(evaluator.evaluate("['a','b']", Map.of())).isEqualTo(List.of("a", "b"));
        assertThat(evaluator.evaluate("null", Map.of())).isNull();
    }

    @Test
    @DisplayName("Should deny access to java.lang.System")
    void testBlocksSystem() {
        assertThatThrownBy(() -> evaluator.evaluate("java.lang.System.getenv('HOME')", Map.of()))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    @DisplayName("Should deny access to the filesystem")
    void testBlocksFileAccess() {
        assertThatThrownBy(() ->
                evaluator.evaluate("new java.io.File('/etc/passwd').exists()", Map.of()))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    @DisplayName("Should deny the Packages back door")
    void testBlocksPackages() {
        assertThatThrownBy(() ->
                evaluator.evaluate("Packages.java.lang.Runtime.getRuntime()", Map.of()))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    @DisplayName("Should abort an expression that never terminates")
    void testAbortsRunawayExpression() {
        ExpressionEvaluator budgeted = new ExpressionEvaluator(10_000);

        assertThatThrownBy(() -> budgeted.evaluate("var i = 0; while (true) { i++; } i", Map.of()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("Should report a syntax error with the expression text")
    void testSyntaxError() {
        assertThatThrownBy(() -> evaluator.evaluate("user.lastName.(", scope()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("user.lastName.(");
    }

    @Test
    @DisplayName("Should report an unknown variable with the expression text")
    void testUnknownVariable() {
        assertThatThrownBy(() -> evaluator.evaluate("absent.field", scope()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("Should not leak variables between evaluations")
    void testNoStateLeak() {
        evaluator.evaluate("var leaked = 'first'", Map.of());

        assertThatThrownBy(() -> evaluator.evaluate("leaked", Map.of()))
                .isInstanceOf(ExpressionException.class);
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

```bash
./mvnw -pl idm-test-engine -am test -Dtest=ExpressionEvaluatorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure — `cannot find symbol: class ExpressionEvaluator`.

- [ ] **Step 8: Implement ExpressionEvaluator**

Create `idm-test-engine/src/main/java/com/idm/test/engine/expr/ExpressionEvaluator.java`:

```java
package com.idm.test.engine.expr;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.Map;

/**
 * Evaluates one JavaScript expression against a set of scenario variables.
 * <p>
 * The governing rule is that <strong>expressions compute values, they never perform
 * actions</strong>. Every Java class is denied, so an expression cannot reach a connector, the
 * filesystem, the network or the clock's host process; and an instruction budget stops one that
 * never terminates. Steps remain the only things in a scenario that do anything, which is what
 * keeps a scenario's step list its complete execution order.
 * <p>
 * Expressions are plain ES6. There is deliberately no helper object: standard JavaScript
 * already covers date arithmetic, capitalisation, padding, conditionals and JSON.
 */
public final class ExpressionEvaluator {

    /** Enough for any reasonable expression; a runaway loop exceeds it in milliseconds. */
    private static final int DEFAULT_INSTRUCTION_BUDGET = 100_000;

    private final ContextFactory contextFactory;

    public ExpressionEvaluator() {
        this(DEFAULT_INSTRUCTION_BUDGET);
    }

    public ExpressionEvaluator(int instructionBudget) {
        this.contextFactory = new BudgetedContextFactory(instructionBudget);
    }

    /**
     * @param expression a JavaScript expression; its final value is the result
     * @param variables  scenario variables, made available by name
     * @return a plain Java value — String, Double, Boolean, Map, List or null
     * @throws ExpressionException on a syntax error, a sandbox violation, an unknown variable,
     *                             or an expression that exceeds its instruction budget
     */
    public Object evaluate(String expression, Map<String, Object> variables) {
        Context cx = contextFactory.enterContext();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);

            // A fresh scope per evaluation: nothing one expression defines is visible to the next.
            Scriptable scope = cx.initStandardObjects();
            variables.forEach((name, value) ->
                    ScriptableObject.putProperty(scope, name, JsValues.toJs(cx, scope, value)));

            // Deny every Java class. Set after the scope is populated, though JsValues never
            // wraps a Java object anyway, which is the point of it.
            cx.setClassShutter(className -> false);

            return JsValues.fromJs(cx.evaluateString(scope, expression, "expression", 1, null));

        } catch (InstructionBudgetExceeded e) {
            throw new ExpressionException(
                    "expression ran too long and was stopped: js:{ " + expression + " }", e);
        } catch (ExpressionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ExpressionException(
                    "could not evaluate js:{ " + expression + " }: " + e.getMessage(), e);
        } finally {
            Context.exit();
        }
    }

    /**
     * Stops an expression that exceeds its instruction budget.
     */
    private static final class BudgetedContextFactory extends ContextFactory {

        private final int budget;

        private BudgetedContextFactory(int budget) {
            this.budget = budget;
        }

        @Override
        protected void onContextCreated(Context cx) {
            cx.setInstructionObserverThreshold(budget);
            super.onContextCreated(cx);
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            throw new InstructionBudgetExceeded();
        }
    }

    /** Internal signal; callers see an ExpressionException. */
    private static final class InstructionBudgetExceeded extends RuntimeException {
        private InstructionBudgetExceeded() {
            super("instruction budget exceeded");
        }
    }

    /**
     * An expression could not be evaluated. The message always includes the expression text,
     * because the reader is often not a programmer.
     */
    public static class ExpressionException extends RuntimeException {
        public ExpressionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

- [ ] **Step 9: Run it to verify it passes**

```bash
./mvnw -pl idm-test-engine -am test -Dtest=ExpressionEvaluatorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`

If `testAbortsRunawayExpression` hangs instead of failing, the instruction observer is not firing — check that `onContextCreated` is being called, which requires entering the context through `contextFactory.enterContext()` and not the static `Context.enter()`.

- [ ] **Step 10: Confirm the no-Spring rule still holds**

```bash
./mvnw -pl idm-test-engine -am test -Dtest=NoSpringDependencyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 11: Commit**

```bash
git add pom.xml idm-test-engine
git commit -m "feat: add a sandboxed expression evaluator

Expressions compute values and never perform actions: every Java class is
denied, so an expression cannot reach a connector, the filesystem or the
network, and an instruction budget stops one that never terminates.

Scenario variables are converted to native JS rather than wrapped as Java
objects. That is not a style choice — wrapping a Java Map requires access
to java.util.ImmutableCollections\$Map1, which the shutter denies, so the
obvious implementation cannot be sandboxed at all.

No helper object: standard ES6 already covers date arithmetic,
capitalisation, padding, conditionals and JSON.

ITF-7

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: `js:{ }` in the value pipeline

Wires the evaluator into interpolation. The subtlety is parsing: expressions legitimately contain `}`.

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/Interpolator.java`
- Modify: `idm-test-engine/src/test/java/com/idm/test/engine/InterpolatorTest.java`

**Interfaces:**
- Consumes: `ExpressionEvaluator.evaluate(String, Map<String,Object>)` and `ExpressionEvaluator.ExpressionException` from Task 1.
- Produces, relied on by Tasks 3 and 4:
  - `Interpolator.resolve(Object value, Map<String,Object> scope, ExpressionEvaluator evaluator)` — the new three-argument form. Keep the existing two-argument `resolve(Object, Map)` delegating to it with a default evaluator, so existing callers compile unchanged.
  - `Interpolator.references(Object)` is unchanged and still returns only `${...}` roots.

- [ ] **Step 1: Write the failing tests**

Add these imports to `idm-test-engine/src/test/java/com/idm/test/engine/InterpolatorTest.java`:

```java
import com.idm.test.engine.expr.ExpressionEvaluator;
import java.util.LinkedHashMap;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

And add these tests to that class:

```java
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    private static Map<String, Object> jsScope() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("firstName", "ada");
        user.put("lastName", "lovelace");
        return Map.of("user", user);
    }

    @Test
    @DisplayName("Should evaluate a value that is entirely an expression")
    void testEvaluatesWholeValueExpression() {
        Object result = Interpolator.resolve(
                "js:{ user.lastName.toUpperCase() }", jsScope(), evaluator);

        assertThat(result).isEqualTo("LOVELACE");
    }

    @Test
    @DisplayName("Should return the expression's native type rather than a string")
    void testExpressionKeepsItsType() {
        assertThat(Interpolator.resolve("js:{ 1 + 1 }", jsScope(), evaluator)).isEqualTo(2.0);
        assertThat(Interpolator.resolve("js:{ true }", jsScope(), evaluator)).isEqualTo(true);
    }

    @Test
    @DisplayName("Should handle braces inside an expression")
    void testNestedBraces() {
        Object result = Interpolator.resolve(
                "js:{ JSON.stringify({a: 1}) }", jsScope(), evaluator);

        assertThat(result).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("Should evaluate expressions nested in maps and lists")
    void testExpressionsInsideStructures() {
        Object result = Interpolator.resolve(
                Map.of("attributes", Map.of("sn", "js:{ user.lastName.toUpperCase() }")),
                jsScope(), evaluator);

        assertThat(result).isEqualTo(Map.of("attributes", Map.of("sn", "LOVELACE")));
    }

    @Test
    @DisplayName("Should resolve ${...} before the expression runs, so both can be combined")
    void testDollarRefsStillWorkAlongsideExpressions() {
        Map<String, Object> params = Map.of(
                "dn", "cn=${user.firstName},ou=users",
                "upper", "js:{ user.firstName.toUpperCase() }");

        assertThat(Interpolator.resolve(params, jsScope(), evaluator)).isEqualTo(
                Map.of("dn", "cn=ada,ou=users", "upper", "ADA"));
    }

    @Test
    @DisplayName("Should reject an expression embedded in a larger string")
    void testRejectsEmbeddedExpression() {
        assertThatThrownBy(() ->
                Interpolator.resolve("cn=js:{ user.lastName }", jsScope(), evaluator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole value");
    }

    @Test
    @DisplayName("Should reject an expression whose braces never close")
    void testRejectsUnterminatedExpression() {
        assertThatThrownBy(() ->
                Interpolator.resolve("js:{ user.lastName", jsScope(), evaluator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not closed");
    }

    @Test
    @DisplayName("Should leave a value alone when js: is not the marker")
    void testPlainValueWithJsPrefixIsUntouched() {
        assertThat(Interpolator.resolve("javascript is fine here", jsScope(), evaluator))
                .isEqualTo("javascript is fine here");
    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./mvnw -pl idm-test-engine -am test -Dtest=InterpolatorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure — no three-argument `resolve`.

- [ ] **Step 3: Implement the expression branch**

In `Interpolator.java`, add these imports:

```java
import com.idm.test.engine.expr.ExpressionEvaluator;
```

Replace the existing `resolve(Object, Map)` method with these three:

```java
    /**
     * Replaces every reference in {@code value}, walking maps and lists, using a default
     * expression evaluator.
     */
    public static Object resolve(Object value, Map<String, Object> scope) {
        return resolve(value, scope, DEFAULT_EVALUATOR);
    }

    /**
     * Replaces every reference in {@code value}, walking maps and lists.
     *
     * @throws IllegalArgumentException naming the reference that could not be resolved, or
     *                                  describing a malformed {@code js:{ }} expression
     */
    public static Object resolve(Object value, Map<String, Object> scope,
                                 ExpressionEvaluator evaluator) {
        if (value instanceof String text) {
            String expression = expressionIn(text);
            if (expression != null) {
                // Deliberately NOT interpolated first. The evaluator already receives the
                // variables, so ${...} inside an expression would be redundant — and actively
                // harmful, because ES6 template literals use the same ${...} syntax and our
                // interpolator would mangle them before JavaScript ever saw them.
                return evaluator.evaluate(expression, scope);
            }
            return resolveString(text, scope);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(k.toString(), resolve(v, scope, evaluator)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            list.forEach(element -> resolved.add(resolve(element, scope, evaluator)));
            return resolved;
        }
        return value;
    }

    /**
     * Extracts the body of a {@code js:{ ... }} value.
     * <p>
     * Brace matching rather than a regex, because expressions legitimately contain braces —
     * {@code js:{ JSON.stringify({a: 1}) }} is valid, and a pattern terminating at the first
     * closing brace would silently truncate it.
     *
     * @return the expression body, or {@code null} when this value is not an expression
     * @throws IllegalArgumentException when the value only looks like one
     */
    private static String expressionIn(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith(EXPRESSION_PREFIX)) {
            return null;
        }

        int depth = 0;
        for (int i = EXPRESSION_PREFIX.length() - 1; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    if (i != trimmed.length() - 1) {
                        throw new IllegalArgumentException(
                                "an expression must be the whole value, but '" + text
                                        + "' has text after the closing brace");
                    }
                    return trimmed.substring(EXPRESSION_PREFIX.length(), i);
                }
            }
        }

        throw new IllegalArgumentException(
                "the expression in '" + text + "' is not closed — a '}' is missing");
    }
```

Add these two constants beside the existing `REFERENCE` pattern:

```java
    private static final String EXPRESSION_PREFIX = "js:{";
    private static final ExpressionEvaluator DEFAULT_EVALUATOR = new ExpressionEvaluator();
```

Note the embedded case: `"cn=js:{ ... }"` does not start with `js:{`, so `expressionIn` returns null and it falls through to `${...}` handling, leaving the literal text. That silently ignores what the author meant. Guard it explicitly by adding this check at the top of `resolveString`:

```java
        if (text.contains(EXPRESSION_PREFIX) && !text.trim().startsWith(EXPRESSION_PREFIX)) {
            throw new IllegalArgumentException(
                    "an expression must be the whole value, but found js:{ inside '" + text + "'");
        }
```

- [ ] **Step 4: Run it to verify it passes**

```bash
./mvnw -pl idm-test-engine -am test -Dtest=InterpolatorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Run the whole module for regressions**

```bash
./mvnw -pl idm-test-engine -am test
```

Expected: `BUILD SUCCESS`. The engine module was at 61 tests before this plan; it should now be 61 + 6 (JsValues) + 11 (evaluator) + 8 (interpolator) = 86.

- [ ] **Step 6: Commit**

```bash
git add idm-test-engine
git commit -m "feat: evaluate js:{ } expressions during interpolation

An expression must be the whole value, never embedded in a larger string.
That keeps parsing tractable and lets an expression return a real number,
boolean or object instead of being coerced to text.

Parsing matches braces rather than using a regex, because expressions
legitimately contain them: js:{ JSON.stringify({a: 1}) } is valid, and a
pattern ending at the first closing brace would truncate it silently.

ITF-7

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: `as:` capture

Gives steps a value to return and the engine a way to bind it.

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/spi/StepOutcome.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapCreateStep.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioValidator.java`
- Modify: `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java`
- Modify: `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1 and 2.
- Produces, relied on by Task 4 and by ITF-8 and ITF-9:
  - `record StepOutcome(boolean success, String message, Runnable undo, Object value)` — `value` is what `as:` binds, `null` when the step produces nothing. The existing factories `success(String)`, `success(String, Runnable)` and `failure(String)` keep their signatures and pass `null` for it. One new factory carries a value: `success(String message, Runnable undo, Object value)`, with `undo` passed as `null` when there is nothing to reverse. There is deliberately no two-argument `success(String, Object)` — see Step 3.
  - `as` is accepted on every step, the way `wait` is on retryable ones. Steps never see it; the engine strips it.
  - `ldap.create`'s value is the DN it created. `ldap.verify`'s value is the entry's attributes as a `Map<String,Object>`, read once after verification succeeds.

- [ ] **Step 1: Write the failing engine test**

Add to `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java`:

```java
    /** Returns a fixed value so `as:` has something to bind. */
    private StepType valueProducing(String name, Object value) {
        return new StepType() {
            public String name() { return name; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params) {
                return StepOutcome.success("ok", null, value);
            }
        };
    }

    @Test
    @DisplayName("Should bind a step's value under its as: name and let later steps read it")
    void testAsCaptureIsVisibleToLaterSteps() {
        List<String> seen = new ArrayList<>();
        StepType reader = new StepType() {
            public String name() { return "reader"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params) {
                seen.add(String.valueOf(params.get("dn")));
                return StepOutcome.success("ok");
            }
        };
        ScenarioEngine engine = engineWith(valueProducing("maker", "cn=made,ou=users"), reader);

        ScenarioResult result = engine.run(scenario(
                new StepSpec("maker", Map.of("dn", "x", "as", "created")),
                new StepSpec("reader", Map.of("dn", "${created}"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(seen).containsExactly("cn=made,ou=users");
    }

    @Test
    @DisplayName("Should not pass the as: key through to the step")
    void testAsIsStrippedBeforeTheStepRuns() {
        List<Map<String, Object>> received = new ArrayList<>();
        StepType recorder = new StepType() {
            public String name() { return "recorder"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params) {
                received.add(params);
                return StepOutcome.success("ok");
            }
        };
        engineWith(recorder).run(scenario(
                new StepSpec("recorder", Map.of("dn", "x", "as", "bound"))), "t.yaml");

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).doesNotContainKey("as");
    }

    @Test
    @DisplayName("Should bind a captured map so its fields are reachable by path")
    void testCapturedMapFieldsAreReachable() {
        List<String> seen = new ArrayList<>();
        StepType reader = new StepType() {
            public String name() { return "reader"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params) {
                seen.add(String.valueOf(params.get("dn")));
                return StepOutcome.success("ok");
            }
        };
        ScenarioEngine engine = engineWith(
                valueProducing("maker", Map.of("id", "42")), reader);

        engine.run(scenario(
                new StepSpec("maker", Map.of("dn", "x", "as", "created")),
                new StepSpec("reader", Map.of("dn", "${created.id}"))), "t.yaml");

        assertThat(seen).containsExactly("42");
    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./mvnw -pl idm-test-engine -am test -Dtest=ScenarioEngineTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure — no `StepOutcome.success(String, Object)`.

- [ ] **Step 3: Add the value component to StepOutcome**

Replace the record declaration and factories in `spi/StepOutcome.java`:

```java
/**
 * What happened when a step ran.
 *
 * @param success whether the step did what it was asked
 * @param message human-readable detail, shown in reports and failure messages
 * @param undo    how to reverse this step, or {@code null} when there is nothing to reverse.
 *                The engine collects these and unwinds them in reverse order.
 * @param value   what {@code as:} binds into the scenario's variables, or {@code null} when the
 *                step produces nothing worth capturing. Use plain values — String, Number,
 *                Boolean, Map or List — so a scenario can reach into them by path.
 */
public record StepOutcome(boolean success, String message, Runnable undo, Object value) {

    public static StepOutcome success(String message) {
        return new StepOutcome(true, message, null, null);
    }

    public static StepOutcome success(String message, Runnable undo) {
        return new StepOutcome(true, message, undo, null);
    }

    /**
     * @param undo  may be null when there is nothing to reverse
     * @param value what {@code as:} binds; may be null
     */
    public static StepOutcome success(String message, Runnable undo, Object value) {
        return new StepOutcome(true, message, undo, value);
    }

    public static StepOutcome failure(String message) {
        return new StepOutcome(false, message, null, null);
    }
}
```

**Deliberately no `success(String, Object)` overload.** It would sit beside `success(String, Runnable)` with an identical shape but completely different meaning, and `Runnable` is an `Object` — so which one a call selects would depend on the static type of the argument. That is a trap for whoever writes the next ten step types. A step with a value but no undo passes `null` explicitly: `StepOutcome.success("verified " + dn, null, attributes)`.

- [ ] **Step 4: Have the LDAP steps return values**

In `steps/LdapCreateStep.java`, replace the success return with:

```java
        return StepOutcome.success("created " + dn, () -> {
            OperationResult<Void> deleted = connector.delete(dn);
            if (!deleted.isSuccess()) {
                throw new IllegalStateException(
                        "cleanup failed to delete " + dn + ": " + deleted.getMessage());
            }
        }, dn);
```

In `steps/LdapVerifyStep.java`, replace the success return with a read that supplies the captured value:

```java
        // Read once, only after verification passes, so a polling assertion does not double
        // its read load on every failed attempt.
        OperationResult<LdapUser> entry = connector.read(dn);
        Map<String, Object> attributes = entry.isSuccess() && entry.getData().isPresent()
                ? attributesOf(entry.getData().get())
                : Map.of();

        return StepOutcome.success("verified " + dn, null, attributes);
```

And add this helper to `LdapVerifyStep`, plus the imports `com.idm.test.connector.ldap.model.LdapUser` and `java.util.LinkedHashMap`:

```java
    /**
     * The attribute view a scenario sees through {@code as:}. Null-valued attributes are
     * omitted rather than bound as null, so a path reference to one fails loudly.
     */
    private static Map<String, Object> attributesOf(LdapUser user) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, "dn", user.getDn());
        putIfPresent(attributes, "cn", user.getCn());
        putIfPresent(attributes, "sn", user.getSn());
        putIfPresent(attributes, "givenName", user.getGivenName());
        putIfPresent(attributes, "mail", user.getMail());
        putIfPresent(attributes, "uid", user.getUid());
        putIfPresent(attributes, "telephoneNumber", user.getTelephoneNumber());
        putIfPresent(attributes, "title", user.getTitle());
        putIfPresent(attributes, "ou", user.getOu());
        putIfPresent(attributes, "description", user.getDescription());
        attributes.putAll(user.getCustomAttributes());
        return attributes;
    }

    private static void putIfPresent(Map<String, Object> attributes, String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }
```

- [ ] **Step 5: Bind `as:` in the engine**

In `ScenarioEngine.runStep`, immediately after the line that removes `wait` from the resolved map, add:

```java
        Object asName = resolved.remove("as");
```

Then wherever the method builds its `StepResult` after a successful outcome — both the waited and non-waited paths — bind the value first. Add this helper to the class and call it with the outcome before each `return`:

```java
    /**
     * Binds a successful step's value under its {@code as:} name, so later steps can read it.
     */
    private static void bind(Map<String, Object> scope, Object asName, StepOutcome outcome) {
        if (asName != null && outcome != null && outcome.success()) {
            scope.put(String.valueOf(asName), outcome.value());
        }
    }
```

- [ ] **Step 6: Accept `as` on every step in the validator**

In `ScenarioValidator`, where the effective accepted set is computed, add `as` alongside the existing `wait` handling:

```java
            Set<String> accepted = new LinkedHashSet<>(type.acceptedParams());
            accepted.addAll(type.requiredParams());
            accepted.add("as");                 // any step's result may be captured
            if (type.retryable()) {
                accepted.add("wait");
            }
```

- [ ] **Step 7: Add the step-level tests**

Add to `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java`:

```java
    @Test
    @DisplayName("ldap.create should return the DN it created, for as: to capture")
    void testCreateReturnsDn() {
        String dn = "cn=ada.value,ou=users";

        StepOutcome outcome = new LdapCreateStep(connector).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.value", "sn", "Lovelace")));

        assertThat(outcome.value()).isEqualTo(dn);
    }

    @Test
    @DisplayName("ldap.verify should return the entry's attributes, for as: to capture")
    void testVerifyReturnsAttributes() {
        String dn = "cn=grace.value,ou=users";
        new LdapCreateStep(connector).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "grace.value", "sn", "Hopper", "givenName", "Grace")));

        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Grace")));

        assertThat(outcome.value()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) outcome.value();
        assertThat(attributes).containsEntry("sn", "Hopper").containsEntry("givenName", "Grace");
    }

    @Test
    @DisplayName("A failed step should carry no captured value")
    void testFailureHasNoValue() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", "cn=absent.value,ou=users",
                "expect", Map.of("givenName", "Ada")));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.value()).isNull();
    }
```

- [ ] **Step 8: Run the module**

```bash
./mvnw -pl idm-test-engine -am test
```

Expected: `BUILD SUCCESS`, 92 tests — 86 after Task 2, plus 3 engine and 3 step tests.

- [ ] **Step 9: Commit**

```bash
git add idm-test-engine
git commit -m "feat: capture a step's result with as:

StepOutcome gains a value component, so a step has something for as: to
bind. ldap.create returns the DN it created; ldap.verify returns the
entry's attributes, read once after verification passes so a polling
assertion does not double its read load on every failed attempt.

as: is accepted on every step and stripped before the step runs, the same
way wait: is.

ITF-7

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: The `set:` step and forward-reference validation

Adds derived variables, and makes the validator reject a reference to a name that does not exist *yet*.

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioValidator.java`
- Modify: `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java`
- Modify: `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioValidatorTest.java`
- Modify: `idm-test-scenarios/src/test/resources/scenarios/user-provisioning.yaml`

**Interfaces:**
- Consumes: everything from Tasks 1 to 3.
- Produces:
  - `set` is a **reserved step type name**. It is not in the registry; the engine and the validator both handle it directly. A registry containing a step named `set` is rejected at construction.
  - A `set:` step's parameters are name-to-value pairs; each value is interpolated and may be a `js:{ }` expression. Entries bind in declaration order, so a later entry may read an earlier one.

- [ ] **Step 1: Write the failing validator tests**

Add to `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioValidatorTest.java`:

```java
    @Test
    @DisplayName("Should accept a reference to a name bound by an earlier as:")
    void testAcceptsReferenceToEarlierCapture() {
        Scenario valid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=x,ou=users", "as", "created")),
                new StepSpec("ldap.verify", Map.of("dn", "${created}", "expect", Map.of())));

        assertThatCode(() -> validator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject a reference to a name bound only by a later step")
    void testRejectsForwardReference() {
        Scenario invalid = scenario(
                new StepSpec("ldap.verify", Map.of("dn", "${created}", "expect", Map.of())),
                new StepSpec("ldap.create", Map.of("dn", "cn=x,ou=users", "as", "created")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("created")
                .hasMessageContaining("bad.yaml");
    }

    @Test
    @DisplayName("Should accept a set: step and the names it binds")
    void testAcceptsSetStep() {
        Scenario valid = scenario(
                new StepSpec("set", Map.of("upper", "js:{ user.userId.toUpperCase() }")),
                new StepSpec("ldap.create", Map.of("dn", "cn=${upper},ou=users")));

        assertThatCode(() -> validator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject a set: step with no entries")
    void testRejectsEmptySet() {
        Scenario invalid = scenario(new StepSpec("set", Map.of()));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one");
    }
```

- [ ] **Step 2: Write the failing engine tests**

Add to `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java`:

```java
    @Test
    @DisplayName("Should bind set: entries and make them readable by later steps")
    void testSetBindsVariables() {
        List<String> seen = new ArrayList<>();
        StepType reader = new StepType() {
            public String name() { return "reader"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params) {
                seen.add(String.valueOf(params.get("dn")));
                return StepOutcome.success("ok");
            }
        };
        ScenarioEngine engine = engineWith(reader);

        ScenarioResult result = engine.run(scenario(
                new StepSpec("set", Map.of("greeting", "js:{ 'hello ' + user.firstName }")),
                new StepSpec("reader", Map.of("dn", "${greeting}"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).startsWith("hello ");
    }

    @Test
    @DisplayName("Should let a set: entry read one bound earlier in the same step")
    void testSetEntriesBindInOrder() {
        List<String> seen = new ArrayList<>();
        StepType reader = new StepType() {
            public String name() { return "reader"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params) {
                seen.add(String.valueOf(params.get("dn")));
                return StepOutcome.success("ok");
            }
        };
        Map<String, Object> ordered = new java.util.LinkedHashMap<>();
        ordered.put("first", "ada");
        ordered.put("second", "js:{ first.toUpperCase() }");

        engineWith(reader).run(scenario(
                new StepSpec("set", ordered),
                new StepSpec("reader", Map.of("dn", "${second}"))), "t.yaml");

        assertThat(seen).containsExactly("ADA");
    }

    @Test
    @DisplayName("Should record a set: step in the results like any other step")
    void testSetAppearsInResults() {
        ScenarioResult result = engineWith().run(scenario(
                new StepSpec("set", Map.of("a", "1"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).type()).isEqualTo("set");
    }

    @Test
    @DisplayName("Should fail the scenario when a set: expression cannot be evaluated")
    void testSetExpressionFailureIsReported() {
        ScenarioResult result = engineWith().run(scenario(
                new StepSpec("set", Map.of("bad", "js:{ absent.field }"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("absent");
    }
```

- [ ] **Step 3: Run both to verify they fail**

```bash
./mvnw -pl idm-test-engine -am test -Dtest='ScenarioEngineTest,ScenarioValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: failures reporting that `set` is an unknown step type.

- [ ] **Step 4: Handle `set` in the validator**

In `ScenarioValidator`, add the constant and reject a registry that shadows it. Add to the constructor:

```java
        if (registry.containsKey(SET_STEP)) {
            throw new IllegalArgumentException(
                    "'" + SET_STEP + "' is a reserved step type and cannot be registered");
        }
```

with the constant:

```java
    /** Reserved: handled by the engine itself rather than by a registered step type. */
    private static final String SET_STEP = "set";
```

Then inside the step loop, before the `registry.get(...)` lookup, handle `set` and track bound names. Replace the loop's opening with:

```java
        Set<String> known = new LinkedHashSet<>(scenario.fixtures().keySet());

        for (int i = 0; i < scenario.steps().size(); i++) {
            StepSpec step = scenario.steps().get(i);

            if (SET_STEP.equals(step.type())) {
                if (step.params().isEmpty()) {
                    throw new ValidationException(sourceName, i, step.type(),
                            "a set: step needs at least one name to bind");
                }
                for (Map.Entry<String, Object> entry : step.params().entrySet()) {
                    requireKnownReferences(entry.getValue(), known, sourceName, i, step.type());
                    known.add(entry.getKey());   // later entries may read earlier ones
                }
                continue;
            }

            StepType type = registry.get(step.type());
```

Replace the existing reference-checking block at the end of the loop with a call to the same helper, then record any `as:` name:

```java
            requireKnownReferences(step.params(), known, sourceName, i, step.type());

            Object asName = step.params().get("as");
            if (asName != null) {
                known.add(String.valueOf(asName));
            }
        }
```

And add the helper:

```java
    /**
     * Every {@code ${...}} reference must name something that exists at this point in the
     * scenario. Checking order matters: a reference to a name bound by a *later* step is a
     * mistake worth catching at load, not three minutes into a provisioning wait.
     */
    private static void requireKnownReferences(
            Object value, Set<String> known, String sourceName, int index, String stepType) {

        for (String reference : Interpolator.references(value)) {
            if (!known.contains(reference)) {
                throw new ValidationException(sourceName, index, stepType,
                        "references '${" + reference + "...}' but nothing named '" + reference
                                + "' has been declared or bound at this point");
            }
        }
    }
```

Add `java.util.LinkedHashSet` to the imports.

- [ ] **Step 5: Handle `set` in the engine**

In `ScenarioEngine`, add the same constant:

```java
    /** Reserved: handled here rather than by a registered step type. */
    private static final String SET_STEP = "set";
```

In `runStep`, immediately after the line that captures `started` and before the registry lookup, add:

```java
        if (SET_STEP.equals(step.type())) {
            return runSet(step, index, scope, started);
        }
```

And add the method:

```java
    /**
     * Binds each entry into the scenario's variables, in declaration order so a later entry may
     * read one bound earlier in the same step.
     */
    private StepResult runSet(StepSpec step, int index, Map<String, Object> scope, long started) {
        Map<String, Object> bound = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : step.params().entrySet()) {
            Object value = Interpolator.resolve(entry.getValue(), scope, evaluator);
            scope.put(entry.getKey(), value);
            bound.put(entry.getKey(), value);
        }

        return new StepResult(index, SET_STEP, bound, true,
                System.currentTimeMillis() - started, "bound " + bound.keySet(), null, null);
    }
```

The engine needs an evaluator. Add the field and constructor parameter:

```java
    private final ExpressionEvaluator evaluator;
```

Add `ExpressionEvaluator evaluator` as the final parameter of the existing constructor, assign it, and import `com.idm.test.engine.expr.ExpressionEvaluator`. Then replace the existing `Interpolator.resolve(step.params(), scope)` call in `runStep` with the three-argument form passing `evaluator`.

An expression that throws inside `runSet` propagates as a `RuntimeException`, which the loop in `run()` already catches and records as a failed step — that is why `testSetExpressionFailureIsReported` expects a failed result rather than an exception.

**A limitation worth stating rather than discovering.** `Interpolator.references` only finds `${...}` references, so the validator cannot see which variables an expression reads — `js:{ absnet.field }` passes validation and fails at run time. Closing that would mean parsing JavaScript, which is not worth it for a typo check. `ExpressionEvaluator` compensates by naming the expression text in its error, so the failure is still actionable. Do not attempt a regex over expression bodies to approximate this: it would produce false rejections on any expression mentioning a word that happens to match a variable name.

- [ ] **Step 6: Update the engine's callers**

`ScenarioEngine`'s constructor gained a parameter. There are three construction sites — update all of them:
- `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java` — the `engineWith` helper
- `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java`
- `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioValidationTest.java`

Pass `new ExpressionEvaluator()` in each. Verify you have them all with:

```bash
grep -rn "new ScenarioEngine(" idm-test-engine/src idm-test-scenarios/src
```

- [ ] **Step 7: Run both test classes**

```bash
./mvnw -pl idm-test-engine -am test -Dtest='ScenarioEngineTest,ScenarioValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all pass — 4 new validator tests and 4 new engine tests.

- [ ] **Step 8: Exercise the feature end to end in a real scenario**

In `idm-test-scenarios/src/test/resources/scenarios/user-provisioning.yaml`, add a `set:` step before the create and use its value, so the YAML path proves expressions work outside unit tests. Replace the `steps:` block with:

```yaml
steps:
  - set:
      displayName: "js:{ user.firstName.charAt(0).toUpperCase() + user.firstName.slice(1) + ' ' + user.lastName.toUpperCase() }"

  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      as: created
      attributes:
        cn: ${user.userId}
        sn: ${user.lastName}
        givenName: ${user.firstName}
        mail: ${user.email}
        description: ${displayName}

  - ldap.verify:
      dn: "${created}"
      expect:
        givenName: ${user.firstName}
        sn: ${user.lastName}
        mail: ${user.email}
        description: ${displayName}
      wait:
        timeout: 30s
        poll: 2s
```

This exercises all three features at once: a `js:{ }` expression, `set:` binding, and `as:` capture feeding the next step's `dn`.

- [ ] **Step 9: Run the whole suite**

```bash
./mvnw clean test
```

Expected: `BUILD SUCCESS`, every line reporting `Failures: 0, Errors: 0, Skipped: 0`. The total was 235 before this plan; it should now be 235 + 6 + 11 + 8 + 6 + 8 = 274. If your number differs, report the actual figure rather than adjusting anything to match.

- [ ] **Step 10: Commit**

```bash
git add idm-test-engine idm-test-scenarios
git commit -m "feat: add the set: step and forward-reference validation

set: is a reserved step type handled by the engine rather than the
registry, because it binds several names at once and steps have no way to
write to scope. Entries bind in declaration order, so a later one may read
an earlier one.

The validator now tracks which names exist at each point in the scenario,
so a reference to something bound only by a later step fails at load
rather than three minutes into a provisioning wait.

user-provisioning.yaml now exercises an expression, a set: binding and an
as: capture, so the YAML path is covered outside the unit tests.

ITF-7

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Done When

- [ ] `./mvnw clean test` returns `BUILD SUCCESS` with every line reporting `Failures: 0, Errors: 0, Skipped: 0`
- [ ] An expression cannot reach Java: `java.lang.System`, `java.io.File` and `Packages.java.lang.Runtime` all raise `ExpressionException`
- [ ] An expression that never terminates is stopped rather than hanging the suite
- [ ] `js:{ JSON.stringify({a: 1}) }` evaluates correctly — braces inside an expression are not truncated
- [ ] An expression embedded in a larger string is rejected with a message saying it must be the whole value
- [ ] A scenario referencing a name bound only by a later step fails at load, naming the file and the step
- [ ] `user-provisioning.yaml` uses `set:`, `js:{ }` and `as:`, and passes
- [ ] `NoSpringDependencyTest` still passes
