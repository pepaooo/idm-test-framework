# `undefined` in a `js:{}` result is an error — design

**Ticket:** ITF-20 (subtask of ITF-4). **Related:** ITF-19 (explicit cleanup), ITF-7 (expressions).

## 1. The bug

In JavaScript, reading a property that does not exist gives `undefined`, not an error.
`JsValues.fromJs`, the one place a `js:{}` result is converted back to Java, turns `undefined`
into `null`. It does this at the top level and inside returned objects and arrays. So a typo
disappears:

```yaml
cleanup:
  - db.execute:
      database: test-db
      statement: "DELETE FROM users WHERE user_id = ?"
      params: ["js:{ user.userid }"]     # typo: userId
```

The parameter resolves to `null`, and the statement becomes `DELETE ... WHERE user_id = NULL`,
which affects 0 rows. `db.execute` treats 0 affected rows as success (deliberately, since ITF-19),
so the entry reports **SUCCEEDED** while the rows it should have removed stay. This is the project's
recurring vacuous-success defect.

The plain reference form already fails loudly: `${user.userid}` makes `Interpolator` refuse the
unknown field. **The gap is specific to `js:`.**

## 2. The fix

`JsValues.fromJs` throws `JsConversionException` when it meets `undefined` **anywhere** in a
result:
- the whole result
- an object property's value
- an array element, including an array hole (`[1, , 3]`), which reads as not-found and which
  JavaScript treats as `undefined`

JavaScript `null` stays allowed everywhere. `js:{ null }` and `js:{ ({ manager: null }) }` still
produce `null`, because an explicit null is a value the author wrote on purpose.

**The error names where the `undefined` sits,** so the typo can be found at a glance. `fromJs`
tracks a path as it walks, written `result`, `result.dn` or `result.items[2]`:

```
result is undefined — a property that does not exist reads as undefined in JavaScript.
Check the name, or write null if no value is intended.
```

For a nested value, the message starts with that path instead of `result`, e.g.
`result.dn is undefined — ...`.

`ExpressionEvaluator.doEvaluate` already catches every `RuntimeException` and rethrows it as
`ExpressionException("could not evaluate js:{ <expr> }: <message>")`. So the final message also
carries the expression text, with no change there.

## 3. Where the error goes

No change is needed anywhere else. `Interpolator.resolve` propagates `ExpressionException`, and
the engine already turns that into:

- **a step parameter:** the step fails (`ScenarioEngine.run`'s per-step catch)
- **a `cleanup:` entry:** the entry is **FAILED**, not a silent 0-row SUCCEEDED, which is the bug
  in §1 (`runCleanup`'s per-entry catch). A FAILED entry fails the scenario.
- **a `set:` entry:** the `set:` step fails

Load-time validation only *compiles* `js:` (`ExpressionEvaluator.validate`) and never evaluates
it, so it is unaffected.

## 4. Decisions

- **`undefined` is an error at any depth, not only at the top level.** Otherwise
  `js:{ ({ dn: user.userid }) }` would slip through as `{ dn: null }`. Decided by jangulo.
- **`set:` may still bind `null`.** ITF-20 suggested refusing it, as `as:` refuses a null
  capture. That was dropped (jangulo). With this fix, a *typo* can no longer produce null through
  `set:`: a `js:` typo now fails, and a `${}` typo already did. What is left is real null data,
  such as a NULL database column captured by `db.verify` (`set: { status: "${row.STATUS}" }`), or an
  explicit `js:{ null }`. Both are legitimate, and refusing them would break real scenarios to
  guard against a risk this fix has already removed.
- **Making root names inside `js:{}` visible to validation and the cleanup skip check is split to ITF-21,
  its own ticket** (jangulo). With this fix, a misspelled *root* such as `js:{ usr.userId }`
  already fails loudly at run time, because it is a JavaScript `ReferenceError` and so becomes a
  FAILED step or entry. The separate ticket is about catching it at load time, and about a cleanup
  entry reading an unbound capture inside `js:` being SKIPPED rather than FAILED. That needs a
  free-variable analysis of the JavaScript syntax tree, with its own design.

## 5. Testing

In the engine module:

- **`JsValues` / `ExpressionEvaluator`** (in the existing expression test class):
  - `js:{ ({}).missing }` → `ExpressionException`, message contains `result is undefined`
  - `js:{ ({ dn: ({}).missing }) }` → message contains `result.dn is undefined`
  - `js:{ [1, ({}).missing] }` → message contains `result[1] is undefined`
  - `js:{ [1, , 3] }` (a hole) → message contains `result[1] is undefined`
  - `js:{ null }` → `null`, and `js:{ ({ a: null, b: [null] }) }` → a map with `a: null` and
    `b: [null]`
  - an ordinary result with no `undefined` converts exactly as before
- **Engine** (`ScenarioEngineTest`):
  - The case from the ticket: a scenario whose cleanup `db.execute`-like entry has
    `params: ["js:{ user.userid }"]` ends with that entry **FAILED**, the scenario failed, and the
    message naming `userid`. Use the test's recording action with a `js:` parameter, so no
    database is needed.
  - The same typo in a step parameter fails that step.
- **Falsification:** restore the undefined-to-null behaviour, and the tests above must fail.
- **Live:** the full default suite. The corpus has one `js:` expression (`displayName` in
  `user-provisioning.yaml`), and the fixtures have a few more. The run proves nothing legitimate
  relied on undefined becoming null. `ContainerScenarios` is not needed: this change is pure
  engine logic, exercised by the embedded corpus.

## 6. Documentation

- `docs/guides/scenario-authoring.md`: one sentence in the expressions section. A `js:{}` result
  containing `undefined` is an error; write `null` for no value.
- `docs/specs/2026-09-24-explicit-cleanup-design.md` gets no change. Its §3 rule already says a
  resolution error is FAILED; this fix makes a `js:` typo *be* a resolution error.

## 7. Out of scope

- Root-name detection inside `js:{}`: ITF-21 (§4).
- Any change to `set:`, `as:` or `${...}` handling.
- Treating `null` parameters as errors in steps.
