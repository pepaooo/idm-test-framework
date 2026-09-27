# A real-environment run requires only what its scenarios use — design

**Ticket:** ITF-30 (subtask of ITF-4), requested by jangulo on 2026-09-27. It extends ITF-12's guards and ITF-15's
"only targets used" rule to every repository.

## 1. The problem

Take a real-environment run (`RealEnvironmentScenarios`) whose only scenario uses `ldap.*`. Today
it still requires:

| Requirement | Enforced by | Needed although unused? |
|---|---|---|
| `idm.test.ldap.url` set and not the placeholder | guard 2 | yes, even for a REST-only run |
| `idm.test.soap.url` set and not the placeholder | guard 2 | **yes** |
| `idm.test.soap.resource-url` set | the runner autowires `ResourceServiceConnector`, which is `@ConditionalOnProperty(resource-url)`, so the Spring context fails | **yes** |
| every database under `idm.test.databases` accepts a connection | `DatabaseConfiguration` builds a `HikariDataSource` per database at startup, and each pool connects immediately | **yes** |
| REST base URL and credentials | guards 4 and 5 | no: only targets used (ITF-15, ITF-23) |
| SOAP credentials | guard 5 | no: only when `roles.*`/`resources.*` are used |

A pure-LDAP run therefore needs a SOAP URL, a resource URL, and three reachable databases that it
never touches.

## 2. Decisions (jangulo, 2026-09-27)

1. **A run requires only what the selected scenarios use.** This covers the guards and the
   connections. Unused repositories may stay as `application.yml`'s placeholders and are never
   contacted.
2. **Check configuration only, and connect lazily.** A used repository must be configured and not
   a placeholder. There is no up-front connection probe. An unreachable used repository fails the
   first step that touches it, with the connector's own message.

## 3. What a run uses: `ScenarioNeeds`

A new package-private record in `com.idm.test.scenarios.engine`, next to
`RealEnvironmentScenarios`:

```java
record ScenarioNeeds(boolean ldap, Set<String> databases, boolean roles, boolean resources,
                     Set<String> restTargets) {
    static ScenarioNeeds of(List<Scenario> scenarios) { ... }
}
```

It reads every entry of every scenario's `steps:` **and** `cleanup:`:

| Step type prefix | Adds |
|---|---|
| `ldap.` | `ldap = true` |
| `db.` | the `database` parameter, to `databases` |
| `roles.` | `roles = true` |
| `resources.` | `resources = true` |
| `rest.` or `http.` | the `target` parameter, to `restTargets` |

- **Parameter values** (`database`, `target`) follow today's `restTargets` rules:
  - a `null` value is skipped
  - any other value is converted with `String.valueOf`
  - a value containing `${` is skipped, because it is only known at run time
  - the sets are sorted (`TreeSet`), so messages are deterministic
- `RealEnvironmentScenarios.restTargets(...)` and `usesSoap(...)` are replaced by
  `ScenarioNeeds`. `usesSoap` becomes `roles || resources`. Their tests move with them, and
  `restTargets` may remain as a thin delegate if that keeps `RealEnvironmentGuardsTest` readable.

## 4. The guards

Order in `scenarios()`:
1. guard 1
2. guard 3
3. select the files
4. load them once
5. compute `ScenarioNeeds`
6. guards 2, 4, DB and 5, all fed from the needs

**Guard 2 moves after selection,** because it now depends on what the run uses.

| Guard | Today | After |
|---|---|---|
| 1: the embedded `test` profile is refused | unconditional | unchanged |
| 2: LDAP URL | always required, non-placeholder | only when `ldap` |
| 2: SOAP URL | always required, non-placeholder | only when `roles` |
| 2: SOAP resource URL | not checked (a context failure instead) | **new:** set, when `resources`. There is no placeholder to compare: `application.yml` defines no resource URL. |
| 3: empty directory | unconditional | unchanged |
| 4: REST targets | targets used | unchanged, fed from `ScenarioNeeds.restTargets` |
| **DB (new)** | — | for each database used, see below |
| 5: credentials | REST targets used; SOAP when `roles`/`resources` | unchanged, fed from `ScenarioNeeds` |

**The DB guard,** for each name in `databases`, in sorted order, refuses:
- **a name not configured** under `idm.test.databases`. The message lists the configured names.
- **a `jdbc-url` that is blank, or still `application.yml`'s placeholder** for that name:
  - `sap`: `jdbc:postgresql://localhost:5432/sap`
  - `oracle-hr`: `jdbc:oracle:thin:@localhost:1521:HRDB`
  - `workday`: `jdbc:postgresql://localhost:5432/workday`
- **a `username` or `password` still at the placeholder:**
  - `sap`: `sapuser`/`sappass`
  - `oracle-hr`: `hruser`/`hrpass`
  - `workday`: `wdayuser`/`wdaypass`

Messages name the property and the variable (`variableFor`, e.g.
`IDM_TEST_DATABASES_SAP_JDBCURL`), never a value. The placeholders are pinned to
`application.yml` by `RealEnvironmentGuardsTest`, as the other guards' placeholders are. A
database the scenarios don't use is never checked.

**Known limit:** a name with a dash (`oracle-hr`) gets a variable suggestion that relaxed binding
reads as a different key. This is ITF-28's existing limit and is not fixed here.

## 5. Connecting lazily

**Databases:**
- `DatabaseConfiguration` builds each database's pool so that it opens **no connection until
  first used**. The intended mechanism is `HikariDataSource`'s no-arg constructor configured
  through `HikariConfig.copyStateTo`: Hikari then starts the pool on the first `getConnection()`,
  and until then there is no initial connection and no background connection for `min-idle`.
- The bean type, `Map<String, JdbcTemplate>`, and `MultiDatabaseConnector` are unchanged.
- A connection failure surfaces from `executeQuery`/`executeUpdate` as today: `DATABASE_ERROR`,
  retried under `wait:` (ITF-26).
- The plan confirms the mechanism with a test that a pool for an unreachable URL is created
  without error and without contact.
- The embedded suite is unaffected: `TestSchemaInitializer` opens `test-db` on purpose.

**SOAP:**
- `RealEnvironmentScenarios` stops requiring `RoleServiceConnector` and `ResourceServiceConnector`.
  It obtains them through `ObjectProvider` (`getIfAvailable()`), so a context without
  `soap.url` or `soap.resource-url` starts.
- The step registry still registers all 13 step types, so validation of any scenario works.
  `roles.*`/`resources.*` steps built with an absent connector fail when executed:
  `roles.grant cannot run: no Role service is configured (idm.test.soap.url)`, and similarly for
  resources with `idm.test.soap.resource-url`, using `StepOutcome.finalFailure`.
- Guard 2 makes that path unreachable in a real run; it exists so that a missing bean is never a
  NullPointerException.
- `YamlScenarioTest.registry(...)` accepts `null` connectors for these two. Its callers in the
  embedded suite still pass real beans.

**LDAP:** already lazy. `LdapContextSource.afterPropertiesSet()` does not bind, so nothing
changes. With a placeholder LDAP URL and no `ldap.*` step, nothing is contacted.

**REST:** unchanged. Nothing is contacted until a step runs.

## 6. What does not change

- **The embedded suite** (`YamlScenarioTest`, the Java workflow tests) and **`ContainerScenarios`**
  configure every repository and behave exactly as today.
- **`application.yml`** keeps its placeholders.
- **Guard 1's and guard 3's messages** are unchanged.

## 7. Testing

**`ScenarioNeedsTest`** (no Spring):
- each step family gives the right field
- entries only in `cleanup:` count
- `${…}` names and `null`s are skipped
- a non-string `database: 1` is collected as `"1"`
- sets are sorted
- a scenario with only `set:` needs nothing

**`RealEnvironmentGuardsTest`**, for each conditional check:
- **passes** when the repository is unused, even with a placeholder or unset value
- **refuses** when it is used with a placeholder or unset value, and the message names the
  variable and never the value

Also:
- the DB guard: unconfigured, a placeholder URL, placeholder username, placeholder password, a
  blank URL, a real configuration passes
- the resource URL: unset and used is refused; unset and unused passes
- the pinning test for the DB placeholders

**Lazy pools** (`DatabaseConfiguration`, in `idm-connector-database`):
- a configuration with an unreachable JDBC URL (e.g. `jdbc:postgresql://localhost:1/none`) builds
  without error
- a query against a reachable H2 database in the same map succeeds
- the unreachable one fails only when queried, with `DATABASE_ERROR`

**Absent SOAP connectors:** a `roles.grant`/`resources.grant` step built with a `null` connector
returns a final failure with the message in §5.

**The runner end to end** (the scenarios module):
- a real-environment context with the `ldap` needs only starts without `soap.url`,
  `soap.resource-url` or reachable databases
- it runs an LDAP-only scenario against the embedded LDAP, standing in for the real directory

The plan decides whether this is a `RealEnvironmentScenarios` subclass in the style of
`ContainerScenarios`, or a focused context test.

**Falsification:** each conditional is made unconditional once, and a test must fail.

**Live:** the full default suite (0 skips), the corpus (0 FAILED), and `ContainerScenarios` if
Docker is available.

## 8. Documentation

- **`scenarios-real/README.md`, "What the environment must provide"** is rewritten around "only
  what your scenarios use", with the per-repository requirements and the DB placeholder list.
  This removes the statements that every database must accept a connection and that
  `soap.resource-url` must always be set.
- **The capability matrix's "Real-environment runner" row:** one line.
- **`docs/reference/running-tests.md`, "Against a real lab":** the five-guard list is updated.

## 9. Out of scope

- A connection probe before the run (rejected by decision 2).
- Making the embedded suite or `BaseIntegrationTest` lazy.
- The dash-in-name variable limit (ITF-28).
- A guard 5 check of the token URI.
- Resolving `${…}` database or target names before the run.
