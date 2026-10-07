# Expression Recursion Limit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `js:{ … }` expression that recurses without end fails its step with `expression recursed too deeply and was stopped: js:{ … }` instead of escaping as a `StackOverflowError`.

**Architecture:** `ExpressionEvaluator.doEvaluate` runs Rhino in interpreted mode with a maximum interpreter stack depth of 1 000 frames, turns Rhino's "Exceeded maximum stack depth" error into an `ExpressionException`, and catches `StackOverflowError` (recursion through Java, e.g. a getter calling a list helper) as a backstop with the same message.

**Tech Stack:** Java 21, Mozilla Rhino 1.8.0 (`Context.setInterpretedMode`, `setMaximumInterpreterStackDepth`), JUnit 5, AssertJ, WireMock.

**Ticket:** ITF-42. **Spec:** `docs/specs/2026-10-07-expression-recursion-limit-design.md`.

## Global Constraints

- Interpreted mode with `MAX_STACK_DEPTH = 1_000` frames (`setInterpretedMode(true)` must precede `setMaximumInterpreterStackDepth`, which throws otherwise).
- Message, exactly: `expression recursed too deeply and was stopped: js:{ <expression> }`, as an `ExpressionException`, for both Rhino's depth error and a `StackOverflowError`.
- Unchanged: the instruction budget and its message, the sandbox, the list helpers' limits, `validate`, the public signatures.
- A recursive expression fails its step like other expression errors; the scenario continues to cleanup and records its result.
- Commit trailer: a blank line, `ITF-42`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven: always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. If `./mvnw` finds no JVM: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.11-zulu`.

---

### Task 1: Stop runaway recursion in expressions

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/expr/ExpressionEvaluator.java` (`doEvaluate`, a new constant)
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/expr/ExpressionEvaluatorTest.java`, `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java`, `idm-test-engine/src/test/java/com/idm/test/engine/steps/HttpVerifyStepsTest.java`
- Docs: `docs/architecture/capabilities.md` (the "Expression restrictions" row), `docs/reference/writing-scenarios.md` (the `js:{ … }` bullet list near the top, ~lines 57-70)

**Interfaces:**
- Produces: `static final int MAX_STACK_DEPTH = 1_000` in `ExpressionEvaluator` (package-private is enough).

- [ ] **Step 1: Write the failing tests**

`ExpressionEvaluatorTest`:

```java
    @Test
    @DisplayName("Endless recursion is stopped with an expression error, and the evaluator still works")
    void testRecursionStopped() {
        assertThatThrownBy(() -> evaluator.evaluate("(function f() { return f(); })()", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessage("expression recursed too deeply and was stopped: js:{ (function f() { return f(); })() }");

        assertThatThrownBy(() -> evaluator.evaluate(
                "(function () { function a() { return b(); } function b() { return a(); } return a(); })()", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessageContaining("recursed too deeply");

        assertThat(evaluator.evaluate("1 + 1", Map.of())).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Recursion through a list helper is stopped too, never as a StackOverflowError")
    void testRecursionThroughHelperStopped() {
        String expression = "(function () {"
                + " var o = {};"
                + " Object.defineProperty(o, 'x', { enumerable: true, get: function () { return containsAll([o], [o]); } });"
                + " return containsAll([o], [o]);"
                + " })()";

        assertThatThrownBy(() -> evaluator.evaluate(expression, Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class);
        assertThat(evaluator.evaluate("containsAll(['a'], 'a')", Map.of())).isEqualTo(true);
    }

    @Test
    @DisplayName("Legitimate recursion well under the limit still works")
    void testModerateRecursionAllowed() {
        assertThat(evaluator.evaluate(
                "(function count(n) { return n === 0 ? 0 : 1 + count(n - 1); })(500)", Map.of()))
                .isEqualTo(500.0);
    }
```

For `testRecursionThroughHelperStopped`, the helpers' own nesting limit (100 levels) or the safety net may be what stops it — either is fine, so only the exception type is asserted. If the test passes before the change (the helpers' limit already stops it with an `ExpressionException`), keep it as a regression test and say so in the report.

`ScenarioEngineTest` (it has `recordingAction`, `scenario(...)`, `withCleanup(...)`, `engineWith(...)` and the `events` list):

```java
    @Test
    @DisplayName("A recursive expression in a parameter fails its step; cleanup still runs and a result is recorded")
    void testRecursiveExpressionFailsStep() {
        ScenarioEngine engine = engineWith(recordingAction("a"), recordingAction("undo"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "js:{ (function f() { return f(); })() }"))),
                new StepSpec("undo", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).success()).isFalse();
        assertThat(result.steps().get(0).message()).contains("recursed too deeply");
        assertThat(events).contains("run:undo:x");
    }
```

If the engine reports a parameter-evaluation failure in `failureDetail` or the summary rather than the step message, assert on the field the engine actually uses for other expression errors (find an existing test for a failing `js:{}` parameter and mirror it); the assertion that matters is that a result is returned with the "recursed too deeply" text and cleanup ran.

`HttpVerifyStepsTest` (it has `run(params)`, `params(...)`, `stubJson(status, body)`):

```java
    @Test
    @DisplayName("A recursive check fails the step with the recursion message")
    void testRecursiveCheckFails() {
        stubJson(200, "{}");

        StepResult step = run(params("check", "js:{ (function f() { return f(); })() }"));

        assertThat(step.success()).isFalse();
        assertThat(step.message()).contains("check 1 of 1 failed: ").contains("recursed too deeply");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='ExpressionEvaluatorTest,ScenarioEngineTest,HttpVerifyStepsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the recursion tests error with `java.lang.StackOverflowError` (or fail on the message).

- [ ] **Step 3: Implement**

In `ExpressionEvaluator`, next to `DEFAULT_INSTRUCTION_BUDGET`:

```java
    /**
     * Frames an expression may nest before it is stopped. Deep enough for any reasonable
     * recursion; endless recursion reaches it long before the Java stack overflows.
     */
    static final int MAX_STACK_DEPTH = 1_000;
```

In `doEvaluate`, right after `cx.setLanguageVersion(Context.VERSION_ES6);`:

```java
            // Interpreted mode makes recursion depth countable: Rhino stops an expression that
            // nests deeper than MAX_STACK_DEPTH with its own error instead of overflowing the
            // Java stack. The instruction budget works the same in this mode.
            cx.setInterpretedMode(true);
            cx.setMaximumInterpreterStackDepth(MAX_STACK_DEPTH);
```

Replace the catch chain with (keep the existing order and messages for the other cases):

```java
        } catch (InstructionBudgetExceeded e) {
            throw new ExpressionException(
                    "expression ran too long and was stopped: js:{ " + expression + " }", e);
        } catch (ExpressionException e) {
            throw e;
        } catch (StackOverflowError e) {
            // Recursion the interpreter cannot count — through Java, such as a getter that calls
            // a list helper on its own object. The stack has unwound by now; Context.exit() below
            // still runs, and the next evaluation gets a fresh scope.
            throw recursedTooDeeply(expression, e);
        } catch (RuntimeException e) {
            if (String.valueOf(e.getMessage()).contains("Exceeded maximum stack depth")) {
                throw recursedTooDeeply(expression, e);
            }
            throw new ExpressionException(
                    "could not evaluate js:{ " + expression + " }: " + e.getMessage(), e);
        } finally {
            Context.exit();
        }
    }

    private static ExpressionException recursedTooDeeply(String expression, Throwable cause) {
        return new ExpressionException(
                "expression recursed too deeply and was stopped: js:{ " + expression + " }", cause);
    }
```

If `ListHelpers` wraps exceptions thrown by a re-entered getter (check how it calls back into JavaScript), make sure a `StackOverflowError` or Rhino's depth error is not converted into a different message on the way out — if it is, let those two pass through unchanged.

- [ ] **Step 4: Docs**

`docs/architecture/capabilities.md`, the "Expression restrictions" row's last column: after `Default instruction threshold 100,000;` insert ` recursion depth 1,000 frames;`.

`docs/reference/writing-scenarios.md`, in the bullet list under **An expression:** (the bullets starting "The expression must be the **whole** value"), add a bullet:

```markdown
  - An expression that runs too long or recurses too deeply is stopped, and its step fails with
    `expression ran too long…` or `expression recursed too deeply…`.
```

- [ ] **Step 5: Run tests, full suite and corpus**

Run: `./mvnw test -pl idm-test-engine -am`, then delete stale `idm-test-scenarios/target/surefire-reports/*ContainerScenarios.*` (if any) and run `./mvnw test`.
Expected: BUILD SUCCESS, 0 failures/errors/skips. Then

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
grep -c '"status" : "FAILED"' idm-test-scenarios/target/multi-directory-results.json
```

Expected: a total, `0`, `0`. Known flakes: `EmbeddedLdapServerTest.testLdifImport`, `LdapStepsTest` closed connection, `SoapStepsTest` port clash — re-run once and report both runs.

- [ ] **Step 6: Commit**

```bash
git add idm-test-engine docs/architecture/capabilities.md docs/reference/writing-scenarios.md
git commit -m "fix: stop runaway recursion in js expressions with an expression error

ITF-42

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
