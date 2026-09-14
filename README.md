# IdM Test Framework

> **Integration testing for Identity Management systems — write scenarios as YAML or as Java**

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Tests](https://img.shields.io/badge/tests-235%20passing-brightgreen.svg)]()
[![Docker](https://img.shields.io/badge/Docker-not%20required-lightgrey.svg)]()

Tests provisioning workflows across LDAP, databases, REST APIs and SOAP services. Built on
Spring Boot, JUnit 5 and an embedded directory — the suite runs with no Docker, no external
services and no manual setup.

---

## Two ways to write a test

The framework has two authoring tiers. Neither replaces the other.

**Declarative (YAML).** A scenario is a data file. No Java, no compilation — add a file and it
runs. Intended for IdM consultants who do not write Java.

```yaml
name: User provisioning to eDirectory

fixtures:
  user:
    generate: random-user
    values: { department: Engineering }

steps:
  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      attributes:
        cn: ${user.userId}
        sn: ${user.lastName}
        givenName: ${user.firstName}

  - ldap.verify:
      dn: "cn=${user.userId},ou=users"
      expect:
        givenName: ${user.firstName}
      wait: { timeout: 30s, poll: 2s }
```

**Programmatic (Java).** A JUnit test that calls the connectors directly. Use it when a scenario
needs real logic — branching, computation, or an operation the step vocabulary does not cover.

```java
OperationResult<LdapUser> result = edirectoryConnector.create(user);
assertThat(result.isSuccess()).isTrue();

boolean provisioned = testExecutor.waitUntil(
        () -> databaseConnector.executeQuery("test-db", sql, userId).isSuccess(),
        Duration.ofMinutes(5), Duration.ofSeconds(5));
```

Both tiers use the same connectors underneath, so a fix in a connector reaches both.

---

## Quick start

**Prerequisites:** Java 21 and Maven 3.9+. That is all — Docker is not required.

```bash
./mvnw clean test
```

The suite starts an embedded UnboundID LDAP directory and a WireMock SOAP server on ephemeral
ports, creates an H2 schema, runs 235 tests, and tears everything down.

```bash
# One module
./mvnw -pl idm-test-engine -am test

# One test class (-am needs this flag, since sibling modules have no matching test)
./mvnw -pl idm-test-scenarios -am test -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false

# Debug logging
./mvnw test -Dlogging.level.com.idm.test=DEBUG
```

Sibling modules are not installed to the local repository, so `-pl <module>` needs `-am`
alongside it.

---

## Writing a YAML scenario

Drop a `.yaml` file into `idm-test-scenarios/src/test/resources/scenarios/`. A `@TestFactory`
discovers it and runs it as its own test.

### File anatomy

| Key | Meaning |
|---|---|
| `name` | Required. Shown in test output and results |
| `description` | Optional |
| `fixtures` | Named test data. `generate: random-user` plus optional literal `values` that override it |
| `steps` | An ordered list. Each entry is a single-key map: the step type, then its parameters |

### Step vocabulary

Actions change state; assertions check it and may retry.

| Step | Kind | Required | Also accepts |
|---|---|---|---|
| `ldap.create` | action | `dn` | `attributes` |
| `ldap.verify` | assertion | `dn`, `expect` | `wait` |

Ten more step types — `ldap.update`, `ldap.delete`, `db.execute`, `db.verify`, `roles.grant`,
`roles.revoke`, `resources.grant`, `resources.revoke`, `rest.request`, `rest.verify` — are
tracked as ITF-8 and ITF-9.

### Waiting

Waiting is a property of assertions, not a step of its own: you never wait for its own sake, you
wait for something you were going to assert anyway. Any assertion accepts `wait`.

```yaml
wait: { timeout: 30s, poll: 2s }   # explicit — durations take s, m or h
wait: true                          # use the configured defaults
```

### Interpolation

`${path}` resolves against fixtures. Property paths only — no arithmetic, conditionals or
function calls. That limit is deliberate: it keeps every value representable in a form, which
matters if a UI is ever built. When a scenario needs computation, that is the signal it belongs
in the Java tier.

`random-user` produces `userId`, `firstName`, `lastName`, `email`, `department`, `jobTitle`,
`phoneNumber` and `employeeId`.

### Validation happens before anything runs

Every scenario file is parsed and validated before the first step executes, because a scenario
can spend minutes waiting for provisioning and discovering a typo afterwards is expensive. A
scenario is rejected at load for an unknown step type, a missing required parameter, an unknown
parameter, a reference to an undeclared fixture, an unknown `generate:` value, or a malformed
`wait:`. Every message names the file and the step.

That last point matters more than it sounds. Because steps declare their parameter names, a
misspelled `expct:` fails at load rather than producing a green test that verified nothing.

### Results

Each run writes `idm-test-scenarios/target/scenario-results.json` — every scenario, every step,
the parameters after interpolation, and for a step that waited, how many attempts it made and
what it last observed. The engine itself renders nothing; this file is the shape an HTML report
or a UI would read.

---

## Project structure

```
idm-test-framework/
├── idm-test-core/           TestExecutor, OperationResult, ErrorType
├── idm-test-utils/          Test data generators, custom assertions, EmbeddedLdapServer
├── idm-test-engine/         YAML scenario engine — loader, validator, executor, step SPI
├── idm-connector-ldap/      eDirectory/LDAP connector
├── idm-connector-database/  Multi-database JDBC connector (raw SQL)
├── idm-connector-rest/      REST client configuration
├── idm-connector-soap/      Role and Resource service connectors (NetIQ SOAP)
├── idm-connector-idm/       Placeholder — no sources yet
├── idm-test-scenarios/      Java scenario tests, YAML scenarios, the JUnit adapter
├── docker-compose.yml       Optional local services; the test suite does not use them
└── docs/                    See Documentation below
```

---

## Architecture

### Principles

- **Spring's built-in clients** — RestClient, JdbcTemplate, WebServiceTemplate. No custom wrappers.
- **Raw SQL for database validation** — no JPA or Hibernate entities.
- **Integration-first** — tests exercise real protocol implementations, not mocks of our own code.
- **Two tiers, one set of connectors** — the engine is a consumer of the connectors, exactly as a
  Java test is.

### The engine

Three properties are enforced rather than merely intended:

**It takes no Spring dependency.** `idm-test-engine` never creates an application context or
reads configuration; it receives its dependencies by constructor. A test walks the module's
sources and fails on any `org.springframework` import. That is what keeps a future CLI a wrapper
rather than a rewrite.

**It interpolates before calling a step, and owns the retry loop.** Step implementations receive
fully resolved parameters and never think about waiting, which keeps each new step type to
roughly forty lines around one connector call.

**It renders nothing.** `run()` returns a `ScenarioResult`; the JSON writer and the JUnit adapter
are both adapters over that one shape.

`run()` always returns a result rather than throwing, and always unwinds cleanup in reverse — a
cleanup failure is logged and never replaces the scenario's own error.

### Test infrastructure

| Concern | Provided by |
|---|---|
| LDAP directory | Embedded UnboundID, ephemeral port, standard schema extended with eDirectory's `startDate`, `loginDisabled` and `c` |
| Databases | H2 in-memory, schema created at context startup |
| SOAP services | WireMock on a dynamic port, stubs under `src/test/resources/wiremock/` |

---

## What exists today

| Module | Tests |
|---|---|
| idm-test-core | 34 |
| idm-test-utils | 51 |
| idm-test-engine | 61 |
| idm-connector-ldap | 14 |
| idm-connector-database | 21 |
| idm-connector-rest | 12 |
| idm-connector-soap | 13 |
| idm-test-scenarios | 29 |
| **Total** | **235** |

The scenarios module holds 27 Java workflow tests covering provisioning, role assignment,
modification, deprovisioning and multi-system flows, plus the YAML scenario and its validation
test.

**Worth being explicit about:** those Java scenarios *simulate* IdM. They write directly to the
embedded LDAP and H2 and assert the result, so they exercise the framework end to end but
validate no real Identity Manager. Pointing the framework at a real environment is separate
work, and the connection details are already externalised so it is a configuration change rather
than a code change.

### Next

| Ticket | Work |
|---|---|
| ITF-7 | Expressions — a sandboxed `js:{ }` escape hatch, the `set:` step, `as:` capture |
| ITF-8 | `ldap.update`, `ldap.delete`, `db.execute`, `db.verify` |
| ITF-9 | `roles.grant`/`revoke`, `resources.grant`/`revoke`, `rest.request`, `rest.verify` |
| ITF-5 | Convert the Java scenarios to YAML; amend the specification |

---

## Optional local services

`docker-compose.yml` starts OpenLDAP, phpLDAPadmin, WireMock and PostgreSQL for manual
exploration. **The test suite does not use them** — it runs against embedded services. These are
here for poking at a directory by hand, and for the eventual move to real infrastructure.

```bash
docker-compose up -d       # phpLDAPadmin at http://localhost:8080
docker-compose down
```

---

## Documentation

Organised by how much you should trust it.

**Reference** — maintained:
- [Specification](docs/reference/specification.md) — architecture and design decisions
- [Configuration](docs/reference/configuration.md) — connectors, databases, endpoints
- [Running tests](docs/reference/running-tests.md) — test execution guide
- [CLAUDE.md](CLAUDE.md) — development guidelines

**Specs and plans** — design documents for in-flight work:
- [Declarative scenario engine](docs/specs/2026-09-13-declarative-scenario-engine-design.md)
- [Green test baseline](docs/specs/2026-09-11-suite-green-baseline-design.md)
- [docs/plans/](docs/plans/) — task breakdowns

**History** — frozen, partly inaccurate, kept for the record:
- [docs/history/](docs/history/README.md) — the banner there says which reports are wrong

---

## Technology stack

| Concern | Choice | Version |
|---|---|---|
| Language | Java | 21 |
| Framework | Spring Boot | 3.5.6 |
| Testing | JUnit 5, AssertJ | 5.11.x, 3.26.3 |
| LDAP | Spring LDAP, UnboundID SDK | 3.2.9, 7.0.1 |
| Database | Spring JDBC, H2 | 2.3.232 |
| SOAP | JAX-WS, JAXB | — |
| YAML/JSON | Jackson | 2.19.2 |
| Mocking | WireMock | 3.10.0 |

No Lombok — Java records instead.
