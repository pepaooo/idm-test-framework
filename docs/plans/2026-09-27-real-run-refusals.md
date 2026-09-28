# Real-Run Refusals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A real-environment run refuses a used in-memory database, and refuses `application.yml`'s placeholder LDAP bind DN and password when `ldap.*` is used.

**Architecture:** Two small additions to `RealEnvironmentScenarios`:
- `requireDatabases` gains an in-memory prefix check
- `requireEndpoints` gains two bind-placeholder checks inside its existing `needs.ldap()` branch

The tests go in `RealEnvironmentGuardsTest`, including placeholder pinning. The docs change in two places.

**Tech Stack:** Java 21, Spring Boot 3.5.6 (`MockEnvironment`), JUnit 5, AssertJ.

**Spec:** `docs/specs/2026-09-27-real-run-refusals-design.md` (commit 2a146be). **Ticket:** ITF-31.

## Global Constraints

- **In-memory prefixes**, in exactly this order: `List.of("jdbc:h2:mem:", "jdbc:hsqldb:mem:", "jdbc:derby:memory:")`. Match them case-insensitively (`Locale.ROOT`) against the start of `jdbc-url`, and check only databases the selected scenarios use.
- **In-memory message, exactly:** `idm.test.databases.<name>.jdbc-url points at an in-memory database (<prefix from the list>), which exists only inside this test run, so nothing another system provisioned could be checked there. Point it at a real database: set <variableFor(property)>.` Never include the full URL.
- **LDAP placeholders:** `LDAP_USER_DN_PLACEHOLDER = "cn=admin,dc=company,dc=com"` and `LDAP_PASSWORD_PLACEHOLDER = "secret"`. They are refused through the existing `refusePlaceholder(property)`, only when `needs.ldap()`.
- **A blank or unset bind DN or password is never refused.** `base-dn` is not checked.
- **The pinning test** asserts that `application.yml` contains `${IDM_LDAP_USER_DN:cn=admin,dc=company,dc=com}` and `${IDM_LDAP_PASSWORD:secret}`.
- **No message contains a password value.**
- **Commits** end with a blank line, `ITF-31`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. Green means 0 failures, errors and skips.

---

### Task 1: In-memory database and LDAP bind placeholder refusals

**Files:**
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java`: constants near `LDAP_URL_PLACEHOLDER` (around line 79), `requireEndpoints` (around line 220), `requireDatabases` (around line 301)
- Test: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentGuardsTest.java`
- Docs:
  - `idm-test-scenarios/src/test/resources/scenarios-real/README.md` (the used-only table rows for `ldap.*` and `db.*`)
  - `docs/architecture/capabilities.md` (the "Real-environment runner" row)

**Interfaces:**
- Consumes (existing):
  - `requireDatabases(Set<String>, DatabaseProperties)`
  - `requireEndpoints(Environment, ScenarioNeeds)`
  - `requireRealTarget(Environment, ScenarioNeeds)`
  - `refusePlaceholder(String)`
  - `variableFor(String)`
  - `record ScenarioNeeds(boolean ldap, Set<String> databases, boolean roles, boolean resources, Set<String> restTargets)`
  - In `RealEnvironmentGuardsTest`, the helpers `target(String ldapUrl, String soapUrl, String... profiles)` → `MockEnvironment`, `databases(String name, String url, String user, String password)` → `DatabaseProperties`, and the constants `REAL_LDAP`, `LDAP_AND_ROLES` and `NOTHING`.
- Produces, in `RealEnvironmentScenarios`:
  - `static final List<String> IN_MEMORY_JDBC_PREFIXES`
  - `static final String LDAP_USER_DN_PLACEHOLDER`
  - `static final String LDAP_PASSWORD_PLACEHOLDER`

- [ ] **Step 1: Write the failing tests**

Add to `RealEnvironmentGuardsTest`:

```java
    private static final ScenarioNeeds LDAP_ONLY = new ScenarioNeeds(true, Set.of(), false, false, Set.of());

    private static MockEnvironment ldapTarget(String userDn, String password) {
        MockEnvironment environment = target(REAL_LDAP, null, "dev");
        if (userDn != null) {
            environment.setProperty("idm.test.ldap.user-dn", userDn);
        }
        if (password != null) {
            environment.setProperty("idm.test.ldap.password", password);
        }
        return environment;
    }

    @Test
    @DisplayName("The DB guard refuses each in-memory database a scenario uses, naming the prefix but never the URL")
    void testInMemoryDatabasesRefused() {
        Map<String, String> urls = Map.of(
                "jdbc:h2:mem:x", "jdbc:h2:mem:",
                "JDBC:H2:MEM:x", "jdbc:h2:mem:",
                "jdbc:hsqldb:mem:x", "jdbc:hsqldb:mem:",
                "jdbc:derby:memory:x;create=true", "jdbc:derby:memory:");
        urls.forEach((url, prefix) -> assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(
                Set.of("hr"), databases("hr", url, "svc", "real")))
                .as(url)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("idm.test.databases.hr.jdbc-url points at an in-memory database (" + prefix
                        + "), which exists only inside this test run, so nothing another system provisioned"
                        + " could be checked there. Point it at a real database: set IDM_TEST_DATABASES_HR_JDBCURL."));
    }

    @Test
    @DisplayName("An in-memory URL's own password parameter never reaches the message")
    void testInMemoryUrlNotEchoed() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("hr"),
                databases("hr", "jdbc:h2:mem:x;PASSWORD=hunter2", "svc", "real")))
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("jdbc:h2:mem:x");
    }

    @Test
    @DisplayName("File-based, networked and real database URLs pass; an unused in-memory database passes")
    void testNonMemoryDatabasesPass() {
        for (String url : List.of("jdbc:h2:file:/tmp/x", "jdbc:h2:tcp://db.example.org/x",
                "jdbc:postgresql://db.example.org:5432/hr")) {
            assertThatCode(() -> RealEnvironmentScenarios.requireDatabases(Set.of("hr"),
                    databases("hr", url, "svc", "real"))).as(url).doesNotThrowAnyException();
        }
        assertThatCode(() -> RealEnvironmentScenarios.requireDatabases(Set.of(),
                databases("test-db", "jdbc:h2:mem:testdb", "sa", ""))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("When ldap.* is used, the placeholder bind DN and password are refused, never echoing the password")
    void testLdapBindPlaceholdersRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                ldapTarget(RealEnvironmentScenarios.LDAP_USER_DN_PLACEHOLDER, "real-pass"), LDAP_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.user-dn")
                .hasMessageContaining("IDM_TEST_LDAP_USERDN");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                ldapTarget("cn=svc,dc=example,dc=org", RealEnvironmentScenarios.LDAP_PASSWORD_PLACEHOLDER), LDAP_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.password")
                .hasMessageContaining("IDM_TEST_LDAP_PASSWORD")
                .hasMessageNotContaining("'" + RealEnvironmentScenarios.LDAP_PASSWORD_PLACEHOLDER + "'")
                .hasMessageNotContaining(": " + RealEnvironmentScenarios.LDAP_PASSWORD_PLACEHOLDER);
    }

    @Test
    @DisplayName("LDAP bind placeholders pass when ldap.* is not used; blank or unset ones pass when it is")
    void testLdapBindChecksConditional() {
        MockEnvironment placeholders = ldapTarget(RealEnvironmentScenarios.LDAP_USER_DN_PLACEHOLDER,
                RealEnvironmentScenarios.LDAP_PASSWORD_PLACEHOLDER);
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(placeholders, NOTHING))
                .doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(ldapTarget(null, null), LDAP_ONLY))
                .doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(ldapTarget("", ""), LDAP_ONLY))
                .doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(
                ldapTarget("cn=svc,dc=example,dc=org", "real-pass"), LDAP_ONLY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The LDAP bind placeholders match the fallbacks application.yml declares")
    void testLdapBindPlaceholdersMatchApplicationYml() throws IOException {
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));
        assertThat(yaml)
                .contains("${IDM_LDAP_USER_DN:" + RealEnvironmentScenarios.LDAP_USER_DN_PLACEHOLDER + "}")
                .contains("${IDM_LDAP_PASSWORD:" + RealEnvironmentScenarios.LDAP_PASSWORD_PLACEHOLDER + "}");
    }
```

Check that `List` is imported (`java.util.List`), and add it if not. The password assertion checks that `secret` never appears as a quoted or colon-prefixed value. `secret` is also an ordinary English word, and the existing `refusePlaceholder` wording must not trip the check. If `refusePlaceholder`'s message happens to contain the bare word "secret", tell me rather than changing that message.

- [ ] **Step 2: Run the tests and confirm they fail to compile**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentGuardsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `cannot find symbol: LDAP_USER_DN_PLACEHOLDER`.

- [ ] **Step 3: Implement**

In `RealEnvironmentScenarios`, next to `LDAP_URL_PLACEHOLDER`:

```java
    /** application.yml's fallback LDAP bind DN and password. Pinned by RealEnvironmentGuardsTest. */
    static final String LDAP_USER_DN_PLACEHOLDER = "cn=admin,dc=company,dc=com";
    static final String LDAP_PASSWORD_PLACEHOLDER = "secret";

    /**
     * JDBC URL prefixes of databases that live only inside the test JVM. Nothing another system
     * provisioned can be in them, so a real run refuses them. File-based and networked embedded
     * databases are left alone: someone may run one on purpose.
     */
    static final List<String> IN_MEMORY_JDBC_PREFIXES = List.of("jdbc:h2:mem:", "jdbc:hsqldb:mem:", "jdbc:derby:memory:");
```

In `requireEndpoints`, replace the `needs.ldap()` branch with:

```java
        if (needs.ldap()) {
            requireNamed(environment, "idm.test.ldap.url", "IDM_TEST_LDAP_URL", LDAP_URL_PLACEHOLDER);
            // A blank bind DN or password is anonymous bind, which is legitimate; only the
            // placeholders are refused. The dev profile overrides the DN but not the password.
            if (LDAP_USER_DN_PLACEHOLDER.equals(environment.getProperty("idm.test.ldap.user-dn"))) {
                refusePlaceholder("idm.test.ldap.user-dn");
            }
            if (LDAP_PASSWORD_PLACEHOLDER.equals(environment.getProperty("idm.test.ldap.password"))) {
                refusePlaceholder("idm.test.ldap.password");
            }
        }
```

In `requireDatabases`, after the `requireNamedValue(prefix + "jdbc-url", …)` call and before the username check, add:

```java
            String jdbcUrl = config.getJdbcUrl().toLowerCase(Locale.ROOT);
            for (String inMemory : IN_MEMORY_JDBC_PREFIXES) {
                if (jdbcUrl.startsWith(inMemory)) {
                    throw new IllegalStateException(prefix + "jdbc-url points at an in-memory database ("
                            + inMemory + "), which exists only inside this test run, so nothing another system"
                            + " provisioned could be checked there. Point it at a real database: set "
                            + variableFor(prefix + "jdbc-url") + ".");
                }
            }
```

`requireNamedValue` has already refused a null or blank URL, so `getJdbcUrl()` is non-null here.

Update the Javadocs:
- `requireDatabases`: add "and one whose jdbc-url is an in-memory database".
- `requireEndpoints`: add "and, for LDAP, the placeholder bind DN and password".

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='RealEnvironmentGuardsTest,RealEnvironmentLdapOnlyTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass, with 0 failures, errors and skips. `RealEnvironmentLdapOnlyTest` binds as `cn=admin,dc=test,dc=com` with password `test`, so it is unaffected.

- [ ] **Step 5: Falsification**

Make each change below, re-run `RealEnvironmentGuardsTest`, confirm that the named test fails, then revert:
- Drop `.toLowerCase(Locale.ROOT)`. `testInMemoryDatabasesRefused` fails on `JDBC:H2:MEM:x`.
- Remove `"jdbc:derby:memory:"` from the list. `testInMemoryDatabasesRefused` fails.
- Remove the password placeholder check. `testLdapBindPlaceholdersRefused` fails.
- Move the two bind checks outside `if (needs.ldap())`. `testLdapBindChecksConditional` fails.
- Change `LDAP_PASSWORD_PLACEHOLDER` to `"secret2"`. `testLdapBindPlaceholdersMatchApplicationYml` fails.

- [ ] **Step 6: Documentation**

**`idm-test-scenarios/src/test/resources/scenarios-real/README.md`, the used-only table.** Replace the `ldap.*` row's second cell (currently `idm.test.ldap.url` (`IDM_TEST_LDAP_URL`) checked against its placeholder; the bind DN and password must also be set but are not placeholder-checked) with:

```markdown
`idm.test.ldap.url` (`IDM_TEST_LDAP_URL`), `user-dn` (`IDM_TEST_LDAP_USERDN`) and `password` (`IDM_TEST_LDAP_PASSWORD`), each refused while still `application.yml`'s placeholder; a blank bind DN or password is allowed (anonymous bind)
```

Replace the `db.*` row's second cell (currently `idm.test.databases.<name>.jdbc-url`, `username`, `password`) with:

```markdown
`idm.test.databases.<name>.jdbc-url`, `username`, `password`, none left as the placeholder; an in-memory URL (`jdbc:h2:mem:`, `jdbc:hsqldb:mem:`, `jdbc:derby:memory:`) is refused, since nothing provisioned by another system can be in it
```

If the existing row text differs slightly from what's quoted, keep the table's column structure and replace the whole second cell with the text above.

**`docs/architecture/capabilities.md`, the "Real-environment runner" row.** In the last cell, after `REST target, database or credential`, insert `, an in-memory database, or placeholder LDAP bind credentials (ITF-31)`.

- [ ] **Step 7: Run the full suite and commit**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS, 0 failures, errors and skips; `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`. Report the total from `grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc`. If `docker info` succeeds, also run `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false` (expect 20/20); otherwise say Docker was unavailable.

```bash
git add idm-test-scenarios docs/architecture/capabilities.md
git commit -m "feat: refuse in-memory databases and LDAP bind placeholders in real runs

ITF-31
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
