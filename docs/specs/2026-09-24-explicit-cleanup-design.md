# Explicit cleanup instead of inferred undo — design

**Ticket:** ITF-19 (subtask of ITF-4). **Supersedes:** ITF-11 (closed), and
`docs/specs/2026-09-13-declarative-scenario-engine-design.md` §7.6.

## 1. The decision

The engine will no longer derive teardown by itself. Today three steps quietly schedule an
inverse action when they succeed:

- `ldap.create` → the entry is deleted afterwards
- `ldap.update` → the prior attribute values are restored
- `resources.grant` → the resource is revoked

A fourth step, `db.execute`, runs an author-written `undo:` statement. After this change, an
author who wants teardown writes it explicitly as ordinary actions in a `cleanup:` block, as
OpenText Identity Validator does. **Nothing happens that the scenario's YAML does not say.**

This is jangulo's requirement, decided 2026-09-24. ITF-11 had deferred a `cleanup:` block for
lack of a scenario that needed it; a user requirement is that evidence.

## 2. The model

- **No step registers an undo.** `LdapCreateStep`, `LdapUpdateStep` and `ResourcesGrantStep`
  lose their undo lambdas.
- **`db.execute` loses its `undo` parameter.** A scenario that still writes `undo:` fails to load
  with the loader's existing unknown-parameter error, so a missed migration is loud.
- **The engine loses its undo machinery.** `StepOutcome`'s undo component, the undo deque, and
  `ScenarioEngine.unwind` are deleted. `StepOutcome.success(String, Runnable, Object)` becomes
  `success(String message, Object capture)`. The earlier rule that there be *no*
  `success(String, Object)`, which existed to force every action step to think about undo, no
  longer has a reason to exist.
- **A new optional top-level `cleanup:` block** is a list of steps with the same shape as
  `steps:`. `Scenario` gains a fifth component, `List<StepSpec> cleanup`, which defaults to empty.

```yaml
steps:
  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      attributes: { cn: "${user.userId}", sn: "${user.lastName}" }
      as: createdDn
  - db.execute:
      database: test-db
      statement: "INSERT INTO users (user_id, ...) VALUES (?, ...)"
      params: ["${user.userId}", ...]

cleanup:
  - ldap.delete:
      dn: "${createdDn}"        # skipped if ldap.create never ran
  - db.execute:
      database: test-db
      statement: "DELETE FROM users WHERE user_id = ?"
      params: ["${user.userId}"]
```

## 3. Running cleanup

- **It always runs,** after the steps, whether they passed or failed. It runs in declaration
  order, and every entry is attempted even when an earlier one fails, so teardown does as much as
  it can.
- **Each entry ends in exactly one of three outcomes:**
  - **SKIPPED:** its parameters reference a *root name* that is not bound in scope, because the
    step that would have bound it (with `as:`) never ran. This is the conditional-cleanup idiom:
    capture with `as:`, and reference the capture in cleanup.
  - **SUCCEEDED:** the step returned success.
  - **FAILED:** the step returned failure, or resolving its parameters failed for any reason
    *other than* an unbound root name. For example, `${user.noSuchField}` on a bound `user` is a
    failure. That way a typo can never pass as a skip.
- **Entries run once.** `wait:` is not allowed, so there are no retries. `as:` is not allowed,
  because nothing runs after cleanup to use a capture. No entry registers anything.
- **Entries see the scenario's full scope:** fixtures plus every capture bound during the steps.

## 4. Result and failure precedence

- A scenario **passes** only if every step passed **and** no cleanup entry FAILED. SKIPPED never
  fails anything.
- **The steps' own failure always leads.** If a step failed, the failure summary starts with that
  step, and any cleanup failures are listed after it. If every step passed but a cleanup entry
  failed, the scenario fails and the summary names the cleanup entry.
- This reverses ITF-11's log-and-continue decision, deliberately. Once cleanup is something the
  author wrote, a cleanup that fails is a defect in the scenario, not engine housekeeping. It also
  keeps a real environment honest: a teardown that didn't happen is visible and red.

## 5. Reporting

- A new record, `CleanupResult(int index, String type, Map<String,Object> resolvedParams,
  CleanupStatus status, long durationMillis, String message)`, where
  `CleanupStatus { SUCCEEDED, FAILED, SKIPPED }`. For SKIPPED, `resolvedParams` is empty and the
  message names the unbound reference.
- `ScenarioResult` gains `List<CleanupResult> cleanup`, placed after `steps`. `JsonResultWriter`
  writes it.
- `failureSummary()` follows §4's precedence.

## 6. Load-time validation

`ScenarioValidator` checks `cleanup:` entries as it checks steps: a known step type, the
required parameters present, and only accepted parameters. There are three differences:

1. **Actions only.** An entry whose type is retryable (`*.verify`) is rejected, because an
   assertion undoes nothing.
2. **`wait:` and `as:` are rejected** on cleanup entries.
3. **References resolve against the full set of names:** the fixtures, plus every `as:` in
   `steps:`. Steps use the progressive set, where each step sees only names bound before it;
   cleanup sees all of them, because it runs after everything. A reference to a name bound
   *nowhere* is still a load-time error, so the runtime SKIPPED case is exactly "bound somewhere,
   but not this time".

## 7. Migrating the corpus and fixtures

Measured over the 20 corpus files in `idm-test-scenarios/src/test/resources/scenarios/`:

- **15 files use `ldap.create`.** In 3 of them the entry is already deleted by the scenario's own
  steps (`database-provisioning.yaml`, `deprovisioning-user-delete.yaml` and
  `integration-employee-termination.yaml`). Those 3 get **no** LDAP cleanup: a cleanup
  `ldap.delete` would find nothing and fail the scenario. The other **12** get `as:` on the create
  and a `cleanup: ldap.delete` on the capture.
- **10 files use `db.execute undo:`.** Each `undo:` statement moves into `cleanup:` as a
  `db.execute` with the same statement and parameters. A `DELETE ... WHERE` whose rows the steps
  already removed affects 0 rows. The plan must confirm that `db.execute` treats 0 affected rows
  as success. If it doesn't, stop and report; don't change `db.execute`'s semantics silently.
- **`ldap.update`** needs no restoring in the corpus. Every file that updates an entry either
  deletes that entry in cleanup or in its own steps.
- **`resources.grant`** is not used by the corpus.
- **The fixtures:**
  - `scenarios-vocabulary-fixture/full-vocabulary.yaml` must still validate, and should add a
    `cleanup:` block so the vocabulary fixture covers it.
  - `scenarios-validation-fixture/valid-user.yaml` gets its cleanup.
  - `broken-wait.yaml` stays broken, for its original reason.

**Acceptance:** all 20 scenarios pass through `YamlScenarioTest` (embedded) and through
`ContainerScenarios` (OpenLDAP, PostgreSQL and WireMock). No scenario reports a FAILED cleanup
entry, which is checked in the JSON.

**Known consequence, accepted:** a scenario that deletes its own entry in `steps:` and fails
*before* that delete leaves the entry behind, because it has no cleanup for it. This is
accepted: the embedded and container stores are disposable, and a real-environment author can
choose the `as:` idiom instead.

## 8. What does not change

- The Java workflow tests, which call connectors directly.
- `RealEnvironmentScenarios`, `ContainerScenarios` and their guards 1–3. They just run the
  engine. The one exception is guard 4: `RealEnvironmentScenarios.restTargets` **must** also
  collect targets from `cleanup:` entries. Otherwise a `rest.request` in cleanup against a
  placeholder target would slip past the guard. That change comes with a test.
- `ldap.delete`'s existence check, which is why a stray cleanup delete fails loudly.
- The rule that the engine never imports Spring (`NoSpringDependencyTest`).

## 9. Testing

**Engine unit tests** (`ScenarioEngineTest` and the step tests):
- cleanup runs after the steps on pass and on fail
- declaration order
- all entries are attempted after one fails
- SKIPPED on an unbound capture
- FAILED on an unknown field of a bound name
- scenario failure precedence: a step failure leads, and a cleanup failure turns only a passing
  scenario red
- SKIPPED never fails a scenario
- no step registers an undo any more: `ldap.create` then scenario end leaves the entry, asserted
  against the connector stub
- the `CleanupResult` shape and the JSON output

**Loader and validator tests:**
- `cleanup:` is parsed and defaults to empty
- a verify step in cleanup is rejected, and so are `wait:` and `as:`
- a reference to a name bound only in a later step is accepted in cleanup
- a reference to a name bound nowhere is rejected
- `db.execute` with `undo:` is rejected

Every refusal is falsification-tested.

**Live:** the full default suite, with `YamlScenarioTest` passing 20/20, and `ContainerScenarios`
passing 20/20.

## 10. Documentation

- `docs/specs/2026-09-13-declarative-scenario-engine-design.md` §7.6 gets a note that it is
  superseded by this spec.
- `docs/reference/specification.md`: the two-tiers section's teardown description, if it
  describes inferred undo.
- `docs/reference/scenario-conversion-findings.md` §1 and §2 get a note pointing here.
- `scenarios-real/README.md` gets a short cleanup section with the `as:` idiom, because
  real-environment authors need it most.

## 11. Out of scope

- Per-step `undo: true` sugar, or any inference. That was rejected by the decision in §1.
- A scenario-level setup block.
- Changing `ldap.delete` to tolerate missing entries.
- Retrying cleanup entries.
