# Several named LDAP directories — design

**Ticket:** ITF-34. **Follow-up:** ITF-33, full Active Directory support (passwords,
`userAccountControl`, binary attributes, paging).

## 1. The problem

The framework knows one directory:
- **Configuration:** one block, `idm.test.ldap` (url, base-dn, user-dn, password, pool settings).
- **Code:** one `LdapTemplate` and one `EdirectoryConnector`, shared by the four `ldap.*` steps.
- **Real-environment runner:** guards only `idm.test.ldap.*`.

IdM provisions into more than one directory: Active Directory, a second eDirectory tree, an
OpenLDAP instance. A scenario can't verify any of them except the one configured.

Databases already solve the same problem: `idm.test.databases.<name>`, with each `db.*` step
naming its `database:`.

## 2. Decisions (jangulo, 2026-09-28)

1. **A map of directories, with an optional `directory:` on the `ldap.*` steps.** The old
   `idm.test.ldap` block keeps working as the directory named `default`, so the 60 existing
   steps (19 files) and existing lab configs don't change.
2. **Several directories, plus a configurable `objectClass` on `ldap.create`.** Full AD support
   is ITF-33.
3. **One `idm.test.default-directory` setting** names the directory used when a step omits it.

## 3. Configuration

```yaml
idm:
  test:
    default-directory: edirectory          # optional; see "The default directory"
    directories:
      edirectory:
        url: ldaps://edir.company.com:636
        base-dn: o=company
        user-dn: cn=admin,o=company
        password: ${EDIR_PASSWORD}
      ad:
        url: ldaps://dc01.company.com:636
        base-dn: dc=corp,dc=company,dc=com
        user-dn: CN=svc-idmtest,OU=Service,DC=corp,DC=company,DC=com
        password: ${AD_PASSWORD}
        pooled: false
```

- **Keys per directory** are exactly today's `LdapProperties`: `url`, `base-dn`, `user-dn`,
  `password`, `pooled` (default `true`), `min-idle` (1), `max-active` (8), `max-total` (8),
  `max-wait` (-1).
- **Directory names** follow the database-name rule: any YAML key. Messages quote them.
- **TLS** is unchanged: an `ldaps://` URL uses the JVM trust store (`configuration.md`, "TLS
  certificates").

### The old block

- **`idm.test.ldap` is still read,** as the directory named `default`. `application.yml` keeps
  its `idm.test.ldap` block and its `IDM_LDAP_*` variables, so every profile that overrides
  `idm.test.ldap.*` keeps working. The container runner and `BaseIntegrationTest`, which set
  `idm.test.ldap.*`, also keep working.
- **When `idm.test.directories.default` is also configured,** it replaces the old block
  entirely. A warning is logged once at startup:
  `idm.test.directories.default replaces the idm.test.ldap block`.
- **The old block counts as configured when its `url` is set.** `application.yml` always sets
  it (to the placeholder `ldap://localhost:389` unless overridden), so in practice `default`
  always exists. The real-environment guards stop a real run from using that placeholder (§6).

### The default directory

In order:
1. `idm.test.default-directory`, if set. It must name a configured directory, or startup fails
   with `idm.test.default-directory is 'x', which is not configured. Configured directories:
   [ad, default, edirectory]`.
2. Otherwise, the only directory, if exactly one is configured.
3. Otherwise, `default`, if configured.
4. Otherwise, there is no default: every `ldap.*` step must name its directory (§5).

## 4. Wiring

- **`LdapDirectories`** (new, `idm-connector-ldap`, package `...ldap.client`) holds one
  `EdirectoryConnector` per directory, keyed by name. Its interface:
  - `Set<String> names()`: the configured names, sorted
  - `Optional<String> defaultName()`: the default, resolved per §3
  - `Optional<EdirectoryConnector> connector(String name)`
- **`LdapConfiguration`** builds, per directory, the same `LdapContextSource` → optional
  `PoolingContextSource` → `LdapTemplate` → `EdirectoryConnector` chain as today, into one
  `LdapDirectories` bean. **Nothing connects at startup:** `afterPropertiesSet` doesn't open a
  connection, and the pool opens connections on first borrow (the lazy rule databases follow
  since ITF-30). A test confirms no connection is attempted to an unreachable directory that
  no step uses.
- **The Java tier is unchanged.** The default directory's `LdapTemplate` and
  `EdirectoryConnector` stay injectable beans, so `BaseIntegrationTest` and the Java workflow
  tests don't move. With no default directory, these two beans don't exist; nothing in the
  repository runs that way.
- **Configuration binding:** a new `LdapDirectoriesProperties`
  (`@ConfigurationProperties("idm.test")`, fields `directories` and `defaultDirectory`), next
  to the existing `LdapProperties`, which stays bound to `idm.test.ldap` for the old block.
- **Class names stay** (`EdirectoryConnector`, `LdapProperties`). Renaming them is out of
  scope.

## 5. Steps

- **`ldap.create`, `ldap.verify`, `ldap.update` and `ldap.delete`** accept an optional
  `directory:`, and take `LdapDirectories` in their constructors in place of an
  `EdirectoryConnector`.
- **Choosing the directory:** the step's `directory:` if given (converted with
  `String.valueOf`), otherwise the default.
- **Final failures** (ITF-26: they end a `wait:` at once):
  - an unknown name: `ldap.verify: unknown directory 'adx'. Configured directories: [ad,
    default, edirectory]`
  - no `directory:` and no default: `ldap.verify: name a directory; there is no default.
    Configured directories: [ad, edirectory]`

  The engine can't see the configuration when a file loads, so these are caught when the step
  runs, on its first attempt, and by the real-environment guards before anything runs. That
  is the same as `db.*`'s unknown-database rule.
- **Messages and results:** when more than one directory is configured, a step's success and
  failure messages name the directory, e.g. `Created cn=jdoe,ou=users in directory 'ad'`. With
  a single directory, messages are unchanged, so existing expectations hold.
- **DNs** stay relative to that directory's `base-dn`, as today.

### `objectClass` on `ldap.create`

- **When `attributes` contains `objectClass`** (a single string or a list of strings), those
  values are written as the entry's object classes, in place of the default
  `top, person, organizationalPerson, inetOrgPerson`. Otherwise the default applies, as today.
- **Example, an AD user:**

  ```yaml
  - ldap.create:
      directory: ad
      dn: "CN=${user.firstName} ${user.lastName},OU=Users"
      attributes:
        objectClass: [top, person, organizationalPerson, user]
        sAMAccountName: ${user.userId}
        givenName: ${user.firstName}
        sn: ${user.lastName}
  ```

  The account is created disabled, without a password: AD's rules, handled in ITF-33.

## 6. The real-environment runner

- **`ScenarioNeeds`** replaces `boolean ldap` with `Set<String> directories`: the literal
  `directory:` values of every `ldap.*` step (in `steps` or `cleanup`), plus a marker for
  "the default" when a step omits it. A value containing `${` is only known at run time and
  is skipped, as for databases.
- **The guards, per directory used** (the default resolved first):
  - a directory that isn't configured is refused before anything runs:
    `Scenarios use directory 'adx', which is not configured. Configured directories: [...]`
  - a step without `directory:` when there is no default is refused the same way
  - for `default` when it comes from the old block: today's checks, unchanged
    (`idm.test.ldap.url` set and not the placeholder, with the `IDM_TEST_LDAP_URL` hint; the
    placeholder bind DN and password refused)
  - for any other directory: `idm.test.directories.<name>.url` must be set; the placeholder
    URL, bind DN and password (`ldap://localhost:389`, `cn=admin,dc=company,dc=com`,
    `secret`) are refused, naming `idm.test.directories.<name>.<key>`
  - directories no scenario uses are not checked
- **`RealEnvironmentLdapOnlyTest`** keeps passing unchanged.

## 7. Documentation

- **`docs/reference/writing-scenarios.md`:**
  - a `directory` row (not required) in the four `ldap.*` parameter tables
  - a "Several directories" section under LDAP: choosing a directory, the default, the two
    final failures
  - the `objectClass` rule and the AD example in `ldap.create`
- **`docs/guides/configuration.md`:** the `directories` map, `default-directory`, the old
  block's status, and the default rules.
- **The real-environment README:** the per-directory guards.
- **The capability matrix and README:** several directories.

## 8. Testing

**Unit tests (connector module):**
- the default rules: an explicit default, one directory, `default` among several, no default
- an unknown `default-directory` fails startup with the message above
- the old block becomes `default`; `directories.default` replaces it, with the warning
- a configured but unreachable directory causes no connection attempt at startup

**Engine (`LdapStepsTest`, embedded UnboundID):**
- `directory:` selects the right connector, for each of the four steps
- an unknown directory and a missing default are final failures, and end a `wait:` after one
  attempt
- messages name the directory only when several are configured
- `objectClass` as a string and as a list replaces the default; without it, the default is
  used

**Runner (`YamlScenarioTest` and the corpus):**
- `YamlScenarioTest` starts a second embedded UnboundID server (base `dc=second,dc=com`) and
  configures it as `idm.test.directories.second`, with the existing server staying `default`
  through the old block. The existing corpus runs unchanged.
- A new corpus scenario creates an entry in `second` with a custom `objectClass`, verifies it
  is there and not in `default`, and deletes it in `cleanup:`.

**Real-environment guards (`RealEnvironmentGuardsTest`):**
- an unconfigured directory, a missing default, and each placeholder for a named directory are
  refused
- the old block's checks are unchanged
- unused directories are not checked

**Also:** `ScenarioReferenceTest` passes with the new rows; a falsification for each rule; the
full suite; the corpus (0 FAILED); `ContainerScenarios` if Docker is available.

## 9. Out of scope

- Everything in ITF-33: `unicodePwd`, `userAccountControl`, readable `objectGUID`/`objectSid`,
  paged searches, Global Catalog.
- A second directory in `ContainerScenarios` (it keeps one OpenLDAP container).
- Renaming `EdirectoryConnector` or `LdapProperties`.
- Choosing a directory in the Java tier beyond the default beans (a Java test can inject
  `LdapDirectories`).
