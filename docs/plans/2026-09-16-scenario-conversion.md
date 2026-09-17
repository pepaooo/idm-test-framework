# Scenario Conversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the 18 convertible Java workflow scenarios to YAML as the engine's acceptance corpus, add the one capability they need, and produce the evidence five deferred design questions are waiting on.

**Architecture:** Each converted scenario becomes a YAML file in the existing `scenarios/` directory and runs as its own JUnit dynamic test through the existing `YamlScenarioTest` `@TestFactory`. No new test harness. One engine change comes first — `exists:` on `ldap.verify` — because one conversion cannot be written without it.

**Tech Stack:** Java 21, Maven multi-module, JUnit 5, AssertJ, embedded UnboundID LDAP, H2, WireMock.

**Ticket:** ITF-5. **Spec:** `docs/specs/2026-09-16-scenario-conversion-design.md`.

## Global Constraints

- `idm-test-engine/src/main` must **never** import `org.springframework`. `NoSpringDependencyTest` enforces this by walking the source tree.
- `StepType` has a **single** abstract method: `StepOutcome execute(Map<String, Object> params, boolean captureRequested)`. It must **never throw** — a failure is a returned `StepOutcome.failure(...)`.
- `StepOutcome` factories: `success(String)`, `success(String, Runnable, Object)`, `failure(String)`. There is deliberately no `success(String, Object)`.
- Reserved keys never share a namespace with user data. `ldap.verify`'s `expect` **is** the user-data namespace (a flat map of attribute names), so `exists` is a sibling of `dn`, never a key inside `expect`.
- **A verification step that reports success having compared nothing is the worst defect possible in this codebase.** It has been found seven times. Any guard added here is checked before I/O and carries a falsification test: with the guard removed, the test must fail.
- Java 21. No Lombok. Prefer records and pattern matching.
- Every Maven command naming a module with `-pl` **must** also pass `-am`.
- Run a single test class with `-Dsurefire.failIfNoSpecifiedTests=false`.
- A run reporting "Unresolved compilation problems" is the IDE's compiler writing a stale broken `.class` into `target/`, not a real break. Run `./mvnw clean test` and do **not** change code in response.
- Commit messages reference the ticket id on its own line before the trailer, and end with exactly:
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`

## Verified Facts

Measured against the repository, not inferred.

- **20 workflow scenarios, not 27**: `UserDeprovisioningWorkflowTest` 5, `UserModificationWorkflowTest` 4, `RoleAssignmentWorkflowTest` 4, `UserProvisioningWorkflowTest` 4, `MultiSystemIntegrationTest` 3. `ConfigurationTest`'s 8 tests assert Spring property loading and are out of scope.
- **18 convert. 2 do not**: `testProvisioningTimeout` (asserts a wait times out) and `testRoleAssignmentFailureHandling` (asserts an operation fails). Both stay in Java.
- `random-user` produces exactly these fields: `userId`, `firstName`, `lastName`, `email`, `department`, `jobTitle`, `phoneNumber`, `employeeId`. `values:` overrides any of them.
- `UserTestData.buildLdapDn("ou=users")` returns `"cn=" + userId + ",ou=users"`, so the YAML equivalent is `"cn=${user.userId},ou=users"`.
- `schema.sql` already creates `users`, `user_access` and `audit_log`, applied by `TestSchemaInitializer`. The `users` columns are `id, user_id, first_name, last_name, email, department, job_title, employee_id, phone, city, country, start_date, active, created_at, updated_at`.
- `BaseIntegrationTest` already wires everything a conversion needs: embedded LDAP via `@DynamicPropertySource`, H2 with the schema, and a WireMock SOAP server with `role-assignment`, `role-removal` and `get-user` stubs. **No existing scenario uses REST.**
- `BaseIntegrationTest.@AfterEach` cleans up in the order **roles → database → LDAP**. The engine's reverse-order unwind produces the same relative order for the undos it registers (database before LDAP), so conversions do not need to fight it.
- `EdirectoryConnector.exists(String dn)` returns `OperationResult<Boolean>` and already exists — `LdapDeleteStep` uses it.
- `YamlScenarioTest.scenarioFiles()` uses `Files.list`, which is **non-recursive**. Converted files go in the flat `scenarios/` directory with prefixed names.
- `ldap.verify` is already `retryable()`, so `wait:` works on it unchanged.
- **Three tests in this suite assert nothing.** `testRoleAssignmentToLdapGroup` (its `waitUntil` predicate is `return true; // Simulated success`), `testRoleAssignmentFailureHandling` (its assertion sits inside `if (result.isFailure())`), and `testAttributeRemoval` (no assertion at all — it assigns a result it never reads).
- **The `role-assignment` WireMock stub is malformed** and will break Task 4: wrong namespace (`urn:idmprov` instead of `http://www.novell.com/role/service`) and a plain-string `<result>` where the generated type is `JAXBElement<DNStringArray>`. Check `role-removal.json` too. The existing Java tests do not notice because they never inspect the returned DNs.

## The conversion mapping

Tasks 2–6 all apply these rules. **The Java source method is the specification for each scenario's content** — read it, then apply this table. Reproducing eighteen files of YAML in this document would be transcribing a guess at the source rather than the source itself.

| Java | YAML |
|---|---|
| `UserTestData.randomUser(gen).withDepartment("X").withJobTitle("Y")` | `fixtures: { user: { generate: random-user, values: { department: X, jobTitle: Y } } }` |
| `testUser.buildLdapDn("ou=users")` | `"cn=${user.userId},ou=users"` |
| `edirectoryConnector.create(testUser.toLdapUser(dn))` | `ldap.create` with `dn` + `attributes` |
| `edirectoryConnector.update(dn, mods)` | `ldap.update` with `dn` + `attributes` |
| `edirectoryConnector.delete(dn)` | `ldap.delete` with `dn` |
| `waitUntil(() -> read(dn).isSuccess(), ...)` | `ldap.verify` with `exists: true` and `wait: true` |
| `assertThat(read(dn).isFailure())` after a delete | `ldap.verify` with `exists: false` |
| `assertThat(user.getGivenName()).isEqualTo(x)` | `ldap.verify` with `expect: { givenName: x }` |
| `databaseConnector.executeUpdate(db, sql, args...)` | `db.execute` with `database`, `statement`, `params` |
| `databaseConnector.executeQuery` + row assertions | `db.verify` with `expect: { rowCount: N, row: {...} }` |
| `roleServiceConnector.assignRoles(userDn, roles, reason)` | `roles.grant` with `user`, `roles`, `reason` |
| `trackLdapDn(dn)` | **nothing** — `ldap.create` registers its own undo |
| `trackDatabaseRecord(db, table, where)` | an `undo:` block on the `db.execute` that created the row |
| `trackRoleAssignment(userDn, roleDn)` | `as:` on the `roles.grant`, plus an explicit `roles.revoke` step — `roles.grant` registers no undo by design |

**Traps, all of which have already cost this project time:**

- `wait: true` uses the engine's configured defaults. Do not restate `timeout`/`poll` unless the Java original used a non-default value.
- A `waitUntil` predicate containing `if (result.isFailure()) return false;` is **poll control flow, not an assertion**. Do not convert it into anything.
- `db.verify`'s `row` matcher requires **exactly one row**. For a multi-row result assert `rowCount` instead.
- Column names in `row:` are matched case-insensitively (Spring returns a `LinkedCaseInsensitiveMap`), so write them as the SQL does.
- A `db.verify` capture is a row **list**, and `${...}` cannot index a list. Only `js:{ rows[0].COLUMN }` reaches it, case-sensitively.

---

### Task 1: `exists:` on `ldap.verify`

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java`

**Interfaces:**
- Consumes: `EdirectoryConnector.exists(String dn)` → `OperationResult<Boolean>`.
- Produces: `ldap.verify` accepts `exists` (a boolean) as a sibling of `dn`. Exactly one of `expect` or `exists` must be supplied. `exists: true` asserts the entry is present without asserting attributes; `exists: false` asserts it is gone. Retryable, so `wait:` works on both.

- [ ] **Step 1: Write the failing tests**

Add to `LdapStepsTest`. It already has an `EmbeddedLdapServer` and a `connector` field — reuse them, and follow the file's existing style.

```java
    @Test
    @DisplayName("ldap.verify exists:false should pass once the entry is gone")
    void testVerifyExistsFalsePassesWhenAbsent() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", "cn=never.created,ou=users", "exists", false), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("ldap.verify exists:false should fail while the entry is still there")
    void testVerifyExistsFalseFailsWhenPresent() {
        LdapCreateStep create = new LdapCreateStep(connector);
        String dn = "cn=ada.stillhere,ou=users";
        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.stillhere", "sn", "Lovelace")), false);

        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", dn, "exists", false), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains(dn);
    }

    @Test
    @DisplayName("ldap.verify exists:true should pass without asserting attributes")
    void testVerifyExistsTruePassesWhenPresent() {
        LdapCreateStep create = new LdapCreateStep(connector);
        String dn = "cn=ada.present,ou=users";
        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.present", "sn", "Lovelace")), false);

        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", dn, "exists", true), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("ldap.verify exists:true should fail when the entry is absent")
    void testVerifyExistsTrueFailsWhenAbsent() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", "cn=not.there,ou=users", "exists", true), false);

        assertThat(outcome.success()).isFalse();
    }

    @Test
    @DisplayName("ldap.verify should reject expect and exists supplied together")
    void testVerifyRejectsExpectAndExistsTogether() {
        // expect already implies existence, so supplying both says nothing about intent.
        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", "cn=ada,ou=users",
                "expect", Map.of("sn", "Lovelace"),
                "exists", true), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("expect").contains("exists");
    }

    @Test
    @DisplayName("ldap.verify should reject neither expect nor exists")
    void testVerifyRejectsNeitherExpectNorExists() {
        // The vacuous-assertion trap: making expect optional means "neither supplied"
        // must fail, or the step verifies nothing at all.
        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", "cn=ada,ou=users"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("expect").contains("exists");
    }

    @Test
    @DisplayName("ldap.verify should reject a non-boolean exists")
    void testVerifyRejectsNonBooleanExists() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", "cn=ada,ou=users", "exists", "yes"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("exists");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=LdapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL. `exists` is not in `acceptedParams()`, and the step currently requires `expect`.

- [ ] **Step 3: Change the parameter contract**

In `LdapVerifyStep`, replace `requiredParams()` and add `acceptedParams()`:

```java
    @Override
    public Set<String> requiredParams() {
        // Only 'dn' is unconditionally required. 'expect' and 'exists' are each optional on
        // their own but exactly one must be present — a rule requiredParams() cannot express,
        // so execute() enforces it before any I/O. See rejectMalformedAssertion.
        return Set.of("dn");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("dn", "expect", "exists", "wait");
    }
```

- [ ] **Step 4: Add the guard and the existence branch**

In `LdapVerifyStep.execute`, replace everything from the start of the method down to (and including) the existing non-map `expect` check with:

```java
        String dn = String.valueOf(params.get("dn"));

        StepOutcome rejection = rejectMalformedAssertion(params, dn);
        if (rejection != null) {
            return rejection;
        }

        if (params.containsKey("exists")) {
            return verifyExistence(dn, (Boolean) params.get("exists"));
        }
```

Then add these two methods to the class:

```java
    /**
     * Asserts only presence or absence, with no attribute comparison.
     * <p>
     * Absence is what makes a de-provisioning testable: after a delete, the honest assertion
     * is that the entry is gone, and until now the DSL could only assert that an entry still
     * had particular values.
     */
    private StepOutcome verifyExistence(String dn, boolean shouldExist) {
        OperationResult<Boolean> result = connector.exists(dn);

        if (!result.isSuccess()) {
            return StepOutcome.failure("ldap.verify could not check whether " + dn
                    + " exists: " + result.getMessage());
        }

        boolean present = Boolean.TRUE.equals(result.getData().orElse(false));
        if (present != shouldExist) {
            return StepOutcome.failure("ldap.verify expected " + dn + " to be "
                    + (shouldExist ? "present" : "absent") + " but it was "
                    + (present ? "present" : "absent"));
        }

        return StepOutcome.success(
                "verified " + dn + " is " + (shouldExist ? "present" : "absent"));
    }

    /**
     * Exactly one of {@code expect} or {@code exists} must be supplied, and each must have the
     * right shape.
     * <p>
     * Making {@code expect} optional is what creates the risk here: "neither supplied" would
     * verify nothing at all while looking like an assertion. That defect class has been found
     * seven times in this project, so the rule is stated positively and checked before any I/O.
     */
    private static StepOutcome rejectMalformedAssertion(Map<String, Object> params, String dn) {
        boolean hasExpect = params.containsKey("expect");
        boolean hasExists = params.containsKey("exists");

        if (hasExpect && hasExists) {
            return StepOutcome.failure("ldap.verify for " + dn + " supplies both 'expect' and "
                    + "'exists'; expect already implies the entry exists, so give only one");
        }
        if (!hasExpect && !hasExists) {
            return StepOutcome.failure("ldap.verify for " + dn + " supplies neither 'expect' "
                    + "nor 'exists' — it would verify nothing. Assert attributes with expect:, "
                    + "or presence with exists:");
        }
        if (hasExists && !(params.get("exists") instanceof Boolean)) {
            return StepOutcome.failure("ldap.verify for " + dn + " has an 'exists' that is not "
                    + "true or false");
        }
        if (hasExpect && !(params.get("expect") instanceof Map<?, ?> expect)) {
            return StepOutcome.failure("ldap.verify for " + dn
                    + " has an expect that is not a map of attribute names to expected values");
        } else if (hasExpect && ((Map<?, ?>) params.get("expect")).isEmpty()) {
            return StepOutcome.failure("ldap.verify for " + dn
                    + " has an empty expect: block — it would verify nothing");
        }

        return null;
    }
```

Delete the now-superseded inline checks in `execute` for a non-map `expect` and an empty `expect` — `rejectMalformedAssertion` covers both. Leave the rest of `execute` (the `verifyAttributes` call, the `captureRequested` branch, the follow-up read) untouched.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=LdapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 6: Falsify each new guard**

For each of `testVerifyRejectsExpectAndExistsTogether`, `testVerifyRejectsNeitherExpectNorExists` and `testVerifyRejectsNonBooleanExists`: comment out only that branch of `rejectMalformedAssertion`, re-run the test, confirm it **FAILS**, then restore it and confirm it passes.

Record the observed failure for each in your report. A guard nobody proved can fail is how this defect class survived seven rounds.

- [ ] **Step 7: Run the whole suite**

Run: `./mvnw clean test`

Expected: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped. `requiredParams()` changed, so `ScenarioValidatorTest` and the existing scenarios exercise the new contract. If an existing test fails because it relied on `expect` being unconditionally required, report it before changing it.

- [ ] **Step 8: Commit**

```bash
git add idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java \
        idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java
git commit -m "feat: add exists: to ldap.verify

Verifying a de-provisioning — that an entry is actually gone — could
not be written in YAML, even though rest.verify has expressed the
equivalent since ITF-9 with expect: { status: 404 }.

exists: is a sibling of dn rather than a key inside expect, because
ldap.verify's expect IS the user-data namespace: a flat map of
attribute names. A reserved key inside it would collide with an
attribute of the same name.

Making expect optional is what creates risk, so the rule is stated
positively: exactly one of expect or exists, checked before any I/O.

ITF-5

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Convert the provisioning scenarios

**Files:**
- Create: `idm-test-scenarios/src/test/resources/scenarios/provisioning-user-to-ldap.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/provisioning-user-to-database.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/provisioning-minimal-user.yaml`
- Modify: `idm-test-scenarios/src/test/resources/schema.sql`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java`

**Source:** `idm-test-scenarios/src/test/java/com/idm/test/scenarios/provisioning/UserProvisioningWorkflowTest.java` — methods `testUserProvisioningToLdap`, `testUserProvisioningToDatabase`, `testMinimalUserProvisioning`. **`testProvisioningTimeout` is NOT converted**; it asserts that a wait times out, which the engine treats as failure by design.

**Interfaces:**
- Consumes: `exists:` on `ldap.verify` from Task 1.
- Produces: the three YAML files above, and `sap_users` moved into `schema.sql`.

- [ ] **Step 1: Move the ad-hoc table into the schema**

ITF-8 added a `CREATE TABLE IF NOT EXISTS sap_users` inside a `@BeforeEach` in `YamlScenarioTest`, written before `schema.sql` was noticed. Append to `schema.sql`, beside the other tables:

```sql
-- ========================================
-- SAP Users Table (target-application stand-in for scenario tests)
-- ========================================
CREATE TABLE sap_users (
    username VARCHAR(50),
    status VARCHAR(20)
);
```

Then delete the `createScenarioTable` method and its `@BeforeEach` from `YamlScenarioTest`, along with any imports left unused.

- [ ] **Step 2: Write the first conversion**

Create `provisioning-user-to-ldap.yaml`. This is the worked example for the whole conversion — the other seventeen follow the mapping table:

```yaml
name: Provision a user to eDirectory
description: Create a user and verify the directory entry carries its attributes

fixtures:
  user:
    generate: random-user
    values:
      department: Engineering
      jobTitle: Software Engineer

steps:
  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      attributes:
        cn: "${user.userId}"
        sn: "${user.lastName}"
        givenName: "${user.firstName}"
        mail: "${user.email}"

  - ldap.verify:
      dn: "cn=${user.userId},ou=users"
      exists: true
      wait: true

  - ldap.verify:
      dn: "cn=${user.userId},ou=users"
      expect:
        givenName: "${user.firstName}"
        sn: "${user.lastName}"
        mail: "${user.email}"
```

Note what is absent: no cleanup instructions. `ldap.create` registers its own undo, which is what `trackLdapDn(userDn)` did by hand in the Java original.

- [ ] **Step 3: Run it**

Run: `./mvnw -pl idm-test-scenarios -am test -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. The new file appears as its own dynamic test named `provisioning-user-to-ldap.yaml`.

- [ ] **Step 4: Convert the remaining two**

Read each source method and apply the mapping table.

`provisioning-user-to-database.yaml` from `testUserProvisioningToDatabase`: a `db.execute` INSERT into `users` carrying an `undo:` that deletes by `user_id` (that is what `trackDatabaseRecord` did), then a `db.verify` asserting `rowCount: 1` and a `row:` matching `first_name`, `last_name`, `email` and `department`. Use `wait: true` on the verify, matching the original's `waitUntil`.

`provisioning-minimal-user.yaml` from `testMinimalUserProvisioning`: whatever that method actually does — read it; it is the smallest of the three.

- [ ] **Step 5: Run the scenarios module**

Run: `./mvnw -pl idm-test-scenarios -am test -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors. All three new scenarios run as dynamic tests, and the pre-existing `user-provisioning.yaml` and `database-provisioning.yaml` still pass.

- [ ] **Step 6: Confirm the scenarios really executed their steps**

Run:

```bash
python3 -c "
import json
d=json.load(open('idm-test-scenarios/target/scenario-results.json'))
for sc in (d if isinstance(d,list) else [d]):
    print(sc.get('name'), '| success =', sc.get('success'), '|', len(sc.get('steps',[])), 'steps')
"
```

Expected: every scenario listed with `success = True` and a non-zero step count. A scenario that "passes" with zero steps has not run.

- [ ] **Step 7: Commit**

```bash
git add idm-test-scenarios/src/test/resources/scenarios/provisioning-*.yaml \
        idm-test-scenarios/src/test/resources/schema.sql \
        idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java
git commit -m "test: convert the provisioning scenarios to YAML

Three of the four convert. testProvisioningTimeout does not: it asserts
that a wait times out, and the engine treats a timed-out wait as a
failure by design.

Moves the sap_users table ITF-8 created ad hoc inside YamlScenarioTest
into schema.sql, beside the tables it belongs with.

ITF-5

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Convert the modification scenarios

**Files:**
- Create: `idm-test-scenarios/src/test/resources/scenarios/modification-user-in-ldap.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/modification-user-in-database.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/modification-email-change.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/modification-attribute-removal.yaml`

**Source:** `.../modification/UserModificationWorkflowTest.java` — `testUserModificationInLdap`, `testUserModificationInDatabase`, `testEmailChangeWorkflow`, `testAttributeRemoval`. All four convert.

**Interfaces:**
- Consumes: `exists:` from Task 1; the mapping table above.
- Produces: four YAML files.

- [ ] **Step 1: Convert all four**

Read each source method and apply the mapping table. Two specifics that will come up:

`testUserModificationInLdap` contains `if (result.isFailure()) return false;` inside its `waitUntil` predicate. **That is poll control flow, not an assertion** — do not convert it into anything. The predicate as a whole becomes one `ldap.verify` with `expect: { title: ... }` and `wait: true`.

`testAttributeRemoval` **asserts nothing**. Its whole body creates a user, calls `update` with `modifications.put("telephoneNumber", null)`, assigns the result to a variable that is never read, and ends with the comment "Actual removal behavior depends on LDAP implementation". It is the third vacuous test in this suite.

Convert the actions only — `ldap.create` then `ldap.update` — and **do not invent an assertion**. Asserting the attribute is gone would need *attribute* absence, which `exists:` does not provide (that is entry-level), and inventing a capability mid-conversion is out of scope.

Two things to report: that the original verified nothing, and whether `ldap.update` with a null attribute value even works. `LdapUpdateStep` passes the map straight to `EdirectoryConnector.update`, which builds `new BasicAttribute(key, value)` — a null there may throw or write a null rather than removing the attribute. If it fails, **do not fix it here**; record it, and convert the scenario as `ldap.create` alone with a note. Whether attribute removal deserves a real capability is evidence for the findings report, not a decision to take in passing.

- [ ] **Step 2: Run the scenarios module**

Run: `./mvnw -pl idm-test-scenarios -am test -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 3: Confirm the steps really executed**

Run the `scenario-results.json` check from Task 2 Step 6. Every new scenario must show `success = True` with a non-zero step count.

- [ ] **Step 4: Commit**

```bash
git add idm-test-scenarios/src/test/resources/scenarios/modification-*.yaml
git commit -m "test: convert the modification scenarios to YAML

All four convert. The isFailure() inside testUserModificationInLdap's
waitUntil predicate is poll control flow, not an assertion, and becomes
a single ldap.verify with a wait.

ITF-5

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Convert the role assignment scenarios

**Files:**
- Create: `idm-test-scenarios/src/test/resources/scenarios/roles-assign-to-ldap-group.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/roles-assign-with-database-access.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/roles-assign-multiple.yaml`

**Source:** `.../provisioning/RoleAssignmentWorkflowTest.java` — `testRoleAssignmentToLdapGroup`, `testRoleAssignmentWithDatabaseAccess`, `testMultipleRoleAssignment`. **`testRoleAssignmentFailureHandling` is NOT converted**; it asserts an operation fails, which the DSL has no way to express.

**Interfaces:**
- Consumes: the mapping table. `roles.grant` takes `user`, `roles` (a list or a bare string), optional `reason`, and captures the returned assignment DNs through `as:`. `roles.revoke` takes `assignments:` — those captured DNs, not a user and a role.
- Produces: three YAML files.

These are the only conversions that exercise SOAP. `BaseIntegrationTest` already points `idm.test.soap.url` at a WireMock server stubbing `role-assignment`, `role-removal` and `get-user`, so they execute for real.

- [ ] **Step 1: Handle the fiction in `testRoleAssignmentToLdapGroup`**

That method's wait predicate is:

```java
boolean roleProvisioned = testExecutor.waitUntil(
        () -> {
            // In real implementation: read group and check member attribute
            return true; // Simulated success
        }, ...);
assertThat(roleProvisioned).isTrue();
```

It asserts nothing. The DSL cannot express a pretend assertion, which is the point of the exercise.

Convert it as: `ldap.create` the user, `roles.grant` with `as: granted`, then `roles.revoke` with `assignments: "${granted}"` (that is what `trackRoleAssignment` did by hand). **Drop the simulated wait entirely** — do not invent an `ldap.verify` to fill the gap unless the WireMock `get-user` stub genuinely supports checking group membership, in which case write the real assertion.

Either way, **record what you did and why in your report.** The converted scenario being shorter than its Java original is the correct outcome: there was no coverage to lose.

- [ ] **Step 2: Convert all three**

Read each source method and apply the mapping table.

- [ ] **Step 3: Run the scenarios module**

Run: `./mvnw -pl idm-test-scenarios -am test -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

**You will hit the stub problem — it is already confirmed, not a risk.** `idm-test-scenarios/src/test/resources/wiremock/mappings/role-assignment.json` returns:

```xml
<ns2:requestRolesAssignmentResponse xmlns:ns2="urn:idmprov">
  <result>SUCCESS</result>
</ns2:requestRolesAssignmentResponse>
```

Two problems. The namespace is `urn:idmprov`, not the role service's `http://www.novell.com/role/service`. And `<result>` is a plain string where the generated type is `JAXBElement<DNStringArray>`, so it unmarshals to **zero** DNs.

The existing Java test does not notice, because it only asserts `assignResult.isSuccess()` and never inspects the result. But `roles.grant` captures those DNs, so `${granted}` will be an empty list and the `roles.revoke` will fail with "has no assignments to remove".

Fix the stub to the shape `SoapStepsTest` already proved unmarshals correctly — note it uses `tns` bound to the role service namespace, and the repeating `dnstring`/`dn` elements:

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:tns="http://www.novell.com/role/service">
  <soap:Body>
    <tns:requestRolesAssignmentResponse>
      <tns:result><tns:dnstring><tns:dn>cn=assignment-1,ou=requests,dc=test,dc=com</tns:dn></tns:dnstring></tns:result>
    </tns:requestRolesAssignmentResponse>
  </soap:Body>
</soap:Envelope>
```

Check `role-removal.json` for the same two problems and fix it the same way. Run the Java `RoleAssignmentWorkflowTest` afterwards to confirm the corrected stubs did not break it, and report both the fix and that result.

- [ ] **Step 4: Confirm the steps really executed**

Run the `scenario-results.json` check from Task 2 Step 6.

- [ ] **Step 5: Commit**

```bash
git add idm-test-scenarios/src/test/resources/scenarios/roles-*.yaml
git commit -m "test: convert the role assignment scenarios to YAML

Three of the four convert. testRoleAssignmentFailureHandling does not:
it asserts an operation fails, which the DSL cannot express.

testRoleAssignmentToLdapGroup's wait predicate was a literal
'return true; // Simulated success', asserting nothing. The conversion
does not reproduce it — the DSL has no way to write a pretend
assertion, which is precisely what this exercise is for.

roles.grant registers no undo by design, so the captured assignment
DNs feed an explicit roles.revoke, replacing trackRoleAssignment.

ITF-5

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Convert the deprovisioning scenarios

**Files:**
- Create: `idm-test-scenarios/src/test/resources/scenarios/deprovisioning-user-disable.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/deprovisioning-user-delete.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/deprovisioning-access-revocation.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/deprovisioning-grace-period.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/deprovisioning-related-data-cleanup.yaml`

**Source:** `.../deprovisioning/UserDeprovisioningWorkflowTest.java` — all five methods convert.

**Interfaces:**
- Consumes: `exists:` from Task 1 — `deprovisioning-user-delete.yaml` is the scenario that needed it.
- Produces: five YAML files.

- [ ] **Step 1: Convert `testUserDeleteWorkflow` first**

This is the scenario Task 1 exists for. Its Java original creates the user in LDAP and the database, deletes from both, then asserts absence in both. The two halves convert differently:

```yaml
  - ldap.delete:
      dn: "cn=${user.userId},ou=users"

  - ldap.verify:
      dn: "cn=${user.userId},ou=users"
      exists: false

  - db.execute:
      database: test-db
      statement: "DELETE FROM users WHERE user_id = ?"
      params: ["${user.userId}"]

  - db.verify:
      database: test-db
      query: "SELECT * FROM users WHERE user_id = ?"
      params: ["${user.userId}"]
      expect:
        rowCount: 0
```

Database absence needed nothing new — `rowCount: 0` already expressed it. Only the LDAP half was missing.

One thing to watch: the Java original deliberately does **not** call `trackLdapDn`, with the comment "Don't track for cleanup since we're testing deletion". In YAML, `ldap.create` registers its undo unconditionally, and that undo will run after the scenario has already deleted the entry. That is harmless — `LdapTemplate.unbind` succeeds silently on a missing DN — but if it produces a noisy cleanup warning, note it in your report rather than working around it.

- [ ] **Step 2: Convert the remaining four**

Read each source method and apply the mapping table. `testRelatedDataCleanup` touches `user_access`; check `schema.sql` for its columns rather than guessing.

- [ ] **Step 3: Run the scenarios module**

Run: `./mvnw -pl idm-test-scenarios -am test -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 4: Confirm the steps really executed**

Run the `scenario-results.json` check from Task 2 Step 6.

- [ ] **Step 5: Commit**

```bash
git add idm-test-scenarios/src/test/resources/scenarios/deprovisioning-*.yaml
git commit -m "test: convert the deprovisioning scenarios to YAML

All five convert. testUserDeleteWorkflow is the scenario that needed
exists: — verifying a de-provisioning is the pattern the DSL could not
express. Its database half needed nothing new, since rowCount: 0
already said 'the row is gone'.

ITF-5

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Convert the multi-system scenarios

**Files:**
- Create: `idm-test-scenarios/src/test/resources/scenarios/integration-new-hire-onboarding.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/integration-employee-termination.yaml`
- Create: `idm-test-scenarios/src/test/resources/scenarios/integration-department-transfer.yaml`

**Source:** `.../integration/MultiSystemIntegrationTest.java` — all three convert. These are the longest scenarios and the best evidence for the vocabulary question, since each spans LDAP, database and SOAP.

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: three YAML files.

- [ ] **Step 1: Convert all three**

Read each source method and apply the mapping table.

`testNewHireOnboardingWorkflow` contains `if (hrResult.isFailure() || ...getData().get().isEmpty())` inside a `waitUntil` predicate. **Poll control flow, not an assertion** — the predicate as a whole becomes one `db.verify` with `wait: true`.

- [ ] **Step 2: Run the whole suite**

Run: `./mvnw clean test`

Expected: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped. Record the reactor total, and state that it is the reactor total rather than a single module's.

- [ ] **Step 3: Confirm every scenario really executed**

Run the `scenario-results.json` check from Task 2 Step 6. There should now be 18 converted scenarios plus the 2 pre-existing ones, every one with `success = True` and a non-zero step count.

- [ ] **Step 4: Commit**

```bash
git add idm-test-scenarios/src/test/resources/scenarios/integration-*.yaml
git commit -m "test: convert the multi-system scenarios to YAML

All three convert. These span LDAP, database and SOAP in one scenario
each, and are the strongest evidence that twelve step types are
sufficient.

ITF-5

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Amend the documentation and record the evidence

**Files:**
- Modify: `docs/reference/specification.md:99`
- Modify: `CLAUDE.md:24`
- Modify: `docs/specs/2026-09-13-declarative-scenario-engine-design.md`
- Create: `docs/reference/scenario-conversion-findings.md`

**Interfaces:**
- Consumes: everything learned in Tasks 1–6.
- Produces: the corrected documents and the findings report five other tickets are waiting on.

- [ ] **Step 1: Scope the orchestration-layer principle in both files**

The principle appears twice, worded differently, and both readings contradict the engine.

`docs/reference/specification.md:99`, under "What We're NOT Doing", currently `- ❌ Orchestration layers hiding workflow logic`. Change to:

```markdown
- ❌ Orchestration layers hiding workflow logic **in the Java tier** (see "Two authoring tiers" below)
```

`CLAUDE.md:24`, under "What We DON'T DO", currently `- ❌ Orchestration layers that hide test logic (tests must be explicit)`. Change to:

```markdown
- ❌ Orchestration layers that hide test logic in the Java tier (tests must be explicit) — the declarative YAML tier is a deliberate exception; see `docs/reference/specification.md`
```

- [ ] **Step 2: Add the two-tier section to the specification**

Add to `docs/reference/specification.md`, after the "What We're NOT Doing" section:

```markdown
## Two authoring tiers

The framework offers two ways to write a scenario.

**The Java tier** is explicit: a test orchestrates its own workflow step by step, and nothing
hides what it does. This is the tier the principles above describe.

**The declarative YAML tier** is an orchestration layer, deliberately, so that IdM consultants
who are not Java developers can author scenarios. It trades explicitness for accessibility and
offers two guarantees in exchange:

1. **A scenario's step list is its complete execution order.** There is no hidden setup, no
   implicit ordering and no inherited behaviour. What the file lists is what runs, top to
   bottom.
2. **Expressions compute values; they never act.** A `js:{ }` expression is evaluated in a
   sandbox with no connector access, no filesystem, no network and no Java class access. It
   cannot change the system under test.

Scenarios that cannot be expressed in the YAML vocabulary stay in Java, and are the documented
examples of what that tier is for — `testProvisioningTimeout` and
`testRoleAssignmentFailureHandling` are the current two.
```

- [ ] **Step 3: Correct the design spec's scenario count**

`docs/specs/2026-09-13-declarative-scenario-engine-design.md` says 27 in four places: §2 ("The 27 existing scenario tests use exactly **nine distinct operations**"), §7.4, §8.3 and the paragraph following §8.3. Correct all four to 20.

§2's companion claim — nine distinct operations with per-operation call-site counts — was measured over the wrong population. Re-measure it across the five workflow files and correct the table, or if the counts are unchanged say so explicitly. Do not assume they survive.

Also correct §7.1's `bodyFrom: user` to `bodyFrom: "${user}"`, decided in ITF-9: a step never receives scenario scope, so a bare name is unresolvable by the step, and a reference inherits the validator's load-time forward-reference checking that a bare name would bypass.

- [ ] **Step 4: Write the findings report**

Create `docs/reference/scenario-conversion-findings.md` answering all five deferred questions with the specific scenarios that motivate each answer. Anything you could not determine, say so — an honest "no evidence either way" is more useful than a guess.

```markdown
# Scenario conversion findings

What converting the Java workflow scenarios to YAML revealed. Each section answers a design
question that was deliberately deferred until this evidence existed.

## 1. Does anything need a scenario-level `cleanup:` block? (ITF-11)

[Did any scenario need to tear down something no step created? The engine auto-undoes what a
step did itself. Name the scenarios, or state that none needed it — in which case ITF-11 should
be closed rather than built.]

## 2. Does anything need a per-step `undo: false` opt-out?

[Did any scenario need to suppress an inferred undo? Note `deprovisioning-user-delete.yaml`,
whose Java original deliberately skipped `trackLdapDn`.]

## 3. Does `${...}` need list indexing?

[How often did a `db.verify` capture need to be read back, and did `js:{ rows[0].COLUMN }`
suffice? Note the case-sensitivity asymmetry with the `row:` matcher.]

## 4. Does `expect:` need load-time validation?

[Design §7.7 wants it; `ScenarioValidator` cannot inspect nested parameters, so four step types
now hand-roll the same guard. Did any conversion mistake get caught late that load-time
validation would have caught early?]

## 5. Does step reuse (`extends` / `include`) earn its place? (design §8.4)

[How much setup is duplicated across the 18 files? Count the repeated blocks. This is the
question §8.4 explicitly left for this data.]

## Vocabulary verdict

[Did twelve step types suffice for all 18? Which parameters went unused? What, if anything,
was missing beyond the `exists:` added in Task 1?]

## Defects found in the Java tier

[The three known vacuous tests — `testRoleAssignmentToLdapGroup` (`return true; // Simulated
success`), `testRoleAssignmentFailureHandling` (assertion inside `if (result.isFailure())`) and
`testAttributeRemoval` (no assertion at all) — the two malformed WireMock stubs, plus anything
else the conversion surfaced.]
```

Replace every bracketed prompt with the actual finding. **A bracketed placeholder left in this file is a failed task** — the whole point of the conversion is this document.

- [ ] **Step 5: Run the whole suite**

Run: `./mvnw clean test`

Expected: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped.

- [ ] **Step 6: Commit**

```bash
git add docs/reference/specification.md CLAUDE.md \
        docs/specs/2026-09-13-declarative-scenario-engine-design.md \
        docs/reference/scenario-conversion-findings.md
git commit -m "docs: amend the specification and record the conversion findings

The 'no orchestration layers' principle appears in two files with
different wording, and both contradict the declarative engine. Each is
now scoped to the Java tier, and the specification describes the two
tiers and the guarantees the declarative one offers in exchange.

Corrects the scenario count from 27 to 20 in all four places, and
bodyFrom to take a reference rather than a bare name.

The findings report answers the five design questions this conversion
existed to settle.

ITF-5

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage**

| Spec section | Task |
|---|---|
| §2 corrected inventory (20, ConfigurationTest out of scope) | Tasks 2–6 convert exactly the 18; the 2 exclusions are named in Tasks 2 and 4 |
| §3 `exists:` on `ldap.verify` | Task 1 |
| §4 flat directory, prefixed names, Java scenarios stay | Tasks 2–6 |
| §4 tidy-ups (`sap_users` into `schema.sql`) | Task 2 Step 1 |
| §5 fictions recorded, not papered over | Task 4 Step 1; Task 7 Step 4 |
| §6 five evidence answers | Task 7 Step 4 |
| §7 specification, CLAUDE.md, 27→20, `bodyFrom` | Task 7 Steps 1–3 |
| §8 testing (conversions are the test; `exists:` unit-tested and falsified) | Task 1 Steps 1–6; Steps 3/6 of each conversion task |

**One §4 item deliberately left to judgement:** whether `scenarios-vocabulary-fixture/full-vocabulary.yaml` stays. The spec says keep it only if it still covers something the real scenarios do not — which cannot be known until they exist. Task 7 does not force a decision, and it should be raised in the final review rather than guessed at now.

**2. Placeholder scan.** No TBD/TODO. Tasks 3–6 tell the implementer to read the Java source rather than reproducing eighteen YAML files here — stated explicitly in "The conversion mapping", with one complete worked example (Task 2 Step 2) plus the specific trap in each task. Writing all eighteen from here would be transcribing a guess at source I have not read line by line; the source is the specification. The bracketed prompts in Task 7's findings template are the document's own structure, and Step 4 states that leaving one is a failed task.

**3. Type consistency.** `exists` is a `Boolean` sibling of `dn` throughout; `rejectMalformedAssertion` and `verifyExistence` are defined in Task 1 Step 4 and referenced only there and in Step 6. `roles.grant`/`roles.revoke` parameter names (`user`, `roles`, `reason`, `as`, `assignments`) match ITF-9's shipped contract. `db.verify`'s `rowCount`/`row` and `db.execute`'s `statement`/`params`/`undo` match ITF-8's.

**4. Risks, both investigated rather than left open.**

The draft flagged `testAttributeRemoval` as possibly needing attribute absence, a capability this plan does not add. Reading it settled the question: it asserts nothing at all, so it needs no assertion capability. Task 3 now states that as fact and tells the implementer not to invent one.

The draft also flagged the `role-assignment` WireMock stub as a possible problem. It is a confirmed one — wrong namespace and a plain-string result where a DN array belongs. Task 4 now carries the corrected envelope rather than a contingency.

**5. What this plan does not decide.** Whether `ldap.update` can express attribute removal at all. `testAttributeRemoval` passes `telephoneNumber: null` to a connector that builds `new BasicAttribute(key, value)`, which may throw or write a null rather than removing. Task 3 has the implementer record what happens instead of fixing it, because "should the DSL be able to remove an attribute" is a design question for the project owner and belongs in the findings report as evidence.
