# Real-environment scenarios

YAML scenarios in this directory run against a **real** OpenText IdM / eDirectory, not the
embedded LDAP, WireMock and H2 the rest of the suite uses. They are run by
`RealEnvironmentScenarios`, which the default build never runs.

## Running

```bash
IDM_TEST_PROFILE=dev ./mvnw test -pl idm-test-scenarios -am \
  -Dtest=RealEnvironmentScenarios -Dsurefire.failIfNoSpecifiedTests=false
```

Results are written to `idm-test-scenarios/target/real-environment-results.json`.

To run one scenario or a group, add `-Dscenario=` with a file name or a glob, for example
`-Dscenario=create-user.yaml` or `-Dscenario='onboarding-*'`. Quote any pattern containing `*`
or braces. The `.yaml` extension is optional. A pattern that matches nothing fails and lists the
files that are available.

## What belongs here

Scenarios that **drive IdM** and then verify what IdM provisioned downstream. For example, a
`rest.request` against the IdM REST API, followed by `ldap.verify` and `db.verify` with
`wait:` against the connected systems.

**Do not copy the scenarios from `../scenarios/` here.** Those write straight to LDAP and the
database to simulate IdM. Pointed at a real directory, they would create entries IdM does not
know about.

This directory ships empty on purpose. Running with no `.yaml` here fails with a message
pointing back to this file, because a run that finds nothing and reports success would be
worse than no run.

## What the environment must provide

The runner refuses to start, before any scenario runs, when:

- the active profile is `test` (the embedded configuration)
- `idm.test.ldap.url` or `idm.test.soap.url` is blank or still the `application.yml` placeholder
  (`ldap://localhost:389`, `https://idm.company.com:8543/IDMProv/role/service`)
- this directory contains no `.yaml`

A real directory genuinely reachable at `ldap://localhost:389` is refused too, because that is
the placeholder value — point it at `ldap://127.0.0.1:389` instead (or any other address) to
run against it.

### Profiles override the `IDM_*` variables — use `IDM_TEST_*` instead

`application.yml` has three documents: the default one (where `idm.test.ldap.url`,
`base-dn`, `user-dn`, `soap.url` and the REST/JDBC URLs are `${IDM_LDAP_URL:...}`-style
placeholders), and `dev`/`prod` documents that load after it and hardcode those same keys.
A later profile document overrides the default one, so under `IDM_TEST_PROFILE=dev` or
`IDM_TEST_PROFILE=prod`, setting `IDM_LDAP_URL` or `IDM_SOAP_URL` has no effect:

- Under `dev`, `idm.test.ldap.url`, `base-dn`, `user-dn`, `soap.url`, the `sap`/`oracle-hr`/
  `workday` JDBC URLs, and the `idm`/`sap`/`workday` REST base URLs are all hardcoded in the
  `dev` block.
- Under `prod`, the same keys are hardcoded in the `prod` block — and `prod`'s `soap.url` is
  exactly `https://idm.company.com:8543/IDMProv/role/service`, the placeholder value. That
  means **`prod` is refused by guard 2 until you override it**, since the profile's own value
  is indistinguishable from nobody having named a target.

To point at a different host without editing `application.yml`, set the Spring property
itself as an environment variable, using its relaxed-binding form, for example
`IDM_TEST_LDAP_URL`, `IDM_TEST_SOAP_URL` or `IDM_TEST_REST_IDM_BASEURL`. OS environment
variables outrank every profile document, so these work under any profile, including `dev`
and `prod`.

Usernames and passwords still come from the `IDM_*`, `SAP_DB_*`, `HR_DB_*` and `WORKDAY_DB_*`
variables listed below, since neither `dev` nor `prod` overrides those keys.

The Spring context will not start unless the following are also true:

- **Every database configured under `idm.test.databases` accepts a connection.** That means
  `sap`, `oracle-hr` and `workday`, not only the ones your scenarios use. Each pool connects
  when it is created (`new HikariDataSource(config)` in `DatabaseConfiguration`).
- **`idm.test.soap.resource-url` is set** (for example with the environment variable
  `IDM_TEST_SOAP_RESOURCEURL`). Without it, the `ResourceServiceConnector` bean is not created
  and the context fails.

Credentials come from the environment variables `application.yml` already references:
`IDM_LDAP_URL`, `IDM_LDAP_USER_DN`, `IDM_LDAP_PASSWORD`, `IDM_SOAP_URL`, `IDM_SOAP_USER`,
`IDM_SOAP_PASSWORD`, `IDM_REST_URL`, `IDM_REST_USER`, `IDM_REST_PASSWORD`, `SAP_DB_*`,
`HR_DB_*` and `WORKDAY_DB_*`.

Scenarios name their database by its key: `sap`, `oracle-hr` or `workday`. `test-db` is the
embedded H2.

## Running against containers

`ContainerScenarios` runs the embedded corpus (`../scenarios/`) through this same runner,
against real OpenLDAP, PostgreSQL and WireMock containers that Testcontainers starts and stops.
It needs a running Docker daemon. The default build never runs it.

Its cleanup restores the system properties it set, so those values do not leak into another
class in the same JVM. That says nothing about the Spring context: `ContainerScenarios` shares
`RealEnvironmentScenarios`' cached-context key, so it carries `@DirtiesContext(BEFORE_CLASS)` to
evict any context a prior `RealEnvironmentScenarios` run left cached, and it checks that the
context it is using actually bound the LDAP URL these containers just published before running
any scenario.

```bash
./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false
```

It proves this runner's configuration path reaches genuine LDAP and SQL servers. It proves
nothing about OpenText IdM: the SOAP services are WireMock stubs.

## Running against a lab

A team with its own eDirectory / IdM environment (containers, VMs or a shared lab) points
this runner at it with the `IDM_TEST_*` variables described above. No code change is needed.
