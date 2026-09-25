# Root Names Inside `js:{}` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Interpolator.references()` reports the free root names used inside a `js:{}` expression. Load-time validation then catches a misspelled root, and cleanup SKIPs an entry whose `js:` reads a capture that was never bound.

**Architecture:** A new pure class, `JsReferences`, parses the expression with Rhino's `Parser` (ES6) and never evaluates it. It collects every `Name` except property names, object keys, `typeof` operands, names declared anywhere in the expression, and standard globals. `Interpolator.collectReferences` feeds a whole-value expression's body to it, instead of returning nothing. The validator and engine consume `references()` unchanged.

**Tech Stack:** Java 21, Rhino 1.8.0 AST (`org.mozilla.javascript.ast`), JUnit 5, AssertJ.

**Spec:** `docs/specs/2026-09-24-js-references-design.md`. **Ticket:** ITF-21.

## Global Constraints

- **`JsReferences.freeNames` never evaluates the expression, and never throws.** On a parse failure, or any `RuntimeException`, it returns an empty set.
- **Exclusions, exactly these five:**
  1. the `property` side of a `PropertyGet`
  2. the `left` side of an `ObjectProperty`
  3. the operand of a `typeof` `UnaryExpression`
  4. any name declared anywhere in the expression: `VariableInitializer` targets, function and arrow parameters (including names inside destructuring patterns), function names, and `catch` parameters
  5. the ids of Rhino's `initStandardObjects()` scope, plus `arguments`
- **Declared anywhere counts as local.** There is no lexical scoping.
- **Results come in source order** (a `LinkedHashSet`).
- **No change** to `ScenarioValidator`, `ScenarioEngine`, `ExpressionEvaluator`, `JsValues`, or evaluation.
- **`idm-test-engine/src/main` must never import `org.springframework`.**
- Every exclusion rule, and the wiring, is falsification-tested.
- `-pl` is always paired with `-am`. Single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`.
- If you see "Unresolved compilation problems", that is stale IDE output. Run `./mvnw clean test` and do not change code.
- Branch: `feat/js-references` off `main`.
- Commit messages end with:
  ```
  ITF-21

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

Paths use `E` = `idm-test-engine/src/main/java/com/idm/test/engine` and `ET` = `idm-test-engine/src/test/java/com/idm/test/engine`.

The `JsReferences` code below was prototyped against the project's `rhino-1.8.0.jar` before this plan was written. It produced exactly the results the tests expect.

---

### Task 0: Branch and baseline

- [ ] **Step 1:** `git checkout main && git checkout -b feat/js-references`
- [ ] **Step 2:** Run `./mvnw clean test`, then:

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
```

Expected: `BUILD SUCCESS` and **514**, with no non-zero `failures=`, `errors=` or `skipped=`.

---

### Task 1: `JsReferences`, wiring, tests, docs

**Files:**
- Create: `E/expr/JsReferences.java`, `ET/expr/JsReferencesTest.java`
- Modify: `E/Interpolator.java` (`collectReferences`, plus one private helper)
- Modify (tests): `ET/InterpolatorTest.java`, `ET/ScenarioValidatorTest.java`, `ET/ScenarioEngineTest.java`
- Modify (docs): `docs/guides/scenario-authoring.md`, `docs/specs/2026-09-24-explicit-cleanup-design.md`

**Interfaces:**
- Produces: `public final class JsReferences` with `public static Set<String> freeNames(String expression)`.
- Consumes, from existing tests:
  - `ScenarioValidatorTest`: `validator` (registry `ldap.create` = `ACTION`, requires `dn`, and `ldap.verify`), `scenario(StepSpec...)` (with a `user` fixture), `scenarioWithCleanup(List<StepSpec>, StepSpec...)`, and `CREATE_AS_CREATED` (an `ldap.create` with `as: created`)
  - `ScenarioEngineTest`: `recordingAction(String)`, `failingAction(String)` (message `"<name> went wrong"`), `valueProducing(String, Object)` (requires `dn`), `withCleanup(List<StepSpec>, StepSpec...)`, `engineWith(StepType...)`, `events`, and `CleanupStatus`

- [ ] **Step 1: Write the failing tests**

Create `ET/expr/JsReferencesTest.java`:

```java
package com.idm.test.engine.expr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("JsReferences")
class JsReferencesTest {

    @Test
    @DisplayName("Reports the root of a property chain, not the properties")
    void testRootOfPropertyChain() {
        assertThat(JsReferences.freeNames("user.firstName.charAt(0)")).containsExactly("user");
        assertThat(JsReferences.freeNames("rows[0].STATUS")).containsExactly("rows");
        assertThat(JsReferences.freeNames("'/users/' + user.userId")).containsExactly("user");
    }

    @Test
    @DisplayName("Reports an object literal's values, not its keys")
    void testObjectValueReportedKeyNot() {
        assertThat(JsReferences.freeNames("({ dn: created.dn, cn: 'x' })")).containsExactly("created");
    }

    @Test
    @DisplayName("Treats shorthand {a} as reading a")
    void testShorthandPropertyIsAReference() {
        assertThat(JsReferences.freeNames("({a, b: c})")).containsExactly("a", "c");
    }

    @Test
    @DisplayName("Reports free names inside a template literal's substitutions")
    void testTemplateLiteral() {
        assertThat(JsReferences.freeNames("`hello ${user.firstName}`")).containsExactly("user");
        assertThat(JsReferences.freeNames("`${ 1 + 1 }`")).isEmpty();
    }

    @Test
    @DisplayName("Treats function and arrow parameters as local")
    void testParametersAreLocal() {
        assertThat(JsReferences.freeNames("items.map(x => x.id)")).containsExactly("items");
        assertThat(JsReferences.freeNames("items.filter(function (y) { return y > limit; })"))
                .containsExactly("items", "limit");
    }

    @Test
    @DisplayName("Treats var, let, function names and catch parameters as local")
    void testDeclaredNamesAreLocal() {
        assertThat(JsReferences.freeNames("var d = 1; d + n")).containsExactly("n");
        assertThat(JsReferences.freeNames("let k = 2; k")).isEmpty();
        assertThat(JsReferences.freeNames("(function f(){ try { x } catch (e) { return e + z; } })()"))
                .containsExactly("x", "z");
    }

    @Test
    @DisplayName("Ignores standard globals")
    void testStandardGlobalsIgnored() {
        assertThat(JsReferences.freeNames(
                "Math.max(1, n) + JSON.stringify(o) + String(undefined) + NaN + Infinity"))
                .containsExactly("n", "o");
        assertThat(JsReferences.freeNames(
                "var d = new Date(user.startDate); d.setDate(d.getDate() + 30); d.toISOString()"))
                .containsExactly("user");
    }

    @Test
    @DisplayName("Ignores the operand of typeof, which is legal for a name that does not exist")
    void testTypeofOperandIgnored() {
        assertThat(JsReferences.freeNames("typeof missing")).isEmpty();
    }

    @Test
    @DisplayName("Returns nothing, and never throws, for a malformed or empty expression")
    void testMalformedReturnsEmpty() {
        assertThatCode(() -> JsReferences.freeNames("user.firstName.(")).doesNotThrowAnyException();
        assertThat(JsReferences.freeNames("user.firstName.(")).isEmpty();
        assertThat(JsReferences.freeNames("")).isEmpty();
        assertThat(JsReferences.freeNames("   ")).isEmpty();
    }
}
```

In `ET/InterpolatorTest.java`, replace the whole `testDoesNotCollectReferencesInsideExpressionTemplateLiteral` method, annotations included, with:

```java
    @Test
    @DisplayName("Should report free JS names in an expression, but never read a template literal's ${...} as a scenario reference")
    void testCollectsFreeNamesInsideExpression() {
        assertThat(Interpolator.references("js:{ `${ 1 + 1 }` }")).isEmpty();
        assertThat(Interpolator.references("js:{ `hello ${user.firstName}` }")).containsExactly("user");
        assertThat(Interpolator.references("js:{ user.lastName.toUpperCase() }")).containsExactly("user");
    }
```

Leave `testCollectingReferencesDoesNotThrowOnMalformedExpression` unchanged: `js:{ unclosed` must still give an empty set.

In `ET/ScenarioValidatorTest.java`, add:

```java
    @Test
    @DisplayName("Should reject a misspelled root inside js:{} at load time")
    void testRejectsUnknownRootInsideExpression() {
        assertThatThrownBy(() -> validator.validate(scenario(
                new StepSpec("ldap.create", Map.of("dn", "js:{ usr.userId }"))), "t.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("usr");
    }

    @Test
    @DisplayName("Should reject a js:{} read of a name bound only by a later step")
    void testRejectsForwardReferenceInsideExpression() {
        assertThatThrownBy(() -> validator.validate(scenario(
                new StepSpec("ldap.create", Map.of("dn", "js:{ created + ',ou=users' }")),
                new StepSpec("ldap.create", Map.of("dn", "cn=x", "as", "created"))), "t.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("created");
    }

    @Test
    @DisplayName("Cleanup js:{} may read a name bound by any step")
    void testCleanupExpressionReadsAnyBinding() {
        assertThatCode(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "js:{ created + ',x' }"))), "c.yaml"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Cleanup js:{} may not read a name bound nowhere")
    void testCleanupExpressionRejectsUnknownRoot() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "js:{ nowhere.dn }"))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cleanup entry 1")
                .hasMessageContaining("nowhere");
    }
```

In `ET/ScenarioEngineTest.java`, add:

```java
    @Test
    @DisplayName("A cleanup js:{} reading a capture that was never bound is SKIPPED")
    void testCleanupExpressionOnUnboundCaptureSkips() {
        ScenarioEngine engine = engineWith(
                failingAction("f"), valueProducing("v", "cn=made"), recordingAction("a"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("f", Map.of()),
                        new StepSpec("v", Map.of("dn", "x", "as", "created"))),
                new StepSpec("a", Map.of("dn", "js:{ created + ',ou=users' }"))), "t.yaml");

        assertThat(events).containsExactly("run:f");
        assertThat(result.cleanup()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(CleanupStatus.SKIPPED);
            assertThat(c.message()).contains("created");
        });
        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("f went wrong").doesNotContain("cleanup entry");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='JsReferencesTest,InterpolatorTest,ScenarioValidatorTest,ScenarioEngineTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure, `cannot find symbol ... JsReferences`.

- [ ] **Step 3: Create `JsReferences`**

Create `E/expr/JsReferences.java`:

```java
package com.idm.test.engine.expr;

import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.CatchClause;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.UnaryExpression;
import org.mozilla.javascript.ast.VariableInitializer;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Finds the scenario names a {@code js:{ ... }} expression reads, so they can be checked at load
 * time like {@code ${...}} references and a cleanup entry can be skipped when one was never bound.
 * <p>
 * Static analysis only: the expression is parsed, never evaluated, which keeps validation free of
 * side effects. A name is free unless it is a property name, an object-literal key, the operand of
 * {@code typeof}, declared anywhere in the expression, or a standard JavaScript global. Declared
 * anywhere counts as local — no lexical scoping — so a local that shadows a scenario name is
 * simply not reported: this can only under-report, never reject a valid expression. Any name it
 * does report must come from the scenario's scope at run time, or it is a guaranteed
 * ReferenceError.
 */
public final class JsReferences {

    private static final Set<String> STANDARD_GLOBALS = standardGlobals();

    private JsReferences() {
    }

    /**
     * @param expression the body of a {@code js:{ ... }} expression
     * @return the free root names it reads, in source order; empty for a malformed expression.
     * Never throws: this is a query, and a malformed expression is reported by
     * {@link ExpressionEvaluator#validate} and at evaluation.
     */
    public static Set<String> freeNames(String expression) {
        AstRoot root;
        try {
            CompilerEnvirons environment = new CompilerEnvirons();
            environment.setLanguageVersion(Context.VERSION_ES6);
            root = new Parser(environment).parse(expression, "expression", 1);
        } catch (RuntimeException malformed) {
            return Set.of();
        }

        Set<String> declared = new HashSet<>();
        root.visit(node -> {
            if (node instanceof VariableInitializer initializer) {
                addNames(initializer.getTarget(), declared);
            } else if (node instanceof FunctionNode function) {
                if (function.getFunctionName() != null) {
                    declared.add(function.getFunctionName().getIdentifier());
                }
                function.getParams().forEach(parameter -> addNames(parameter, declared));
            } else if (node instanceof CatchClause catchClause && catchClause.getVarName() != null) {
                declared.add(catchClause.getVarName().getIdentifier());
            }
            return true;
        });

        Set<String> free = new LinkedHashSet<>();
        root.visit(node -> {
            if (node instanceof Name name && isReference(name)) {
                String identifier = name.getIdentifier();
                if (!declared.contains(identifier) && !STANDARD_GLOBALS.contains(identifier)) {
                    free.add(identifier);
                }
            }
            return true;
        });
        return free;
    }

    /** False for a name that is not read as a variable: a property, an object key, a typeof operand. */
    private static boolean isReference(Name name) {
        AstNode parent = name.getParent();
        if (parent instanceof PropertyGet get && get.getProperty() == name) {
            return false;
        }
        if (parent instanceof ObjectProperty property && property.getLeft() == name) {
            return false;
        }
        return !(parent instanceof UnaryExpression unary && unary.getType() == Token.TYPEOF);
    }

    /** Every name in a declaration target, including inside a destructuring pattern. */
    private static void addNames(AstNode pattern, Set<String> into) {
        if (pattern == null) {
            return;
        }
        pattern.visit(node -> {
            if (node instanceof Name name) {
                into.add(name.getIdentifier());
            }
            return true;
        });
    }

    private static Set<String> standardGlobals() {
        Context context = new ContextFactory().enterContext();
        try {
            context.setLanguageVersion(Context.VERSION_ES6);
            ScriptableObject scope = context.initStandardObjects();
            Set<String> names = new HashSet<>();
            for (Object id : scope.getAllIds()) {
                names.add(String.valueOf(id));
            }
            names.add("arguments");
            return Set.copyOf(names);
        } finally {
            Context.exit();
        }
    }
}
```

- [ ] **Step 4: Wire it into `Interpolator`**

In `E/Interpolator.java`, add `import com.idm.test.engine.expr.JsReferences;` beside the existing `expr` import.

In `collectReferences`, replace:

```java
            if (isWholeValueExpression(text)) {
                // An expression is JavaScript, not a scenario reference — a template literal's
                // own ${...} substitutions must not be mistaken for ${path} references. A
                // malformed expression is reported by resolve(), not here: references() is a
                // query and must not throw.
                return;
            }
```

with:

```java
            if (isWholeValueExpression(text)) {
                // An expression is JavaScript, not ${path} syntax: a template literal's own
                // ${...} is never read as a scenario reference. Its free JS names are, because
                // the scenario scope must supply them. A malformed expression contributes nothing
                // and is reported by resolve(), not here: references() is a query and must not throw.
                String body = expressionBodyOrNull(text);
                if (body != null) {
                    roots.addAll(JsReferences.freeNames(body));
                }
                return;
            }
```

Add this private helper next to `isWholeValueExpression`:

```java
    private static String expressionBodyOrNull(String text) {
        try {
            return expressionIn(text);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
```

Update the javadoc of `references(Object)` to: `@return the root name of every reference found, for example "user" in "${user.email}", and the free names a js:{ } expression reads, for example "user" in "js:{ user.email.toLowerCase() }"`.

- [ ] **Step 5: Run the tests to verify they pass**

Run the Step 2 command. Expected: all pass, `BUILD SUCCESS`.

- [ ] **Step 6: Falsify**

Do each mutation one at a time. Re-run `./mvnw test -pl idm-test-engine -am -Dtest='JsReferencesTest,InterpolatorTest,ScenarioValidatorTest,ScenarioEngineTest' -Dsurefire.failIfNoSpecifiedTests=false` after each, record which tests fail, then revert:

1. In `isReference`, remove the `PropertyGet` check. `testRootOfPropertyChain` must fail.
2. Remove the `ObjectProperty` check. `testObjectValueReportedKeyNot` must fail.
3. Replace the `typeof` return with `return true;`. `testTypeofOperandIgnored` must fail.
4. Remove `!declared.contains(identifier) &&`. `testParametersAreLocal` and `testDeclaredNamesAreLocal` must fail.
5. Remove `&& !STANDARD_GLOBALS.contains(identifier)`. `testStandardGlobalsIgnored` must fail.
6. In `collectReferences`, restore a bare `return;` in place of the new body. `testCollectsFreeNamesInsideExpression`, `testRejectsUnknownRootInsideExpression`, `testRejectsForwardReferenceInsideExpression`, `testCleanupExpressionRejectsUnknownRoot` and `testCleanupExpressionOnUnboundCaptureSkips` must fail.

- [ ] **Step 7: Docs**

In `docs/guides/scenario-authoring.md`, replace the sentence

`Expression syntax is checked up front, but unknown JS variables can still fail at runtime.`

with

`Expression syntax is checked up front, and so are root names: \`js:{ usr.userId }\` is rejected at load time exactly like \`${usr.userId}\`. A misspelled property such as \`user.userid\` is caught when the step runs.`

Keep the rest of that paragraph, including the ITF-20 sentence about `undefined`.

In `docs/specs/2026-09-24-explicit-cleanup-design.md`, at the end of §3's SKIPPED bullet, add: `(Since ITF-21 this also covers names read inside a \`js:{ ... }\` expression.)`

- [ ] **Step 8: Full suite**

Run `./mvnw clean test` and the Task 0 count command. Expected:
- `BUILD SUCCESS` and **528** (514 + 9 `JsReferencesTest` + 4 validator + 1 engine; the `InterpolatorTest` change is a replacement), with no non-zero failures, errors or skips.
- `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`.
- The corpus, fixtures and every existing validator test must still validate. If any now fails because a `js:` name is reported as unknown, **stop and report it**. It is either a real bug in that scenario, or a false positive in `JsReferences`; do not loosen the rules or edit scenarios to get green.

- [ ] **Step 9: Commit**

```bash
git add idm-test-engine docs/guides/scenario-authoring.md docs/specs/2026-09-24-explicit-cleanup-design.md
git commit -F - <<'EOF'
feat: check root names inside js:{} expressions at load time

JsReferences finds the free names an expression reads by parsing it,
never evaluating it. references() now reports them, so the validator
rejects js:{ usr.userId } like ${usr.userId}, and a cleanup entry whose
js: reads a capture that was never bound is SKIPPED, not FAILED.

ITF-21

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```
