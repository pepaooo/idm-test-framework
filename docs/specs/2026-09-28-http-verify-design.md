# `http.verify`: JavaScript checks against an HTTP response — design

**Ticket:** ITF-24, sub-project 3 of 4 of the generic HTTP connector (see
`docs/specs/2026-09-24-http-request-design.md` §9). It also settles ITF-25's approach: the shape
of `response` it relies on.

## 1. The problem

`http.request` captures `{status, headers, body}` as text and never fails on status. There is no
way to **check** what came back and retry until it is right:
- **Parameters are resolved once, before a step runs.** `Interpolator.resolve` in
  `ScenarioEngine.runStep` resolves every `${…}` and `js:{…}` before `execute`. An expression
  therefore can't see the response of the attempt it is judging.
- **`rest.verify`'s `expect:`** is a static JSON matcher, and it doesn't cover SOAP or raw
  bodies.

## 2. Decisions (jangulo, 2026-09-28)

1. **A new assertion step, `http.verify`.** It takes `http.request`'s parameters plus `check:`.
   `http.request` stays an action that never fails on status. `rest.verify` is unchanged.
2. **`response` carries engine-parsed fields.** They are `status`, `headers`, `body` and
   `json` now, plus `xml` in ITF-25, where the engine parses XML into plain objects. JavaScript
   stays helper-free, keeping ITF-7's rule, so JSON and SOAP are checked the same way. ITF-25
   defines the XML-to-object mapping.
3. **`check:` takes one condition or a list,** and all must be true. A failure names the first
   false one. A check must return true or false: anything else fails the step, and ends a wait
   at once.

A clarification made during design: a check that **throws** (for example reading a property of
`response.json` while it is `null`, before provisioning finishes) is an ordinary, retried
failure. Only a non-boolean result is final.

## 3. The step

```yaml
- http.verify:
    target: idm
    method: GET
    path: "/users/${user.userId}"
    check:
      - "js:{ response.status === 200 }"
      - "js:{ response.json.department === 'Finance' }"
    wait: { timeout: 2m, poll: 5s }
    as: account
```

- **Kind:** assertion (`retryable() == true`), so it accepts `wait:`. It is not allowed in
  `cleanup:`, which the existing rule for assertions already enforces.
- **Required:** `target`, `method`, `path`, `check`.
- **Optional:** `headers` and `body`. They have `http.request`'s exact rules:
  - `body` must be text, and `headers` must be a map
  - the default `Content-Type: text/plain; charset=UTF-8` with a body, and `Accept: */*`
  - target authentication through `RestAuthorization`, with the step's own `Authorization`
    header winning
  - methods from `IdmRestConnector.SUPPORTED_METHODS`
- **`check`:** a single string or a list of strings, each a whole-value `js:{ … }` expression.
- **Each attempt** sends the request, builds `response`, and then evaluates the checks in order,
  stopping at the first that is not `true`.
- **`as:`** binds the last attempt's `response` map (the same five keys as §4, `xml` excluded).
- **Shared code:** the request-building and sending logic is shared with `HttpRequestStep` (one
  helper), not copied.

## 4. `response`

A plain map, rebuilt on every attempt:

| Key | Value |
|---|---|
| `status` | the HTTP status, a number |
| `headers` | a map of lower-case header name to a list of values |
| `body` | the body as text; `""` when empty, never null |
| `json` | the body parsed (maps, lists, scalars) when the response's `Content-Type` is `application/json` or ends in `+json` and the text parses; otherwise `null` |

- **`xml` is reserved for ITF-25.** It is absent now. A check that reads `response.xml` sees
  `undefined`, which fails it under ITF-20's rule, and the failure message says `response.xml`
  arrives with ITF-25.
- **JSON parsing** uses the same lenient rule as `IdmRestConnector.parseBody`: a declared JSON
  body that fails to parse gives `json: null`, not an error.

## 5. Deferred parameters (engine)

A new, optional capability on `StepType`:

```java
/** Parameters the engine must not resolve up front; the step evaluates them itself. */
default Set<String> deferredParams() { return Set.of(); }

/** Names the step binds when it evaluates its deferred parameters, e.g. "response". */
default Set<String> deferredBindings() { return Set.of(); }
```

**In `ScenarioEngine.runStep`:**
1. Remove each deferred parameter from the map before `Interpolator.resolve`.
2. Resolve the rest as today.
3. Put each deferred parameter back as a `DeferredExpression`, or a `List<DeferredExpression>`
   when its value is a list.

`DeferredExpression` is a new engine type in `com.idm.test.engine.spi`:

```java
public interface DeferredExpression {
    /** The expression as written, e.g. "js:{ response.status === 200 }", for messages. */
    String source();
    /** Evaluates against the scenario's names at the step's start, plus {@code bindings}. */
    Object evaluate(Map<String, Object> bindings);
}
```

- **The engine's implementation** captures a copy of the scope as it stood when the step started,
  the expression body (`Interpolator.expressionBody`) and the scenario's `ExpressionEvaluator`.
- **`evaluate`** merges the bindings over the scope copy (a binding wins on a name clash), calls
  `ExpressionEvaluator.evaluate`, and converts the result to plain Java values, as `js:{}` does
  elsewhere. An `undefined` result raises the usual ITF-20 error.
- **`resolvedParams` in the results** record each deferred parameter as its source text, not its
  evaluated value.
- **Nothing else changes** for steps that declare no deferred parameters. The engine stays
  Spring-free.

## 6. Load-time validation

In `ScenarioValidator`, for a step whose type declares deferred parameters:
- **Each** deferred parameter's value, or each list item, must be a string that is a whole-value
  `js:{…}` expression. Otherwise:
  `step N (http.verify): 'check' must be js:{ … } expressions; got "…"`.
- **Each expression is compiled** (existing `ExpressionEvaluator.validate`).
- **Root names** (`Interpolator.references`) must be known names **or** one of the step's
  `deferredBindings()`. `response` is therefore valid inside `check` and unknown everywhere else,
  as today.
- **An empty list** is rejected: `'check' needs at least one condition`.

## 7. Outcomes on each attempt

| What happens | Result | Under `wait:` |
|---|---|---|
| every check returns `true` | success, `GET <url> passed <n> check(s) (status <s>)` | stops |
| a check returns `false` | failure, `http.verify GET <url>: check <i> of <n> is false: <source> (status <s>, body: <excerpt>)` | retried |
| a check throws (a type error, `undefined` from ITF-20, …) | failure, `http.verify GET <url>: check <i> of <n> failed: <error> (status <s>, body: <excerpt>)` | retried |
| a check returns a non-boolean (`"ACTIVE"`, `1`, a map, `null`) | **final** failure, `http.verify GET <url>: check <i> of <n> must return true or false, got <value>` | **stops at once** |
| transport error, or a 5xx/I/O error from the token server | failure, as `http.request` | retried |
| unknown target or no base-url; misconfigured or rejected credentials; unsupported method; non-text `body`; non-map `headers` | **final** failure | **stops at once** (ITF-26) |

- **`<excerpt>`** is the response body passed through the same rules as
  `TokenFailureText.safe(body, null)`: whitespace collapsed, capped at 300 characters with `…`.
  The helper is currently package-private in `idm-connector-rest`. The plan decides whether to
  make it public or reuse it through a small engine-side equivalent. The behaviour must be
  identical.
- **The failure summary** keeps ITF-26's suffixes (`(not retried: …)` or
  `(last observed value after retrying)`).

## 8. Registration and documentation

- **Registered** in `YamlScenarioTest.registry(...)`, which `RealEnvironmentScenarios` and
  `ContainerScenarios` reuse. The vocabulary becomes 14 step types.
- **`ScenarioNeeds`** already maps the `http.` prefix to REST targets, so guards 4 and 5 cover
  `http.verify` without change. A test confirms this.
- **`docs/reference/writing-scenarios.md`** gets a `#### \`http.verify\`` section under "Raw
  HTTP", with the standard table (the drift test requires it), the `response` table, the outcome
  table and one REST and one SOAP example. The SOAP example checks `response.body`, until ITF-25
  adds `response.xml`. "Waiting" lists `http.verify` among the assertions.
- **The README's** step table gains a row. The capability matrix's `http.request` row notes
  `http.verify`.

## 9. Testing

**`HttpVerifyStepsTest`**, against WireMock:
- a single check passes, and a list passes
- the first false check is named, with its index, status and excerpt, and later checks aren't
  evaluated
- `response.json` is parsed for `application/json` and `application/problem+json`, and is `null`
  for `text/plain` and for invalid JSON
- a throwing check is an ordinary failure, and a non-boolean result is final
- `response.xml` fails with the ITF-25 hint
- a `404` then a `200` (WireMock scenario states) under the engine's `wait:` passes on attempt 2
- `as:` binds the last response
- authentication, the author's own header, and the method/body/headers rules behave exactly as
  in `http.request` (one test each, reusing the fixtures)
- an unknown target or bad credentials under `wait:` stops after 1 attempt

**`ScenarioEngineTest`:**
- a deferred parameter reaches the step unevaluated, and evaluates with the scope plus the
  bindings
- it is re-evaluated each attempt, with different bindings
- the results record its source text
- a step with no deferred parameters is unaffected

**`ScenarioValidatorTest`**, or the existing validation tests:
- a plain-string check, a `${…}` check and an empty list are rejected
- `response` inside `check` is accepted, and outside it is rejected
- `http.verify` in `cleanup:` is rejected

**Also:**
- `ScenarioReferenceTest` passes with the new section, and fails without it.
- A falsification for each rule.
- The full suite, the corpus (0 FAILED), and `ContainerScenarios` if Docker is available.

## 10. Out of scope

- `response.xml` and the XML mapping (ITF-25).
- Checks on `rest.verify`.
- Checking a response captured earlier by `http.request`.
- Deferred parameters on any step other than `http.verify`.
