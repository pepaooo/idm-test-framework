# Real-environment runs refuse in-memory databases and LDAP bind placeholders — design

**Ticket:** ITF-31 (subtask of ITF-4). Follow-up to ITF-30.

## 1. The problem

The real-environment runner (`RealEnvironmentScenarios`) exists to refuse a run that would test
nothing real. Two cases still pass every guard.

- **An in-memory database.** Suppose a `scenarios-real` file uses `database: test-db`.
  `application.yml`'s default document always defines `test-db` as
  `jdbc:h2:mem:testdb;…`. `test-db` has no entry in `DATABASE_PLACEHOLDERS`, so the DB guard
  (`requireDatabases`) only checks that its URL is not blank. The scenario then verifies an H2
  database inside the test JVM, which no other system could have provisioned.
- **LDAP bind placeholders.** When `ldap.*` is used, the LDAP/SOAP URL check validates only the
  URL. `application.yml`'s placeholder bind DN (`cn=admin,dc=company,dc=com`) and password
  (`secret`) pass. The `dev` profile overrides the URL, base DN and bind DN, **but not the
  password**. A `dev` run without `IDM_LDAP_PASSWORD` therefore passes every guard and fails at
  the first bind.

## 2. Decisions (jangulo, 2026-09-27)

1. **LDAP:** refuse the placeholder **bind DN and password** when `ldap.*` is used. `base-dn` is
   not checked: a real directory could plausibly be rooted at `dc=company,dc=com`.
2. **Databases:** refuse **in-memory URLs of any engine** (`jdbc:h2:mem:`, `jdbc:hsqldb:mem:`,
   `jdbc:derby:memory:`) for a used database. File-based and networked embedded databases are
   left alone, because someone may run one on purpose.

## 3. The DB guard: in-memory databases

In `RealEnvironmentScenarios.requireDatabases`, for each **used** database, after the existing
not-configured, blank-URL and placeholder checks:

- **The prefixes refused:** `IN_MEMORY_JDBC_PREFIXES = List.of("jdbc:h2:mem:", "jdbc:hsqldb:mem:",
  "jdbc:derby:memory:")`, matched case-insensitively (`Locale.ROOT`) against the start of
  `jdbc-url`.
- **On a match**, `IllegalStateException` with exactly:

  ```
  idm.test.databases.<name>.jdbc-url points at an in-memory database (<matched prefix>), which exists only inside this test run, so nothing another system provisioned could be checked there. Point it at a real database: set <variableFor(property)>.
  ```

- **Only the matched prefix is quoted,** in its canonical lower-case form from the list, never the
  full URL. A JDBC URL can carry a password parameter.
- **An in-memory database that no scenario uses** is not checked, as for every repository since
  ITF-30.
- **Unaffected:** `jdbc:h2:file:…`, `jdbc:h2:tcp://…`, `jdbc:hsqldb:file:…`, and every networked
  engine URL.

## 4. The LDAP/SOAP URL check: bind placeholders

In `RealEnvironmentScenarios.requireEndpoints`, when `needs.ldap()`, after the URL check:

- **The placeholders:** `LDAP_USER_DN_PLACEHOLDER = "cn=admin,dc=company,dc=com"` and
  `LDAP_PASSWORD_PLACEHOLDER = "secret"`.
- **The bind DN:** if `environment.getProperty("idm.test.ldap.user-dn")` equals its placeholder,
  refuse using the existing `refusePlaceholder("idm.test.ldap.user-dn")`. The message names
  `IDM_TEST_LDAP_USERDN`.
- **The password:** likewise for `idm.test.ldap.password`, with `IDM_TEST_LDAP_PASSWORD`. The
  message never contains the value.
- **A blank or unset bind DN or password** is not refused. Anonymous bind is a legitimate
  configuration.
- **`base-dn`** is not checked (decision 1).
- **When `ldap.*` is not used,** nothing about LDAP is checked, as today.

## 5. Pinning

`RealEnvironmentGuardsTest` gets an assertion that `application.yml` contains
`${IDM_LDAP_USER_DN:cn=admin,dc=company,dc=com}` and `${IDM_LDAP_PASSWORD:secret}`, matching the
two constants. This is the same pattern as the other placeholder pins.

## 6. What does not change

- **`RealEnvironmentLdapOnlyTest`** binds as `cn=admin,dc=test,dc=com` with password `test` and
  uses no database.
- **`ContainerScenarios`** publishes the same bind DN and password, and points every database,
  `test-db` included, at PostgreSQL.
- **The embedded suite** does not run these guards.
- **`application.yml`** is unchanged.

## 7. Testing

**`RealEnvironmentGuardsTest`:**
- **Each in-memory prefix is refused** (`jdbc:h2:mem:x`, `JDBC:H2:MEM:x`, `jdbc:hsqldb:mem:x`,
  `jdbc:derby:memory:x;create=true`). Each message contains the lower-case prefix and
  `IDM_TEST_DATABASES_<NAME>_JDBCURL`, and not the full URL.
- **An in-memory URL carrying a password parameter**
  (`jdbc:h2:mem:x;PASSWORD=hunter2`) gives a message without `hunter2`.
- **These pass:** `jdbc:h2:file:/tmp/x`, `jdbc:h2:tcp://db.example.org/x` and a PostgreSQL URL.
- **An unused in-memory database passes.**
- **The LDAP bind DN placeholder and the password placeholder are each refused** when `ldap.*` is
  used, naming `IDM_TEST_LDAP_USERDN` / `IDM_TEST_LDAP_PASSWORD`. The password message does not
  contain `secret`.
- **When `ldap.*` is not used,** the same placeholders pass.
- **A blank or unset bind DN and password pass** when `ldap.*` is used with a real URL.
- **The pinning test** (§5).
- **The existing `testRealTargetPasses`-style tests** keep passing. Their `target(...)` helper
  sets no bind DN or password, so the new checks don't trigger.

**Falsification:** remove each new check once, and a test must fail.

**Live:** the full default suite (0 skips), the corpus (0 FAILED), `RealEnvironmentLdapOnlyTest`,
and `ContainerScenarios` if Docker is available.

## 8. Documentation

- **`scenarios-real/README.md`'s used-only table:**
  - the `ldap.*` row says the bind DN and password are refused when still `application.yml`'s
    placeholders (a blank one is allowed, for anonymous bind)
  - the database row says in-memory URLs (`jdbc:h2:mem:`, `jdbc:hsqldb:mem:`,
    `jdbc:derby:memory:`) are refused
- **The capability matrix's "Real-environment runner" row:** one clause.

## 9. Out of scope

- `base-dn` (decision 1).
- File-based or networked embedded databases (decision 2).
- LDAP bind DN/password half-set checks. An anonymous or partial bind is the directory's
  decision.
- The dash-in-name variable limit (ITF-28).
