# Running the tests

Everything below runs from the repository root with Java 21 and the Maven wrapper. The default
suite needs nothing else: no Docker, no external services.

## The default suite

```bash
./mvnw clean test
```

This starts an embedded UnboundID directory and a WireMock server on ephemeral ports, creates an
H2 schema, and runs every module's tests: 591 on 2026-09-25. Every test runs; none is skipped
for a missing service. A connector the context cannot build fails the context, so a broken
environment is red, never quietly green.

It includes `YamlScenarioTest`, which runs the 20 YAML scenarios in
`idm-test-scenarios/src/test/resources/scenarios/` against the embedded services and writes
`idm-test-scenarios/target/scenario-results.json`.

## Narrowing a run

A module depends on its siblings (the scenarios module needs core, utils, the engine and every
connector), and Maven must find their jars somewhere. `-am` ("also make") builds them in the same
run, so pair `-pl` with `-am` unless you have installed them (see
[Skipping the upstream build](#skipping-the-upstream-build)). With `-am`, add
`-Dsurefire.failIfNoSpecifiedTests=false` when naming a test, because the sibling modules have no
test by that name.

`-am` compiles the upstream modules but runs none of their tests when `-Dtest` names a class they
don't have, so a single-scenario run still reports `Tests run: 1`. Most of its time is the Spring
context the scenario starts, which any run pays.

```bash
# One module
./mvnw -pl idm-connector-rest -am test

# One test class, or one method
./mvnw -pl idm-test-scenarios -am test -Dtest=UserProvisioningWorkflowTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl idm-test-scenarios -am test -Dtest='UserProvisioningWorkflowTest#testUserProvisioningToLdap' -Dsurefire.failIfNoSpecifiedTests=false

# One YAML scenario, or a glob-selected group (quote patterns containing * or braces)
./mvnw -pl idm-test-scenarios -am test -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false -Dscenario=provisioning-user-to-ldap.yaml
./mvnw -pl idm-test-scenarios -am test -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false -Dscenario='provisioning-*'

# Debug logging
./mvnw test -Dlogging.level.com.idm.test=DEBUG
```

`-Dscenario` matches file names with or without `.yaml`. A blank value, an invalid glob, or a
pattern that matches nothing fails the run instead of running zero scenarios.

### Skipping the upstream build

When you are only editing scenario YAML or the scenarios module's own tests, install the other
modules once and then build the scenarios module alone:

```bash
./mvnw install -DskipTests
./mvnw -pl idm-test-scenarios test -Dtest=YamlScenarioTest -Dscenario=provisioning-user-to-ldap.yaml
```

Without `-am` only `idm-test-scenarios` is built, and `-Dsurefire.failIfNoSpecifiedTests=false`
is no longer needed.

The installed jars are a snapshot in `~/.m2`. After you change the engine, a connector, core or
utils, or pull such a change, run `./mvnw install -DskipTests` again. Otherwise the scenarios
module keeps testing the old code, and nothing tells you so. When in doubt, use `-am`.

## Against containers (optional, needs Docker)

```bash
./mvnw -pl idm-test-scenarios -am test -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false
```

`ContainerScenarios` runs the same 20 scenarios against OpenLDAP, PostgreSQL and WireMock started
by Testcontainers. It has no `Test` suffix, so the default suite never runs it, and a machine
without Docker is never asked for one. It accepts `-Dscenario` too.

## Against a real lab

```bash
IDM_TEST_PROFILE=dev ./mvnw -pl idm-test-scenarios -am test -Dtest=RealEnvironmentScenarios -Dsurefire.failIfNoSpecifiedTests=false
```

`RealEnvironmentScenarios` runs the scenarios you put in
`idm-test-scenarios/src/test/resources/scenarios-real/` against the environment your profile and
environment variables name. Before any scenario runs, it refuses:

- the embedded `test` profile
- an empty scenario directory
- for the LDAP and SOAP services the selected scenarios use: a URL that is unset or still the placeholder
- for the REST targets and databases they use: one that is unconfigured, or still the placeholder
- placeholder or half-given credentials for the REST targets and SOAP services they use

Nothing the selected scenarios don't use is checked or contacted.

[scenarios-real/README.md](../../idm-test-scenarios/src/test/resources/scenarios-real/README.md)
lists the variables to set and explains why profile values need the `IDM_TEST_*` form.

## Reading the results

- Surefire reports: each module's `target/surefire-reports/`.
- YAML results: `idm-test-scenarios/target/scenario-results.json` for the embedded run, or
  `idm-test-scenarios/target/real-environment-results.json` for container and real-lab runs. Each record has the scenario's status,
  duration, every step with its resolved parameters, retry attempts, and every `cleanup:` entry.
  Parameters are not redacted; keep secrets in target settings, not in scenario YAML.

To total the tests across modules:

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
```

## Troubleshooting

- **"Unresolved compilation problems" at run time:** an IDE wrote stale `.class` files into
  `target/`. Run `./mvnw clean test`.
- **Mockito/Byte Buddy agent attachment fails:** seen only in sandboxed environments; the same
  command outside the sandbox passes.
