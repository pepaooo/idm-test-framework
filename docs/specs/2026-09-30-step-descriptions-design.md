# Step descriptions — design

**Ticket:** ITF-35. A prerequisite for the HTML report.

## 1. The problem

A scenario file has a top-level `description:`, but a step has no way to say why it exists:
- **`description:` on a step is rejected at load** as an unknown parameter.
- **On a `set:` step it silently binds a variable** named `description`, because `set:` treats
  every key as a name.
- **Authors use YAML comments,** which never reach the results.
- **The scenario-level `description`** is loaded into the model but is not written to the
  results either.

The results (`*-results.json`, JUnit failure messages) and the future HTML report therefore
can't explain a step to someone who hasn't read the YAML.

## 2. Decisions (jangulo, 2026-09-30)

1. **A reserved `description:` key on every step and cleanup entry,** like `as:` and `wait:`.
   It is reserved on `set:` too.
2. **Text only, with `${…}` references resolved like any parameter value.**

## 3. Authoring

```yaml
steps:
  - roles.grant:
      description: "Grant the Finance roles to ${user.userId}"
      user: "cn=${user.userId},ou=users,o=data"
      roles: [ ... ]

cleanup:
  - ldap.delete:
      description: Remove the test account
      dn: "${createdDn}"
```

- **Where:** any step and any cleanup entry, whatever its type, `set:` included. It is optional.
- **Load-time validation** (`ScenarioValidator`), with the usual location prefix
  (`<file>, step N (<type>): …` or `<file>, cleanup entry N (<type>): …`):
  - **not text** (a map, list, number or boolean): `'description' must be text`
  - **a whole-value `js:{…}` expression:** `'description' must be text; js:{ … } is not allowed here`
  - **an unknown `${name}` root:** rejected exactly as in any other parameter, with the same scope
    rules (a step sees names bound before it; a cleanup entry sees every name bound anywhere)
  - **on `set:`,** `description` is not bound as a variable; it is the step's description
- **No step type changes:** `description` is not part of any step's accepted parameters. The
  validator accepts it on every step the way it accepts `as` (and `wait` on assertions).

## 4. Running

- **The engine removes `description` from a step's parameters before the step runs,** as it
  does `as` and `wait`, and resolves it with the same scope as the step's parameters.
- **A step whose description can't be resolved at run time** fails the same way as a step whose
  parameter can't be resolved (the existing interpolation-failure path), and the message names
  `description`.
- **A cleanup entry:** when the entry runs, its description is resolved like its parameters.
  When the entry is SKIPPED, or its parameters fail to resolve, the description is recorded as
  written (unresolved), so the result still says what the entry was for.
- **`set:`:** the description is removed before the names are bound.

## 5. Results

- **`StepResult`** gains `String description`: the resolved text, or `null` when the step has
  none. It is not in `resolvedParams`.
- **`CleanupResult`** gains `String description`, with the same rule (or the text as written,
  per §4).
- **`ScenarioResult`** gains `String description`: the scenario's own top-level description, or
  `null`.
- **The JSON results** (`scenario-results.json`, `real-environment-results.json`,
  `multi-directory-results.json`) carry the new fields through the existing writer.
- **The failure summary** quotes a failing step's description after its type:
  ```
  <file>, step 3 (roles.grant: "Grant the Finance roles to test.user.1790…"): <message>
  ```
  and likewise for a FAILED cleanup entry:
  `<file>, cleanup entry 1 (ldap.delete: "Remove the test account"): <message>`.
  A step or entry without a description keeps today's format exactly.
- **Record constructors:** existing call sites (tests, `ScenarioSupport.buildTests`' load-failure
  result) keep compiling through convenience constructors that pass `null` descriptions, as
  `ScenarioResult` already does for `cleanup`.

## 6. Documentation

- **`docs/reference/writing-scenarios.md`:**
  - "Values" gains a **Describing steps** part: the key, text only, `${…}` allowed, reserved on
    `set:`, where it appears (results and failure messages)
  - "What validation rejects at load" gains the two `description` messages
  - "Results" says steps, cleanup entries and the scenario carry `description`
  - the worked example gains a few step descriptions (the drift test keeps checking it loads
    and validates)
- **The authoring guide:** one sentence pointing to it, in place of recommending comments for
  step intent, if it recommends them.
- **Two bundled scenarios** gain step and cleanup descriptions, so the corpus exercises the
  feature end to end.

## 7. Testing

**`ScenarioValidatorTest`** (or the existing validation tests):
- accepted on an action, an assertion, `set:` and a cleanup entry
- rejected when a map or number, and when `js:{…}`
- an unknown `${name}` in a description is rejected; a known one is accepted, including a name
  bound only later in `steps` when used in `cleanup:`
- on `set:`, `description` binds no variable (a later `${description}` is unknown)

**`ScenarioEngineTest`:**
- the step receives no `description` parameter
- the resolved description is in `StepResult.description` and absent from `resolvedParams`
- no description gives `null`
- a SKIPPED cleanup entry records its description as written
- the scenario description reaches `ScenarioResult.description`
- the failure summary quotes the description, and is unchanged without one

**`JsonResultWriterTest`** (if present): the three fields are written.

**Also:** `ScenarioReferenceTest` passes; a falsification for each rule; the full suite; the
corpus (0 FAILED); `multi-directory-results.json` (0 FAILED).

## 8. Out of scope

- The HTML report itself.
- Descriptions on fixtures.
- Using a description anywhere other than results and failure messages (for example logs).
