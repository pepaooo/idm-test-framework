# Scenario conversion findings

What converting the Java workflow scenarios to YAML revealed. Each section answers a design
question that was deliberately deferred until this evidence existed.

## 1. Does anything need a scenario-level `cleanup:` block? (ITF-11)

> **Superseded by ITF-19** — see docs/specs/2026-09-24-explicit-cleanup-design.md.

No. Across all 18 converted scenarios, every teardown obligation traces back to something a
step in that same scenario created, and the two existing mechanisms already cover it:

- **Automatic undo**, registered by the step itself when it succeeds — `ldap.create` (unbind on
  cleanup) in 12 of the 18 files, and the explicit `undo:` blocks on `db.execute` inserts
  (`provisioning-user-to-database.yaml`, `modification-user-in-database.yaml`,
  `modification-email-change.yaml`, `deprovisioning-user-disable.yaml`,
  `deprovisioning-grace-period.yaml`, `deprovisioning-access-revocation.yaml`,
  `roles-assign-with-database-access.yaml`, `integration-department-transfer.yaml`,
  `integration-new-hire-onboarding.yaml`) in the rest.
- **Explicit teardown steps**, written directly into the step list where the Java original also
  tore down what it made — `deprovisioning-user-delete.yaml` and
  `deprovisioning-related-data-cleanup.yaml` end with their own `ldap.delete`/`db.execute`
  DELETE steps; `roles-assign-multiple.yaml`, `roles-assign-to-ldap-group.yaml`,
  `roles-assign-with-database-access.yaml` and `integration-new-hire-onboarding.yaml` all end
  with an explicit `roles.revoke` (`roles.grant` registers no undo by design, so this is the
  only way to reverse it, and every scenario that grants a role also revokes it).

No scenario needed to tear down state it did not create, or state created by a step in another
scenario, or infrastructure outside any step's reach. `grep -rn "cleanup:" scenarios/*.yaml`
against all 18 files returns nothing, and none of the five task reports (1–6) flagged a
teardown gap the two mechanisms above couldn't close.

**Recommendation: close ITF-11.** The 18-scenario corpus never exercised the case it exists to
cover. If a future scenario genuinely needs it — teardown of shared fixture state that outlives
a single scenario, for instance — that is new evidence and a new ticket, not a reason to keep
this one open speculatively.

## 2. Does anything need a per-step `undo: false` opt-out?

> **Superseded by ITF-19** — see docs/specs/2026-09-24-explicit-cleanup-design.md.

No, and the feature does not exist to opt out of today: `ldap.create`'s `acceptedParams()` is
only `{dn, attributes}` — there is no `undo` parameter to set to `false`. Its undo (unbind the
DN) is unconditional whenever the create succeeds.

The one scenario built to test this exact mismatch is `deprovisioning-user-delete.yaml`. Its
Java original, `testUserDeleteWorkflow`, deliberately calls neither `trackLdapDn` nor
`trackDatabaseRecord` — the comment reads "Don't track for cleanup since we're testing
deletion" — because the whole point of the test is to delete the entry itself and then verify
it is gone. The YAML conversion cannot express that choice: `ldap.create` registers its
unbind-on-cleanup regardless, so after the scenario's own `ldap.delete` step removes the entry,
the engine's automatic undo runs `connector.delete(dn)` a second time against a DN that no
longer exists.

Task 5 verified what actually happens, with log evidence for this exact scenario's DN:

```
23:16:20.124 DEBUG EdirectoryConnector -- Deleting LDAP user: cn=test.user...   <- scenario's own ldap.delete step
23:16:20.124 INFO  EdirectoryConnector -- LDAP user deleted successfully: cn=test.user...
23:16:20.125 DEBUG EdirectoryConnector -- LDAP entry does not exist: cn=test.user...   <- ldap.verify exists:false
23:16:20.126 DEBUG EdirectoryConnector -- Deleting LDAP user: cn=test.user...   <- undo unbind, after the fact
23:16:20.127 INFO  EdirectoryConnector -- LDAP user deleted successfully: cn=test.user...
```

`LdapTemplate.unbind` on an absent DN completes silently against the embedded UnboundID server
and logs at INFO, not WARN or ERROR. The scenario's `success: true` result and step count are
unaffected — the only visible cost is one redundant "LDAP user deleted successfully" INFO line
per run.

**This did not cause a real problem.** It is deliberately not worked around anywhere in the
corpus. Unless a real directory (as opposed to the embedded test server) throws or logs a
warning on a double-delete of an already-absent DN — which is untested — there is no evidence
that `undo: false` is worth building. This should stay a documented, accepted rough edge rather
than a justification for new engine surface, unless a future scenario against a real eDirectory
proves otherwise.

## 3. Does `${...}` need list indexing?

**No evidence either way — the corpus never exercised this path.** Zero of the 20 scenario
files capture a `db.verify` result with `as:` at all (`grep -n "as:" scenarios/*.yaml` shows
five hits total: `roles.grant`'s DN list in four files, and `ldap.create`'s DN in
`user-provisioning.yaml` — none on `db.verify`). Every `db.verify` in the corpus asserts
against a single already-known row via `expect.row`, and none of the eighteen conversions ever
needed to read a query result back into a later step, so the question of whether
`js:{ rows[0].COLUMN }` is sufficient for that was never put to the test.

What can be said from reading the code rather than from the corpus: `DbVerifyStep.execute`
returns the raw `List<Map<String, Object>>` from `MultiDatabaseConnector.executeQuery` as its
capturable value, and the `row:` matcher in that same step compares against that map with a
comment noting it is "case-insensitive on column names" — i.e. `row: { status: ... }` matches a
column stored as `STATUS`. A hypothetical `js:{ rows[0].status }` reading the same capture would
not get that same leniency: JavaScript property access is case-sensitive, so it would need to
match whatever casing the JDBC driver/H2 actually returns the key as. That asymmetry is real in
the code, but no scenario in this conversion ever reached it, so whether it is a problem in
practice — and whether list indexing itself needs new interpolation syntax versus already being
expressible via `js:{ }` — has no data point from this exercise.

## 4. Does `expect:` need load-time validation?

**No conversion mistake surfaced that load-time validation would have caught earlier.** All 18
conversions across Tasks 2–6 passed on their first `scenario-results.json` check reported in
each task's report — none of the six reports describes a malformed `expect:` block written
during conversion, caught only at run time, that a load-time schema check would have flagged
sooner. The one genuine defect found in this exercise (the two WireMock stubs, §"Defects found"
below) was a pre-existing fixture problem, not an `expect:`-shape mistake in a converted
scenario, and it was caught by reading the fixtures, not by a failing assertion.

What the design's concern describes is confirmed as real by inspection, even without a
conversion mistake to point to: `ScenarioValidator` (290 lines) validates step-level shape —
unknown step type, missing required param, unresolved/forward-referenced `${...}`, malformed
`js:{ }` — but does not descend into a step's nested parameters, so it cannot see that an
`expect:` map is empty, a scalar, or missing both `rowCount`/`row`/`exists`. Three step types
hand-roll that guard at execution time instead, with four methods total:
`LdapVerifyStep.rejectMalformedAssertion`, `DbVerifyStep.rejectMalformedExpect`,
`RestVerifyStep.rejectMalformedExpect`, and `RestVerifyStep.rejectVacuousField` (a second,
recursive guard inside the REST one, for a nested vacuous body). This is duplicated logic
exactly as the design worried about — but this conversion is not the evidence that it caused a
real authoring mistake, because the eighteen files here were never authored blind; they were
built directly from working Java assertions, which is a different failure mode than a
consultant's typo. Whether load-time validation of `expect:` shape earns its cost should wait
for evidence from real (non-converted) scenario authoring, which this corpus cannot provide.

## 5. Does step reuse (`extends` / `include`) earn its place? (design §8.4)

Yes, the duplication is real and substantial — this is exactly the data §8.4 asked for.

**The `ldap.create` setup block.** The same four-attribute block —

```yaml
- ldap.create:
    dn: "cn=${user.userId},ou=users"
    attributes:
      cn: "${user.userId}"
      sn: "${user.lastName}"
      givenName: "${user.firstName}"
      mail: "${user.email}"
```

— appears **byte-for-byte identical in 12 of the 18 converted files**
(`deprovisioning-user-delete.yaml`, `deprovisioning-user-disable.yaml`,
`integration-department-transfer.yaml`, `integration-employee-termination.yaml`,
`integration-new-hire-onboarding.yaml`,
`modification-email-change.yaml`, `modification-user-in-ldap.yaml`,
`provisioning-minimal-user.yaml`, `provisioning-user-to-ldap.yaml`,
`roles-assign-multiple.yaml`, `roles-assign-to-ldap-group.yaml`,
`roles-assign-with-database-access.yaml`), verified by a literal string match against every
file, not by eye. Three more converted files don't match the exact block: `database-provisioning.yaml`'s
pre-existing `ldap.create` and `user-provisioning.yaml`'s pre-existing one create an LDAP entry
with extra or different attributes, and `modification-attribute-removal.yaml` carries the same
four attributes plus a fifth (`telephoneNumber`), so its block is a superset, not a byte-for-byte
match; every other converted scenario that touches LDAP uses this one verbatim.

**The `users`-table insert.** 12 of the 18 files insert into `users`, in three column-list
variants. The most common single statement —

```sql
INSERT INTO users (user_id, first_name, last_name, email, active) VALUES (?, ?, ?, ?, ?)
```

— is repeated **verbatim in 7 files** (`deprovisioning-access-revocation.yaml`,
`deprovisioning-grace-period.yaml`, `deprovisioning-related-data-cleanup.yaml`,
`deprovisioning-user-delete.yaml`, `deprovisioning-user-disable.yaml`,
`modification-email-change.yaml`, `roles-assign-with-database-access.yaml`); a
department+job_title 7-column variant repeats in 4 more (`integration-department-transfer.yaml`,
`integration-new-hire-onboarding.yaml`, `modification-user-in-database.yaml`,
`provisioning-user-to-database.yaml`); and a department-only 6-column variant appears once
(`integration-employee-termination.yaml`). The original estimate of "~8 files" undercounted
this slightly — it is 12 files across three variants, 7 of them sharing one statement exactly.

None of the twelve step types offers a setup/background/fixture mechanism to factor either
block out; every scenario file is fully self-contained by design (design §7.1: "one scenario
per file, four top-level keys"). This is a real, quantified maintenance cost — a directory
rename or an added required attribute on the base user shape would require editing the same
block in more than half the corpus.

**Recommendation:** this is the strongest single piece of evidence the conversion produced, and
it directly answers what §8.4 asked for. `extends`/`include` (or a richer fixture that also
creates the LDAP entry, folding the 13-file duplicate into `fixtures:`) is justified by this
corpus; the two blocks identified above are the concrete first targets.

## Vocabulary verdict

**Yes, the 8 step types the corpus actually exercised sufficed for all 18 conversions, with no
new step type invented — but the corpus never touched the other 4 of the 12.** `rest.request`,
`rest.verify`, `resources.grant` and `resources.revoke` are untouched by every conversion (see
"Parameters that went unused" below), so this exercise validates 8 of 12 step types, not all
twelve. For the 8 it did exercise, every one of the five task reports (2 through 6) states
explicitly that nothing new was needed, and the evidence is consistent across provisioning,
modification, role assignment, deprovisioning and multi-system scenarios — the last group is the
strongest single data point, since it forced LDAP, database and SOAP steps into single flows and
still expressed cleanly.

The only vocabulary change made anywhere in this exercise was Task 1's addition of `exists:` to
`ldap.verify`, to assert an entry is (or is not) present without needing an `expect:` map — the
capability `deprovisioning-user-delete.yaml` needed to assert a deleted DN is really gone, and
that `provisioning-minimal-user.yaml`/`provisioning-user-to-ldap.yaml`/
`integration-new-hire-onboarding.yaml` also use to assert an entry now exists.

**Parameters that went unused across the whole corpus:**
- `rest.request`, `rest.verify`, `resources.grant`, `resources.revoke` — none of the 18
  conversions touch REST or the resource-service step types at all, matching the design's own
  observation (§2) that no Java scenario ever used REST.
- `db.verify`'s `wait:` object form (`wait: { timeout: ..., poll: ... }`) — every converted
  scenario that waits uses the bare `wait: true` form and takes the configured defaults; only
  the pre-existing `user-provisioning.yaml` uses the explicit `{timeout: 30s, poll: 2s}` shape.
- `as:` on `db.verify` and `ldap.update` — never used in any converted scenario (see Question 3
  above).
- `set:` — used only in the pre-existing `user-provisioning.yaml`; none of the 18 conversions
  needed a derived value, because every value they need is either a fixture field or a literal
  matching what the Java original hardcoded.

**The one genuine gap, confirmed across five of the six task reports:** `db.execute` cannot
assert its own affected-row count. Its `acceptedParams()` is exactly `{database, statement,
params, undo}` — there is no assertion field. Every Java `UPDATE`/`DELETE` in the source asserts
`isEqualTo(1)` (or the loop count) on the rows affected; the DSL conversion carries no
equivalent for that specific check. Where the Java also re-queries afterward, a following
`db.verify` recovers the same end-state guarantee (this covers the large majority of cases —
provisioning, modification, deprovisioning-user-disable, deprovisioning-access-revocation,
deprovisioning-user-delete, both integration scenarios). Two scenarios have no such recovery
because the Java original itself never re-queries: `deprovisioning-grace-period.yaml`'s final
DELETE and all of `deprovisioning-related-data-cleanup.yaml` (which has no `db.verify` step at
all) convert their affected-row assertions to nothing — not a weaker check, but no check. This
is a faithful conversion of what the Java actually verifies, not a regression introduced by the
conversion, but it is a real, reproducible vocabulary gap in `db.execute` worth a small design
follow-up (e.g. an optional `expect: { rowsAffected: N }` on `db.execute` itself).

## Defects found in the Java tier

**Four vacuous or weak tests**, all in `idm-test-scenarios/src/test/java/com/idm/test/scenarios`:

1. **`testRoleAssignmentToLdapGroup`** (`RoleAssignmentWorkflowTest`) — its `waitUntil`
   predicate is a literal `return true; // Simulated success`, so
   `assertThat(roleProvisioned).isTrue()` asserts on a constant and passes unconditionally,
   regardless of whether any role or group membership exists. The YAML conversion
   (`roles-assign-to-ldap-group.yaml`) correctly drops this predicate rather than inventing a
   real check to replace it — there is nothing in the test environment (WireMock SOAP stub, no
   LDAP group write) for a real assertion to observe.
2. **`testRoleAssignmentFailureHandling`** (`RoleAssignmentWorkflowTest`) — its only assertion is
   nested `if (result.isFailure()) { assertThat(...); }`, so when the underlying operation
   *succeeds* (as it does against the WireMock stub), the test body asserts nothing at all. This
   is one of the two scenarios that stays in Java by design (the DSL has no expected-failure
   step type), so the conversion did not touch it, but the defect is real and worth fixing in
   the Java tier independent of this conversion.
3. **`testAttributeRemoval`** (`UserModificationWorkflowTest`) — asserts nothing at all: it
   creates a user, calls `update()` with `telephoneNumber: null`, assigns the result to a
   variable that is only ever logged, and ends with a comment that removal behavior "depends on
   the LDAP implementation." Converted as pure action (`ldap.create` → `ldap.update`) with no
   invented assertion, per Task 3's instruction and the brief's explicit guidance not to build a
   new absence-checking capability to compensate.
4. **`testRelatedDataCleanup`** (`UserDeprovisioningWorkflowTest`) — never runs a single
   `SELECT`; it asserts only the affected-row counts of two `DELETE` statements
   (`deletedAccess == 2`, `deletedUsers == 1`). The YAML conversion
   (`deprovisioning-related-data-cleanup.yaml`) is consequently five `db.execute` steps with no
   `db.verify` at all — the weakest-verified scenario in the corpus, purely because the Java
   source it was built from never checked end state either.

Plus one additional weak spot, not vacuous but misleading: **`testDepartmentTransferWorkflow`**
(`MultiSystemIntegrationTest`) logs `"✓ Department transfer verified in both systems"` at line
482, but its actual assertions (`assertThat(record.get("department"))`,
`assertThat(record.get("job_title"))`) are both against the database only — nothing in the
method reads LDAP back after the `ldap.update` to confirm the directory side. The YAML
conversion (`integration-department-transfer.yaml`) is honest about this: it has no `ldap.verify`
step after its `ldap.update`, exactly matching what the Java actually checks rather than what
its log message claims.

**Two malformed WireMock stubs**, fixed in Task 4: `role-assignment.json` and
`role-removal.json` both used the wrong SOAP namespace (`urn:idmprov` instead of the role
service's actual `http://www.novell.com/role/service`, confirmed against the JAXB-generated
types and `SoapStepsTest`'s own fixtures) and a plain-string `<result>SUCCESS</result>` where
the generated return type is `JAXBElement<DNStringArray>` — so before the fix, both
unmarshalled to zero DNs regardless of what the mock intended to return. Fixed to the
correctly-namespaced, `dnstring`/`dn`-nested shape. `get-user.json` has the identical defect but
was left as-is: no DSL step type calls the role service's `getUser` operation, so it has no
effect on anything converted or on `RoleAssignmentWorkflowTest`.

**One latent defect in the engine itself**, not the Java tier, surfaced while investigating the
stub fix: `roles.grant` (`RolesGrantStep.execute`) reports success and binds `as:` to an empty
list when the role service returns zero DNs — `SoapDns.of` returns `List.of()` rather than
`null` on an empty/absent result, and `ScenarioEngine.bind()` only downgrades a capture to a
failure when the value is `null` (confirmed by reading both), not when it is an empty
collection. This is caught today only because every one of the four scenarios that call
`roles.grant` follows it with a `roles.revoke`, whose own `assignments.isEmpty()` guard fails
loudly. If a scenario ever granted a role without a following revoke, a service returning zero
DNs would pass silently. Worth a small follow-up (treat an empty capturable collection the same
as null in `bind()`, or have `RolesGrantStep` fail on an empty DN list itself), independent of
this conversion task's scope.
