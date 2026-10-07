# List helpers for http.verify checks, and database recipes — design

**Ticket:** ITF-41. **Related:** ITF-38, ITF-39 (`ldap.verify` set matchers).

## 1. The problem

ITF-41 asked for set matchers (contains, excludes, an exact set in any order) in `db.verify` and
`http.verify`.

- **`db.verify`:** the query can already express every one of these (`IN`, `COUNT`, `SUM(CASE …)`,
  `LIKE`), in the database's own comparison and collation rules, without fetching rows to compare
  them in Java. What is missing is guidance.
- **`http.verify`:** `check:` is JavaScript, so `response.json.groups.includes('HR')` works, but
  checking several values, excluding several, or an exact set in any order is verbose, and a failing
  check only says `check 1 of 1 is false: <source>` — never which value was missing.

## 2. Decisions (jangulo, 2026-10-07)

1. **`db.verify`: no code change.** The assertion belongs in the SQL; the docs gain a recipes
   section.
2. **`http.verify`: helper functions inside checks** (`containsAll`, `excludesAll`, `sameMembers`),
   rather than a declarative `expect:` or `rest.verify` matchers.
3. **Values compare as text, case-sensitive** (`String(a) === String(b)`); objects by content.
4. **Helpers implemented in Java, called from expressions, not counted against the instruction
   budget; notes collected in Java** and returned to the step. (revised after final review, jangulo 2026-10-07) The first version was a
   JavaScript prelude, which ran out of budget at about 480 strings or 110 objects.

## 3. The helpers

Available in every `js:{ … }` expression (checks, `set:`, parameters):

| Function | True when |
|---|---|
| `containsAll(list, values)` | every one of `values` is in `list` |
| `excludesAll(list, values)` | none of `values` is in `list` |
| `sameMembers(list, values)` | both hold the same values, in any order; duplicates ignored |

- **Arguments:** each may be a single value or an array. A `null` or `undefined` list is an empty
  list, so `excludesAll(response.json.groups, 'X')` passes when `groups` is absent, and
  `containsAll(response.xml.user.role, 'HR')` works whether `role` occurs once or several times.
  A missing, `null` or `undefined` *values* argument is an error (revised after final review, jangulo 2026-10-07): the helper
  throws `containsAll: the values to look for are undefined` (likewise `excludesAll:`,
  `sameMembers:`), which fails the expression with the usual `could not evaluate js:{ … }: …`
  (in `http.verify`, `check N of M failed: …`). Otherwise a misspelled `expected.grups` would make
  `containsAll` and `excludesAll` pass without comparing anything.
- **Equality:** two values are equal when their canonical text is equal:
  - a primitive's text is `String(value)` (`42` equals `'42'`, `true` equals `'true'`; case matters)
  - an object's text is its keys sorted, each with its value's canonical text; an array's is its
    elements' canonical text in order — so `{id: 1, role: 'HR'}` equals `{role: 'HR', id: '1'}`
  - inside an array or object, a primitive's text is JSON-quoted and composites carry a marker, so
    `['a,b']` differs from `['a', 'b']` and `[1]` from `'[1]'`, while `1` still equals `'1'` at every
    depth; prototype names (`constructor`, `__proto__`) are ordinary values
- **Failure notes:** when a helper returns `false` it records one note:
  - `containsAll: missing [VPN]`
  - `excludesAll: found [Finance]`
  - `sameMembers: missing [VPN]; unexpected [Finance]` (either part left out when empty)

  Each list shows at most 10 values, then `… N more`. Top-level strings show as themselves,
  nested strings JSON-quoted, objects as `{key: value}`; values under secret-looking keys
  (`Secrets.isSecret`) show as `***` (revised after final review, jangulo 2026-10-07). Masking affects the note only, not the
  comparison.
- **Reserved names:** a fixture, `set:` or `as:` named `containsAll`, `excludesAll` or
  `sameMembers` is rejected at validation: `'containsAll' is a built-in helper name; choose another`.

## 4. Components

- **`ExpressionEvaluator`** (engine `expr`):
  - **`ListHelpers`** (package-private, `expr`) (revised after final review, jangulo 2026-10-07): per evaluation, defines three
    Rhino function objects (`BaseFunction`) named `containsAll`, `excludesAll`, `sameMembers` in the
    evaluation scope, after the scenario variables, and keeps that evaluation's notes in a Java list.
    The functions work on the JavaScript values directly and expose no Java object or class, so the
    sandbox (`ClassShutter` denies every class) is unchanged. Their work is not counted against the
    instruction budget (the expression calling them still is); instead one evaluation's helper calls
    may visit at most 1,000,000 values, nested at most 100 levels, or the helper throws. There is no
    `__checkNotes` variable, so an expression can neither fake nor break the notes
  - `public Explained evaluateExplained(String expression, Map<String, Object> variables)` returns
    `record Explained(Object value, List<String> notes)` (notes in call order; empty when none)
  - `evaluate(...)` is unchanged in behaviour and ignores notes
  - `validate(...)` accepts expressions that call the helpers
- **`DeferredExpression`** (engine `spi`) gains `Explained explain(Map<String, Object> bindings)`;
  **`ScopedExpression`** implements it with `evaluateExplained`. The existing `evaluate` stays.
- **`JsReferences`:** the three names count as standard globals, so they are never reported as
  undeclared references.
- **`ScenarioValidator`:** rejects the reserved names as declared names (fixtures, `set:`, `as:`).
- **`HttpVerifyStep`:** evaluates each check with `explain`. When a check returns `false` and notes
  were recorded, the failure message inserts ` — ` plus the notes joined by `; ` after the check's
  source, before the existing status/body context and XML hint:
  `http.verify GET <url>: check 2 of 3 is false: js:{ containsAll(response.json.groups, ['HR','VPN']) } — containsAll: missing [VPN] (status 200, body: …)`.
  The notes pass through `TokenFailureText.safe` like the rest of the check text. A check that is
  `false` without notes, or `true`, produces exactly today's message.

## 5. Documentation

- **`docs/reference/writing-scenarios.md` §`http.verify`:** a "Checking lists" subsection — the
  three helpers, single value or list, text equality, objects by content, the notes, the reserved
  names, a JSON example and an XML example.
- **§`db.verify`:** a "Checking several rows" subsection with recipes and the reason (the database
  compares with its own rules, in one query):
  - has READ and WRITE (others allowed): `COUNT(DISTINCT permission) … WHERE permission IN ('READ','WRITE')` →
    `row: { n: 2 }`
  - lacks ADMIN: `… WHERE permission = 'ADMIN'` → `rowCount: 0`
  - exactly READ and WRITE: `COUNT(DISTINCT permission) AS total, COUNT(DISTINCT CASE WHEN permission IN ('READ','WRITE') THEN permission END) AS wanted` → `row: { total: 2, wanted: 2 }` (`DISTINCT` so duplicate rows cannot pass; revised after final review, jangulo 2026-10-07)
  - a count: `COUNT(*) AS n` → `row: { n: 3 }`
  - a pattern: `WHERE mail LIKE 'jdoe@%'` → `rowCount: 1`
  - column aliases are case-insensitive in `row:` matching, as today
- **The capability matrix:** the helpers and the db recipes (ITF-41).

## 6. Testing

**`ExpressionEvaluatorTest`:**
- each helper true and false; single values and arrays on either side; `null`/`undefined`
- `42` vs `'42'`; case-sensitivity; objects in different key order; nested arrays; duplicates in
  `sameMembers`
- the notes' exact text, the 10-value cap with `… N more`, notes empty for a true result, notes not
  leaking from one evaluation into the next
- `evaluate` still returns plain values; the sandbox still blocks Java classes; the helpers are not
  counted against the budget, but an expression looping over them is; 5,000 strings and 1,000
  objects finish at the default budget well under a second; a missing values argument is an error;
  secret-looking keys are masked in notes (revised after final review, jangulo 2026-10-07)

**`JsReferencesTest` / `ScenarioValidatorTest`:** a check calling the helpers validates with no
undeclared-name error; a fixture, `set:` or `as:` named like a helper is rejected with the message.

**`HttpVerifyStepsTest`** (WireMock):
- `containsAll` on a JSON array passes, and fails with `— containsAll: missing [VPN]` in the message
- `sameMembers` failing shows `missing` and `unexpected`
- an XML repeated element, and a single occurrence, with `containsAll`
- a plain `false` check without helpers keeps today's message exactly

**Also:** the full suite and the corpus (0 FAILED).

## 7. Out of scope

- `db.verify` matchers (decided against; recipes instead).
- Matchers in `rest.verify`'s `expect.body`; a declarative `expect:` on `http.verify`.
- Case-insensitive options for the helpers (lower-case in the check instead).
