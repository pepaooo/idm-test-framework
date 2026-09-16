# LDAP and Database Step Types Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the four step types `ldap.update`, `ldap.delete`, `db.execute` and `db.verify` to the declarative scenario engine, on a directory read path that returns every attribute rather than ten hardcoded ones.

**Architecture:** Each step type is a small final class implementing `StepType`, constructed with the connector it wraps and registered by name. Action steps return an undo through `StepOutcome`; the engine unwinds them in reverse. Task 1 is a prerequisite: `EdirectoryConnector`'s read path currently maps ten fixed attributes and never populates custom ones, which makes `ldap.update`'s "restore prior values" undo structurally impossible and already causes false failures in the shipped `ldap.verify`.

**Tech Stack:** Java 21, Maven multi-module, JUnit 5, AssertJ, Mockito, UnboundID in-memory LDAP, H2, Spring LDAP `LdapTemplate`, Spring `JdbcTemplate`.

**Ticket:** ITF-8 (subtask of ITF-4). **Spec:** `docs/specs/2026-09-13-declarative-scenario-engine-design.md` §7.2, §7.3, §7.5, §7.6.

## Global Constraints

- `idm-test-engine/src/main` must **never** import `org.springframework`. `NoSpringDependencyTest` enforces this by walking the source tree; it is not advisory.
- Expressions compute values, they never perform actions. No connector access, no filesystem, no network, no Java class access.
- No Lombok. Use records and plain classes with explicit constructors.
- Java 21. Prefer records, pattern matching, switch expressions.
- A `StepType.execute` implementation **must never throw**. A failure is a returned `StepOutcome.failure(...)`, not an exception. The engine treats a thrown exception as a bug in the step.
- Returning a `null` `StepOutcome.value()` while `captureRequested` is true fails the scenario. A step whose value can legitimately be absent must return a non-null sentinel.
- `StepType` has a **single** abstract method: `StepOutcome execute(Map<String, Object> params, boolean captureRequested)`. Do not add overloads.
- The engine strips `as` and `wait` from `params` before calling `execute`. Neither is ever present in `params`.
- Reserved keys never share a namespace with user data (spec §7.3). `db.verify` separates `rowCount` from the `row` matcher so a table with a column named `rows` cannot change how a scenario parses.
- Every Maven command that names a module with `-pl` **must** also pass `-am`, or sibling `com.idm.test:*` artifacts fail to resolve.
- Run a single test class with `-Dsurefire.failIfNoSpecifiedTests=false`, otherwise modules without a match fail the build.
- Commit messages reference the ticket id on its own line before the trailers, and end with exactly:
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`

## Verified Facts

These were measured against the running systems, not inferred. Do not re-litigate them.

- `UserContextMapper.doMapFromContext` maps exactly ten attributes and **never** calls `addCustomAttribute`. `verifyAttributes` then calls `actualAttributes.putAll(user.getCustomAttributes())` over a permanently empty map. Proven: an entry created with `employeeNumber=E12345` verifies as `expected=E12345, actual=null`.
- A mapper over `ctx.getAttributes().getAll()` returns everything, including multi-valued `objectClass` (4 values).
- `JdbcTemplate.queryForList` returns `org.springframework.util.LinkedCaseInsensitiveMap`. `row: { status: ACTIVE }` therefore matches H2's `STATUS` **as long as you look up against the returned map directly**. Copying rows into a plain `HashMap` before matching would silently break this.
- A SQL alias survives verbatim: `SELECT username AS "userName"` yields the key `userName`.
- `Interpolator.lookup` navigates **Maps only** — there is no list indexing. `${rows.0.status}` cannot work. A captured row list is reachable only from `js:{ rows[0].STATUS }`.
- JS property access is case-sensitive and a miss returns `null` silently: `js:{ rows[0].status }` on a `STATUS` key yields `null`, not an error.
- `JsValues.fromJs` normalises every number to `Double` **deliberately** (see its comment). `js:{ rows[0].ATTEMPTS }` yields `3.0`, not `3`. Do not "fix" this. The DB path is unaffected because value comparison is numeric-tolerant.
- `MultiDatabaseConnector.valuesEqual` already compares `Number` via `doubleValue()`, so YAML's `Integer` matches JDBC's `Long`/`BigDecimal`.
- `idm-test-engine/pom.xml` depends on `idm-test-core`, `idm-test-utils` and `idm-connector-ldap`. It does **not** depend on `idm-connector-database`. Task 4 adds it.
- There is no central step registry. `YamlScenarioTest.registry(...)` constructs it by hand; that is the production wiring point.
- The scenarios module has an H2 database configured under the name `test-db` (`idm-test-scenarios/src/test/resources/application.yml`), alongside PostgreSQL/Oracle entries that do not resolve in the test environment. Use `test-db`.
- `ErrorType` has no `CONNECTION_ERROR`. The values are `NETWORK_ERROR, AUTHENTICATION_ERROR, NOT_FOUND, VALIDATION_ERROR, TIMEOUT, SERVER_ERROR, DATABASE_ERROR, LDAP_ERROR, CONFIGURATION_ERROR, UNKNOWN_ERROR`.

## Decisions Already Made

Do not re-open these; they were settled with the project owner.

1. **Task 1 is a prerequisite, inside this branch.** It also fixes the shipped `ldap.verify` defect recorded on ITF-6.
2. **`ldap.delete` registers NO undo.** Even with a complete read, a recreated entry silently loses `userPassword` and operational attributes. A step whose undo cannot be faithful is better with no undo than a convincing fake.
3. **`db.execute` takes an optional explicit `undo:`.** No engine can infer the inverse of arbitrary SQL.
4. **A per-step `undo: false` opt-out is deferred.** None of these four need it. ITF-5's conversion of the 27 existing scenarios is the evidence that should decide its shape.
5. **The scenario-level `cleanup:` block (spec §7.6) is out of scope**, filed as ITF-11.
6. **`userPassword` is excluded from the generic attribute view.** It would otherwise flow into `as:` captures and into `JsonResultWriter` output — a credential in a report file. The consequence is that a password change cannot be undone; that is consistent with decision 2.

## File Structure

**Create:**
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapAttributes.java` — the one place that flattens an `LdapUser` into a name→value map. Extracted from `LdapVerifyStep` so `LdapUpdateStep` cannot drift from it.
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapUpdateStep.java`
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapDeleteStep.java`
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/DbExecuteStep.java`
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/DbVerifyStep.java`
- `idm-connector-database/src/main/java/com/idm/test/connector/database/client/ValueComparison.java` — shared expected-vs-actual equality, so the connector and `db.verify` cannot drift.
- `idm-test-engine/src/test/java/com/idm/test/engine/steps/DbStepsTest.java`
- `idm-test-scenarios/src/test/resources/scenarios/database-provisioning.yaml`

**Modify:**
- `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/EdirectoryConnector.java` — attribute-complete `UserContextMapper`, plus `removeAttributes`.
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java` — delegate to `LdapAttributes`.
- `idm-test-engine/pom.xml` — add `idm-connector-database`.
- `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java` — tests for update and delete.
- `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/EdirectoryConnectorTest.java` — regression test for the false-failure bug.
- `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java` — register the four new step types.

---

### Task 1: Make the LDAP read path attribute-complete

**Files:**
- Modify: `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/EdirectoryConnector.java`
- Test: `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/EdirectoryConnectorTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `EdirectoryConnector.read(String dn)` now returns an `LdapUser` whose `getCustomAttributes()` contains every attribute outside the ten mapped names and outside `userPassword`. Single-valued attributes are the scalar value; multi-valued attributes are a `java.util.List<Object>` of all values. `verifyAttributes(String, Map)` consequently works for any attribute.

- [ ] **Step 1: Write the failing regression test**

Add to `EdirectoryConnectorTest`. That class is a `@SpringBootTest` wiring `EmbeddedLdapServerConfig`, `LdapConfiguration` and `EdirectoryConnector`, with an `@Autowired EdirectoryConnector connector` field — reuse it, do not start a second directory. Its entries live under `ou=people` (the class constant is `TEST_BASE_DN`), **not** `ou=users`; the DNs below follow that.

The class carries `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)`. Un-annotated methods sort last, which is fine here because each test below creates the entry it reads. Do not add `@Order` values that interleave these with the existing ordered tests.

`EdirectoryConnector.create` sets `objectClass` to `{top, person, organizationalPerson, inetOrgPerson}`, so `employeeNumber` is a permitted attribute.

```java
@Test
@DisplayName("read should return attributes outside the ten mapped names")
void testReadReturnsCustomAttributes() {
    String dn = "cn=custom.attr,ou=people";
    LdapUser user = new LdapUser();
    user.setDn(dn);
    user.setCn("custom.attr");
    user.setSn("Custom");
    user.addCustomAttribute("employeeNumber", "E12345");

    assertThat(connector.create(user).isSuccess()).isTrue();

    OperationResult<LdapUser> read = connector.read(dn);

    assertThat(read.isSuccess()).isTrue();
    assertThat(read.getData().orElseThrow().getCustomAttributes())
            .containsEntry("employeeNumber", "E12345");
}

@Test
@DisplayName("verifyAttributes should pass for an attribute outside the ten mapped names")
void testVerifyAttributesSeesCustomAttributes() {
    String dn = "cn=custom.verify,ou=people";
    LdapUser user = new LdapUser();
    user.setDn(dn);
    user.setCn("custom.verify");
    user.setSn("Custom");
    user.addCustomAttribute("employeeNumber", "E67890");

    assertThat(connector.create(user).isSuccess()).isTrue();

    // Before the fix this fails with "expected=E67890, actual=null" even though the
    // directory genuinely holds the value.
    assertThat(connector.verifyAttributes(dn, Map.of("employeeNumber", "E67890")).isSuccess())
            .isTrue();
}

@Test
@DisplayName("read should return a multi-valued attribute as a list")
void testReadReturnsMultiValuedAttributeAsList() {
    String dn = "cn=multi.valued,ou=people";
    LdapUser user = new LdapUser();
    user.setDn(dn);
    user.setCn("multi.valued");
    user.setSn("Multi");

    assertThat(connector.create(user).isSuccess()).isTrue();

    Object objectClass = connector.read(dn).getData().orElseThrow()
            .getCustomAttributes().get("objectClass");

    assertThat(objectClass).isInstanceOf(List.class);
    assertThat((List<?>) objectClass).contains("inetOrgPerson");
}

@Test
@DisplayName("read should never expose userPassword, which would leak into reports")
void testReadExcludesUserPassword() {
    String dn = "cn=secret.holder,ou=people";
    LdapUser user = new LdapUser();
    user.setDn(dn);
    user.setCn("secret.holder");
    user.setSn("Secret");
    user.setUserPassword("s3cr3t");

    assertThat(connector.create(user).isSuccess()).isTrue();

    assertThat(connector.read(dn).getData().orElseThrow().getCustomAttributes())
            .doesNotContainKey("userPassword");
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl idm-connector-ldap -am test -Dtest=EdirectoryConnectorTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL. `testReadReturnsCustomAttributes` fails because `getCustomAttributes()` is `{}`; `testVerifyAttributesSeesCustomAttributes` fails with `Attribute mismatch for 'employeeNumber': expected=E67890, actual=null`.

- [ ] **Step 3: Replace UserContextMapper with an attribute-complete mapper**

Replace the whole `UserContextMapper` class (currently at the bottom of `EdirectoryConnector.java`) with:

```java
    /**
     * The ten attribute names that have first-class getters on {@link LdapUser}. Everything
     * else read from the directory becomes a custom attribute.
     */
    private static final Set<String> MAPPED_ATTRIBUTES = Set.of(
            "cn", "sn", "givenName", "mail", "uid",
            "telephoneNumber", "title", "ou", "description");

    /**
     * Never surfaced. A password read back here would flow into an {@code as:} capture and
     * from there into the JSON result file — a credential written to disk by a test report.
     * The cost is that a password change cannot be undone by cleanup; that is the honest
     * trade, and it matches why ldap.delete registers no undo at all.
     */
    private static final String EXCLUDED_ATTRIBUTE = "userPassword";

    /**
     * Maps every attribute the directory returns, not a fixed list.
     * <p>
     * The previous version read ten hardcoded names and never populated custom attributes,
     * which made {@link #verifyAttributes} report {@code actual=null} for any other attribute
     * even when the directory genuinely held a value — a false failure, not a missed one.
     */
    private static class UserContextMapper extends AbstractContextMapper<LdapUser> {
        @Override
        protected LdapUser doMapFromContext(DirContextOperations context) {
            LdapUser user = new LdapUser();
            user.setDn(context.getDn().toString());

            Attributes attributes = context.getAttributes();
            if (attributes == null) {
                return user;
            }

            try {
                NamingEnumeration<? extends Attribute> all = attributes.getAll();
                while (all.hasMore()) {
                    Attribute attribute = all.next();
                    String id = attribute.getID();

                    if (EXCLUDED_ATTRIBUTE.equalsIgnoreCase(id)) {
                        continue;
                    }
                    if (MAPPED_ATTRIBUTES.contains(id)) {
                        // The first-class getters are typed String, so a multi-valued
                        // instance of one of these collapses to its first value — the same
                        // thing getStringAttribute did before.
                        setMapped(user, id, String.valueOf(attribute.get()));
                    } else {
                        user.addCustomAttribute(id, valueOf(attribute));
                    }
                }
            } catch (NamingException e) {
                // Returning a partially-populated entry would be a silent lie: callers would
                // see a missing attribute as absent rather than unread. The template wraps
                // this into its own exception hierarchy, which read() already reports.
                throw new org.springframework.ldap.UncategorizedLdapException(
                        "Failed to read attributes for " + context.getDn(), e);
            }

            return user;
        }

        /**
         * A single-valued attribute reads as its scalar value; a multi-valued one as a list
         * of every value, so nothing is silently dropped.
         */
        private static Object valueOf(Attribute attribute) throws NamingException {
            if (attribute.size() == 1) {
                return attribute.get();
            }
            List<Object> values = new ArrayList<>(attribute.size());
            for (int i = 0; i < attribute.size(); i++) {
                values.add(attribute.get(i));
            }
            return values;
        }

        private static void setMapped(LdapUser user, String id, String value) {
            switch (id) {
                case "cn" -> user.setCn(value);
                case "sn" -> user.setSn(value);
                case "givenName" -> user.setGivenName(value);
                case "mail" -> user.setMail(value);
                case "uid" -> user.setUid(value);
                case "telephoneNumber" -> user.setTelephoneNumber(value);
                case "title" -> user.setTitle(value);
                case "ou" -> user.setOu(value);
                case "description" -> user.setDescription(value);
                default -> throw new IllegalStateException("not a mapped attribute: " + id);
            }
        }
    }
```

`EdirectoryConnector` already imports `javax.naming.NamingException`, `javax.naming.directory.Attribute`, `javax.naming.directory.Attributes`, `java.util.List` and `java.util.Map`, so the code above uses the short names. Add only the three that are missing: `javax.naming.NamingEnumeration`, `java.util.ArrayList`, `java.util.Set`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl idm-connector-ldap -am test -Dtest=EdirectoryConnectorTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 5: Run the whole suite — the attribute view got wider**

Run: `./mvnw test`

Expected: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped.

`ldap.verify`'s `as:` capture and `verifyAttributes`' actual-value map now include `objectClass` and any other previously-invisible attribute. If a test asserted exact map equality on a capture, it will now fail — **fix the assertion, do not narrow the mapper**. Report in your task report any test you changed and why.

- [ ] **Step 6: Commit**

```bash
git add idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/EdirectoryConnector.java \
        idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/EdirectoryConnectorTest.java
git commit -m "fix: read every LDAP attribute, not ten hardcoded names

UserContextMapper mapped a fixed list and never populated custom
attributes, so verifyAttributes compared every other attribute against
null and reported a false mismatch. An entry created with
employeeNumber=E12345 verified as 'expected=E12345, actual=null'.

userPassword stays excluded: it would otherwise reach as: captures and
the JSON result file.

ITF-8

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: ldap.update, with an undo that restores prior values

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapAttributes.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapUpdateStep.java`
- Modify: `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/EdirectoryConnector.java` (add `removeAttributes`)
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java`

**Interfaces:**
- Consumes: `EdirectoryConnector.read(String)` returning a complete attribute view (Task 1).
- Produces:
  - `LdapAttributes.of(LdapUser user)` → `Map<String, Object>`, package-private static, in `com.idm.test.engine.steps`.
  - `EdirectoryConnector.removeAttributes(String dn, Collection<String> attributeNames)` → `OperationResult<Void>`.
  - `new LdapUpdateStep(EdirectoryConnector)`, `name()` = `"ldap.update"`, requires `dn` and `attributes`, not retryable, `value()` is the dn.

- [ ] **Step 1: Write the failing tests**

Add to `LdapStepsTest`:

```java
@Test
@DisplayName("ldap.update should change attributes and restore prior values on undo")
void testUpdateRestoresPriorValues() {
    LdapCreateStep create = new LdapCreateStep(connector);
    LdapUpdateStep update = new LdapUpdateStep(connector);
    String dn = "cn=ada.update,ou=users";

    create.execute(Map.of("dn", dn, "attributes",
            Map.of("cn", "ada.update", "sn", "Lovelace", "title", "Analyst")), false);

    StepOutcome outcome = update.execute(
            Map.of("dn", dn, "attributes", Map.of("title", "Engineer")), false);

    assertThat(outcome.success()).isTrue();
    assertThat(connector.read(dn).getData().orElseThrow().getTitle()).isEqualTo("Engineer");

    outcome.undo().run();
    assertThat(connector.read(dn).getData().orElseThrow().getTitle()).isEqualTo("Analyst");
}

@Test
@DisplayName("ldap.update undo should remove an attribute that did not exist before")
void testUpdateUndoRemovesNewlyAddedAttribute() {
    LdapCreateStep create = new LdapCreateStep(connector);
    LdapUpdateStep update = new LdapUpdateStep(connector);
    String dn = "cn=ada.added,ou=users";

    create.execute(Map.of("dn", dn, "attributes",
            Map.of("cn", "ada.added", "sn", "Lovelace")), false);

    StepOutcome outcome = update.execute(
            Map.of("dn", dn, "attributes", Map.of("description", "temporary")), false);

    assertThat(outcome.success()).isTrue();
    assertThat(connector.read(dn).getData().orElseThrow().getDescription())
            .isEqualTo("temporary");

    // 'description' had no prior value, so restoring it means removing it. Without this the
    // undo would silently leave the attribute behind and still report success.
    outcome.undo().run();
    assertThat(connector.read(dn).getData().orElseThrow().getDescription()).isNull();
}

@Test
@DisplayName("ldap.update should report failure rather than throw for a missing entry")
void testUpdateMissingEntryFails() {
    LdapUpdateStep update = new LdapUpdateStep(connector);

    StepOutcome outcome = update.execute(
            Map.of("dn", "cn=nobody.here,ou=users", "attributes", Map.of("title", "X")), false);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("cn=nobody.here,ou=users");
    assertThat(outcome.undo()).isNull();
}

@Test
@DisplayName("ldap.update should bind the dn when a capture was requested")
void testUpdateCaptureIsTheDn() {
    LdapCreateStep create = new LdapCreateStep(connector);
    LdapUpdateStep update = new LdapUpdateStep(connector);
    String dn = "cn=ada.capture,ou=users";

    create.execute(Map.of("dn", dn, "attributes",
            Map.of("cn", "ada.capture", "sn", "Lovelace")), false);

    StepOutcome outcome = update.execute(
            Map.of("dn", dn, "attributes", Map.of("title", "Engineer")), true);

    assertThat(outcome.success()).isTrue();
    assertThat(outcome.value()).isEqualTo(dn);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=LdapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL to compile — `LdapUpdateStep` does not exist.

- [ ] **Step 3: Add removeAttributes to EdirectoryConnector**

Insert after the existing `update` method:

```java
    /**
     * Removes attributes entirely.
     * <p>
     * {@link #update} can only REPLACE, which cannot express "this attribute had no value
     * before". Without removal, an undo that restores prior state would silently leave behind
     * every attribute the update introduced.
     *
     * @param dn             the distinguished name
     * @param attributeNames attributes to remove; an empty collection is a no-op success
     * @return operation result indicating success or failure
     */
    public OperationResult<Void> removeAttributes(String dn, Collection<String> attributeNames) {
        log.debug("Removing LDAP attributes {} from: {}", attributeNames, dn);

        if (attributeNames.isEmpty()) {
            return OperationResult.<Void>builder()
                    .success(true)
                    .message("No attributes to remove")
                    .build();
        }

        try {
            Name name = LdapNameBuilder.newInstance(dn).build();

            javax.naming.directory.ModificationItem[] mods =
                new javax.naming.directory.ModificationItem[attributeNames.size()];

            int i = 0;
            for (String attributeName : attributeNames) {
                mods[i++] = new javax.naming.directory.ModificationItem(
                    javax.naming.directory.DirContext.REMOVE_ATTRIBUTE,
                    new BasicAttribute(attributeName));
            }

            ldapTemplate.modifyAttributes(name, mods);

            return OperationResult.<Void>builder()
                    .success(true)
                    .message("Attributes removed successfully")
                    .build();

        } catch (Exception e) {
            log.error("Failed to remove LDAP attributes from '{}': {}", dn, e.getMessage());
            return OperationResult.<Void>builder()
                    .success(false)
                    .message("Failed to remove attributes: " + e.getMessage())
                    .errorType(ErrorType.LDAP_ERROR)
                    .throwable(e)
                    .build();
        }
    }
```

Add the import `java.util.Collection` if absent.

- [ ] **Step 4: Extract LdapAttributes from LdapVerifyStep**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapAttributes.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.model.LdapUser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flattens an {@link LdapUser} into the name→value view a scenario sees.
 * <p>
 * One implementation, two callers: {@code LdapVerifyStep} exposes it through {@code as:} and
 * {@code LdapUpdateStep} reads prior values from it to build its undo. Two copies would drift,
 * and a drifted copy here means an undo that restores the wrong thing.
 */
final class LdapAttributes {

    private LdapAttributes() {
    }

    /**
     * Null-valued attributes are omitted rather than bound as null, so a path reference to one
     * fails loudly instead of rendering the literal text "null".
     */
    static Map<String, Object> of(LdapUser user) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, "dn", user.getDn());
        putIfPresent(attributes, "cn", user.getCn());
        putIfPresent(attributes, "sn", user.getSn());
        putIfPresent(attributes, "givenName", user.getGivenName());
        putIfPresent(attributes, "mail", user.getMail());
        putIfPresent(attributes, "uid", user.getUid());
        putIfPresent(attributes, "telephoneNumber", user.getTelephoneNumber());
        putIfPresent(attributes, "title", user.getTitle());
        putIfPresent(attributes, "ou", user.getOu());
        putIfPresent(attributes, "description", user.getDescription());
        attributes.putAll(user.getCustomAttributes());
        return attributes;
    }

    private static void putIfPresent(Map<String, Object> attributes, String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }
}
```

Then in `LdapVerifyStep`, delete its private `attributesOf` and `putIfPresent` methods and change the success return to:

```java
        return StepOutcome.success("verified " + dn, null, LdapAttributes.of(entry.getData().get()));
```

Remove the now-unused `java.util.LinkedHashMap` import from `LdapVerifyStep` if nothing else uses it.

- [ ] **Step 5: Write LdapUpdateStep**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapUpdateStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces attribute values on an existing LDAP entry.
 * <p>
 * Reads the entry first so its undo can put back exactly what was there. Attributes that had
 * no prior value are removed rather than left behind, which is why this needs
 * {@link EdirectoryConnector#removeAttributes} — a REPLACE cannot express "there was nothing
 * here before".
 */
public final class LdapUpdateStep implements StepType {

    private final EdirectoryConnector connector;

    public LdapUpdateStep(EdirectoryConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "ldap.update";
    }

    @Override
    public Set<String> requiredParams() {
        // 'attributes' is required, not optional: an update with nothing to change is always
        // an authoring mistake, and silently succeeding would hide it.
        return Set.of("dn", "attributes");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the dn is already in hand, so there is no extra work
        // to skip when nobody asked to capture it.
        String dn = String.valueOf(params.get("dn"));
        Map<String, Object> attributes = (Map<String, Object>) params.get("attributes");

        if (attributes == null || attributes.isEmpty()) {
            return StepOutcome.failure(
                    "ldap.update for " + dn + " has no attributes to change");
        }

        // Read before writing, so the undo restores values rather than guessing at them.
        OperationResult<LdapUser> before = connector.read(dn);
        if (!before.isSuccess() || before.getData().isEmpty()) {
            return StepOutcome.failure("ldap.update could not read " + dn
                    + " before updating it: " + before.getMessage());
        }

        Map<String, Object> priorView = LdapAttributes.of(before.getData().get());

        Map<String, Object> restore = new LinkedHashMap<>();
        List<String> absentBefore = new ArrayList<>();
        for (String name : attributes.keySet()) {
            if (priorView.containsKey(name)) {
                restore.put(name, priorView.get(name));
            } else {
                absentBefore.add(name);
            }
        }

        OperationResult<Void> result = connector.update(dn, attributes);
        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "ldap.update failed for " + dn + ": " + result.getMessage());
        }

        return StepOutcome.success(
                "updated " + dn + " " + attributes.keySet(),
                () -> undo(dn, restore, absentBefore),
                dn);
    }

    /**
     * Puts the entry back: prior values replaced, newly-introduced attributes removed.
     * Throws on failure so {@code ScenarioEngine.unwind} logs a real cleanup problem rather
     * than reporting a restore that did not happen.
     */
    private void undo(String dn, Map<String, Object> restore, List<String> absentBefore) {
        if (!restore.isEmpty()) {
            OperationResult<Void> restored = connector.update(dn, restore);
            if (!restored.isSuccess()) {
                throw new IllegalStateException("cleanup failed to restore " + restore.keySet()
                        + " on " + dn + ": " + restored.getMessage());
            }
        }
        if (!absentBefore.isEmpty()) {
            OperationResult<Void> removed = connector.removeAttributes(dn, absentBefore);
            if (!removed.isSuccess()) {
                throw new IllegalStateException("cleanup failed to remove " + absentBefore
                        + " from " + dn + ": " + removed.getMessage());
            }
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=LdapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 7: Commit**

```bash
git add idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/EdirectoryConnector.java \
        idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapAttributes.java \
        idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapUpdateStep.java \
        idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java \
        idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java
git commit -m "feat: add ldap.update step type with a restoring undo

Reads the entry before writing so the undo puts back exactly what was
there. Attributes with no prior value are removed rather than left
behind, which is what removeAttributes is for: REPLACE cannot express
'there was nothing here before'.

LdapAttributes is extracted from LdapVerifyStep so the capture view and
the undo's prior-value view cannot drift apart.

ITF-8

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: ldap.delete

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapDeleteStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java`

**Interfaces:**
- Consumes: `EdirectoryConnector.delete(String dn)` → `OperationResult<Void>`.
- Produces: `new LdapDeleteStep(EdirectoryConnector)`, `name()` = `"ldap.delete"`, requires `dn`, not retryable, **registers no undo**, `value()` is the dn.

- [ ] **Step 1: Write the failing tests**

Add to `LdapStepsTest`:

```java
@Test
@DisplayName("ldap.delete should remove the entry and register no undo")
void testDeleteRegistersNoUndo() {
    LdapCreateStep create = new LdapCreateStep(connector);
    LdapDeleteStep delete = new LdapDeleteStep(connector);
    String dn = "cn=ada.delete,ou=users";

    create.execute(Map.of("dn", dn, "attributes",
            Map.of("cn", "ada.delete", "sn", "Lovelace")), false);

    StepOutcome outcome = delete.execute(Map.of("dn", dn), false);

    assertThat(outcome.success()).isTrue();
    assertThat(connector.read(dn).isSuccess()).isFalse();

    // Deliberate: a recreated entry silently loses userPassword and operational attributes,
    // so no undo is better than a convincing fake.
    assertThat(outcome.undo()).isNull();
}

@Test
@DisplayName("ldap.delete should report failure rather than throw for a missing entry")
void testDeleteMissingEntryFails() {
    LdapDeleteStep delete = new LdapDeleteStep(connector);

    StepOutcome outcome = delete.execute(Map.of("dn", "cn=never.existed,ou=users"), false);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("cn=never.existed,ou=users");
}

@Test
@DisplayName("ldap.delete should bind the dn when a capture was requested")
void testDeleteCaptureIsTheDn() {
    LdapCreateStep create = new LdapCreateStep(connector);
    LdapDeleteStep delete = new LdapDeleteStep(connector);
    String dn = "cn=ada.deletecapture,ou=users";

    create.execute(Map.of("dn", dn, "attributes",
            Map.of("cn", "ada.deletecapture", "sn", "Lovelace")), false);

    StepOutcome outcome = delete.execute(Map.of("dn", dn), true);

    assertThat(outcome.success()).isTrue();
    assertThat(outcome.value()).isEqualTo(dn);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=LdapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL to compile — `LdapDeleteStep` does not exist.

- [ ] **Step 3: Write LdapDeleteStep**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapDeleteStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;

/**
 * Deletes an LDAP entry.
 * <p>
 * <b>Registers no undo, deliberately.</b> Recreating the entry would need every attribute it
 * had, and {@code userPassword} and operational attributes (createTimestamp, entryUUID) cannot
 * be read back. The "restored" entry would differ from the original without saying so, and a
 * cleanup that quietly produces a different entry is worse than one that does nothing. A
 * scenario that needs the entry back should recreate it with an explicit {@code ldap.create}.
 */
public final class LdapDeleteStep implements StepType {

    private final EdirectoryConnector connector;

    public LdapDeleteStep(EdirectoryConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "ldap.delete";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("dn");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the dn is already in hand, so there is no extra work
        // to skip when nobody asked to capture it.
        String dn = String.valueOf(params.get("dn"));

        OperationResult<Void> result = connector.delete(dn);

        if (!result.isSuccess()) {
            return StepOutcome.failure("ldap.delete failed for " + dn + ": " + result.getMessage());
        }

        return StepOutcome.success("deleted " + dn, null, dn);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=LdapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 5: Commit**

```bash
git add idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapDeleteStep.java \
        idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java
git commit -m "feat: add ldap.delete step type

Registers no undo by design: userPassword and operational attributes
cannot be read back, so a recreated entry would silently differ from
the original. No cleanup beats a convincing fake.

ITF-8

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: db.execute

**Files:**
- Modify: `idm-test-engine/pom.xml`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/DbExecuteStep.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/steps/DbStepsTest.java`

**Interfaces:**
- Consumes: `MultiDatabaseConnector.executeUpdate(String databaseName, String sql, Object... params)` → `OperationResult<Integer>`.
- Produces: `new DbExecuteStep(MultiDatabaseConnector)`, `name()` = `"db.execute"`, requires `database` and `statement`, also accepts `params` and `undo`, not retryable, `value()` is the affected row count as an `Integer`.

YAML shape:

```yaml
- db.execute:
    database: test-db
    statement: "INSERT INTO sap_users (username, status) VALUES (?, ?)"
    params: ["${user.userId}", "ACTIVE"]
    undo:
      statement: "DELETE FROM sap_users WHERE username = ?"
      params: ["${user.userId}"]
```

`undo` is optional. Its `statement` and `params` are already interpolated by the time the step runs, so the undo closes over the values as they were at execution time — which is what you want.

- [ ] **Step 1: Add the database module dependency**

`idm-test-engine/pom.xml` currently depends on `idm-test-core`, `idm-test-utils` and `idm-connector-ldap` only. Add, next to the `idm-connector-ldap` dependency:

```xml
        <dependency>
            <groupId>com.idm.test</groupId>
            <artifactId>idm-connector-database</artifactId>
        </dependency>
```

Add H2 as a test-scoped dependency in the same file so `DbStepsTest` has a real database:

```xml
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Write the failing tests**

Create `idm-test-engine/src/test/java/com/idm/test/engine/steps/DbStepsTest.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.engine.spi.StepOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the database step types against H2.
 * <p>
 * Builds the JdbcTemplate directly rather than through Spring, because this module has no
 * Spring context by design.
 */
@DisplayName("Database step types")
class DbStepsTest {

    private static final String DB = "test-db";

    private static JdbcTemplate jdbc;
    private static MultiDatabaseConnector connector;

    @BeforeAll
    static void startDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:dbsteps;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sap_users ("
                + "username VARCHAR(50), status VARCHAR(20), attempts INT)");
        connector = new MultiDatabaseConnector(Map.of(DB, jdbc));
    }

    @BeforeEach
    void clearTable() {
        jdbc.update("DELETE FROM sap_users");
    }

    @Test
    @DisplayName("db.execute should insert a row and report the affected count")
    void testExecuteInsert() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO sap_users (username, status) VALUES (?, ?)",
                "params", List.of("jdoe", "ACTIVE")), true);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sap_users WHERE username = 'jdoe'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("db.execute should run its declared undo on unwind")
    void testExecuteUndo() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO sap_users (username, status) VALUES (?, ?)",
                "params", List.of("jdoe", "ACTIVE"),
                "undo", Map.of(
                        "statement", "DELETE FROM sap_users WHERE username = ?",
                        "params", List.of("jdoe"))), false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.undo()).isNotNull();

        outcome.undo().run();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sap_users", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("db.execute should register no undo when none was declared")
    void testExecuteWithoutUndo() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO sap_users (username, status) VALUES ('x', 'y')"), false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("db.execute should report failure rather than throw on bad SQL")
    void testExecuteBadSqlFails() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO no_such_table (a) VALUES (1)"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("db.execute should report failure for an unknown database name")
    void testExecuteUnknownDatabaseFails() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", "no-such-db",
                "statement", "INSERT INTO sap_users (username) VALUES ('x')"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("no-such-db");
    }

    @Test
    @DisplayName("db.execute should reject an undo block with no statement")
    void testExecuteRejectsUndoWithoutStatement() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO sap_users (username) VALUES ('x')",
                "undo", Map.of("params", List.of("x"))), false);

        // Caught before the statement runs: an undo that cannot run is a cleanup hole, and
        // discovering it at unwind time is far too late.
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("undo");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sap_users", Integer.class))
                .isZero();
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=DbStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL to compile — `DbExecuteStep` does not exist.

- [ ] **Step 4: Write DbExecuteStep**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/DbExecuteStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs an INSERT, UPDATE or DELETE.
 * <p>
 * The undo is explicit, never inferred: there is no general way to derive the inverse of an
 * arbitrary SQL statement, and a guessed inverse would be worse than none. A scenario that
 * needs its rows cleaned up declares how.
 */
public final class DbExecuteStep implements StepType {

    private final MultiDatabaseConnector connector;

    public DbExecuteStep(MultiDatabaseConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "db.execute";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("database", "statement");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("database", "statement", "params", "undo");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the affected-row count comes back from the statement
        // itself, so there is no extra work to skip when nobody asked to capture it.
        String database = String.valueOf(params.get("database"));
        String statement = String.valueOf(params.get("statement"));
        Object[] arguments = argumentsOf(params.get("params"));

        Map<String, Object> undoSpec = (Map<String, Object>) params.get("undo");
        String undoStatement = null;
        Object[] undoArguments = null;

        if (undoSpec != null) {
            Object rawUndoStatement = undoSpec.get("statement");
            if (rawUndoStatement == null) {
                // Validated before running anything. An undo that cannot run is a cleanup
                // hole, and unwind time — after the scenario has already finished — is far
                // too late to find out.
                return StepOutcome.failure("db.execute on '" + database
                        + "' declares an undo with no statement");
            }
            undoStatement = String.valueOf(rawUndoStatement);
            undoArguments = argumentsOf(undoSpec.get("params"));
        }

        OperationResult<Integer> result = connector.executeUpdate(database, statement, arguments);

        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "db.execute failed on '" + database + "': " + result.getMessage());
        }

        int affected = result.getData().orElse(0);
        String message = "executed on '" + database + "', " + affected + " row(s) affected";

        if (undoStatement == null) {
            return StepOutcome.success(message, null, affected);
        }

        String finalUndoStatement = undoStatement;
        Object[] finalUndoArguments = undoArguments;
        return StepOutcome.success(message, () -> {
            OperationResult<Integer> undone =
                    connector.executeUpdate(database, finalUndoStatement, finalUndoArguments);
            if (!undone.isSuccess()) {
                throw new IllegalStateException("cleanup failed on '" + database + "': "
                        + undone.getMessage());
            }
        }, affected);
    }

    /**
     * YAML gives a list; JdbcTemplate wants a varargs array. An absent list means no
     * parameters, which is ordinary for a statement with no placeholders.
     */
    private static Object[] argumentsOf(Object raw) {
        if (raw == null) {
            return new Object[0];
        }
        if (raw instanceof List<?> list) {
            return list.toArray();
        }
        return new Object[]{raw};
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=DbStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 6: Confirm the engine still has no Spring dependency in main**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=NoSpringDependencyTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. `DbExecuteStep` imports only `com.idm.test.*` and `java.*`. If this fails you have imported a Spring type into `src/main` — remove it; do not weaken the test.

- [ ] **Step 7: Commit**

```bash
git add idm-test-engine/pom.xml \
        idm-test-engine/src/main/java/com/idm/test/engine/steps/DbExecuteStep.java \
        idm-test-engine/src/test/java/com/idm/test/engine/steps/DbStepsTest.java
git commit -m "feat: add db.execute step type with an explicit undo

The undo is declared, never inferred: there is no general inverse of an
arbitrary SQL statement, and a guessed one would be worse than none. An
undo block missing its statement fails before the statement runs, since
unwind time is too late to discover a cleanup hole.

ITF-8

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: db.verify

**Files:**
- Create: `idm-connector-database/src/main/java/com/idm/test/connector/database/client/ValueComparison.java`
- Modify: `idm-connector-database/src/main/java/com/idm/test/connector/database/client/MultiDatabaseConnector.java` (delegate `valuesEqual`)
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/DbVerifyStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/DbStepsTest.java`

**Interfaces:**
- Consumes: `MultiDatabaseConnector.executeQuery(String databaseName, String sql, Object... params)` → `OperationResult<List<Map<String, Object>>>`.
- Produces:
  - `ValueComparison.equal(Object expected, Object actual)` → `boolean`, public static.
  - `new DbVerifyStep(MultiDatabaseConnector)`, `name()` = `"db.verify"`, requires `database`, `query` and `expect`, also accepts `params`, **retryable**, `value()` is the `List<Map<String, Object>>` of rows.

YAML shape (spec §7.1, verbatim):

```yaml
- db.verify:
    database: sap
    query: "SELECT status FROM sap_users WHERE username = ?"
    params: ["${user.userId}"]
    expect:
      rowCount: 1
      row: { status: ACTIVE }
    wait: true
```

Semantics, decided here:
- `expect` must contain at least one of `rowCount` or `row`. An `expect` with neither would assert nothing while looking like an assertion — the same vacuous-assertion trap ITF-6 already had to fix once.
- Any key inside `expect` other than `rowCount` and `row` is an error, so `rowCont:` is caught rather than ignored.
- `rowCount` asserts the exact number of rows.
- `row` requires **exactly one** row. Matching "the first row" of a multi-row result would depend on SQL's unordered result set — non-deterministic without an ORDER BY, and intermittently green is worse than red.
- Column lookup goes against the map `executeQuery` returned, which is a `LinkedCaseInsensitiveMap`. That is what makes `row: { status: ACTIVE }` match H2's `STATUS`. **Do not copy rows into a plain map before matching** — it would silently break case-insensitivity.

- [ ] **Step 1: Write the failing tests**

Add to `DbStepsTest`:

```java
@Test
@DisplayName("db.verify should pass when rowCount and row both match")
void testVerifyMatches() {
    jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 3);
    DbVerifyStep verify = new DbVerifyStep(connector);

    StepOutcome outcome = verify.execute(Map.of(
            "database", DB,
            "query", "SELECT * FROM sap_users WHERE username = ?",
            "params", List.of("jdoe"),
            "expect", Map.of("rowCount", 1, "row", Map.of("status", "ACTIVE"))), false);

    assertThat(outcome.success()).isTrue();
}

@Test
@DisplayName("db.verify should match a column regardless of the database's casing")
void testVerifyIsCaseInsensitiveOnColumnNames() {
    jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 3);
    DbVerifyStep verify = new DbVerifyStep(connector);

    // H2 stores unquoted identifiers uppercase, so the row's key is STATUS. A scenario
    // author writes status. Spring's LinkedCaseInsensitiveMap bridges the two — this test
    // exists so a refactor that copies rows into a plain HashMap gets caught.
    StepOutcome outcome = verify.execute(Map.of(
            "database", DB,
            "query", "SELECT * FROM sap_users",
            "expect", Map.of("row", Map.of("status", "ACTIVE"))), false);

    assertThat(outcome.success()).isTrue();
}

@Test
@DisplayName("db.verify should match a numeric column across JDBC and YAML types")
void testVerifyMatchesNumericAcrossTypes() {
    jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 3);
    DbVerifyStep verify = new DbVerifyStep(connector);

    StepOutcome outcome = verify.execute(Map.of(
            "database", DB,
            "query", "SELECT * FROM sap_users",
            "expect", Map.of("row", Map.of("attempts", 3))), false);

    assertThat(outcome.success()).isTrue();
}

@Test
@DisplayName("db.verify should fail on a value mismatch and name the column")
void testVerifyValueMismatchFails() {
    jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "PENDING", 0);
    DbVerifyStep verify = new DbVerifyStep(connector);

    StepOutcome outcome = verify.execute(Map.of(
            "database", DB,
            "query", "SELECT * FROM sap_users",
            "expect", Map.of("row", Map.of("status", "ACTIVE"))), false);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("status").contains("ACTIVE").contains("PENDING");
}

@Test
@DisplayName("db.verify should fail when rowCount does not match")
void testVerifyRowCountMismatchFails() {
    jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 1);
    jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "asmith", "ACTIVE", 1);
    DbVerifyStep verify = new DbVerifyStep(connector);

    StepOutcome outcome = verify.execute(Map.of(
            "database", DB,
            "query", "SELECT * FROM sap_users",
            "expect", Map.of("rowCount", 1)), false);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("2");
}

@Test
@DisplayName("db.verify should reject a row matcher against more than one row")
void testVerifyRowMatcherRequiresExactlyOneRow() {
    jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 1);
    jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "asmith", "ACTIVE", 1);
    DbVerifyStep verify = new DbVerifyStep(connector);

    // Matching "the first row" would depend on an unordered result set: intermittently
    // green, which is worse than red.
    StepOutcome outcome = verify.execute(Map.of(
            "database", DB,
            "query", "SELECT * FROM sap_users",
            "expect", Map.of("row", Map.of("status", "ACTIVE"))), false);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("exactly one row");
}

@Test
@DisplayName("db.verify should reject an expect block that asserts nothing")
void testVerifyRejectsEmptyExpect() {
    DbVerifyStep verify = new DbVerifyStep(connector);

    // The ITF-6 vacuous-assertion trap: an expect with nothing in it would pass having
    // compared nothing at all.
    StepOutcome outcome = verify.execute(Map.of(
            "database", DB,
            "query", "SELECT * FROM sap_users",
            "expect", Map.of()), false);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("rowCount").contains("row");
}

@Test
@DisplayName("db.verify should reject an unknown key inside expect")
void testVerifyRejectsUnknownExpectKey() {
    DbVerifyStep verify = new DbVerifyStep(connector);

    StepOutcome outcome = verify.execute(Map.of(
            "database", DB,
            "query", "SELECT * FROM sap_users",
            "expect", Map.of("rowCont", 1)), false);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("rowCont");
}

@Test
@DisplayName("db.verify should bind the rows when a capture was requested")
void testVerifyCaptureIsTheRows() {
    jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 3);
    DbVerifyStep verify = new DbVerifyStep(connector);

    StepOutcome outcome = verify.execute(Map.of(
            "database", DB,
            "query", "SELECT * FROM sap_users",
            "expect", Map.of("rowCount", 1)), true);

    assertThat(outcome.success()).isTrue();
    assertThat(outcome.value()).isInstanceOf(List.class);
    assertThat((List<?>) outcome.value()).hasSize(1);
}

@Test
@DisplayName("db.verify should be retryable so the engine can wait on provisioning")
void testVerifyIsRetryable() {
    assertThat(new DbVerifyStep(connector).retryable()).isTrue();
}

@Test
@DisplayName("db.verify should report failure rather than throw on bad SQL")
void testVerifyBadSqlFails() {
    DbVerifyStep verify = new DbVerifyStep(connector);

    StepOutcome outcome = verify.execute(Map.of(
            "database", DB,
            "query", "SELECT * FROM no_such_table",
            "expect", Map.of("rowCount", 0)), false);

    assertThat(outcome.success()).isFalse();
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=DbStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL to compile — `DbVerifyStep` does not exist.

- [ ] **Step 3: Extract the value comparison so it cannot drift**

Create `idm-connector-database/src/main/java/com/idm/test/connector/database/client/ValueComparison.java`:

```java
package com.idm.test.connector.database.client;

/**
 * Compares an expected value from a scenario against an actual value from JDBC.
 * <p>
 * Shared deliberately. {@code MultiDatabaseConnector.verifyRecord} and the engine's
 * {@code db.verify} step both answer "does this column hold what the author expected?", and
 * two copies of that answer would drift — one of them tolerating a type difference the other
 * rejects, so the same assertion passes in one place and fails in the other.
 */
public final class ValueComparison {

    private ValueComparison() {
    }

    /**
     * A driver may return a column as Integer, Long or BigDecimal where YAML supplied a plain
     * integer, so numbers compare by value rather than by boxed type. Everything else falls
     * back to equals, then to string form, which lets a scenario write a date or enum the way
     * a person would.
     */
    public static boolean equal(Object expected, Object actual) {
        if (expected == null && actual == null) {
            return true;
        }
        if (expected == null || actual == null) {
            return false;
        }
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            return expectedNumber.doubleValue() == actualNumber.doubleValue();
        }
        return expected.equals(actual) || expected.toString().equals(actual.toString());
    }
}
```

Then replace the body of `MultiDatabaseConnector.valuesEqual` so there is one implementation:

```java
    private boolean valuesEqual(Object expected, Object actual) {
        return ValueComparison.equal(expected, actual);
    }
```

- [ ] **Step 4: Write DbVerifyStep**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/DbVerifyStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.database.client.ValueComparison;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Asserts that a query returns the expected rows.
 * <p>
 * Retryable: provisioning is asynchronous, so the engine may call this repeatedly under a
 * {@code wait} until it passes or the timeout expires.
 * <p>
 * {@code rowCount} and {@code row} are separate keys rather than one merged matcher, so a
 * table with a column named {@code rows} or {@code rowCount} cannot change how a scenario
 * parses (spec §7.3).
 */
public final class DbVerifyStep implements StepType {

    private static final String ROW_COUNT = "rowCount";
    private static final String ROW = "row";
    private static final Set<String> EXPECT_KEYS = Set.of(ROW_COUNT, ROW);

    private final MultiDatabaseConnector connector;

    public DbVerifyStep(MultiDatabaseConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "db.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("database", "query", "expect");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("database", "query", "params", "expect", "wait");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String database = String.valueOf(params.get("database"));
        String query = String.valueOf(params.get("query"));
        Object[] arguments = argumentsOf(params.get("params"));
        Map<String, Object> expect = (Map<String, Object>) params.get("expect");

        StepOutcome rejection = rejectMalformedExpect(expect, database);
        if (rejection != null) {
            return rejection;
        }

        OperationResult<List<Map<String, Object>>> result =
                connector.executeQuery(database, query, arguments);

        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "db.verify failed on '" + database + "': " + result.getMessage());
        }

        List<Map<String, Object>> rows = result.getData().orElse(List.of());

        if (expect.containsKey(ROW_COUNT)) {
            Object expectedCount = expect.get(ROW_COUNT);
            if (!ValueComparison.equal(expectedCount, rows.size())) {
                return StepOutcome.failure("db.verify on '" + database + "' expected "
                        + expectedCount + " row(s) but the query returned " + rows.size());
            }
        }

        if (expect.containsKey(ROW)) {
            if (rows.size() != 1) {
                // Matching "the first row" of a multi-row result would depend on an unordered
                // result set — intermittently green, which is worse than red.
                return StepOutcome.failure("db.verify on '" + database
                        + "' uses a row matcher, which needs exactly one row, but the query "
                        + "returned " + rows.size()
                        + ". Narrow the query, or assert rowCount instead.");
            }

            // Matched against the map executeQuery returned, which is case-insensitive on
            // column names. Copying it into a plain map first would silently break
            // `row: { status: ... }` against a database that stores STATUS.
            Map<String, Object> actual = rows.get(0);
            Map<String, Object> expectedRow = (Map<String, Object>) expect.get(ROW);

            for (Map.Entry<String, Object> column : expectedRow.entrySet()) {
                Object actualValue = actual.get(column.getKey());
                if (!ValueComparison.equal(column.getValue(), actualValue)) {
                    return StepOutcome.failure("db.verify on '" + database
                            + "' found a mismatch for column '" + column.getKey()
                            + "': expected=" + column.getValue() + ", actual=" + actualValue);
                }
            }
        }

        String message = "verified " + rows.size() + " row(s) on '" + database + "'";
        return captureRequested
                ? StepOutcome.success(message, null, rows)
                : StepOutcome.success(message);
    }

    /**
     * An {@code expect} that asserts nothing, or one whose keys are typos, would pass having
     * compared nothing at all — the vacuous-assertion trap, caught once already on
     * {@code ldap.verify}. Checked before the query runs, since neither problem depends on
     * the result.
     */
    private static StepOutcome rejectMalformedExpect(Map<String, Object> expect, String database) {
        if (expect == null || expect.isEmpty()) {
            return StepOutcome.failure("db.verify on '" + database
                    + "' has an empty expect: block. Assert " + ROW_COUNT + ", " + ROW
                    + ", or both — an empty expect would verify nothing.");
        }

        Set<String> unknown = new TreeSet<>(expect.keySet());
        unknown.removeAll(EXPECT_KEYS);
        if (!unknown.isEmpty()) {
            return StepOutcome.failure("db.verify on '" + database
                    + "' has unknown key(s) in expect: " + unknown + ". expect accepts: "
                    + new TreeSet<>(EXPECT_KEYS));
        }

        if (expect.get(ROW) != null && !(expect.get(ROW) instanceof Map)) {
            return StepOutcome.failure("db.verify on '" + database + "' has a " + ROW
                    + " that is not a map of column names to expected values");
        }

        return null;
    }

    /**
     * YAML gives a list; JdbcTemplate wants a varargs array. An absent list means no
     * parameters, which is ordinary for a query with no placeholders.
     */
    private static Object[] argumentsOf(Object raw) {
        if (raw == null) {
            return new Object[0];
        }
        if (raw instanceof List<?> list) {
            return list.toArray();
        }
        return new Object[]{raw};
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=DbStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 6: Run the database connector tests, which now share the comparison**

Run: `./mvnw -pl idm-connector-database -am test -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors. `ValueComparison.equal` reproduces `valuesEqual`'s behaviour exactly, so nothing should change.

- [ ] **Step 7: Commit**

```bash
git add idm-connector-database/src/main/java/com/idm/test/connector/database/client/ValueComparison.java \
        idm-connector-database/src/main/java/com/idm/test/connector/database/client/MultiDatabaseConnector.java \
        idm-test-engine/src/main/java/com/idm/test/engine/steps/DbVerifyStep.java \
        idm-test-engine/src/test/java/com/idm/test/engine/steps/DbStepsTest.java
git commit -m "feat: add db.verify step type

rowCount and row stay separate keys so a table with a column named rows
cannot change how a scenario parses. A row matcher requires exactly one
row: matching the first row of an unordered result set would be
intermittently green, which is worse than red.

An empty or misspelled expect: fails rather than verifying nothing —
the vacuous-assertion trap already caught once on ldap.verify.

Value comparison is extracted so the connector and the step cannot
drift into disagreeing about the same assertion.

ITF-8

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Register the four step types and prove them end to end

**Files:**
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java`
- Create: `idm-test-scenarios/src/test/resources/scenarios/database-provisioning.yaml`

**Interfaces:**
- Consumes: `LdapUpdateStep`, `LdapDeleteStep`, `DbExecuteStep`, `DbVerifyStep` from Tasks 2–5; `BaseIntegrationTest.databaseConnector` (`MultiDatabaseConnector`, already `@Autowired`).
- Produces: a registry containing all six step types, and a YAML scenario exercising the new ones.

- [ ] **Step 1: Widen the registry**

In `YamlScenarioTest`, change the `registry` method's signature and body:

```java
    static Map<String, StepType> registry(
            EdirectoryConnector edirectoryConnector, MultiDatabaseConnector databaseConnector) {
        Map<String, StepType> registry = new LinkedHashMap<>();
        for (StepType type : List.of(
                new LdapCreateStep(edirectoryConnector),
                new LdapVerifyStep(edirectoryConnector),
                new LdapUpdateStep(edirectoryConnector),
                new LdapDeleteStep(edirectoryConnector),
                new DbExecuteStep(databaseConnector),
                new DbVerifyStep(databaseConnector))) {
            registry.put(type.name(), type);
        }
        return registry;
    }
```

Update the single call site in `scenarios()` to `registry(edirectoryConnector, databaseConnector)`, and add the imports for `MultiDatabaseConnector`, `LdapUpdateStep`, `LdapDeleteStep`, `DbExecuteStep` and `DbVerifyStep`.

Search the module for any other caller of `registry(` and update it too:

```bash
grep -rn "registry(" idm-test-scenarios/src/test/java/
```

- [ ] **Step 2: Create the H2 table the scenario needs**

The scenarios module configures an H2 database named `test-db`, but no table. Add a `@BeforeAll` to `YamlScenarioTest` that creates one, using the connector rather than a second DataSource:

```java
    @BeforeAll
    static void createScenarioTable(@Autowired MultiDatabaseConnector databaseConnector) {
        databaseConnector.executeUpdate("test-db",
                "CREATE TABLE IF NOT EXISTS sap_users ("
                        + "username VARCHAR(50), status VARCHAR(20))");
    }
```

Add imports for `org.junit.jupiter.api.BeforeAll` and `org.springframework.beans.factory.annotation.Autowired` if absent. If the class's Spring test configuration does not support parameter injection on a static `@BeforeAll`, make it a non-static `@BeforeEach` using the inherited `databaseConnector` field instead — `CREATE TABLE IF NOT EXISTS` is idempotent, so running it per test is harmless.

- [ ] **Step 3: Write the scenario**

Create `idm-test-scenarios/src/test/resources/scenarios/database-provisioning.yaml`:

```yaml
name: Database provisioning and directory update
description: Insert an application account, verify it, then update and delete the directory entry

fixtures:
  user:
    generate: random-user

steps:
  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      attributes:
        cn: "${user.userId}"
        sn: "${user.lastName}"
        givenName: "${user.firstName}"
        title: Analyst

  - db.execute:
      database: test-db
      statement: "INSERT INTO sap_users (username, status) VALUES (?, ?)"
      params: ["${user.userId}", "ACTIVE"]
      undo:
        statement: "DELETE FROM sap_users WHERE username = ?"
        params: ["${user.userId}"]

  - db.verify:
      database: test-db
      query: "SELECT status FROM sap_users WHERE username = ?"
      params: ["${user.userId}"]
      expect:
        rowCount: 1
        row: { status: ACTIVE }
      wait: true

  - ldap.update:
      dn: "cn=${user.userId},ou=users"
      attributes:
        title: Engineer

  - ldap.verify:
      dn: "cn=${user.userId},ou=users"
      expect:
        title: Engineer

  - ldap.delete:
      dn: "cn=${user.userId},ou=users"
```

Note the scenario ends by deleting the entry that `ldap.create` registered an undo for. The engine's unwind will then try to delete an entry that is already gone; that undo failure is logged and does not fail the scenario (`ScenarioEngine.unwind`). If the run log is noisy about it, report that in your task report rather than changing `unwind`'s contract.

- [ ] **Step 4: Run the scenario end to end**

Run: `./mvnw -pl idm-test-scenarios -am test -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. Both `user-provisioning.yaml` and `database-provisioning.yaml` run as their own dynamic tests, 0 failures.

- [ ] **Step 5: Run the whole suite**

Run: `./mvnw test`

Expected: BUILD SUCCESS, 0 failures, 0 errors, **0 skipped**. Record the exact total in your task report.

- [ ] **Step 6: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java \
        idm-test-scenarios/src/test/resources/scenarios/database-provisioning.yaml
git commit -m "feat: register the LDAP and database step types end to end

A YAML scenario now drives ldap.create, db.execute, db.verify,
ldap.update, ldap.verify and ldap.delete against the embedded directory
and H2, which is the only test that exercises the engine and the
connectors together.

ITF-8

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage**

| Spec requirement | Task |
|---|---|
| §7.3 `ldap.update` | Task 2 |
| §7.3 `ldap.delete` | Task 3 |
| §7.3 `db.execute` | Task 4 |
| §7.3 `db.verify` (assertion, retryable) | Task 5 |
| §7.2 `wait` on assertions, `wait: true` uses defaults | Task 5 (`retryable()` true, `wait` accepted); engine already parses it |
| §7.3 reserved keys never share a namespace with user data | Task 5 (`rowCount`/`row` separate, unknown keys rejected) |
| §7.5 `as:` binds the rows for `db.verify` | Task 5 |
| §7.6 every action step registers its undo | Tasks 2 and 4; Task 3 is the documented, owner-approved exception |
| §7.6 optional `cleanup:` block | **Out of scope — ITF-11** |

**2. Placeholder scan.** No TBD/TODO. Every code step carries complete code. Every command is exact and carries its expected result.

**3. Type consistency.** `StepOutcome.success(String, Runnable, Object)` and `StepOutcome.success(String)` are the only factories used; there is no `success(String, Object)` and none is referenced. `execute(Map<String, Object>, boolean)` is the single abstract method everywhere. `ValueComparison.equal` is defined in Task 5 before its uses in the same task. `LdapAttributes.of` is defined in Task 2 and used by both `LdapVerifyStep` and `LdapUpdateStep`. `removeAttributes(String, Collection<String>)` is defined in Task 2 Step 3 and called in Task 2 Step 5.

**4. Known gaps, recorded rather than hidden.**

- A `db.verify` capture binds a `List<Map<String,Object>>`, which `Interpolator.lookup` cannot index — it navigates Maps only. `${rows...}` will not reach a row; only `js:{ rows[0].STATUS }` will, and the key is case-sensitive there even though the `row:` matcher is not. This follows spec §7.5 as written. ITF-5's conversion of the 27 existing scenarios is the right place to decide whether `${}` needs list indexing.
- A password change made by `ldap.update` cannot be undone, because `userPassword` is deliberately never read back (decision 6).
