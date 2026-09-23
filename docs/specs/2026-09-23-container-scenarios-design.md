# Running the scenario corpus against containers — design

**Ticket:** ITF-14. **Depends on:** ITF-12 (`RealEnvironmentScenarios`), ITF-13 (`-Dscenario`).

## 1. Purpose

ITF-12 built `RealEnvironmentScenarios`, a runner that takes its target from configuration
instead of the embedded overrides. Its structure is tested. What nobody has shown is that pointing
it at genuine servers really is just a change of address. This ticket shows that for LDAP and SQL.
It runs the 18-scenario corpus through that runner against a real OpenLDAP, a real PostgreSQL and a
WireMock container, which Testcontainers starts and stops.

**What it proves:**
- Genuine OpenLDAP server behaviour.
- Genuine PostgreSQL behaviour, and therefore SQL that works on two databases.
- That both are reached through ITF-12's configuration path.

**What it does not prove:**
- Anything new about network sockets. The embedded UnboundID server already listens on a real
  socket; what is new is the real server behind it.
- Anything about OpenText IdM: not the SOAP role or resource services, not the REST API, and not
  whether IdM provisions correctly. OpenText images are not used.

A green container run must not be read as "validated against IdM".

## 2. Decisions already taken

- **The corpus, not a new scenario set.** The same 18 scenarios the embedded suite runs.
- **Optional, and never in the default build.** The runner is named without a `Test` suffix, like
  `RealEnvironmentScenarios`, so surefire's includes skip it. A plain `./mvnw test` never touches
  Docker. Anyone without Docker keeps a green build and never notices this exists.
- **eDirectory and IdM stay a running lab.** A team that has them points `RealEnvironmentScenarios`
  at them through configuration (ITF-12). This ticket does not start licensed images.

## 3. The Docker API clash is resolved by an upgrade

Measured on this machine (Docker Desktop, engine 29.8.0, API 1.56, minimum 1.40, arm64). Each
check used a throwaway probe test that started `postgres:16-alpine`; the version was overridden
from the command line and the probe deleted afterwards:

| Testcontainers | Result |
|---|---|
| 1.20.4 (current) | `client version 1.32 is too old. Minimum supported API version is 1.40`, the original clash |
| 1.21.3 | "Could not find a valid Docker environment": Docker Desktop answers the handshake with status 400 and an empty body. It works only with `-Dapi.version=1.44`. `DOCKER_API_VERSION` has no effect. |
| **1.21.4** | **Container started, with no pin** |

So the parent POM's `testcontainers.version` goes from `1.20.4` to `1.21.4`. Moving to 2.0.x
(latest is 2.0.5) would also rename modules and packages. That is a separate change with nothing
to gain here.

## 4. Shape

```java
public class ContainerScenarios extends RealEnvironmentScenarios {

    @BeforeAll
    static void startContainers() { ... }   // start containers, then set idm.test.* system properties

    @AfterAll
    static void stopContainers() { ... }    // clear exactly the properties it set, stop the containers

    @Override
    String scenarioDirectory() {
        return "scenarios";
    }
}
```

**Why a subclass, and why system properties.** The values are published as system properties
because that is exactly what an operator would pass with `-D`. Spring gives them precedence over
every profile document, and they are not `@DynamicPropertySource`, which
`RealEnvironmentScenariosStructureTest` exists to keep out of that runner. Everything after the
properties are set goes through `RealEnvironmentScenarios` unchanged: its three guards, and no
pinned profile or overridden properties.

Two alternatives were rejected:
- **A standalone runner with `@DynamicPropertySource`.** It would prove the connectors against real
  servers without going through ITF-12's path, which is the thing this ticket exists to prove.
- **Starting the containers with `docker compose` by hand.** It is not self-contained, and its
  ports are fixed. It stays in the README as the "running lab" route.

**Ordering is an assumption to verify first.** Spring's `SpringExtension` loads the application
context when it post-processes the test instance, which is after static `@BeforeAll` methods run
under JUnit's default per-method lifecycle. The plan's first task proves this with the containers
running, and stops if it is wrong.

**Cleanup is part of correctness.** `@AfterAll` removes every system property the runner set,
restoring any value that existed before. That way nothing leaks into another class that runs in the
same JVM, for example when someone runs `-Dtest='ContainerScenarios,YamlScenarioTest'`.

## 5. The containers

### OpenLDAP (`osixia/openldap:1.5.0`, which publishes an arm64 image)

- Domain `test.com`, so the base is `dc=test,dc=com`. Admin is `cn=admin,dc=test,dc=com` with
  password `test`, matching the embedded server.
- After start, the runner loads `ldap-init.ldif` (the same OUs the embedded server imports) using
  `ldapadd` inside the container.
- **Schema extension.** Two corpus scenarios (`deprovisioning-user-disable.yaml` and
  `integration-employee-termination.yaml`) set `loginDisabled`. The connector writes every entry
  with the fixed object classes `top, person, organizationalPerson, inetOrgPerson`, so an
  auxiliary class is not an option. The extension must do what `IdmTestSchema` does: define
  `startDate` and `loginDisabled` (same OIDs, same syntaxes), then add them and `c` to
  `inetOrgPerson`'s optional attributes. On OpenLDAP that means an `ldapmodify` against `cn=config`,
  inside the container.
- **This is the second assumption to verify first.** It relies on OpenLDAP accepting a change to an
  existing object class at runtime. If it refuses, the plan stops and reports rather than work
  around it.

### PostgreSQL (`postgres:16-alpine`)

- `test-db` points here, and so do `sap`, `oracle-hr` and `workday`. Every database configured
  under `idm.test.databases` must accept a connection or the Spring context will not start (see
  ITF-16).
- `oracle-hr` on PostgreSQL is a stand-in, and its driver class is overridden to
  `org.postgresql.Driver`. It says nothing about Oracle.
- `TestSchemaInitializer` already runs `schema.sql` against `test-db`, whatever that database is.
  So the schema reaches PostgreSQL with no new code, once `schema.sql` is portable (§6).

### WireMock (`wiremock/wiremock`, version pinned in the plan)

- It serves the existing stubs under `src/test/resources/wiremock` with global response
  templating, the same stubs the embedded WireMock uses.
- `idm.test.soap.url` and `idm.test.soap.resource-url` point at it.

### Properties set

The runner sets `idm.test.ldap.url`, `base-dn`, `user-dn` and `password`, then `idm.test.soap.url`
and `resource-url`. For each of `test-db`, `sap`, `oracle-hr` and `workday` it sets
`idm.test.databases.<name>.jdbc-url`, `username`, `password` and `driver-class-name`.

It sets no profile. The run happens under whatever `IDM_TEST_PROFILE` resolves to, normally
`default`, and the system properties override that profile's placeholders. That makes guard 2 in
`RealEnvironmentScenarios` genuinely exercised: it passes because real addresses were supplied.

## 6. Changes to existing files

- **`RealEnvironmentScenarios`**: `SCENARIO_DIR` is replaced by a package-private instance method
  `String scenarioDirectory()` that returns `"scenarios-real"`. The factory, guard 3 and the
  `-Dscenario` filter all use it. `RealEnvironmentGuardsTest` references `SCENARIO_DIR` for
  `testShippedDirectoryHasNoScenarios`; that becomes a separate package-private constant
  `DEFAULT_SCENARIO_DIR`, which `scenarioDirectory()` returns. The structural test still holds:
  there is still no profile pin, no `@DynamicPropertySource`, no superclass, and no embedded server
  in its source.
- **`schema.sql`**: `INT AUTO_INCREMENT PRIMARY KEY` becomes
  `INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`, which is standard SQL accepted by both H2 and
  PostgreSQL. The MySQL-only `ON UPDATE CURRENT_TIMESTAMP` is removed from `users.updated_at`.
  Nothing reads `updated_at`; it is referenced only in `schema.sql`. Every other statement in the
  file is already portable, and so is the corpus's own SQL. The corpus uses no H2 functions and
  no case-sensitive `rows[0].COLUMN` references.
- **Parent `pom.xml`**: `testcontainers.version` changes from 1.20.4 to 1.21.4.
- **Dependencies**: `org.testcontainers:postgresql` (test scope) moves from `idm-connector-database`,
  where no source uses it, to `idm-test-scenarios`, where it is now used. The WireMock and
  OpenLDAP containers are plain `GenericContainer`s from the core `org.testcontainers:testcontainers`
  artifact, which `postgresql` brings in transitively. `org.testcontainers:junit-jupiter` is not
  added, because the containers are started and stopped by hand in `@BeforeAll` and `@AfterAll`.

`BaseIntegrationTest`, its subclasses, the corpus YAML and the embedded infrastructure are
unchanged. The default suite stays Docker-free.

## 7. Failing loudly

- **Docker is unavailable.** `@BeforeAll` checks `DockerClientFactory.instance().isDockerAvailable()`
  before starting anything. If Docker is missing, it throws `IllegalStateException` with this
  message: "Docker is not available. ContainerScenarios needs a running Docker daemon; the default
  suite does not." That replaces Testcontainers' multi-strategy stack trace. JUnit reports it as a
  failure, not a skip.
- **A container fails to start or set up.** The step is named in the failure: which container, and
  which of LDIF load, schema extension, or start. Any containers already started are stopped.
- **The existing guards still apply.** Guard 1 (the embedded `test` profile) still refuses, even
  here. If someone runs `IDM_TEST_PROFILE=test` with this runner, it fails, and should.

## 8. Testing

**Probes first, in the plan's first task.** These are run by hand, not kept as tests. They check
the two assumptions in §4 and §5 with the containers running:
1. Properties set in `@BeforeAll` are visible to the Spring context. Checked by the connectors
   reaching the container addresses, not the placeholders.
2. The OpenLDAP schema extension is accepted, and an entry with `loginDisabled` can be written
   through the connector.

If either fails, stop and report.

**Permanent tests, in the default build, with no Docker:**
- `ContainerScenariosStructureTest`:
  - `ContainerScenarios` does not end in `Test` or `Tests`
  - it extends `RealEnvironmentScenarios`
  - it declares no `@ActiveProfiles`, `@DynamicPropertySource` or `@TestPropertySource`
  - its `scenarioDirectory()` returns `scenarios`
  - each assertion is falsification-tested
- The Docker-unavailable message comes from a small static method,
  `requireDocker(boolean available)`, which is unit-tested with `false` and `true`. It is also
  falsification-tested.
- The existing `RealEnvironmentGuardsTest` and `RealEnvironmentScenariosStructureTest` still pass,
  adjusted only for the rename in §6.
- `schema.sql` on H2 is covered by the whole existing default suite, which runs it at context
  startup.

**Live, run by hand in the plan:**
- `-Dtest=ContainerScenarios`: 18 of 18 corpus scenarios pass, recorded in the report.
- `-Dtest=ContainerScenarios -Dscenario=deprovisioning-user-disable.yaml`: 1 test passes. This is
  one of the two schema-extension scenarios.
- With Docker stopped, or `DOCKER_HOST` pointing nowhere: `BUILD FAILURE` with the plain message.
- `./mvnw clean test`: the previous total plus the new unit tests, 0 failures, errors or skips,
  and no container started. Check that Docker shows no new containers.

## 9. Documentation

- `scenarios-real/README.md` gets a short "Running against containers" section with the command,
  and a "Running against a lab" note. The lab note says that a team with eDirectory/IdM points the
  runner at it with the `IDM_TEST_*` variables, which is route C from §4.
- `CLAUDE.md` gets one command line.
- The stale comments in `BaseIntegrationTest` about "Testcontainers automatically start" are out of
  scope. That file stays untouched, per ITF-12.

## 10. Out of scope

- OpenText eDirectory or IdM images.
- Testcontainers 2.x.
- Running containers in CI. That needs a CI with Docker, and is a separate decision.
- ITF-16 (Oracle `SELECT 1`, eager pool connections). Pointing `oracle-hr` at PostgreSQL here
  sidesteps it; it does not fix it.
- Reusing containers across runs (`withReuse`).
