# Root names inside `js:{}` visible to validation and cleanup — design

**Ticket:** ITF-21 (subtask of ITF-4). **Split from:** ITF-20. **Related:** ITF-19 (explicit
cleanup), ITF-7 (expressions).

## 1. The gap

`Interpolator.references(value)` returns the root names a value uses, and it deliberately returns
**nothing** for a whole-value `js:{...}`. The reason is that a JavaScript template literal's own
`${...}` must not be mistaken for a scenario `${path}` reference. Three things consume
`references()`:

- `ScenarioValidator` step checks (a name must be bound *before* the step)
- `ScenarioValidator` cleanup checks (a name must be bound *anywhere*)
- `ScenarioEngine.runCleanupEntry`'s SKIPPED check

So none of them can see names inside `js:`. Two consequences:

1. **A misspelled root passes load-time validation.** `js:{ usr.userId }` is only caught when the
   step runs, which can be minutes in, after earlier steps have changed a real environment. The
   same typo as `${usr.userId}` is rejected at load time.
2. **A cleanup entry reading an unbound capture inside `js:` is FAILED instead of SKIPPED.** For
   example, `dn: "js:{ created.dn }"` in a scenario where the `as: created` step never ran. That
   contradicts `docs/specs/2026-09-24-explicit-cleanup-design.md` §3.

Since ITF-20, a *property* typo (`user.userid`) fails loudly at run time. This ticket is about
*root* names, and about catching them at load time.

## 2. Approach: static analysis of the parsed expression

Validation must never execute an expression; `ExpressionEvaluator.validate` only compiles. So
free names are found from Rhino 1.8.0's syntax tree (`org.mozilla.javascript.Parser`, ES6 mode →
`AstRoot`), never by evaluating.

**Verified before this design was written,** with a throwaway probe against the project's Rhino
1.8.0 jar. The parser labels each `Name` by its parent node:

| Expression | Names, with parent |
|---|---|
| `user.firstName.charAt(0)` | `user`, `firstName` and `charAt` all under `PropertyGet`, with `user` as its target |
| `({ dn: created.dn, cn: 'x' })` | `dn`/`cn` under `ObjectProperty` (keys); `created` under `PropertyGet` |
| `items.map(x => x.id)` | `x` under `FunctionNode` (a parameter) |
| `var d = new Date(...)` | `d` under `VariableInitializer`; `Date` under `NewExpression` |
| `` `hello ${user.firstName}` `` | `user` under `PropertyGet` |
| `rows[0].STATUS` | `rows` under `ElementGet` |
| `user.firstName.(` | the parser throws `EvaluatorException` |

`initStandardObjects()`, read with `getAllIds()`, lists 78 standard globals, including `Math`,
`JSON`, `Date`, `String`, `undefined`, `NaN` and `Infinity`.

**Rejected alternatives:**
- **Evaluate with a scope that records lookups.** That breaks the rule that validation never runs
  an expression.
- **A regex over the expression.** It cannot tell a property from a root, or a string from code.

## 3. `JsReferences.freeNames`

A new class, `com.idm.test.engine.expr.JsReferences`, with one method:

```java
/** Free root names in a js:{} expression body, in source order. Never throws. */
static Set<String> freeNames(String expression)
```

It collects every `Name` node **except**:

1. **A property name:** the `property` side of a `PropertyGet` (`user.firstName` yields `user`,
   not `firstName`).
2. **An object-literal key:** the `left` side of an `ObjectProperty`.
3. **The operand of `typeof`.** `typeof x` is legal even when `x` doesn't exist, so it must not be
   rejected.
4. **A name declared anywhere in the expression:**
   - `var`, `let` and `const` targets (`VariableInitializer`)
   - function and arrow-function parameters
   - function names
   - `catch` parameters

   **Declared anywhere counts as local** (jangulo's decision). If a local shadows a scenario name,
   that name is simply not reported. This can only *under-report*: a mistake is then caught at run
   time. It can never reject a valid scenario.
5. **A standard global:** the ids of Rhino's `initStandardObjects()` scope, computed once, plus
   `arguments`.

**It never throws.** A parse failure, or any other `RuntimeException`, returns an empty set.
`references()` is a query, and a malformed expression is already reported by
`ExpressionEvaluator.validate` (at load time) and by `resolve` (at run time).

**Why every reported name is a real dependency.** At run time, a name that passes rules 1–5 can
only come from the scenario scope that `ExpressionEvaluator` populates. Otherwise it is a
guaranteed `ReferenceError`. So reporting it can never reject an expression that would have run.

## 4. Wiring

`Interpolator.collectReferences` no longer returns early for a whole-value expression. For
`js:{ <body> }` it adds `JsReferences.freeNames(<body>)`, taking the body from the existing
`expressionIn(text)`. A malformed expression still contributes nothing, and still does not throw.
The rule "a template literal's `${...}` is not a scenario reference" still holds: `user` in
`` `hello ${user.firstName}` `` is reported only because it is a free JavaScript name that the
scenario scope must provide.

With no other code changes, this gives:

- **Validator, steps:** `js:{ usr.userId }` is rejected at load time, naming `usr`. A name bound
  only by a later step is rejected. `set:` entries, which already go through
  `requireKnownReferences`, are covered too.
- **Validator, cleanup:** an entry may read any name bound anywhere, and a name bound nowhere is
  rejected.
- **Engine, cleanup:** an entry whose `js:` reads a capture that was never bound is **SKIPPED**,
  closing the ITF-19 spec §3 gap.

## 5. Testing

- **`JsReferencesTest`** (new; one case per rule):
  - reported: `user.firstName.charAt(0)` → `[user]`; `rows[0].STATUS` → `[rows]`;
    `({ dn: created.dn })` → `[created]`; `` `hello ${user.firstName}` `` → `[user]`;
    `'/users/' + user.userId` → `[user]`
  - not reported: property names; object keys; arrow and function parameters
    (`items.map(x => x.id)` → `[items]`); `var`/`let` (`var d = 1; d + n` → `[n]`); globals
    (`Math.max(1, n) + JSON.stringify(o) + String(undefined) + NaN + Infinity` → `[n, o]`);
    `typeof missing` → `[]`
  - `` `${ 1 + 1 }` `` → `[]`; a malformed `user.firstName.(` → `[]`, and it does not throw
- **`InterpolatorTest`:** the test at line 166 that asserts
  `` references("js:{ `hello ${user.firstName}` }") `` is empty pins the old behaviour. It changes
  to expect `[user]`. The `` `${ 1 + 1 }` `` and `js:{ unclosed` cases stay empty.
- **`ScenarioValidatorTest`:**
  - a step with `dn: "js:{ usr.userId }"` is rejected, and the message names `usr`
  - a step reading, inside `js:`, a name bound only by a later `as:` is rejected
  - a cleanup entry reading, inside `js:`, a name bound by any step is accepted
  - a cleanup entry reading, inside `js:`, a name bound nowhere is rejected
- **`ScenarioEngineTest`:** a cleanup `dn: "js:{ created.dn }"` where the `as: created` step never
  ran (an earlier step failed) is **SKIPPED**, its action does not run, and the summary shows only
  the step failure.
- **Falsification:** remove each exclusion rule (1–5) in turn, and at least one test must fail.
  Separately, restore the early return in `collectReferences`, and the validator and engine tests
  above must fail.
- **Live:** the full default suite. The corpus's two `js:` expressions, the fixtures, and every
  existing validator test with `js:` must still validate. That is the main check against false
  positives.

## 6. Documentation

- `docs/guides/scenario-authoring.md`: replace "Expression syntax is checked up front, but unknown
  JS variables can still fail at runtime." with: "Expression syntax is checked up front, and so
  are root names: `js:{ usr.userId }` is rejected at load time exactly like `${usr.userId}`. A
  misspelled *property* such as `user.userid` is caught when the step runs."
- `docs/specs/2026-09-24-explicit-cleanup-design.md` §3: add a note that ITF-21 makes
  unbound roots inside `js:` SKIPPED as well.

## 7. Out of scope

- Precise lexical scoping (rejected in §3, rule 4).
- Checking *property* names against the shape of bound values.
- Any change to evaluation, `ExpressionEvaluator.validate`, or ITF-20's `undefined` rule.
