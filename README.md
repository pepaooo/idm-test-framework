# IdM Test Framework

> **Integration testing for Identity Management systems — write scenarios as YAML or as Java**

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Tests](https://img.shields.io/badge/tests-591%20passing-brightgreen.svg)]()
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
ports, creates an H2 schema, runs 591 tests, and tears everything down.

```bash
# One module
./mvnw -pl idm-test-engine -am test

# One test class (-am needs this flag, since sibling modules have no matching test)
./mvnw -pl idm-test-scenarios -am test -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false

# One YAML scenario, or a glob-selected group (quote patterns containing * or braces)
./mvnw -pl idm-test-scenarios -am test -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dscenario='provisioning-*'

# Debug logging
./mvnw test -Dlogging.level.com.idm.test=DEBUG
```

Two further runners take the same YAML scenarios elsewhere; neither is part of the default suite
(see [Running against containers or a real lab](#running-against-containers-or-a-real-lab)).

Sibling modules are not installed to the local repository by default, so `-pl <module>` needs
`-am` alongside it. To skip rebuilding them while you work only on scenarios, see
[running tests](docs/reference/running-tests.md#skipping-the-upstream-build).

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
| `cleanup` | Optional. Steps with the same shape, run after `steps` whether they passed or failed; each entry is SUCCEEDED, FAILED or SKIPPED |

### Step vocabulary

Actions change state; assertions check it and may retry.

| Step | Kind | Required | Also accepts |
|---|---|---|---|
| `ldap.create` | action | `dn` | `attributes` (may include `objectClass`), `directory` |
| `ldap.update` | action | `dn`, `attributes` | `directory` |
| `ldap.delete` | action | `dn` | `directory` |
| `ldap.verify` | assertion | `dn`, and exactly one of `expect` or `exists` | `wait`, `directory` |
| `db.execute` | action | `database`, `statement` | `params` |
| `db.verify` | assertion | `database`, `query`, `expect` | `params`, `wait` |
| `roles.grant` | action | `user`, `roles` | `reason` |
| `roles.revoke` | action | `assignments` | — |
| `resources.grant` | action | `user`, `resource` | `reason` |
| `resources.revoke` | action | `user`, `resource` | `reason` |
| `rest.request` | action | `target`, `method`, `path` | `body`, `bodyFrom`, `headers` |
| `rest.verify` | assertion | `target`, `path`, `expect` | `method`, `headers`, `wait` |
| `http.request` | action | `target`, `method`, `path` | `headers`, `body` (a raw string: JSON, XML, a SOAP envelope) |
| `http.verify` | assertion | `target`, `method`, `path`, `check` | `headers`, `body`, `wait` (JavaScript conditions on `response`, including its parsed JSON and XML) |

Every step also accepts `as:` to capture its result and `description:` to say why the step exists. `rest.*` and `http.request` address a named
target (`idm.test.rest.<target>.base-url`) and authenticate with that target's settings — basic,
OAuth2 client credentials, or none — unless the step writes its own `Authorization` header. The
[authoring guide](docs/guides/scenario-authoring.md) walks through it. The
[authoring reference](docs/reference/writing-scenarios.md) documents every parameter, capture and failure mode.

### Waiting

Waiting is a property of assertions, not a step of its own: you never wait for its own sake, you
wait for something you were going to assert anyway. Any assertion accepts `wait`.

```yaml
wait: { timeout: 30s, poll: 2s }   # explicit — durations take s, m or h
wait: true                          # use the configured defaults
```

### Interpolation, expressions and captures

`${path}` resolves against fixtures, `set:` variables and `as:` captures. Property paths only —
no arithmetic, conditionals or function calls.

When a value needs computing, a whole value can be a sandboxed JavaScript expression:
`"js:{ user.firstName.toUpperCase() }"`. It reads scope names directly, cannot reach Java classes
or connectors, and a result containing `undefined` anywhere is an error rather than a silent
`null`. `set:` binds such values to names; `as:` captures a step's result. When a scenario needs
real logic — branching, loops, an operation the vocabulary lacks — it belongs in the Java tier.

`random-user` produces `userId`, `firstName`, `lastName`, `email`, `department`, `jobTitle`,
`phoneNumber` and `employeeId`.

### Validation happens before anything runs

Every scenario file is parsed and validated before the first step executes, because a scenario
can spend minutes waiting for provisioning and discovering a typo afterwards is expensive. A
scenario is rejected at load for an unknown step type, a missing required parameter, an unknown
parameter, a reference to a name that is never bound (in `${...}` or inside `js:{...}`), an
unknown `generate:` value, a `js:{...}` that does not compile, or a malformed `wait:`. Every message names the file and the step.

That last point matters more than it sounds. Because steps declare their parameter names, a
misspelled `expct:` fails at load rather than producing a green test that verified nothing.

### Results

Each run writes `idm-test-scenarios/target/scenario-results.json` — every scenario, every step,
the parameters after interpolation, and for a step that waited, how many attempts it made and
what it last observed. `ScenarioEngine` itself renders nothing; the runners write both this JSON file and
the HTML report from its results.

Each run also writes a self-contained HTML report beside it (`scenario-report.html`, `real-environment-report.html`, `multi-directory-report.html`) — open it in a browser to read or share the run.

---

## Project structure

```
idm-test-framework/
├── idm-test-core/           TestExecutor, OperationResult, ErrorType
├── idm-test-utils/          Test data generators, custom assertions, EmbeddedLdapServer
├── idm-test-engine/         YAML scenario engine — loader, validator, executor, step SPI
├── idm-connector-ldap/      eDirectory/LDAP connector
├── idm-connector-database/  Multi-database JDBC connector (raw SQL)
├── idm-connector-rest/      REST connector, JDK HTTP client, target authentication (basic, OAuth2)
├── idm-connector-soap/      Role and Resource service connectors (NetIQ SOAP)
├── idm-connector-idm/       Placeholder — no sources yet
├── idm-test-scenarios/      Java scenario tests, YAML scenarios, the JUnit adapter
├── docker-compose.yml       Optional local services; the test suite does not use them
└── docs/                    See Documentation below
```

---

## Architecture

### Principles

- **Spring's built-in clients** — RestClient, JdbcTemplate, Spring Security's OAuth2 client; generated
  JAX-WS ports for SOAP. No custom wrappers and no custom token handling.
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

`run()` always returns a result rather than throwing. It infers no teardown of its own: an author
who needs cleanup writes it explicitly as a top-level `cleanup:` block, which runs after the steps,
pass or fail, in declaration order. A `FAILED` cleanup entry fails the scenario; a step failure
still leads the failure summary. See
[docs/specs/2026-09-24-explicit-cleanup-design.md](docs/specs/2026-09-24-explicit-cleanup-design.md).

### Test infrastructure

| Concern | Provided by |
|---|---|
| LDAP directory | Embedded UnboundID, ephemeral port, standard schema extended with eDirectory's `startDate`, `loginDisabled` and `c` |
| Databases | H2 in-memory, schema created at context startup |
| SOAP and REST services | WireMock on a dynamic port, stubs under `src/test/resources/wiremock/` |

---

## What exists today

Verified on 2026-09-25 with `./mvnw clean test` at `fb35547`:

| Module | Tests |
|---|---|
| idm-test-core | 34 |
| idm-test-utils | 51 |
| idm-test-engine | 302 |
| idm-connector-ldap | 18 |
| idm-connector-database | 21 |
| idm-connector-rest | 36 |
| idm-connector-soap | 18 |
| idm-test-scenarios | 111 |
| **Total** | **591** |

The scenarios module holds the Java workflow tests (provisioning, role assignment, modification,
deprovisioning and multi-system flows), a corpus of 20 YAML scenarios converted from them, and the
tests of the runners and their guards.

**Worth being explicit about:** the default suite *simulates* IdM. The Java and YAML scenarios
write directly to the embedded LDAP, H2 and WireMock and assert the result, so they exercise the
framework end to end but validate no real Identity Manager. The runners below are how the same
scenarios reach real systems.

### Running against containers or a real lab

| Runner | Runs | Needs |
|---|---|---|
| `YamlScenarioTest` | the corpus in `scenarios/`, embedded | nothing — part of the default suite |
| `ContainerScenarios` | the same corpus against OpenLDAP, PostgreSQL and WireMock | Docker; optional, not in the default suite |
| `RealEnvironmentScenarios` | your scenarios in `scenarios-real/` against a real lab | a profile and credentials for your environment |

```bash
./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false
./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentScenarios -Dsurefire.failIfNoSpecifiedTests=false
```

Both accept `-Dscenario=<glob>`. The real-environment runner checks only what the selected
scenarios use, and refuses to start, before any scenario runs, when it would test nothing real:
the embedded profile, an empty directory, or — for repositories the scenarios use — an unset or
placeholder LDAP/SOAP URL, REST target, database, or credentials. See
[scenarios-real/README.md](idm-test-scenarios/src/test/resources/scenarios-real/README.md).

### Next

| Ticket | Work |
|---|---|
| ITF-16 | Oracle-compatible connection test query for database pools |
| ITF-17 | Stop the runners starting embedded LDAP and WireMock as a side effect |
| ITF-18 | Check ContainerScenarios' "Docker is not available" message without Docker |

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

**Project introduction and architecture**:

- [Documentation index](docs/README.md) — reading paths for coworkers, test authors, and maintainers
- [Project overview](docs/overview.md) — purpose, users, and a simple scenario
- [Architecture guide](docs/architecture/README.md) — system context, modules, and execution diagrams
- [Capability baseline](docs/architecture/capabilities.md) — implemented capabilities, planned work, and verification evidence
- [Scenario authoring](docs/guides/scenario-authoring.md) — YAML and Java examples
- [Architecture decisions](docs/architecture/decisions/README.md) — design choices and their rationale
- Team presentation — [PowerPoint](docs/presentations/project-introduction.pptx), [PDF](docs/presentations/project-introduction.pdf), and [speaker notes](docs/presentations/project-introduction.md)

**Reference** — maintained:
- [Writing scenarios](docs/reference/writing-scenarios.md) — every step, parameter and rule of the YAML tier
- [Specification](docs/reference/specification.md) — architecture and design decisions
- [Configuration](docs/reference/configuration.md) — connectors, databases, endpoints
- [Running tests](docs/reference/running-tests.md) — test execution guide
- [CLAUDE.md](CLAUDE.md) — development guidelines

**Specs and plans** — design documents for in-flight work:
- [Declarative scenario engine](docs/specs/2026-09-13-declarative-scenario-engine-design.md)
- [Green test baseline](docs/specs/2026-09-11-suite-green-baseline-design.md)
- [docs/specs/](docs/specs/) — one design per ticket since ITF-12, e.g. [explicit cleanup](docs/specs/2026-09-24-explicit-cleanup-design.md), [http.request](docs/specs/2026-09-24-http-request-design.md), [HTTP authentication](docs/specs/2026-09-25-http-auth-design.md)
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
| Expressions | Mozilla Rhino | 1.8.0 |
| HTTP | JDK HttpClient via Spring RestClient; Spring Security OAuth2 client | — |
| Containers (optional runner) | Testcontainers | 1.21.4 |
| Mocking | WireMock | 3.10.0 |

No Lombok — Java records instead.
