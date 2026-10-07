# A recursion limit for js:{} expressions — design

**Ticket:** ITF-42 (bug). **Found in:** the ITF-41 final review; predates ITF-41.

## 1. The problem

`ExpressionEvaluator` bounds expressions with Rhino's instruction budget (default 100 000), but
unbounded recursion overflows the Java stack before the budget trips:

- `js:{ (function f() { return f(); })() }`
- a getter that calls a list helper (`containsAll`, …) on its own object, which re-enters the
  getter through Java

Rhino 1.8.0 runs in its default compiled mode, the evaluator catches only `RuntimeException`, and
the engine's step handling catches only `RuntimeException`. So the `java.lang.StackOverflowError`
escapes the step and aborts the scenario's test, with no step result recorded, instead of failing
the step with an expression error.

## 2. Decision (jangulo, 2026-10-07)

**Both a recursion-depth limit and a safety net:** run expressions in Rhino's interpreted mode
with a maximum interpreter stack depth, so plain JavaScript recursion stops deterministically, and
catch `StackOverflowError` as a backstop for recursion the interpreter cannot count (through Java
code such as the list helpers).

## 3. Design

Only `ExpressionEvaluator.doEvaluate` (engine module, `com.idm.test.engine.expr`) changes:

- **Interpreted mode and a depth limit:** `cx.setInterpretedMode(true)` (the Rhino 1.8 API; the
  older equivalent is optimization level −1) and
  `cx.setMaximumInterpreterStackDepth(MAX_STACK_DEPTH)`, a new constant of **1 000** frames.
- **Message:** Rhino's "Exceeded maximum stack depth" error, and a `StackOverflowError`, both
  become `ExpressionException("expression recursed too deeply and was stopped: js:{ <expression> }")`.
- **Safety net:** a `catch (StackOverflowError e)` around evaluation, alongside the existing
  catches. `finally { Context.exit(); }` still runs, and each evaluation already uses a fresh
  scope, so nothing carries over to the next evaluation.
- **Unchanged:** the instruction budget (it works in interpreted mode), the sandbox (every Java
  class denied), the list helpers' own limits (1 000 000 values, 100 nesting levels), `validate`
  (compile only), `evaluate`/`evaluateExplained` signatures.

**Effect on steps:** a recursive expression ends like other expression errors. In a step parameter
it fails the step with the message; in an `http.verify` check it is `check N of M failed: …`,
retried under `wait:` as other expression errors are today. The scenario continues to cleanup and
records its result.

## 4. Documentation

- `docs/architecture/capabilities.md`, the "Expression restrictions" row: add the recursion depth
  of 1 000 frames.
- `docs/reference/writing-scenarios.md`, where expression limits are described (the `js:{ … }`
  section): one line that an expression running too long or recursing too deeply is stopped and
  fails its step.

## 5. Testing

**`ExpressionEvaluatorTest`:**
- plain recursion and mutual recursion each throw `ExpressionException` containing
  `recursed too deeply`
- a getter that calls `containsAll` on its own object throws `ExpressionException`, never
  `StackOverflowError`
- the evaluator works normally on the next call after each
- legitimate recursion of depth 500 succeeds
- existing budget, sandbox and helper tests stay green

**`ScenarioEngineTest`:** a step whose parameter is a recursive expression produces a failed
`StepResult` with the message; the scenario runs its cleanup and returns a result.

**`HttpVerifyStepsTest`:** a recursive check fails the step with `check 1 of 1 failed: …` and the
message.

**Also:** the full suite and the corpus (0 FAILED).

## 6. Out of scope

- Making expression errors (budget, recursion) final failures under `wait:`.
- A wall-clock limit for expressions.
