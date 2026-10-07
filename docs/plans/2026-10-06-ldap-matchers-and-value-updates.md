# ldap.verify Matchers and ldap.update Add/Remove Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ldap.verify` gains `equals`, `count`, `matches` and `ignoreCase` matcher keys; `ldap.update` can add or remove single values (`{ add: …, remove: … }`), failing strictly with a clear message when a value to add exists or a value to remove does not.

**Architecture:** `LdapValueMatcher` (idm-connector-ldap) handles the new keys and their shape rules. A new `LdapValueChanges` (same package) holds the add/remove shape rules and failure wording; `EdirectoryConnector.update` turns add/remove into `ADD_ATTRIBUTE`/`REMOVE_ATTRIBUTE` modification items in the same request and explains LDAP errors 20/16 by reading the entry once. The engine's `LdapLists` validates add/remove for `ldap.update` and refuses them in `ldap.create`.

**Tech Stack:** Java 21, Spring LDAP / JNDI, UnboundID (embedded tests), JUnit 5, AssertJ, Mockito.

**Ticket:** ITF-39. **Spec:** `docs/specs/2026-10-06-ldap-matchers-and-value-updates-design.md`.

## Global Constraints

- Matcher keys: `equals`, `contains`, `excludes`, `count`, `matches`, `ignoreCase`; any combination; all given must hold; checked in that order; first failure reported. Plain values and plain lists keep their ITF-38 meaning.
- `count`: a non-negative YAML integer (`Integer`, `Long`, `Short`, `Byte`, or a `BigInteger` that fits a long); absent attribute = 0 values. Rejected: `3.0`, `"3"`, `-1`.
- `matches`: a regex or a list of them; each pattern must match the **whole** of at least one value (`Pattern.compile(p).matcher(value).matches()`); case-sensitive unless `(?i)`; `ignoreCase` does not apply to it.
- `ignoreCase: true|false` applies to `equals`, `contains`, `excludes` (text `equalsIgnoreCase`); DNs already compare ignoring case; it requires at least one of those keys.
- Messages: `Attribute 'groupMembership': expected 3 values, found 2: [a, b]` (`expected 1 value`; `found 0` with no list when absent); `Attribute 'mail': no value matches ^jdoe@.*$; actual=<actual as given>` (`actual=null` when absent); `equals` uses the exact-set message.
- New shape messages (fragments after `attribute '<name>' `): `is an empty map; use equals, contains, excludes, count, matches or ignoreCase`; `has unknown matcher keys [x]; use equals, contains, excludes, count, matches or ignoreCase`; `has an empty 'equals'`; `has a null, map or list value in 'equals'; give plain values`; `has a 'count' that is not a non-negative whole number: <value>`; `has an empty 'matches'`; `has a 'matches' that is not a regular expression or a list of them`; `has an invalid 'matches' pattern '<p>': <PatternSyntaxException description>`; `has an 'ignoreCase' that is not true or false`; `has an 'ignoreCase' with no equals, contains or excludes to apply to`. The contains/excludes overlap check honours `ignoreCase`.
- `ldap.update` add/remove: an attribute value `{ add: …, remove: … }` (value or list each, at least one key) → `ADD_ATTRIBUTE` / `REMOVE_ATTRIBUTE` in the same modify request as any replaces.
- add/remove shape messages: `is an empty map; use add and/or remove`; `has unknown keys [x]; use add and/or remove`; `has an empty 'add'` / `'remove'`; `has a null, map or list value in 'add'; give plain values`; `lists [x] in both 'add' and 'remove'` (DN-aware). In `ldap.create`: `ldap.create for <dn>: attribute '<a>' uses add/remove, which only apply to ldap.update`.
- Strict failure messages: `cannot add [v] to <attribute>: the entry already has it` / `cannot remove [v] from <attribute>: the entry does not have it` (`them` for several values); several attributes joined with `; `; falls back to `Failed to update user: <directory message>` when the explanation read fails or finds nothing. The step prefixes `ldap.update failed for <where>: `.
- Secrets never appear in step messages, reports or logs (ITF-23 rule).
- Commit trailer: a blank line, `ITF-39`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven: always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. If `./mvnw` finds no JVM: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.11-zulu`.

---

## File Structure

| File | Responsibility |
|---|---|
| Modify `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/LdapValueMatcher.java` | New matcher keys, shape rules, case-insensitive comparison |
| Modify `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/LdapValueMatcherTest.java` | Tests; two existing expectations change wording |
| Modify `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java` | Two existing expectations change wording (Task 1); add/remove step tests (Task 2) |
| Create `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/LdapValueChanges.java` | add/remove shape rules, offending values, failure wording |
| Create `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/LdapValueChangesTest.java` | Unit tests |
| Modify `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/EdirectoryConnector.java` (`update`) | ADD/REMOVE items, strict explanations |
| Modify `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/EdirectoryConnectorTest.java` | Embedded tests |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapLists.java` | add/remove validation in update, refusal in create |
| Modify `idm-test-scenarios/src/test/resources/scenarios/ldap-multi-valued-attributes.yaml` | Corpus coverage |
| Modify `docs/reference/writing-scenarios.md`, `README.md`, `docs/architecture/capabilities.md` | Docs |

---

### Task 1: `equals`, `count`, `matches` and `ignoreCase` in `LdapValueMatcher`

**Files:**
- Modify: `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/LdapValueMatcher.java`
- Test: `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/LdapValueMatcherTest.java`
- Modify: `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java` (only the two expectation strings in `testVerifyRejectsMalformedMatchers`)

**Interfaces:**
- Produces: constants `LdapValueMatcher.EQUALS`, `COUNT`, `MATCHES`, `IGNORE_CASE` (besides `CONTAINS`, `EXCLUDES`); `static boolean sameValue(Object expected, Object actual, boolean ignoreCase)` (package-private; the 2-arg `sameValue` stays and means `ignoreCase = false`). `match` and `shapeProblem` keep their signatures.

- [ ] **Step 1: Write the failing tests**

In `LdapValueMatcherTest`, change the three existing expectations that mention `use contains and/or excludes` (in `malformed` and `matchRejectsMalformed`) to the new wording:

```java
        assertThat(LdapValueMatcher.shapeProblem(Map.of()))
                .isEqualTo("is an empty map; use equals, contains, excludes, count, matches or ignoreCase");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contain", "a")))
                .isEqualTo("has unknown matcher keys [contain]; use equals, contains, excludes, count, matches or ignoreCase");
```

```java
        assertThat(LdapValueMatcher.match("groupMembership", Map.of(), List.of(DEV)))
                .isEqualTo("Attribute 'groupMembership' is an empty map; use equals, contains, excludes, count, matches or ignoreCase");
```

In `LdapStepsTest.testVerifyRejectsMalformedMatchers`, change the two `cases.put` lines likewise:

```java
        cases.put(Map.of("contain", "a"), "has unknown matcher keys [contain]; use equals, contains, excludes, count, matches or ignoreCase");
        cases.put(Map.of(), "is an empty map; use equals, contains, excludes, count, matches or ignoreCase");
```

Add to `LdapValueMatcherTest`:

```java
    @Test
    @DisplayName("equals is the exact set, like a plain list")
    void equalsKey() {
        assertThat(LdapValueMatcher.match("ou", Map.of("equals", List.of("b", "a")), List.of("a", "b"))).isNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("equals", "a"), "a")).isNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("equals", List.of("a")), List.of("a", "b")))
                .isEqualTo("Attribute 'ou': expected exactly [a] in any order; unexpected [b]");
    }

    @Test
    @DisplayName("count compares the number of values; an absent attribute has none")
    void countKey() {
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("count", 2), List.of("a", "b"))).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("count", 0), null)).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("count", 3), List.of("a", "b")))
                .isEqualTo("Attribute 'groupMembership': expected 3 values, found 2: [a, b]");
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("count", 1), null))
                .isEqualTo("Attribute 'groupMembership': expected 1 value, found 0");
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("count", 2L), List.of("a", "b"))).isNull();
    }

    @Test
    @DisplayName("matches needs each pattern to match the whole of some value")
    void matchesKey() {
        assertThat(LdapValueMatcher.match("mail", Map.of("matches", "^jdoe@.*$"), List.of("x@y.com", "jdoe@a.com")))
                .isNull();
        assertThat(LdapValueMatcher.match("mail", Map.of("matches", "^jdoe@.*$"), "x@y.com"))
                .isEqualTo("Attribute 'mail': no value matches ^jdoe@.*$; actual=x@y.com");
        assertThat(LdapValueMatcher.match("mail", Map.of("matches", "^jdoe@.*$"), null))
                .isEqualTo("Attribute 'mail': no value matches ^jdoe@.*$; actual=null");
        assertThat(LdapValueMatcher.match("mail", Map.of("matches", "jdoe"), "jdoe@a.com"))
                .as("the whole value must match").isNotNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("matches", List.of("^a.*", "^b.*")), List.of("ax", "bx"))).isNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("matches", List.of("^a.*", "^b.*")), List.of("ax")))
                .isEqualTo("Attribute 'ou': no value matches ^b.*; actual=[ax]");
        assertThat(LdapValueMatcher.match("mail", Map.of("matches", "(?i)JDOE.*"), "jdoe@a.com")).isNull();
    }

    @Test
    @DisplayName("ignoreCase applies to equals, contains and excludes")
    void ignoreCaseKey() {
        assertThat(LdapValueMatcher.match("title", Map.of("equals", "Senior Engineer", "ignoreCase", true),
                "senior engineer")).isNull();
        assertThat(LdapValueMatcher.match("title", Map.of("equals", "Senior Engineer"), "senior engineer")).isNotNull();
        assertThat(LdapValueMatcher.match("title", Map.of("equals", "A", "ignoreCase", false), "a")).isNotNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("contains", List.of("SALES"), "ignoreCase", true),
                List.of("sales", "hr"))).isNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("excludes", "SALES", "ignoreCase", true), "sales"))
                .isEqualTo("Attribute 'ou': expected not to contain [SALES]; found [SALES]");
    }

    @Test
    @DisplayName("Keys combine: every one must hold")
    void combined() {
        Map<String, Object> matcher = Map.of("contains", "cn=vpn,o=x", "count", 2, "ignoreCase", true);
        assertThat(LdapValueMatcher.match("groupMembership", matcher, List.of("CN=VPN,O=X", "cn=dev,o=x"))).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", matcher, List.of("CN=VPN,O=X", "cn=dev,o=x", "cn=hr,o=x")))
                .isEqualTo("Attribute 'groupMembership': expected 2 values, found 3: [CN=VPN,O=X, cn=dev,o=x, cn=hr,o=x]");
    }

    @Test
    @DisplayName("shapeProblem names each malformed new key")
    void malformedNewKeys() {
        assertThat(LdapValueMatcher.shapeProblem(Map.of("count", 3.0)))
                .isEqualTo("has a 'count' that is not a non-negative whole number: 3.0");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("count", "3")))
                .isEqualTo("has a 'count' that is not a non-negative whole number: 3");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("count", -1)))
                .isEqualTo("has a 'count' that is not a non-negative whole number: -1");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("matches", List.of()))).isEqualTo("has an empty 'matches'");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("matches", 5)))
                .isEqualTo("has a 'matches' that is not a regular expression or a list of them");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("matches", "[")))
                .startsWith("has an invalid 'matches' pattern '[': ");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("equals", "a", "ignoreCase", "yes")))
                .isEqualTo("has an 'ignoreCase' that is not true or false");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("ignoreCase", true)))
                .isEqualTo("has an 'ignoreCase' with no equals, contains or excludes to apply to");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("count", 1, "ignoreCase", true)))
                .isEqualTo("has an 'ignoreCase' with no equals, contains or excludes to apply to");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("equals", List.of()))).isEqualTo("has an empty 'equals'");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("equals", List.of("a", Map.of()))))
                .isEqualTo("has a null, map or list value in 'equals'; give plain values");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", "Sales", "excludes", "SALES", "ignoreCase", true)))
                .isEqualTo("lists [Sales] in both 'contains' and 'excludes'");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", "Sales", "excludes", "SALES"))).isNull();
        assertThat(LdapValueMatcher.shapeProblem(Map.of("count", java.math.BigInteger.TWO))).isNull();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-connector-ldap -am -Dtest=LdapValueMatcherTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: failures in the new tests and the changed expectations.

- [ ] **Step 3: Implement**

Replace the body of `LdapValueMatcher` (keep the class Javadoc, extending its last sentence with: `A matcher map may also give equals, count, matches and ignoreCase; every key given must hold.`). Imports: `java.math.BigInteger`, `java.util.regex.Pattern`, `java.util.regex.PatternSyntaxException` plus the existing ones.

```java
    public static final String EQUALS = "equals";
    public static final String CONTAINS = "contains";
    public static final String EXCLUDES = "excludes";
    public static final String COUNT = "count";
    public static final String MATCHES = "matches";
    public static final String IGNORE_CASE = "ignoreCase";

    private static final List<String> KEYS = List.of(EQUALS, CONTAINS, EXCLUDES, COUNT, MATCHES, IGNORE_CASE);
    /** The keys whose values are compared as values, and so are affected by ignoreCase. */
    private static final List<String> VALUE_KEYS = List.of(EQUALS, CONTAINS, EXCLUDES);
    private static final String KEY_HINT = "use equals, contains, excludes, count, matches or ignoreCase";

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
            boolean ignoreCase = Boolean.TRUE.equals(matcher.get(IGNORE_CASE));
            if (matcher.containsKey(EQUALS)) {
                String mismatch = exactSet(attribute, values(matcher.get(EQUALS)), actualValues, ignoreCase);
                if (mismatch != null) {
                    return mismatch;
                }
            }
            if (matcher.containsKey(CONTAINS)) {
                List<Object> wanted = distinct(values(matcher.get(CONTAINS)), ignoreCase);
                List<Object> missing = absentFrom(wanted, actualValues, ignoreCase);
                if (!missing.isEmpty()) {
                    return "Attribute '" + attribute + "': expected to contain " + wanted
                            + "; missing " + missing + "; actual=" + actual;
                }
            }
            if (matcher.containsKey(EXCLUDES)) {
                List<Object> unwanted = distinct(values(matcher.get(EXCLUDES)), ignoreCase);
                List<Object> found = presentIn(unwanted, actualValues, ignoreCase);
                if (!found.isEmpty()) {
                    return "Attribute '" + attribute + "': expected not to contain " + unwanted
                            + "; found " + found;
                }
            }
            if (matcher.containsKey(COUNT)) {
                long wanted = ((Number) matcher.get(COUNT)).longValue();
                if (actualValues.size() != wanted) {
                    return "Attribute '" + attribute + "': expected " + wanted + (wanted == 1 ? " value" : " values")
                            + ", found " + actualValues.size() + (actualValues.isEmpty() ? "" : ": " + actualValues);
                }
            }
            if (matcher.containsKey(MATCHES)) {
                for (Object pattern : values(matcher.get(MATCHES))) {
                    Pattern compiled = Pattern.compile((String) pattern);
                    if (actualValues.stream().noneMatch(value -> compiled.matcher(String.valueOf(value)).matches())) {
                        return "Attribute '" + attribute + "': no value matches " + pattern + "; actual=" + actual;
                    }
                }
            }
            return null;
        }

        if (expected instanceof Collection<?> list) {
            return exactSet(attribute, new ArrayList<>(list), actualValues, false);
        }

        boolean matches = expected == null
                ? actualValues.isEmpty()
                : actualValues.size() == 1 && sameValue(expected, actualValues.get(0));
        return matches ? null
                : "Attribute '" + attribute + "': expected=" + expected + ", actual=" + actual;
    }

    private static String exactSet(String attribute, List<Object> expected, List<Object> actualValues,
                                   boolean ignoreCase) {
        List<Object> wanted = distinct(expected, ignoreCase);
        List<Object> missing = absentFrom(wanted, actualValues, ignoreCase);
        List<Object> unexpected = absentFrom(actualValues, wanted, ignoreCase);
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

    /**
     * @return {@code null} when {@code expected} is well-formed, else what is wrong with it, as a
     *         fragment to follow the attribute's name
     */
    public static String shapeProblem(Object expected) {
        if (expected instanceof Map<?, ?> matcher) {
            if (matcher.isEmpty()) {
                return "is an empty map; " + KEY_HINT;
            }
            Set<String> unknown = new TreeSet<>();
            for (Object key : matcher.keySet()) {
                if (!KEYS.contains(String.valueOf(key))) {
                    unknown.add(String.valueOf(key));
                }
            }
            if (!unknown.isEmpty()) {
                return "has unknown matcher keys " + unknown + "; " + KEY_HINT;
            }
            for (String key : VALUE_KEYS) {
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
            if (matcher.containsKey(COUNT) && !wholeNonNegative(matcher.get(COUNT))) {
                return "has a 'count' that is not a non-negative whole number: " + matcher.get(COUNT);
            }
            if (matcher.containsKey(MATCHES)) {
                String patternProblem = patternProblem(matcher.get(MATCHES));
                if (patternProblem != null) {
                    return patternProblem;
                }
            }
            boolean ignoreCase = false;
            if (matcher.containsKey(IGNORE_CASE)) {
                if (!(matcher.get(IGNORE_CASE) instanceof Boolean flag)) {
                    return "has an 'ignoreCase' that is not true or false";
                }
                if (VALUE_KEYS.stream().noneMatch(matcher::containsKey)) {
                    return "has an 'ignoreCase' with no equals, contains or excludes to apply to";
                }
                ignoreCase = flag;
            }
            if (matcher.containsKey(CONTAINS) && matcher.containsKey(EXCLUDES)) {
                List<Object> overlap = presentIn(distinct(values(matcher.get(CONTAINS)), ignoreCase),
                        values(matcher.get(EXCLUDES)), ignoreCase);
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

    private static String patternProblem(Object raw) {
        List<Object> patterns = values(raw);
        if (patterns.isEmpty()) {
            return "has an empty 'matches'";
        }
        for (Object pattern : patterns) {
            if (!(pattern instanceof String text)) {
                return "has a 'matches' that is not a regular expression or a list of them";
            }
            try {
                Pattern.compile(text);
            } catch (PatternSyntaxException e) {
                return "has an invalid 'matches' pattern '" + text + "': " + e.getDescription();
            }
        }
        return null;
    }

    /** A YAML integer that is zero or more; decimals, text and booleans are not counts. */
    private static boolean wholeNonNegative(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue() >= 0;
        }
        return value instanceof BigInteger big && big.signum() >= 0 && big.bitLength() < 64;
    }

    /** Equal as DNs when both parse as DNs, otherwise equal as text. */
    static boolean sameValue(Object expected, Object actual) {
        return sameValue(expected, actual, false);
    }

    /** Equal as DNs when both parse as DNs, otherwise equal as text, ignoring case when asked. */
    static boolean sameValue(Object expected, Object actual, boolean ignoreCase) {
        LdapName expectedDn = dn(expected);
        LdapName actualDn = dn(actual);
        if (expectedDn != null && actualDn != null) {
            return expectedDn.equals(actualDn);
        }
        if (expected.equals(actual)) {
            return true;
        }
        String expectedText = String.valueOf(expected);
        String actualText = String.valueOf(actual);
        return ignoreCase ? expectedText.equalsIgnoreCase(actualText) : expectedText.equals(actualText);
    }
```

Keep `dn`, `values`, `plain` as they are. Change `distinct`, `absentFrom` and `presentIn` to take a trailing `boolean ignoreCase` and pass it to `sameValue` (and to the nested `distinct` call). Leave no 2-arg overloads of those three if nothing else uses them.

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl idm-connector-ldap,idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures/errors/skips. Known flake: `LdapStepsTest` "closed connection" — re-run once and report both runs if it alone fails.

- [ ] **Step 5: Commit**

```bash
git add idm-connector-ldap idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java
git commit -m "feat: equals, count, matches and ignoreCase matchers for ldap.verify

ITF-39

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Add and remove single values in `ldap.update`

**Files:**
- Create: `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/LdapValueChanges.java`
- Create: `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/LdapValueChangesTest.java`
- Modify: `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/EdirectoryConnector.java` (`update`)
- Modify: `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/EdirectoryConnectorTest.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapLists.java`
- Modify: `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java`

**Interfaces:**
- Consumes: `LdapValueMatcher.sameValue(Object, Object)` (package-private, same package).
- Produces:
  - `public final class LdapValueChanges` with `ADD = "add"`, `REMOVE = "remove"`, `public static String shapeProblem(Object value)` (null for a non-map or a well-formed change), `static List<Object> offending(String key, Object wanted, Object actual)`, `static String reason(String key, String attribute, List<Object> offending)`.
  - `EdirectoryConnector.update(String dn, Map<String, Object> attributes)` — same signature; a map value is an add/remove change.
  - `LdapLists.problem(String step, String where, Map<String, Object> attributes, boolean update)` — the last parameter renamed from `emptyAllowed` (same callers: create passes `false`, update `true`).

- [ ] **Step 1: Write the failing tests**

`LdapValueChangesTest.java`:

```java
package com.idm.test.connector.ldap.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LdapValueChanges")
class LdapValueChangesTest {

    @Test
    @DisplayName("Well-formed changes, and anything that is not a map, have no problem")
    void wellFormed() {
        assertThat(LdapValueChanges.shapeProblem(Map.of("add", "a"))).isNull();
        assertThat(LdapValueChanges.shapeProblem(Map.of("add", List.of("a"), "remove", "b"))).isNull();
        assertThat(LdapValueChanges.shapeProblem("plain")).isNull();
        assertThat(LdapValueChanges.shapeProblem(List.of("a"))).isNull();
    }

    @Test
    @DisplayName("Each malformed change is named")
    void malformed() {
        assertThat(LdapValueChanges.shapeProblem(Map.of())).isEqualTo("is an empty map; use add and/or remove");
        assertThat(LdapValueChanges.shapeProblem(Map.of("ad", "a"))).isEqualTo("has unknown keys [ad]; use add and/or remove");
        assertThat(LdapValueChanges.shapeProblem(Map.of("add", List.of()))).isEqualTo("has an empty 'add'");
        Map<String, Object> nullRemove = new HashMap<>();
        nullRemove.put("remove", null);
        assertThat(LdapValueChanges.shapeProblem(nullRemove)).isEqualTo("has an empty 'remove'");
        assertThat(LdapValueChanges.shapeProblem(Map.of("add", List.of("a", List.of("b")))))
                .isEqualTo("has a null, map or list value in 'add'; give plain values");
        assertThat(LdapValueChanges.shapeProblem(Map.of("add", "cn=a,o=x", "remove", "CN=A,O=X")))
                .isEqualTo("lists [cn=a,o=x] in both 'add' and 'remove'");
    }

    @Test
    @DisplayName("Offending values: those already present for add, those missing for remove")
    void offending() {
        assertThat(LdapValueChanges.offending("add", List.of("cn=a,o=x", "cn=b,o=x"), List.of("CN=A,O=X")))
                .containsExactly("cn=a,o=x");
        assertThat(LdapValueChanges.offending("remove", List.of("x", "y"), "x")).containsExactly("y");
        assertThat(LdapValueChanges.offending("remove", "x", null)).containsExactly("x");
    }

    @Test
    @DisplayName("Reasons say which values and why")
    void reasons() {
        assertThat(LdapValueChanges.reason("add", "seeAlso", List.of("cn=a")))
                .isEqualTo("cannot add [cn=a] to seeAlso: the entry already has it");
        assertThat(LdapValueChanges.reason("remove", "seeAlso", List.of("cn=a", "cn=b")))
                .isEqualTo("cannot remove [cn=a, cn=b] from seeAlso: the entry does not have them");
    }
}
```

Add to `EdirectoryConnectorTest`, in the ITF-38 section (it already has `DEV`, `VPN` and `createMultiValued(String cn)`, which creates `ou: [Engineering, Security]`, `seeAlso: [DEV, VPN]`):

```java
    private static final String FINANCE = "cn=finance,ou=groups,dc=test,dc=com";

    @Test
    @DisplayName("update adds and removes values in one request, alongside a replace")
    void testUpdateAddsAndRemovesValues() {
        String dn = createMultiValued("multi.addremove");

        OperationResult<Void> result = connector.update(dn, Map.of(
                "seeAlso", Map.of("add", List.of(FINANCE), "remove", List.of(VPN)),
                "title", "Engineer"));

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        Map<String, Object> read = connector.readAttributes(dn).getData().orElseThrow();
        assertThat((List<?>) read.get("seeAlso")).map(String::valueOf).containsExactlyInAnyOrder(DEV, FINANCE);
        assertThat(read.get("title")).isEqualTo("Engineer");

        connector.delete(dn);
    }

    @Test
    @DisplayName("Adding a value the entry has fails, says which, and changes nothing")
    void testUpdateAddExistingFails() {
        String dn = createMultiValued("multi.addexisting");

        OperationResult<Void> result = connector.update(dn, Map.of(
                "seeAlso", Map.of("add", "CN=Developers,OU=Groups,DC=test,DC=com"),
                "title", "Never"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage())
                .isEqualTo("cannot add [CN=Developers,OU=Groups,DC=test,DC=com] to seeAlso: the entry already has it");
        Map<String, Object> read = connector.readAttributes(dn).getData().orElseThrow();
        assertThat(read).doesNotContainKey("title");
        assertThat((List<?>) read.get("seeAlso")).hasSize(2);

        connector.delete(dn);
    }

    @Test
    @DisplayName("Removing a value the entry lacks fails and says which")
    void testUpdateRemoveMissingFails() {
        String dn = createMultiValued("multi.removemissing");

        OperationResult<Void> result = connector.update(dn, Map.of("seeAlso", Map.of("remove", List.of(FINANCE))));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage())
                .isEqualTo("cannot remove [" + FINANCE + "] from seeAlso: the entry does not have it");

        connector.delete(dn);
    }
```

Add to `LdapStepsTest` (ITF-38 section):

```java
    @Test
    @DisplayName("ldap.update adds and removes single values, records them as written, and reports strict failures")
    void testUpdateAddRemove() {
        String dn = "cn=ada.addremove,ou=users";
        String a = "cn=a,ou=groups,dc=test,dc=com";
        String b = "cn=b,ou=groups,dc=test,dc=com";
        new LdapCreateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("cn", "ada.addremove", "sn", "Lovelace", "seeAlso", List.of(a))), false);

        StepOutcome updated = new LdapUpdateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("seeAlso", Map.of("add", b, "remove", a))), false);
        assertThat(updated.success()).as(updated.message()).isTrue();
        assertThat(String.valueOf(((LdapEvidence) updated.evidence()).attributes().get("seeAlso"))).contains("add");
        assertThat(new LdapVerifyStep(directories).execute(Map.of("dn", dn,
                "expect", Map.of("seeAlso", Map.of("equals", b, "count", 1))), false).success()).isTrue();

        StepOutcome refused = new LdapUpdateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("seeAlso", Map.of("add", b))), false);
        assertThat(refused.success()).isFalse();
        assertThat(refused.message())
                .isEqualTo("ldap.update failed for " + dn + ": cannot add [" + b + "] to seeAlso: the entry already has it");

        connector.delete(dn);
    }

    @Test
    @DisplayName("Malformed add/remove fails before the directory is called; ldap.create refuses add/remove")
    void testAddRemoveRejectedBeforeIo() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        LdapDirectories mocked = only(mockConnector);

        StepOutcome empty = new LdapUpdateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("seeAlso", Map.of("add", List.of()))), false);
        assertThat(empty.message()).isEqualTo("ldap.update for cn=x,ou=users: attribute 'seeAlso' has an empty 'add'");

        StepOutcome unknown = new LdapUpdateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("seeAlso", Map.of("append", "a"))), false);
        assertThat(unknown.message())
                .isEqualTo("ldap.update for cn=x,ou=users: attribute 'seeAlso' has unknown keys [append]; use add and/or remove");

        StepOutcome create = new LdapCreateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("cn", "x", "sn", "X", "seeAlso", Map.of("add", "a"))), false);
        assertThat(create.message())
                .isEqualTo("ldap.create for cn=x,ou=users: attribute 'seeAlso' uses add/remove, which only apply to ldap.update");

        verify(mockConnector, never()).update(any(), any());
        verify(mockConnector, never()).create(any());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-connector-ldap,idm-test-engine -am -Dtest='LdapValueChangesTest,EdirectoryConnectorTest,LdapStepsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`LdapValueChanges` missing).

- [ ] **Step 3: Implement**

`LdapValueChanges.java`:

```java
package com.idm.test.connector.ldap.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rules for an {@code ldap.update} attribute written as {@code { add: …, remove: … }}: values added
 * to or removed from the attribute, leaving its other values as they are. Values compare DN-aware,
 * as in {@link LdapValueMatcher}.
 */
public final class LdapValueChanges {

    public static final String ADD = "add";
    public static final String REMOVE = "remove";

    private LdapValueChanges() {
    }

    /**
     * @return {@code null} when {@code value} is not a change (not a map) or is a well-formed one,
     *         else what is wrong with it, as a fragment to follow the attribute's name
     */
    public static String shapeProblem(Object value) {
        if (!(value instanceof Map<?, ?> change)) {
            return null;
        }
        if (change.isEmpty()) {
            return "is an empty map; use add and/or remove";
        }
        Set<String> unknown = new TreeSet<>();
        for (Object key : change.keySet()) {
            if (!ADD.equals(key) && !REMOVE.equals(key)) {
                unknown.add(String.valueOf(key));
            }
        }
        if (!unknown.isEmpty()) {
            return "has unknown keys " + unknown + "; use add and/or remove";
        }
        for (String key : List.of(ADD, REMOVE)) {
            if (!change.containsKey(key)) {
                continue;
            }
            Object values = change.get(key);
            if (values == null || values instanceof Collection<?> c && c.isEmpty()) {
                return "has an empty '" + key + "'";
            }
            if (values instanceof Map<?, ?> || values instanceof Collection<?> c
                    && c.stream().anyMatch(v -> v == null || v instanceof Map<?, ?> || v instanceof Collection<?>)) {
                return "has a null, map or list value in '" + key + "'; give plain values";
            }
        }
        if (change.containsKey(ADD) && change.containsKey(REMOVE)) {
            List<Object> overlap = new ArrayList<>();
            for (Object added : values(change.get(ADD))) {
                boolean removed = values(change.get(REMOVE)).stream()
                        .anyMatch(other -> LdapValueMatcher.sameValue(added, other));
                if (removed && overlap.stream().noneMatch(kept -> LdapValueMatcher.sameValue(kept, added))) {
                    overlap.add(added);
                }
            }
            if (!overlap.isEmpty()) {
                return "lists " + overlap + " in both 'add' and 'remove'";
            }
        }
        return null;
    }

    /** For add, the wanted values the entry already has; for remove, those it does not have. */
    static List<Object> offending(String key, Object wanted, Object actual) {
        List<Object> current = values(actual);
        boolean add = ADD.equals(key);
        List<Object> result = new ArrayList<>();
        for (Object value : values(wanted)) {
            boolean present = current.stream().anyMatch(other -> LdapValueMatcher.sameValue(value, other));
            if (present == add) {
                result.add(value);
            }
        }
        return result;
    }

    static String reason(String key, String attribute, List<Object> offending) {
        String pronoun = offending.size() == 1 ? "it" : "them";
        return ADD.equals(key)
                ? "cannot add " + offending + " to " + attribute + ": the entry already has " + pronoun
                : "cannot remove " + offending + " from " + attribute + ": the entry does not have " + pronoun;
    }

    static List<Object> values(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of(value);
    }
}
```

`EdirectoryConnector.update` — replace the method (imports as needed: `java.util.ArrayList`, `javax.naming.directory.ModificationItem`, `javax.naming.directory.DirContext`):

```java
    /**
     * Updates attributes in one modify request. A plain value or list replaces the attribute (an
     * empty list removes it); a {@code { add: …, remove: … }} map adds or removes those values and
     * leaves the others. The directory applies the request entirely or not at all. When it refuses
     * because a value to add exists or a value to remove does not, the message says which.
     *
     * @param dn the distinguished name
     * @param attributes map of attribute names to new values or add/remove changes
     * @return operation result indicating success or failure
     */
    public OperationResult<Void> update(String dn, Map<String, Object> attributes) {
        log.debug("Updating LDAP user: {}", dn);

        try {
            Name name = LdapNameBuilder.newInstance(dn).build();

            List<ModificationItem> mods = new ArrayList<>();
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> change) {
                    if (change.containsKey(LdapValueChanges.ADD)) {
                        mods.add(new ModificationItem(DirContext.ADD_ATTRIBUTE,
                                attributeOf(entry.getKey(), change.get(LdapValueChanges.ADD))));
                    }
                    if (change.containsKey(LdapValueChanges.REMOVE)) {
                        mods.add(new ModificationItem(DirContext.REMOVE_ATTRIBUTE,
                                attributeOf(entry.getKey(), change.get(LdapValueChanges.REMOVE))));
                    }
                } else {
                    mods.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                            attributeOf(entry.getKey(), entry.getValue())));
                }
            }

            ldapTemplate.modifyAttributes(name, mods.toArray(new ModificationItem[0]));

            log.info("LDAP user updated successfully: {}", dn);

            return OperationResult.<Void>builder()
                    .success(true)
                    .message("User updated successfully")
                    .build();

        } catch (org.springframework.ldap.AttributeInUseException e) {
            return refused(dn, attributes, LdapValueChanges.ADD, e);
        } catch (org.springframework.ldap.NoSuchAttributeException e) {
            return refused(dn, attributes, LdapValueChanges.REMOVE, e);
        } catch (Exception e) {
            log.error("Failed to update LDAP user '{}': {}", dn, e.getMessage());
            return OperationResult.<Void>builder()
                    .success(false)
                    .message("Failed to update user: " + e.getMessage())
                    .errorType(ErrorType.LDAP_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /** A collection is several values (an empty one, none); anything else is one value. */
    private static Attribute attributeOf(String name, Object value) {
        if (value instanceof Collection<?> values) {
            Attribute attribute = new BasicAttribute(name);
            values.forEach(attribute::add);
            return attribute;
        }
        return new BasicAttribute(name, value);
    }

    /**
     * The directory refused an add of an existing value (LDAP 20) or a remove of a missing one
     * (LDAP 16). The entry is read once, only here, to say which values; when that is not
     * possible, the directory's own message stands.
     */
    private OperationResult<Void> refused(String dn, Map<String, Object> attributes, String key, Exception e) {
        String explanation = explain(dn, attributes, key);
        log.error("Failed to update LDAP user '{}': {}", dn, explanation != null ? explanation : e.getMessage());
        return OperationResult.<Void>builder()
                .success(false)
                .message(explanation != null ? explanation : "Failed to update user: " + e.getMessage())
                .errorType(ErrorType.LDAP_ERROR)
                .throwable(e)
                .build();
    }

    private String explain(String dn, Map<String, Object> attributes, String key) {
        OperationResult<Map<String, Object>> read = readAttributes(dn);
        if (!read.isSuccess() || read.getData().isEmpty()) {
            return null;
        }
        Map<String, Object> actual = read.getData().get();
        List<String> reasons = new ArrayList<>();
        attributes.forEach((attribute, value) -> {
            if (value instanceof Map<?, ?> change && change.containsKey(key)) {
                List<Object> offending = LdapValueChanges.offending(key, change.get(key), actual.get(attribute));
                if (!offending.isEmpty()) {
                    reasons.add(LdapValueChanges.reason(key, attribute, offending));
                }
            }
        });
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }
```

If the embedded tests show Spring translating LDAP 20/16 to other exception classes, catch those instead (check `org.springframework.ldap.support.LdapUtils.convertLdapException`) and say so in the report.

`LdapLists.problem` — rename the last parameter to `update` and handle maps first in the loop:

```java
    /**
     * @param step   the step name, for the message
     * @param where  the entry, as the step names it
     * @param update whether this is ldap.update: an empty list is meaningful there (it removes the
     *               attribute), and so is an add/remove map; ldap.create allows neither
     * @return the failure message, or {@code null} when every value can be written
     */
    static String problem(String step, String where, Map<String, Object> attributes, boolean update) {
        for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
            String prefix = step + " for " + where + ": attribute '" + attribute.getKey() + "' ";
            Object value = attribute.getValue();
            if (value instanceof Map<?, ?>) {
                if (!update) {
                    return prefix + "uses add/remove, which only apply to ldap.update";
                }
                String changeProblem = LdapValueChanges.shapeProblem(value);
                if (changeProblem != null) {
                    return prefix + changeProblem;
                }
                continue;
            }
            if (!(value instanceof Collection<?> values)) {
                continue;
            }
            if (values.isEmpty() && !update) {
                return prefix + "has an empty list; there is nothing to write";
            }
            if (values.stream().anyMatch(v -> v == null || v instanceof Map<?, ?> || v instanceof Collection<?>)) {
                return prefix + "has a null, map or list value inside its list; give plain values";
            }
        }
        return null;
    }
```

(import `com.idm.test.connector.ldap.client.LdapValueChanges`; update the class Javadoc's first sentence to mention add/remove.)

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl idm-connector-ldap,idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures/errors/skips. Known flake: `LdapStepsTest` "closed connection" — re-run once and report both runs if it alone fails.

- [ ] **Step 5: Commit**

```bash
git add idm-connector-ldap idm-test-engine
git commit -m "feat: ldap.update adds and removes single values, failing clearly when they exist or are missing

ITF-39

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Corpus scenario, docs and full suite

**Files:**
- Modify: `idm-test-scenarios/src/test/resources/scenarios/ldap-multi-valued-attributes.yaml`
- Modify: `docs/reference/writing-scenarios.md` (`ldap.update` section ~line 227; `ldap.verify` **Values** ~line 291)
- Modify: `README.md` (step table `ldap.update` / `ldap.verify` rows, ~lines 119-121)
- Modify: `docs/architecture/capabilities.md` (the `ldap.verify` row, line ~14)

**Interfaces:**
- Consumes: Tasks 1–2 behaviour.

- [ ] **Step 1: Extend the scenario**

Append these steps after the last `ldap.verify` in `steps:` (before `cleanup:`):

```yaml
  - ldap.update:
      description: Put the user back in the VPN group and take them out of developers
      dn: "${createdDn}"
      attributes:
        seeAlso:
          add: ["cn=vpn-users,ou=groups,o=data"]
          remove: ["cn=developers,ou=groups,o=data"]

  - ldap.verify:
      description: One group left, matched in another spelling; two departments, any case
      dn: "${createdDn}"
      expect:
        seeAlso:
          equals: ["CN=VPN-Users,OU=Groups,O=Data"]
          count: 1
        ou:
          contains: [engineering]
          ignoreCase: true
          count: 2
          matches: "(?i)sec.*"
```

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false -Dscenario=ldap-multi-valued-attributes.yaml`
Expected: BUILD SUCCESS; `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`.

- [ ] **Step 2: Docs**

`docs/reference/writing-scenarios.md`, `ldap.verify` **Values**: extend the forms table with rows

```markdown
| `{ equals: [...] }` | the attribute has exactly these values, in any order (the same as a plain list; use it to add `ignoreCase`) |
| `{ count: 3 }` | the attribute has this many values; an absent attribute has 0 |
| `{ matches: "^jdoe@.*$" }` | each pattern (one, or a list) matches the whole of at least one value; Java regular expressions, case-sensitive unless the pattern starts with `(?i)` |
| `{ ignoreCase: true, … }` | `equals`, `contains` and `excludes` compare text ignoring case (DNs always do) |
```

and replace the row for `{ contains: [...], excludes: [...] }` with: `| several keys together, e.g. { contains: [...], count: 3 } | every key given holds |`. Add to the bullets: `count` must be a whole number (not `3.0` or `"3"`); an invalid regular expression, an empty `matches`, or `ignoreCase` without `equals`/`contains`/`excludes` ends the wait at once like the other malformed forms; add these to the **Fails when** list of malformed forms too.

`ldap.update`: after the **Several values** paragraph add:

~~~markdown
**Adding or removing values:** give an attribute a map with `add` and/or `remove` (each one value
or a list) to change some values and keep the rest, in the same request as any replaced attributes:

```yaml
- ldap.update:
    dn: "${createdDn}"
    attributes:
      groupMembership:
        add: ["cn=VPN,ou=groups,o=data"]
        remove: ["cn=Finance,ou=groups,o=data"]
      title: Engineer
```

Adding a value the entry already has, or removing one it does not have, fails the step and
changes nothing (`cannot add [cn=VPN,ou=groups,o=data] to groupMembership: the entry already has
it`). DNs compare ignoring case and spacing. `add`/`remove` only apply to `ldap.update`;
`ldap.create` refuses them. An empty `add` or `remove`, unknown keys, or the same value in both
fail the step before the directory is contacted.
~~~

and extend its **Fails when** paragraph with: `, or an add/remove that the directory refuses as above`.

`README.md` step table: `ldap.update` Required cell → `` `dn`, `attributes` (a list writes several values, `[]` removes the attribute, `{add, remove}` changes single values) ``; `ldap.verify` Required cell → `` `dn`, and exactly one of `expect` (a value, a list as the exact set in any order, or `{equals, contains, excludes, count, matches, ignoreCase}`) or `exists` ``.

`docs/architecture/capabilities.md` `ldap.verify` row, last column: append `; count, matches and ignoreCase matchers, and add/remove of single values in ldap.update (ITF-39)`.

- [ ] **Step 3: Full suite and corpus**

Delete stale `idm-test-scenarios/target/surefire-reports/*ContainerScenarios.*` files if present. Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures/errors/skips. Then

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
grep -c '"status" : "FAILED"' idm-test-scenarios/target/multi-directory-results.json
```

Expected: a total, `0`, `0`. If Docker is available: `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false` passes 21/21 (the scenario must also pass on OpenLDAP). Known flakes: `EmbeddedLdapServerTest.testLdifImport`, `LdapStepsTest` closed connection, `SoapStepsTest` port clash — re-run once and report both runs.

- [ ] **Step 4: Commit**

```bash
git add idm-test-scenarios/src/test/resources/scenarios/ldap-multi-valued-attributes.yaml docs/reference/writing-scenarios.md README.md docs/architecture/capabilities.md
git commit -m "docs: describe the new ldap.verify matchers and ldap.update add/remove, and cover them in the corpus

ITF-39

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
