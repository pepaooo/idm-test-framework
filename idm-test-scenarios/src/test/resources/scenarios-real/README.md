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
