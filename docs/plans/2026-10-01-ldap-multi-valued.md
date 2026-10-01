# Multi-valued LDAP attributes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ldap.verify` compares multi-valued attributes as unordered sets, with `contains`/`excludes` matchers and DN-aware value comparison; `ldap.create` and `ldap.update` write YAML lists as multi-valued attributes.

**Architecture:** A pure `LdapValueMatcher` in the LDAP connector module holds the comparison and shape rules. `EdirectoryConnector` gains `readAttributes(dn)` (every attribute, all values) and delegates `verifyAttributes` to the matcher; `create`/`update` write collections as several values. The engine's `LdapVerifyStep` rejects malformed matchers before I/O and captures from `readAttributes`; `LdapCreateStep`/`LdapUpdateStep` validate lists.

**Tech Stack:** Java 21, Spring LDAP (`LdapTemplate`, `DirContextAdapter`), JNDI (`javax.naming.ldap.LdapName`), JUnit 5, AssertJ, Mockito, UnboundID embedded LDAP.

**Ticket:** ITF-38. **Spec:** `docs/specs/2026-10-01-ldap-multi-valued-design.md`.

## Global Constraints

- The engine module stays Spring-free (`NoSpringDependencyTest`); the matcher lives in `idm-connector-ldap`, which the engine already depends on.
- A plain list in `expect` is the exact set of values, in any order; `contains` = every listed value present, others ignored; `excludes` = none present, an absent attribute passes; both may be given in one map, `contains` checked first.
- Two values compare as DNs when both parse as `javax.naming.ldap.LdapName` with at least one RDN (`LdapName.equals`); otherwise `toString()` equality (so `"200"` matches `200`).
- The actual value is a set: absent = empty set, scalar = one value, list = its values; duplicates ignored.
- Failure messages, exactly:
  - scalar: `Attribute 'title': expected=Manager, actual=[Manager, Lead]` (`actual=null` when absent)
  - plain list: `Attribute 'groupMembership': expected exactly [a, b] in any order; missing [b]; unexpected [c]` — `missing`/`unexpected` each left out when empty
  - contains: `Attribute 'groupMembership': expected to contain [a, b]; missing [b]; actual=[a, c]`
  - excludes: `Attribute 'groupMembership': expected not to contain [f]; found [f]`
  - a single actual value shows as itself (`actual=Manager`), several as a list
- `contains`/`excludes` accept a list or a single value.
- Rejected before any I/O in `ldap.verify` (`StepOutcome.finalFailure`, naming step, entry and attribute): empty plain list (message points to `excludes`); map keys other than `contains`/`excludes`, or neither; empty `contains`/`excludes`; a `null`, map or list inside them or inside a plain list; the same value (DN-aware) in both `contains` and `excludes`.
- `readAttributes` excludes `userPassword`; `LdapUser` and `read` are unchanged.
- `ldap.create`: a list writes several values (a list for one of the ten built-in attributes goes to the custom attributes); an empty list fails the step. `ldap.update`: a list replaces with several values; an empty list removes the attribute. A `null`, map or list item in a list fails either step.
- Secrets never appear in step messages, reports or logs.
- Commit trailer: a blank line, `ITF-38`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven: always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`.

**Clarification beyond the spec (controller decision):** `readAttributes` returns a `TreeMap` ordered by `String.CASE_INSENSITIVE_ORDER`, so `expect: { telephonenumber: … }` and `${entry.telephoneNumber}` find the attribute whatever case the directory reports — the ten built-in names were canonical before, and this keeps them reliable.

---

## File Structure

| File | Responsibility |
|---|---|
| Create `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/LdapValueMatcher.java` | Pure comparison and shape rules for one attribute |
| Create `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/LdapValueMatcherTest.java` | Unit tests for the matcher |
| Modify `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/EdirectoryConnector.java` | `readAttributes`, `verifyAttributes` via the matcher, list writes in `create`/`update`; remove `valuesEqual` |
| Modify `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/EdirectoryConnectorTest.java` | Embedded-LDAP tests for the above |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java` | Shape checks; capture from `readAttributes` |
| Delete `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapAttributes.java` | Replaced by `readAttributes` |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapCreateStep.java` | Lists stay custom attributes; list validation |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapUpdateStep.java` | List validation |
| Modify `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java` | Step tests |
| Create `idm-test-scenarios/src/test/resources/scenarios/ldap-multi-valued-attributes.yaml` | Corpus scenario |
| Modify `docs/reference/writing-scenarios.md`, `docs/architecture/capabilities.md`, `README.md`, `docs/reference/running-tests.md` | Docs; corpus count 20 → 21 |

---

### Task 1: `LdapValueMatcher`

**Files:**
- Create: `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/LdapValueMatcher.java`
- Test: `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/LdapValueMatcherTest.java`

**Interfaces:**
- Produces:
  - `public static final String LdapValueMatcher.CONTAINS = "contains"`, `EXCLUDES = "excludes"`
  - `public static String LdapValueMatcher.match(String attribute, Object expected, Object actual)` — `null` on a match, else the failure message. `expected == null` passes only when the attribute is absent. A malformed map/list (per `shapeProblem`) fails with `"Attribute '<a>' " + problem`.
  - `public static String LdapValueMatcher.shapeProblem(Object expected)` — `null` when well-formed (scalars always are), else a fragment such as `is an empty list, …` that callers prefix with the attribute.
  - `static boolean LdapValueMatcher.sameValue(Object expected, Object actual)` (package-private, DN-aware).

- [ ] **Step 1: Write the failing test**

```java
package com.idm.test.connector.ldap.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LdapValueMatcher")
class LdapValueMatcherTest {

    private static final String DEV = "cn=developers,ou=groups,o=data";
    private static final String VPN = "cn=vpn,ou=groups,o=data";
    private static final String FIN = "cn=finance,ou=groups,o=data";

    @Test
    @DisplayName("A scalar matches exactly one equal value")
    void scalar() {
        assertThat(LdapValueMatcher.match("title", "Manager", "Manager")).isNull();
        assertThat(LdapValueMatcher.match("title", "Manager", List.of("Manager", "Lead")))
                .isEqualTo("Attribute 'title': expected=Manager, actual=[Manager, Lead]");
        assertThat(LdapValueMatcher.match("title", "Manager", null))
                .isEqualTo("Attribute 'title': expected=Manager, actual=null");
        assertThat(LdapValueMatcher.match("title", "Manager", "Lead"))
                .isEqualTo("Attribute 'title': expected=Manager, actual=Lead");
    }

    @Test
    @DisplayName("Numbers and their text compare equal, as before")
    void numberAndText() {
        assertThat(LdapValueMatcher.match("employeeNumber", 200, "200")).isNull();
    }

    @Test
    @DisplayName("A plain list is the exact set, in any order")
    void exactSet() {
        assertThat(LdapValueMatcher.match("groupMembership", List.of(VPN, DEV), List.of(DEV, VPN))).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", List.of(DEV, VPN), List.of(DEV, FIN)))
                .isEqualTo("Attribute 'groupMembership': expected exactly [" + DEV + ", " + VPN
                        + "] in any order; missing [" + VPN + "]; unexpected [" + FIN + "]");
        assertThat(LdapValueMatcher.match("groupMembership", List.of(DEV, VPN), DEV))
                .isEqualTo("Attribute 'groupMembership': expected exactly [" + DEV + ", " + VPN
                        + "] in any order; missing [" + VPN + "]");
        assertThat(LdapValueMatcher.match("groupMembership", List.of(DEV), List.of(DEV, VPN)))
                .isEqualTo("Attribute 'groupMembership': expected exactly [" + DEV
                        + "] in any order; unexpected [" + VPN + "]");
    }

    @Test
    @DisplayName("A one-item list and a single value are the same")
    void oneItemList() {
        assertThat(LdapValueMatcher.match("groupMembership", List.of(DEV), DEV)).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", DEV, List.of(DEV))).isNull();
    }

    @Test
    @DisplayName("Duplicates are ignored")
    void duplicates() {
        assertThat(LdapValueMatcher.match("ou", List.of("Sales", "Sales"), "Sales")).isNull();
    }

    @Test
    @DisplayName("contains passes when every listed value is present, ignoring others")
    void contains() {
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("contains", List.of(DEV)), List.of(DEV, VPN)))
                .isNull();
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("contains", DEV), List.of(DEV, VPN)))
                .isNull();
        assertThat(LdapValueMatcher.match("groupMembership",
                Map.of("contains", List.of(DEV, VPN)), List.of(DEV, FIN)))
                .isEqualTo("Attribute 'groupMembership': expected to contain [" + DEV + ", " + VPN
                        + "]; missing [" + VPN + "]; actual=[" + DEV + ", " + FIN + "]");
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("contains", List.of(DEV)), null))
                .isEqualTo("Attribute 'groupMembership': expected to contain [" + DEV
                        + "]; missing [" + DEV + "]; actual=null");
    }

    @Test
    @DisplayName("excludes passes when no listed value is present, including when the attribute is absent")
    void excludes() {
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("excludes", List.of(FIN)), List.of(DEV)))
                .isNull();
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("excludes", FIN), null)).isNull();
        assertThat(LdapValueMatcher.match("groupMembership",
                Map.of("excludes", List.of(FIN, VPN)), List.of(DEV, FIN)))
                .isEqualTo("Attribute 'groupMembership': expected not to contain [" + FIN + ", " + VPN
                        + "]; found [" + FIN + "]");
    }

    @Test
    @DisplayName("contains and excludes together: both must hold, contains reported first")
    void both() {
        Map<String, Object> matcher = Map.of("contains", List.of(DEV), "excludes", List.of(FIN));
        assertThat(LdapValueMatcher.match("groupMembership", matcher, List.of(DEV, VPN))).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", matcher, List.of(VPN, FIN)))
                .startsWith("Attribute 'groupMembership': expected to contain");
        assertThat(LdapValueMatcher.match("groupMembership", matcher, List.of(DEV, FIN)))
                .startsWith("Attribute 'groupMembership': expected not to contain");
    }

    @Test
    @DisplayName("DNs compare ignoring case and spacing; other text stays exact")
    void dnAware() {
        assertThat(LdapValueMatcher.sameValue("CN=Developers, OU=Groups, O=Data", DEV)).isTrue();
        assertThat(LdapValueMatcher.sameValue("cn=developers,ou=groups,o=other", DEV)).isFalse();
        assertThat(LdapValueMatcher.sameValue("Manager", "manager")).isFalse();
        assertThat(LdapValueMatcher.sameValue("", "")).isTrue();
        assertThat(LdapValueMatcher.match("groupMembership",
                Map.of("contains", "CN=Developers,OU=Groups,O=Data"), List.of(DEV, VPN))).isNull();
    }

    @Test
    @DisplayName("A null expected value passes only when the attribute is absent")
    void nullExpected() {
        assertThat(LdapValueMatcher.match("title", null, null)).isNull();
        assertThat(LdapValueMatcher.match("title", null, "Manager"))
                .isEqualTo("Attribute 'title': expected=null, actual=Manager");
    }

    @Test
    @DisplayName("shapeProblem accepts scalars and well-formed lists and matchers")
    void wellFormed() {
        assertThat(LdapValueMatcher.shapeProblem("x")).isNull();
        assertThat(LdapValueMatcher.shapeProblem(List.of("a", "b"))).isNull();
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", "a"))).isNull();
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", List.of("a"), "excludes", List.of("b")))).isNull();
    }

    @Test
    @DisplayName("shapeProblem names each malformed shape")
    void malformed() {
        assertThat(LdapValueMatcher.shapeProblem(List.of())).isEqualTo(
                "is an empty list, which a misspelled attribute name would also satisfy; "
                        + "to assert values are absent use { excludes: [...] }");
        assertThat(LdapValueMatcher.shapeProblem(Arrays.asList("a", null)))
                .isEqualTo("has a null, map or list value inside its list; give plain values");
        assertThat(LdapValueMatcher.shapeProblem(List.of("a", List.of("b"))))
                .isEqualTo("has a null, map or list value inside its list; give plain values");
        assertThat(LdapValueMatcher.shapeProblem(Map.of()))
                .isEqualTo("is an empty map; use contains and/or excludes");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contain", "a")))
                .isEqualTo("has unknown matcher keys [contain]; use contains and/or excludes");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", List.of())))
                .isEqualTo("has an empty 'contains'");
        Map<String, Object> nullExcludes = new HashMap<>();
        nullExcludes.put("excludes", null);
        assertThat(LdapValueMatcher.shapeProblem(nullExcludes)).isEqualTo("has an empty 'excludes'");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", Map.of("a", "b"))))
                .isEqualTo("has a null, map or list value in 'contains'; give plain values");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("excludes", List.of("a", Map.of()))))
                .isEqualTo("has a null, map or list value in 'excludes'; give plain values");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", List.of(DEV, VPN),
                "excludes", List.of("CN=Developers,OU=Groups,O=Data"))))
                .isEqualTo("lists [" + DEV + "] in both 'contains' and 'excludes'");
    }

    @Test
    @DisplayName("match never passes a malformed matcher")
    void matchRejectsMalformed() {
        assertThat(LdapValueMatcher.match("groupMembership", List.of(), null))
                .startsWith("Attribute 'groupMembership' is an empty list");
        assertThat(LdapValueMatcher.match("groupMembership", Map.of(), List.of(DEV)))
                .isEqualTo("Attribute 'groupMembership' is an empty map; use contains and/or excludes");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl idm-connector-ldap -am -Dtest=LdapValueMatcherTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class LdapValueMatcher`.

- [ ] **Step 3: Write the implementation**

```java
package com.idm.test.connector.ldap.client;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares one attribute's expected value against what the directory holds.
 * <p>
 * The actual value is treated as a set: an absent attribute is the empty set, a scalar is one
 * value, a list is its values. LDAP keeps no value order, so a plain expected list is the exact
 * set in any order; {@code contains} and {@code excludes} check part of it. Two values that both
 * parse as DNs compare as DNs, because the directory may spell the same DN with another case or
 * spacing.
 */
public final class LdapValueMatcher {

    public static final String CONTAINS = "contains";
    public static final String EXCLUDES = "excludes";

    private LdapValueMatcher() {
    }

    /**
     * @return {@code null} when {@code actual} satisfies {@code expected}, else the failure message
     */
    public static String match(String attribute, Object expected, Object actual) {
        String problem = shapeProblem(expected);
        if (problem != null) {
            // A malformed matcher would otherwise compare nothing and pass.
            return "Attribute '" + attribute + "' " + problem;
        }
        List<Object> actualValues = values(actual);

        if (expected instanceof Map<?, ?> matcher) {
            if (matcher.containsKey(CONTAINS)) {
                List<Object> wanted = distinct(values(matcher.get(CONTAINS)));
                List<Object> missing = absentFrom(wanted, actualValues);
                if (!missing.isEmpty()) {
                    return "Attribute '" + attribute + "': expected to contain " + wanted
                            + "; missing " + missing + "; actual=" + actual;
                }
            }
            if (matcher.containsKey(EXCLUDES)) {
                List<Object> unwanted = distinct(values(matcher.get(EXCLUDES)));
                List<Object> found = presentIn(unwanted, actualValues);
                if (!found.isEmpty()) {
                    return "Attribute '" + attribute + "': expected not to contain " + unwanted
                            + "; found " + found;
                }
            }
            return null;
        }

        if (expected instanceof Collection<?> list) {
            List<Object> wanted = distinct(new ArrayList<>(list));
            List<Object> missing = absentFrom(wanted, actualValues);
            List<Object> unexpected = absentFrom(actualValues, wanted);
            if (missing.isEmpty() && unexpected.isEmpty()) {
                return null;
            }
            StringBuilder message = new StringBuilder("Attribute '" + attribute + "': expected exactly "
                    + wanted + " in any order");
            if (!missing.isEmpty()) {
                message.append("; missing ").append(missing);
            }
            if (!unexpected.isEmpty()) {
                message.append("; unexpected ").append(unexpected);
            }
            return message.toString();
        }

        boolean matches = expected == null
                ? actualValues.isEmpty()
                : actualValues.size() == 1 && sameValue(expected, actualValues.get(0));
        return matches ? null
                : "Attribute '" + attribute + "': expected=" + expected + ", actual=" + actual;
    }

    /**
     * @return {@code null} when {@code expected} is well-formed, else what is wrong with it, as a
     *         fragment to follow the attribute's name
     */
    public static String shapeProblem(Object expected) {
        if (expected instanceof Map<?, ?> matcher) {
            if (matcher.isEmpty()) {
                return "is an empty map; use contains and/or excludes";
            }
            Set<String> unknown = new TreeSet<>();
            for (Object key : matcher.keySet()) {
                if (!CONTAINS.equals(key) && !EXCLUDES.equals(key)) {
                    unknown.add(String.valueOf(key));
                }
            }
            if (!unknown.isEmpty()) {
                return "has unknown matcher keys " + unknown + "; use contains and/or excludes";
            }
            for (String key : List.of(CONTAINS, EXCLUDES)) {
                if (!matcher.containsKey(key)) {
                    continue;
                }
                Object value = matcher.get(key);
                if (value == null || value instanceof Collection<?> c && c.isEmpty()) {
                    return "has an empty '" + key + "'";
                }
                if (value instanceof Map<?, ?> || value instanceof Collection<?> c && !plain(c)) {
                    return "has a null, map or list value in '" + key + "'; give plain values";
                }
            }
            if (matcher.containsKey(CONTAINS) && matcher.containsKey(EXCLUDES)) {
                List<Object> overlap = presentIn(distinct(values(matcher.get(CONTAINS))),
                        values(matcher.get(EXCLUDES)));
                if (!overlap.isEmpty()) {
                    return "lists " + overlap + " in both 'contains' and 'excludes'";
                }
            }
            return null;
        }
        if (expected instanceof Collection<?> list) {
            if (list.isEmpty()) {
                return "is an empty list, which a misspelled attribute name would also satisfy; "
                        + "to assert values are absent use { excludes: [...] }";
            }
            if (!plain(list)) {
                return "has a null, map or list value inside its list; give plain values";
            }
        }
        return null;
    }

    /** Equal as DNs when both parse as DNs, otherwise equal as text. */
    static boolean sameValue(Object expected, Object actual) {
        LdapName expectedDn = dn(expected);
        LdapName actualDn = dn(actual);
        if (expectedDn != null && actualDn != null) {
            return expectedDn.equals(actualDn);
        }
        return expected.equals(actual) || String.valueOf(expected).equals(String.valueOf(actual));
    }

    private static LdapName dn(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            LdapName name = new LdapName(text);
            return name.size() > 0 ? name : null;
        } catch (InvalidNameException | IllegalArgumentException e) {
            return null;
        }
    }

    private static List<Object> values(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of(value);
    }

    private static boolean plain(Collection<?> values) {
        return values.stream().allMatch(v -> v != null && !(v instanceof Map<?, ?>) && !(v instanceof Collection<?>));
    }

    private static List<Object> distinct(List<Object> values) {
        List<Object> result = new ArrayList<>();
        for (Object value : values) {
            if (result.stream().noneMatch(kept -> sameValue(kept, value))) {
                result.add(value);
            }
        }
        return result;
    }

    /** The values of {@code source} that have no equal in {@code target}. */
    private static List<Object> absentFrom(List<Object> source, List<Object> target) {
        return distinct(source).stream()
                .filter(value -> target.stream().noneMatch(other -> sameValue(value, other)))
                .toList();
    }

    /** The values of {@code source} that have an equal in {@code target}. */
    private static List<Object> presentIn(List<Object> source, List<Object> target) {
        return distinct(source).stream()
                .filter(value -> target.stream().anyMatch(other -> sameValue(value, other)))
                .toList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl idm-connector-ldap -am -Dtest=LdapValueMatcherTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. If `dnAware` fails on the spacing case, check how `LdapName` parses `", "` before changing the matcher — the expected behaviour (spaces after `,` ignored) is the spec's, so adapt the parsing (e.g. compare `getRdns()`), not the test.

- [ ] **Step 5: Commit**

```bash
git add idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/LdapValueMatcher.java idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/LdapValueMatcherTest.java
git commit -m "feat: compare LDAP attribute values as unordered sets, with contains, excludes and DN-aware equality

ITF-38

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `EdirectoryConnector` — `readAttributes`, matcher-based `verifyAttributes`, list writes

**Files:**
- Modify: `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/EdirectoryConnector.java` (`create` custom-attribute loop ~line 122, `update` ~line 213, `verifyAttributes` ~line 377, `valuesEqual` ~line 593, `UserContextMapper.valueOf` ~line 551)
- Test: `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/EdirectoryConnectorTest.java`

**Interfaces:**
- Consumes: `LdapValueMatcher.match(String, Object, Object)` (Task 1).
- Produces:
  - `public OperationResult<Map<String, Object>> EdirectoryConnector.readAttributes(String dn)` — a `TreeMap` with `String.CASE_INSENSITIVE_ORDER`, keys as the directory spells them; scalar for one value, `List<Object>` for several; no `userPassword`. Not found → failure, `ErrorType.NOT_FOUND`, message `Entry not found: <dn>`; other errors → `ErrorType.LDAP_ERROR`, `Failed to read entry: <message>`.
  - `verifyAttributes(String, Map<String, Object>)` — unchanged signature; mismatch message is the matcher's, `ErrorType.VALIDATION_ERROR`.
  - `create(LdapUser)` writes a custom attribute whose value is a `Collection` as several values.
  - `update(String, Map<String, Object>)` writes a `Collection` value as several values; an empty collection removes the attribute.

- [ ] **Step 1: Write the failing tests**

In `EdirectoryConnectorTest`, change the assertion in `testVerifyAttributesMismatch` (line 236) from `contains("mismatch")` to:

```java
        assertThat(result.getMessage()).isEqualTo("Attribute 'sn': expected=Wrong, actual=Doe");
```

Add these tests before the `createTestUser()` helper (add `import java.util.Set;` if needed):

```java
    // ------------------------------------------------------------------
    // ITF-38: multi-valued attributes
    // ------------------------------------------------------------------

    private static final String DEV = "cn=developers,ou=groups,dc=test,dc=com";
    private static final String VPN = "cn=vpn,ou=groups,dc=test,dc=com";

    private String createMultiValued(String cn) {
        String dn = "cn=" + cn + "," + TEST_BASE_DN;
        connector.delete(dn);
        LdapUser user = new LdapUser();
        user.setDn(dn);
        user.setCn(cn);
        user.setSn("Multi");
        user.addCustomAttribute("ou", List.of("Engineering", "Security"));
        user.addCustomAttribute("seeAlso", List.of(DEV, VPN));
        user.setUserPassword("s3cr3t");
        OperationResult<LdapUser> created = connector.create(user);
        assertThat(created.isSuccess()).as(created.getMessage()).isTrue();
        return dn;
    }

    @Test
    @DisplayName("readAttributes returns every value of a multi-valued attribute, built-in or not, and no userPassword")
    void testReadAttributesReturnsLists() {
        String dn = createMultiValued("multi.read");

        OperationResult<Map<String, Object>> read = connector.readAttributes(dn);

        assertThat(read.isSuccess()).as(read.getMessage()).isTrue();
        Map<String, Object> attributes = read.getData().orElseThrow();
        assertThat((List<?>) attributes.get("ou")).map(String::valueOf)
                .containsExactlyInAnyOrder("Engineering", "Security");
        assertThat((List<?>) attributes.get("seeAlso")).map(String::valueOf)
                .containsExactlyInAnyOrder(DEV, VPN);
        assertThat(attributes.get("sn")).isEqualTo("Multi");
        assertThat(attributes.get("SN")).as("keys ignore case").isEqualTo("Multi");
        assertThat(attributes.keySet()).noneMatch(key -> key.equalsIgnoreCase("userPassword"));

        connector.delete(dn);
    }

    @Test
    @DisplayName("readAttributes reports a missing entry as NOT_FOUND")
    void testReadAttributesMissingEntry() {
        OperationResult<Map<String, Object>> read = connector.readAttributes("cn=nobody.itf38," + TEST_BASE_DN);

        assertThat(read.isSuccess()).isFalse();
        assertThat(read.getErrorType()).contains(ErrorType.NOT_FOUND);
        assertThat(read.getMessage()).contains("not found");
    }

    @Test
    @DisplayName("verifyAttributes matches lists in any order, DNs ignoring case, and contains/excludes")
    void testVerifyAttributesMultiValued() {
        String dn = createMultiValued("multi.verify");

        assertThat(connector.verifyAttributes(dn, Map.of("ou", List.of("Security", "Engineering"))).isSuccess())
                .isTrue();
        assertThat(connector.verifyAttributes(dn, Map.of("seeAlso",
                Map.of("contains", "CN=Developers, OU=Groups, DC=test, DC=com",
                        "excludes", "cn=finance,ou=groups,dc=test,dc=com"))).isSuccess())
                .isTrue();

        OperationResult<Boolean> scalar = connector.verifyAttributes(dn, Map.of("ou", "Engineering"));
        assertThat(scalar.isSuccess()).isFalse();
        assertThat(scalar.getErrorType()).contains(ErrorType.VALIDATION_ERROR);
        assertThat(scalar.getMessage()).startsWith("Attribute 'ou': expected=Engineering, actual=[");

        connector.delete(dn);
    }

    @Test
    @DisplayName("update writes a list as several values, and an empty list removes the attribute")
    void testUpdateWritesLists() {
        String dn = createMultiValued("multi.update");

        assertThat(connector.update(dn, Map.of("seeAlso", List.of(DEV))).isSuccess()).isTrue();
        assertThat(connector.readAttributes(dn).getData().orElseThrow().get("seeAlso")).isEqualTo(DEV);

        assertThat(connector.update(dn, Map.of("ou", List.of("A", "B", "C"))).isSuccess()).isTrue();
        assertThat((List<?>) connector.readAttributes(dn).getData().orElseThrow().get("ou")).hasSize(3);

        assertThat(connector.update(dn, Map.of("seeAlso", List.of())).isSuccess()).isTrue();
        assertThat(connector.readAttributes(dn).getData().orElseThrow()).doesNotContainKey("seeAlso");

        connector.delete(dn);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-connector-ldap -am -Dtest=EdirectoryConnectorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: method readAttributes(java.lang.String)`.

- [ ] **Step 3: Implement**

3a. In `create`, replace the custom-attribute loop:

```java
            // Set custom attributes; a collection is one attribute with several values
            for (Map.Entry<String, Object> entry : custom.entrySet()) {
                if (entry.getValue() instanceof Collection<?> values) {
                    context.setAttributeValues(entry.getKey(), values.toArray());
                } else {
                    context.setAttributeValue(entry.getKey(), entry.getValue());
                }
            }
```

3b. In `update`, replace the attribute construction inside the loop:

```java
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                // A collection is several values; an empty one replaces the attribute with
                // nothing, which removes it.
                javax.naming.directory.Attribute attr;
                if (entry.getValue() instanceof Collection<?> values) {
                    attr = new BasicAttribute(entry.getKey());
                    values.forEach(attr::add);
                } else {
                    attr = new BasicAttribute(entry.getKey(), entry.getValue());
                }
                mods[i++] = new javax.naming.directory.ModificationItem(
                    javax.naming.directory.DirContext.REPLACE_ATTRIBUTE, attr);
            }
```

3c. Add `readAttributes` after `read` (imports: `org.springframework.ldap.core.AttributesMapper`, `java.util.TreeMap`):

```java
    /**
     * Reads every attribute of an entry, with all its values.
     * <p>
     * Unlike {@link #read}, nothing collapses to a first value: a single value is a scalar and
     * several are a list, for the ten names {@link LdapUser} types as well. Keys keep the
     * directory's spelling but are looked up ignoring case, as LDAP names are.
     * {@code userPassword} is never returned (see {@link #EXCLUDED_ATTRIBUTE}).
     *
     * @param dn the distinguished name
     * @return operation result containing the attributes or error
     */
    public OperationResult<Map<String, Object>> readAttributes(String dn) {
        log.debug("Reading LDAP attributes: {}", dn);

        try {
            Name name = LdapNameBuilder.newInstance(dn).build();

            Map<String, Object> attributes = ldapTemplate.lookup(name,
                    (AttributesMapper<Map<String, Object>>) EdirectoryConnector::attributeMap);

            return OperationResult.<Map<String, Object>>builder()
                    .success(true)
                    .data(attributes)
                    .message("Attributes read successfully")
                    .build();

        } catch (org.springframework.ldap.NameNotFoundException e) {
            log.warn("LDAP entry not found: {}", dn);
            return OperationResult.<Map<String, Object>>builder()
                    .success(false)
                    .message("Entry not found: " + dn)
                    .errorType(ErrorType.NOT_FOUND)
                    .throwable(e)
                    .build();
        } catch (Exception e) {
            log.error("Failed to read LDAP attributes '{}': {}", dn, e.getMessage());
            return OperationResult.<Map<String, Object>>builder()
                    .success(false)
                    .message("Failed to read entry: " + e.getMessage())
                    .errorType(ErrorType.LDAP_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    private static Map<String, Object> attributeMap(Attributes attributes) throws NamingException {
        Map<String, Object> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        NamingEnumeration<? extends Attribute> all = attributes.getAll();
        while (all.hasMore()) {
            Attribute attribute = all.next();
            if (!EXCLUDED_ATTRIBUTE.equalsIgnoreCase(attribute.getID())) {
                result.put(attribute.getID(), valueOf(attribute));
            }
        }
        return result;
    }

    /**
     * A single-valued attribute reads as its scalar value; a multi-valued one as a list
     * of every value, so nothing is silently dropped.
     */
    private static Object valueOf(Attribute attribute) throws NamingException {
        if (attribute.size() == 1) {
            return attribute.get();
        }
        List<Object> values = new java.util.ArrayList<>(attribute.size());
        for (int i = 0; i < attribute.size(); i++) {
            values.add(attribute.get(i));
        }
        return values;
    }
```

Move `valueOf` out of `UserContextMapper` (delete the nested copy; the mapper's `user.addCustomAttribute(id, valueOf(attribute))` then calls the outer static method).

3d. Replace the body of `verifyAttributes` between the read and the success result:

```java
            OperationResult<Map<String, Object>> readResult = readAttributes(dn);

            if (!readResult.isSuccess()) {
                return OperationResult.<Boolean>builder()
                        .success(false)
                        .data(false)
                        .message("Failed to read entry: " + readResult.getMessage())
                        .errorType(readResult.getErrorType().orElse(ErrorType.LDAP_ERROR))
                        .throwable(readResult.getThrowable().orElse(null))
                        .build();
            }

            Map<String, Object> actualAttributes = readResult.getData().orElseThrow();

            for (Map.Entry<String, Object> entry : expectedAttributes.entrySet()) {
                String mismatch = LdapValueMatcher.match(
                        entry.getKey(), entry.getValue(), actualAttributes.get(entry.getKey()));

                if (mismatch != null) {
                    log.warn(mismatch);

                    return OperationResult.<Boolean>builder()
                            .success(false)
                            .data(false)
                            .message(mismatch)
                            .errorType(ErrorType.VALIDATION_ERROR)
                            .build();
                }
            }
```

Update the Javadoc's `@param expectedAttributes` to: `map of attribute names to expected values: a value, a list (the exact set, any order) or a {contains, excludes} map; see LdapValueMatcher`.

3e. Delete `valuesEqual` (now unused) and any imports left unused (`HashMap` if nothing else uses it).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl idm-connector-ldap -am`
Expected: BUILD SUCCESS, 0 failures/errors/skips across `EdirectoryConnectorTest`, `LdapValueMatcherTest` and the module's other tests.

- [ ] **Step 5: Commit**

```bash
git add idm-connector-ldap
git commit -m "feat: read every LDAP attribute value, verify through the matcher, and write lists as several values

ITF-38

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: LDAP steps — matcher shapes, capture, list writes

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java`
- Delete: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapAttributes.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapCreateStep.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapUpdateStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java`

**Interfaces:**
- Consumes: `LdapValueMatcher.shapeProblem(Object)` (Task 1); `EdirectoryConnector.readAttributes(String)` returning `OperationResult<Map<String, Object>>` (Task 2); `create`/`update` writing collections (Task 2).
- Produces: step behaviour only.

- [ ] **Step 1: Write the failing tests**

In `LdapStepsTest`, update the two mock tests that stub `read`:

- `testVerifySkipsReadWhenNoCaptureRequested`: after `verify(mockConnector, never()).read(any());` add `verify(mockConnector, never()).readAttributes(any());`.
- `testVerifyFailsWhenCaptureReadFailsAfterSuccessfulVerify`: replace the `read` stub with

```java
        when(mockConnector.readAttributes(any()))
                .thenReturn(OperationResult.<Map<String, Object>>failure("connection dropped"));
```

Add these tests at the end of the class:

```java
    // ------------------------------------------------------------------
    // ITF-38: multi-valued attributes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ldap.create writes lists as several values, built-in attributes included, and ldap.verify matches them")
    void testCreateAndVerifyLists() {
        String dn = "cn=ada.multi,ou=users";
        StepOutcome created = new LdapCreateStep(directories).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.multi", "sn", "Lovelace",
                        "ou", List.of("Engineering", "Security"),
                        "seeAlso", List.of("cn=a,ou=groups,dc=test,dc=com", "cn=b,ou=groups,dc=test,dc=com"))),
                false);
        assertThat(created.success()).as(created.message()).isTrue();

        StepOutcome verified = new LdapVerifyStep(directories).execute(Map.of(
                "dn", dn,
                "expect", Map.of(
                        "ou", List.of("Security", "Engineering"),
                        "seeAlso", Map.of("contains", "CN=A,OU=Groups,DC=test,DC=com",
                                "excludes", "cn=c,ou=groups,dc=test,dc=com"))), true);

        assertThat(verified.success()).as(verified.message()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> captured = (Map<String, Object>) verified.value();
        assertThat(captured.get("dn")).isEqualTo(dn);
        assertThat((List<?>) captured.get("ou")).map(String::valueOf)
                .containsExactlyInAnyOrder("Engineering", "Security");
        assertThat(captured.get("sn")).isEqualTo("Lovelace");

        connector.delete(dn);
    }

    @Test
    @DisplayName("ldap.update writes a list as several values and an empty list removes the attribute")
    void testUpdateLists() {
        String dn = "cn=ada.multiupdate,ou=users";
        new LdapCreateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("cn", "ada.multiupdate", "sn", "Lovelace")), false);

        assertThat(new LdapUpdateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("ou", List.of("A", "B"))), false).success()).isTrue();
        assertThat(new LdapVerifyStep(directories).execute(Map.of("dn", dn,
                "expect", Map.of("ou", List.of("B", "A"))), false).success()).isTrue();

        assertThat(new LdapUpdateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("ou", List.of())), false).success()).isTrue();
        assertThat(new LdapVerifyStep(directories).execute(Map.of("dn", dn,
                "expect", Map.of("ou", Map.of("excludes", List.of("A", "B")))), false).success()).isTrue();
        assertThat(connector.readAttributes(dn).getData().orElseThrow()).doesNotContainKey("ou");

        connector.delete(dn);
    }

    @Test
    @DisplayName("ldap.create rejects an empty list, and both write steps reject null, map or list items, without calling the directory")
    void testWriteStepsRejectMalformedLists() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        LdapDirectories mocked = only(mockConnector);

        StepOutcome empty = new LdapCreateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("cn", "x", "sn", "X", "ou", List.of())), false);
        assertThat(empty.success()).isFalse();
        assertThat(empty.message()).isEqualTo(
                "ldap.create for cn=x,ou=users: attribute 'ou' has an empty list; there is nothing to write");

        StepOutcome nested = new LdapCreateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("cn", "x", "sn", "X", "ou", List.of("A", List.of("B")))), false);
        assertThat(nested.success()).isFalse();
        assertThat(nested.message()).isEqualTo("ldap.create for cn=x,ou=users: attribute 'ou' "
                + "has a null, map or list value inside its list; give plain values");

        StepOutcome updateNested = new LdapUpdateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("ou", List.of(Map.of("a", "b")))), false);
        assertThat(updateNested.success()).isFalse();
        assertThat(updateNested.message()).isEqualTo("ldap.update for cn=x,ou=users: attribute 'ou' "
                + "has a null, map or list value inside its list; give plain values");

        verify(mockConnector, never()).create(any());
        verify(mockConnector, never()).update(any(), any());
    }

    @Test
    @DisplayName("A malformed matcher in ldap.verify is a final failure before the directory is called")
    void testVerifyRejectsMalformedMatchers() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        LdapVerifyStep verify = new LdapVerifyStep(only(mockConnector));

        Map<Object, String> cases = new java.util.LinkedHashMap<>();
        cases.put(List.of(), "is an empty list, which a misspelled attribute name would also satisfy; "
                + "to assert values are absent use { excludes: [...] }");
        cases.put(Map.of("contain", "a"), "has unknown matcher keys [contain]; use contains and/or excludes");
        cases.put(Map.of(), "is an empty map; use contains and/or excludes");
        cases.put(Map.of("excludes", List.of()), "has an empty 'excludes'");
        cases.put(Map.of("contains", List.of("a", List.of("b"))),
                "has a null, map or list value in 'contains'; give plain values");
        cases.put(List.of("a", Map.of()), "has a null, map or list value inside its list; give plain values");
        cases.put(Map.of("contains", "cn=a,o=x", "excludes", "CN=A,O=X"),
                "lists [cn=a,o=x] in both 'contains' and 'excludes'");

        cases.forEach((expected, problem) -> {
            StepOutcome outcome = verify.execute(Map.of("dn", "cn=x,ou=users",
                    "expect", Map.of("groupMembership", expected)), false);
            assertThat(outcome.success()).as(problem).isFalse();
            assertThat(outcome.isFinal()).as(problem).isTrue();
            assertThat(outcome.message()).isEqualTo(
                    "ldap.verify for cn=x,ou=users: attribute 'groupMembership' " + problem);
        });

        verify(mockConnector, never()).verifyAttributes(any(), any());
    }
```

Note: `Map.of("excludes", List.of())` is a valid key with an empty value, and `Map.of()` as a value is an empty matcher — both are expected by the cases above.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=LdapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the new tests fail (e.g. `testWriteStepsRejectMalformedLists` reaches the connector; `testVerifyRejectsMalformedMatchers` is not final / message differs; the capture test still reads through `read`).

- [ ] **Step 3: Implement**

3a. `LdapVerifyStep` — in `rejectMalformedAssertion`, inside the `for (Map.Entry<?, ?> attribute : expect.entrySet())` loop, after the `null` check:

```java
                String problem = LdapValueMatcher.shapeProblem(attribute.getValue());
                if (problem != null) {
                    return StepOutcome.finalFailure("ldap.verify for " + where + ": attribute '"
                            + attribute.getKey() + "' " + problem);
                }
```

Replace the capture read at the end of `execute`:

```java
        // Read once, only after verification passes and only because a capture was actually
        // requested, so a polling assertion does not double its read load on every attempt.
        OperationResult<Map<String, Object>> entry = connector.readAttributes(dn);
        if (!entry.isSuccess() || entry.getData().isEmpty()) {
            // Verification passed but the entry could not be re-read for its as: capture.
            // Reporting success with an empty map would let `${v.cn}` miss silently later, so
            // this fails the step instead of fabricating a value.
            return StepOutcome.failure("ldap.verify passed for " + where
                    + " but the follow-up read for its as: capture failed: " + entry.getMessage());
        }

        // Every value of every attribute: a multi-valued one is a list, so it can be written
        // back by an ldap.update in cleanup.
        Map<String, Object> captured = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        captured.putAll(entry.getData().get());
        captured.put("dn", dn);
        return StepOutcome.success("verified " + where, captured);
```

Imports: add `com.idm.test.connector.ldap.client.LdapValueMatcher`, `java.util.TreeMap`; remove `com.idm.test.connector.ldap.model.LdapUser`. Update the class Javadoc's first line to: `Asserts that an LDAP entry carries the expected attribute values — a value, the exact set of a multi-valued attribute in any order, or {@code contains}/{@code excludes} (see LdapValueMatcher).`

3b. Delete `LdapAttributes.java`.

3c. Add a shared list check. Create it as a package-private static method in a new small class `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapLists.java`:

```java
package com.idm.test.engine.steps;

import java.util.Collection;
import java.util.Map;

/**
 * Checks the lists an {@code ldap.create} or {@code ldap.update} writes as multi-valued
 * attributes, before the directory is called.
 */
final class LdapLists {

    private LdapLists() {
    }

    /**
     * @param step       the step name, for the message
     * @param where      the entry, as the step names it
     * @param emptyAllowed whether an empty list is meaningful (it removes the attribute in an update)
     * @return the failure message, or {@code null} when every list can be written
     */
    static String problem(String step, String where, Map<String, Object> attributes, boolean emptyAllowed) {
        for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
            if (!(attribute.getValue() instanceof Collection<?> values)) {
                continue;
            }
            String prefix = step + " for " + where + ": attribute '" + attribute.getKey() + "' ";
            if (values.isEmpty() && !emptyAllowed) {
                return prefix + "has an empty list; there is nothing to write";
            }
            if (values.stream().anyMatch(v -> v == null || v instanceof Map<?, ?> || v instanceof Collection<?>)) {
                return prefix + "has a null, map or list value inside its list; give plain values";
            }
        }
        return null;
    }
}
```

3d. `LdapCreateStep.execute`: after reading `attributes` and before `toLdapUser`:

```java
        String listProblem = LdapLists.problem("ldap.create", where, attributes, false);
        if (listProblem != null) {
            return StepOutcome.failure(listProblem);
        }
```

And change `take` so a list for a built-in name stays a custom attribute:

```java
    private static String take(Map<String, Object> attributes, String key) {
        if (attributes.get(key) instanceof Collection<?>) {
            // Several values: the typed field holds one, so leave it to be written as a
            // custom attribute with every value.
            return null;
        }
        Object value = attributes.remove(key);
        return value == null ? null : value.toString();
    }
```

(import `java.util.Collection`). Update the Javadoc on `toLdapUser` to add: `A list, for any name, is written as several values.`

3e. `LdapUpdateStep.execute`: after the empty-attributes check:

```java
        String listProblem = LdapLists.problem("ldap.update", where, attributes, true);
        if (listProblem != null) {
            return StepOutcome.failure(listProblem);
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures/errors/skips (includes `LdapStepsTest`, `NoSpringDependencyTest`).

- [ ] **Step 5: Commit**

```bash
git add -A idm-test-engine
git commit -m "feat: ldap.verify rejects malformed matchers and captures every value; ldap.create and ldap.update write lists

ITF-38

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Corpus scenario and docs

**Files:**
- Create: `idm-test-scenarios/src/test/resources/scenarios/ldap-multi-valued-attributes.yaml`
- Modify: `docs/reference/writing-scenarios.md` (`ldap.create`, `ldap.update`, `ldap.verify` sections)
- Modify: `docs/architecture/capabilities.md` (line 14 `ldap.verify` row; line 41 corpus count)
- Modify: `README.md` (line 264 corpus count)
- Modify: `docs/reference/running-tests.md` (lines 17 and 76 corpus count)

**Interfaces:**
- Consumes: the behaviour of Tasks 1–3.

- [ ] **Step 1: Write the scenario**

```yaml
name: Verify multi-valued attributes in the directory
description: Several ou and seeAlso values, checked as an exact set in any order, with contains and with excludes

fixtures:
  user:
    generate: random-user

steps:
  - ldap.create:
      description: Create a user in two departments and two groups
      dn: "cn=${user.userId},ou=users"
      as: createdDn
      attributes:
        cn: "${user.userId}"
        sn: "${user.lastName}"
        ou: [Engineering, Security]
        seeAlso:
          - "cn=developers,ou=groups,o=data"
          - "cn=vpn-users,ou=groups,o=data"

  - ldap.verify:
      description: Both departments, listed in the other order
      dn: "${createdDn}"
      expect:
        ou: [Security, Engineering]

  - ldap.verify:
      description: The groups, with a DN written in another case and spacing
      dn: "${createdDn}"
      expect:
        seeAlso:
          contains: ["CN=Developers, OU=Groups, O=Data"]
          excludes: ["cn=finance,ou=groups,o=data"]

  - ldap.update:
      description: Take the user out of the VPN group
      dn: "${createdDn}"
      attributes:
        seeAlso: ["cn=developers,ou=groups,o=data"]

  - ldap.verify:
      description: The VPN group is gone and the other stays
      dn: "${createdDn}"
      expect:
        seeAlso:
          contains: ["cn=developers,ou=groups,o=data"]
          excludes: ["cn=vpn-users,ou=groups,o=data"]
      wait: true

cleanup:
  - ldap.delete:
      dn: "${createdDn}"
```

- [ ] **Step 2: Run it**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false -Dscenario=ldap-multi-valued-attributes.yaml`
Expected: BUILD SUCCESS; `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`.

- [ ] **Step 3: Update `docs/reference/writing-scenarios.md`**

In `ldap.create`, after the **Object classes** paragraph and its example, add:

```markdown
**Several values:** give a list to write an attribute with several values, for any attribute
(`ou: [Engineering, Security]`). An empty list, or a list holding `null`, a map or a list, fails
the step.
```

In `ldap.update`, change the `attributes` row's meaning to `a map of attribute name to new value, or a list of values; attributes not listed are left as they are` and add after **Captures**:

```markdown
**Several values:** a list replaces every value of the attribute (`seeAlso: [cn=a,ou=groups,o=data]`);
an empty list (`[]`) removes the attribute. To restore an entry, capture it with `ldap.verify … as:`
first: a multi-valued attribute is captured as a list, so writing it back restores every value.
```

In `ldap.verify`, change the `expect` row's meaning to `a map of attribute name to expected value (see **Values** below); only the listed attributes are compared. Give exactly one of `expect` or `exists``, change **Captures** to `with `expect`, a map of the entry's attributes, read after the check passes (for example `${entry.mail}`); an attribute with several values is a list. With `exists`, nothing, so declaring `as:` fails the step.`, and add before **Fails when**:

~~~markdown
**Values:** each attribute in `expect` takes one of these forms. The directory's values are
compared as a set, since LDAP keeps no order.

| Form | Passes when |
|---|---|
| a value, `title: Manager` | the attribute has exactly that one value |
| a list, `ou: [Engineering, Security]` | the attribute has exactly these values, in any order |
| `{ contains: [...] }` | every listed value is present; other values are ignored |
| `{ excludes: [...] }` | none of the listed values is present; an absent attribute passes |
| `{ contains: [...], excludes: [...] }` | both |

- `contains` and `excludes` take a list or a single value.
- When both sides are DNs, they compare as DNs: `CN=Finance, OU=Groups, O=Data` matches
  `cn=finance,ou=groups,o=data`. Other values compare as text, exactly.
- A value is only a match for an attribute with **one** value: on an entry with two `cn` values,
  `cn: jdoe` fails. Use `cn: { contains: [jdoe] }`.
- `excludes` cannot tell a misspelled attribute name from an absent attribute, so it passes for
  either. Pair it with a `contains` on the same attribute, or another check in the same step.

```yaml
- roles.revoke:
    assignments: ["${grant}"]

- ldap.verify:
    dn: "cn=${user.userId},ou=users"
    expect:
      groupMembership:
        contains: ["cn=Employees,ou=groups,o=data"]
        excludes: ["cn=Finance,ou=groups,o=data"]
    wait: { timeout: 5m, poll: 10s }
```
~~~

And extend **Fails when** — the sentence listing what ends the wait at once — with: `, or a malformed value form (an empty list; a map with keys other than `contains`/`excludes` or with neither; an empty `contains`/`excludes`; a `null`, map or list inside a list; the same value in both `contains` and `excludes`)`.

- [ ] **Step 4: Update counts and the capability matrix**

- `docs/architecture/capabilities.md` line 14, last column: append `. Multi-valued attributes compare as sets in any order, with `contains`/`excludes` and DN-aware values (ITF-38); `ldap.create`/`ldap.update` write lists as several values`.
- `docs/architecture/capabilities.md` line 41: `20 YAML scenarios` → `21 YAML scenarios`.
- `README.md` line 264: `a corpus of 20 YAML scenarios` → `a corpus of 21 YAML scenarios`.
- `docs/reference/running-tests.md` lines 17 and 76: `20` → `21`.

Run: `grep -rn "20 YAML\|20 scenarios\|corpus of 20" README.md docs/reference docs/architecture`
Expected: no output.

- [ ] **Step 5: Run the full suite and the corpus checks**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures/errors/skips. Then:

```bash
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
grep -c '"status" : "FAILED"' idm-test-scenarios/target/multi-directory-results.json
```

Expected: `0` and `0`. If Docker is available: `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false` passes 21/21.

- [ ] **Step 6: Commit**

```bash
git add idm-test-scenarios/src/test/resources/scenarios/ldap-multi-valued-attributes.yaml docs/reference/writing-scenarios.md docs/architecture/capabilities.md README.md docs/reference/running-tests.md
git commit -m "docs: describe multi-valued attributes and add a corpus scenario for them

ITF-38

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
