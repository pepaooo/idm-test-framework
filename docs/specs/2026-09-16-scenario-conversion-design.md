# Converting the Java scenarios to YAML — design

**Ticket:** ITF-5. **Supersedes on one point:** the scenario count in ITF-5 and in
`docs/specs/2026-09-13-declarative-scenario-engine-design.md` §8.3, both of which say 27.

## 1. Purpose

Convert the existing Java workflow scenarios to YAML, primarily as the engine's acceptance
corpus rather than as migration. If they express cleanly in twelve step types, the vocabulary
is proven sufficient by construction. The ones that resist are the most valuable output: they
define what the Java tier is for.

**The honest limit, restated.** These scenarios are fictional. They simulate IdM by writing
directly to embedded LDAP and H2, and validate no real system. Converting them produces a
corpus that exercises the engine well and still tests no IdM. The conversions stay labelled as
examples; real scenarios get written fresh when the framework is pointed at a real environment.
They should not be polished beyond what the engine needs.

## 2. The corrected inventory

There are **20 workflow scenarios, not 27**, across five files:

| File | Scenarios |
|---|---|
| `UserDeprovisioningWorkflowTest` | 5 |
| `UserModificationWorkflowTest` | 4 |
| `RoleAssignmentWorkflowTest` | 4 |
| `UserProvisioningWorkflowTest` | 4 |
| `MultiSystemIntegrationTest` | 3 |

`ConfigurationTest` holds the other 8 tests and is **out of scope**. It asserts that Spring
configuration properties load — LDAP URLs, pool sizes, timeouts. That is not an IdM workflow,
there is no step type for "assert a config property loaded", and there should not be one.

Of the 20:

- **17 convert with today's twelve step types.**
- **1 needs a new capability** — `testUserDeleteWorkflow` asserts an LDAP entry is gone after
  deletion, which the DSL cannot express. See §3. Note the database half of that same scenario
  needs nothing new: absence of a row is already `db.verify: expect: { rowCount: 0 }`.
- **2 genuinely resist** and stay in Java:
  - `testProvisioningTimeout` asserts that a wait **times out**. The engine treats a timed-out
    wait as a failure, correctly; there is no way to say "I expect this to time out", and
    adding one would mean a step whose failure is success.
  - `testRoleAssignmentFailureHandling` asserts that an operation against a non-existent user
    fails. Same shape: the DSL has no expected-failure mechanism.

  Both test engine and connector behaviour rather than an IdM workflow, which is exactly the
  boundary §8.3 predicted the conversion would reveal. They become the documented examples of
  what the Java tier is for.

**An earlier count of five "resistant" scenarios was wrong.** It came from grepping for
`isFalse()` / `isFailure()`, which also matches `if (result.isFailure()) return false;` inside
a `waitUntil` predicate — poll control flow, not an assertion. `UserModificationWorkflowTest`
and `MultiSystemIntegrationTest` convert normally.

## 3. New capability: `exists:` on `ldap.verify`

```yaml
- ldap.verify:
    dn: "cn=${user.userId},ou=users"
    exists: false
    wait: { timeout: 30s, poll: 2s }
```

`exists: false` asserts the entry is gone; `exists: true` asserts it is there without asserting
anything about its attributes.

**Why this earns its place.** Verifying de-provisioning — "the account is actually gone" — is
among the most important things an IdM test does, and it is already expressible for REST
(`rest.verify: expect: { status: 404 }`). Today you can assert a REST resource is gone but not
an eDirectory entry. That asymmetry is a hole, not a scenario that deserves to stay in Java.
The `exists: true` form comes free and covers "wait until the entry appears", which currently
requires naming an arbitrary attribute.

**Why `exists:` rather than `absent:`.** `absent: true` reads well but `absent: false` is a
double negative a non-Java author will misread. `exists:` reads plainly in both directions.

**Why a sibling of `dn` rather than a key inside `expect`.** For `db.verify` and `rest.verify`,
reserved keys live inside `expect` because that block has structure — `rowCount`/`row`,
`status`/`body`. But `ldap.verify`'s `expect` *is* the user-data namespace: a flat map of
attribute names to values. A reserved key inside it would collide with an attribute of the same
name, violating the project rule that reserved keys never share a namespace with user data
(design §7.3).

**Exactly one of `expect` or `exists` must be supplied.** Both is ambiguous — `expect` already
implies existence. Neither verifies nothing. This makes `expect` optional on the step, which is
precisely the shape that has produced seven vacuous-assertion defects in this project, so the
guard is strict in both directions, checked before any I/O, and carries falsification tests:
with the guard removed, each test must fail.

Implementation is small. `EdirectoryConnector.exists(String dn)` already exists —
`LdapDeleteStep` uses it. `ldap.verify` is already retryable, so `wait` works unchanged, which
is what asynchronous de-provisioning needs.

**Vocabulary stays at twelve.** This is a parameter on an existing type, not a thirteenth type.

## 4. Structure of the conversion

**One flat directory.** The converted files join `idm-test-scenarios/src/test/resources/scenarios/`
with prefixed names — `provisioning-user-to-ldap.yaml`, `deprovisioning-user-delete.yaml`.
`YamlScenarioTest.scenarioFiles()` uses `Files.list`, which is non-recursive, so subdirectories
mirroring the Java packages would mean changing the loader for cosmetics. Prefixes group them
for free.

**The Java scenarios stay.** This is a corpus, not a migration. The two tiers test different
things: the Java tests drive the connectors directly, the YAML ones drive the engine driving
those connectors. The accepted cost is that ~18 scenarios are covered twice and the suite grows
by 18 executing scenarios.

**Every converted scenario executes.** The infrastructure is already in place, verified:

| | |
|---|---|
| LDAP | embedded server, wired through `@DynamicPropertySource` into `idm.test.ldap.*` |
| Database | H2 with `schema.sql` (`users`, `user_access`, `audit_log`) applied by `TestSchemaInitializer` |
| SOAP | WireMock on a dynamic port, stubs for `role-assignment`, `role-removal`, `get-user` |
| REST | not used by any existing scenario, so nothing is needed |

This is the wall ITF-9's Task 7 could not get past, and it is not present here.

**Two tidy-ups in code being touched.** ITF-8 added an ad-hoc `CREATE TABLE sap_users` inside
`YamlScenarioTest`, written before `schema.sql` was noticed; it belongs in `schema.sql` with the
other tables. And `scenarios-vocabulary-fixture/full-vocabulary.yaml` from ITF-9 becomes largely
redundant once real scenarios cover the same vocabulary — keep it only if it still covers
something they do not.

## 5. Fictions in the Java suite

Converting forces assertions into the open, because the DSL cannot express a pretend one. Two
exist, both in `RoleAssignmentWorkflowTest`:

1. **`testRoleAssignmentToLdapGroup`** — its `waitUntil` predicate is a literal
   `return true; // Simulated success`, and the test then asserts that result is true. It
   verifies nothing.
2. **`testRoleAssignmentFailureHandling`** — its assertion is inside `if (result.isFailure())`,
   so when the operation succeeds the test asserts nothing and passes.

**How to handle them.** Each is a finding to record, not something to paper over. Where a real
assertion is available, write it — for (1), an `ldap.verify` against the role group. Where it is
not, drop the step and record that the original verified nothing. Some converted scenarios will
therefore be *shorter* than their Java originals, and that is the correct outcome rather than a
loss of coverage: there was no coverage to lose.

This is the same defect class the engine has now closed seven times, found in the Java tier.

## 6. Evidence this conversion must produce

Five design questions were deliberately deferred to this work, each because answering it
earlier would mean guessing at an API before a real scenario needed it. Record each answer with
the specific scenarios that motivate it.

1. **Scenario-level `cleanup:` block (ITF-11).** Does any scenario tear down something no step
   created? The engine already auto-undoes what a step did itself. If none does, close ITF-11
   rather than build it.
2. **Per-step `undo: false` opt-out.** Does any scenario need to suppress an inferred undo?
3. **List indexing in `${...}`.** A `db.verify` capture is a row list, reachable only from
   `js:{ rows[0].STATUS }` and case-sensitively there, while the `row:` matcher is
   case-insensitive. Do real scenarios hit this often enough to justify indexing?
4. **Load-time validation of `expect:` contents.** Design §7.7 wants load-time validation, but
   `ScenarioValidator` is step-type-driven and cannot inspect nested parameters, so three step
   types hand-roll the same guard. Does the corpus argue for a validation hook on `StepType`?
5. **Step reuse (`extends` / `include`), design §8.4.** How badly does duplicated setup hurt
   across the whole corpus?

## 7. Specification amendments

Two, both in scope here.

**The "no orchestration layers" principle appears in two files, worded differently.** Both
contradict the engine and both need amending.

`docs/reference/specification.md:99`, under "What We're NOT Doing":

> ❌ Orchestration layers hiding workflow logic

`CLAUDE.md:24`, under "What We DON'T DO":

> ❌ Orchestration layers that hide test logic (tests must be explicit)

A declarative engine is an orchestration layer. In each file, scope the bullet to the Java
tier, and in `specification.md` add a section describing the two tiers and the guarantee the
declarative tier offers in exchange: a scenario's step list is its complete execution order,
and expressions may compute values but never act. `CLAUDE.md` gets the scoping only, with a
pointer to that section — it is a day-to-day guide, not the architecture record.

**Design §7.1** shows `bodyFrom: user` as a bare name. It is `bodyFrom: "${user}"`. Decided in
ITF-9: a step never receives scenario scope, so a bare name is unresolvable by the step, and a
reference inherits the validator's load-time forward-reference checking that a bare name would
bypass.

**The count of 27 appears four times in the design spec** — §2 ("The 27 existing scenario
tests use exactly nine distinct operations"), §7.4, §8.3 and its following paragraph. Correct
all four to 20. Note §2's companion claim — nine distinct operations with per-operation call
site counts — was measured against that wrong population; re-measure it over the 20 rather
than assuming the operation counts survive the correction.

## 8. Testing

The converted scenarios *are* the test — each runs as its own JUnit dynamic test through the
existing `YamlScenarioTest` `@TestFactory`, against the same embedded infrastructure. No new
test harness.

The `exists:` capability gets unit coverage in `LdapStepsTest` alongside the other LDAP step
tests: entry present with `exists: true`, entry absent with `exists: false`, both failing
directions, `expect` and `exists` supplied together, and neither supplied. Each guard test is
falsified — with the guard removed it must fail.

A conversion is correct when the YAML scenario passes against the same infrastructure the Java
original used, and its steps correspond to the original's assertions. Where they do not
correspond, §5 applies: record the difference.

## 9. Out of scope

- `ConfigurationTest` (§2).
- An expected-failure mechanism. Only `testProvisioningTimeout` and
  `testRoleAssignmentFailureHandling` would use it, both test engine rather than IdM behaviour,
  and "a step whose failure is success" is a large semantic addition to a DSL whose guarantee is
  that a step list is its execution order.
- Deleting the Java scenarios (§4).
- Acting on the §6 evidence. This work *produces* the evidence; each answer becomes its own
  ticket or closes an existing one.
