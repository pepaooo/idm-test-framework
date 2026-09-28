# Authoring reference for the YAML scenario tier — design

**Ticket:** ITF-10. The ticket was written when 2 of the step types existed. This spec re-scopes
it to today's vocabulary (13 step types), `cleanup:` (ITF-19), expressions (ITF-7, ITF-20,
ITF-21), `http.request` and authentication (ITF-22, ITF-23), final failures (ITF-26) and the
real-environment runner (ITF-12, ITF-30, ITF-31).

## 1. The problem

A consultant who doesn't write Java cannot author a scenario from the docs alone:
- **`docs/guides/scenario-authoring.md`** is a walkthrough. Its "Supported steps" table lists
  only `ldap.create`, `ldap.verify`, `http.request` and `set`.
- **The README** has a 13-row table of parameter names, with no captures, failure behaviour or
  examples.
- **Everything else** (what `as:` captures, which parameters are required, how a step fails)
  lives in the step classes.

## 2. Decisions (jangulo, 2026-09-28)

1. **A new reference page, `docs/reference/writing-scenarios.md`.** The authoring guide stays a
   short walkthrough and links to the reference for details.
2. **A drift test** checks each step's parameter table against the step registry, so a step or
   parameter can't be added or renamed without the page changing too. The page's worked example
   is also loaded and validated. The other examples are not executed or validated.

## 3. The page

Written for a consultant: no Java types, class names or engine internals in the prose. Sections,
in order:

1. **File anatomy:**
   - the keys `name`, `description`, `fixtures`, `steps` and `cleanup`
   - where files go (`scenarios/` for the embedded and container runners, `scenarios-real/` for a
     real lab)
   - that each file is one test
   - `-Dscenario` selection
2. **Values:**
   - literals
   - `${path}` references: property paths only
   - whole-value `js:{ … }` expressions: a sandbox, no Java or connectors, an `undefined`
     anywhere is an error, and root names are checked at load
   - `set:`
   - `as:`: a fresh name, binding the step's capture
   - scope: a name exists only after the step that binds it, except in `cleanup:`, which sees
     every name bound anywhere
3. **Fixtures:** the `random-user` generator and its exact keys (`userId`, `firstName`,
   `lastName`, `email`, `department`, `jobTitle`, `phoneNumber`, `employeeId`); literal-only
   fixtures; and `values` overriding generated keys without being evaluated.
4. **Step reference.** One `### \`<step>\`` section per registered step type, grouped under
   `##`-level headings (LDAP, database, SOAP roles and resources, REST, raw HTTP). Each section
   has:
   - a one-line purpose, and its **kind**: action, or assertion (accepts `wait:`)
   - **the parameter table** (§4), immediately after the purpose line
   - **captures:** what `as:` binds, or "nothing", in which case declaring `as:` fails the step
   - **failure:** what fails the step; for assertions, which failures end a `wait:` at once
     (ITF-26) and which are retried
   - **one short YAML example**
5. **Waiting:**
   - assertions only
   - `wait: true` uses the profile's defaults: `test` 60 s / 1 s, and default / `dev` / `prod`
     5 min / 5 s, unless `idm.test.executor.*` overrides them
   - `{timeout, poll}` with `s`/`m`/`h`
   - parameters are resolved once before polling
   - which failures end the wait early
6. **Cleanup:** the explicit `cleanup:` block and its three outcomes (SUCCEEDED, FAILED,
   SKIPPED), actions only, no `wait:`/`as:`, and the capture-then-reference idiom.
7. **Targets and configuration (pointers only):** named REST targets, their authentication, and
   the step's own `Authorization` header winning; database names; the SOAP URLs; LDAP DNs
   relative to the base DN, versus SOAP's full DNs; TLS. Each links to `configuration.md`, or to
   the real-environment README, instead of repeating it.
8. **What validation rejects at load**, each with a short sample message. For example: an unknown
   step type, a missing or unknown parameter, an unbound `${name}` or `js:` root, an unknown
   generator, a malformed `wait:`, `wait:`/`as:` in `cleanup:`, an assertion in `cleanup:`, and a
   non-compiling `js:{}`.
9. **Results:** where `scenario-results.json` and `real-environment-results.json` land, what a
   record contains (resolved parameters are unredacted, so keep secrets in target settings), and
   how to read a failure summary, including "(not retried …)" and "(last observed value after
   retrying)".
10. **A worked example:** one realistic multi-system scenario. It uses `random-user`, `set:`
    with a `js:{}` value, `ldap.create` with `as:`, `roles.grant`, `db.verify` with `wait:`,
    `rest.verify` with `wait:`, `ldap.verify` with `wait:`, and a `cleanup:` block. It is the
    **first `yaml` fenced block** after the section's heading, and it must be a complete
    scenario.

**Linking:** the README's reference section and its step table, the authoring guide's "Supported
steps" section, and `docs/README.md` link to the page.

**Moving content:** the authoring guide's `http.request` and authentication sections move into
the reference's `http.request`, `rest.request` and targets sections. The guide keeps a two-line
pointer. The guide's §4 table becomes a link to the reference.

## 4. The parameter table format

Every step section's first table has exactly this header row:

```markdown
| Parameter | Required | Meaning |
|---|---|---|
| `dn` | yes | … |
| `attributes` | no | … |
```

- **Parameter:** the parameter name in backticks.
- **Required:** exactly `yes` or `no`.
- **The rows are the step's required parameters plus its other accepted parameters, excluding
  `as` and `wait`.** Those two are documented once, in "Values" and "Waiting", not per step.
- **A rule that the parameter lists cannot express** goes in the Meaning cell, and both
  parameters are marked `no`. Example: `ldap.verify` takes exactly one of `expect` or `exists`.

## 5. The drift test

`ScenarioReferenceTest`, in `idm-test-scenarios` next to the other engine-adapter tests, with no
Spring context:

- **The registry** is built with `YamlScenarioTest.registry(...)`, using `null` connectors and an
  empty `RestProperties`. The step constructors only store their arguments, so this is enough to
  read names and parameter sets.
- **The parser** reads `docs/reference/writing-scenarios.md` relative to the repository root.
  The module runs from `idm-test-scenarios/`, so the path is `../docs/reference/…`. For every
  line matching `` ^### `(<step>)` ``, it takes the first table that follows before the next
  `###`/`##` heading, and collects `name → required?` from its rows.
- **Assertions:**
  1. **Coverage:** the set of documented step names equals the registry's names, `set` excluded.
     The message lists what is undocumented and what is documented but unregistered.
  2. **Required parameters:** for each step, the documented `yes` parameters equal
     `requiredParams()`.
  3. **All parameters:** the documented parameters equal
     `acceptedParams() ∪ requiredParams()`, minus `as` and `wait`.
  4. **The worked example:** the first `yaml` fence after the `## 10.` heading loads with
     `ScenarioLoader` and passes `ScenarioValidator` (the registry above, `SUPPORTED_GENERATORS`).
     It is not executed.
- **Messages** name the step and the differing parameter names, so a failure says exactly what to
  document.
- **Falsification:**
  - removing a step section fails assertion 1
  - removing a row or flipping `yes`/`no` fails 2 or 3
  - adding a bogus row fails 3
  - misspelling a parameter in the worked example fails 4

## 6. What does not change

No production code, configuration or step behaviour. `application.yml`, the corpus and every
existing test are untouched.

## 7. Out of scope

- Executing or validating the short per-step examples. Only the worked example is validated.
- Generating the page from code.
- Checking captures or failure prose.
- Translations.
