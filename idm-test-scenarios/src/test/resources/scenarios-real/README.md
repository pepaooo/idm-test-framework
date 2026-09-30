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
Open `target/real-environment-report.html` for a readable report of the run (see the authoring reference, section 9).

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

## Cleaning up

The engine never tears anything down on its own. Write teardown in a `cleanup:` block, which
runs after the steps whether they passed or not. Capture what a step created with `as:`, and
reference it in cleanup, so the entry is skipped if that step never ran:

```yaml
steps:
  - rest.request:
      target: idm
      method: POST
      path: /users
      body: { ... }
      as: created
cleanup:
  - rest.request:
      target: idm
      method: DELETE
      path: "/users/${created.body.id}"
```

A cleanup entry that fails turns the scenario red. A skipped one never does. Each entry's
outcome is in the JSON report.

## What the environment must provide

The runner refuses to start, before any scenario runs, when:

- the active profile is `test` (the embedded configuration)
- this directory contains no `.yaml`
- a scenario about to run uses a REST `target:` that is not configured, or whose `base-url` is
  blank or still its `application.yml` placeholder. Only targets the selected scenarios use are
  checked: a run with no `rest.*` steps is never asked for REST addresses.
- the credentials a scenario about to run needs are still `application.yml`'s placeholders, or
  only half given (the credentials check):
  - **for each REST target used:**
    - a `username`/`password` still equal to `idmadmin`/`idmpass`, or only one of them set
    - `use-oauth` with no `oauth-client-name`, or a registration with no `client-id`/`client-secret`
    - `client-id`/`client-secret` still at their placeholders, e.g. `sap-test-client`/`sap-test-secret`
  - **for SOAP, only when `roles.*` or `resources.*` are used:** the same checks on
    `idm.test.soap.username`/`password`

  An unauthenticated target (no credentials at all) passes. Messages name the variable to set,
  for example `IDM_TEST_REST_IDM_PASSWORD`, `IDM_TEST_SOAP_USERNAME` or
  `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_SAP_CLIENTSECRET`, and never the value.

- when your scenarios use `ldap.*`, the bind DN or password while still `application.yml`'s
  placeholders (`cn=admin,dc=company,dc=com` and `secret`). A blank or unset DN or password is
  allowed, for anonymous bind.
- a used database whose `jdbc-url` is in-memory: `jdbc:h2:mem:`, `jdbc:hsqldb:mem:`, or
  `jdbc:derby:memory:`. Nothing another system provisioned can exist there.

When your scenarios use `ldap.*`, a real directory genuinely reachable at `ldap://localhost:389`
is refused too, because that is the placeholder value — point it at `ldap://127.0.0.1:389`
instead (or any other address) to run against it.

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
  means **a `prod` run whose scenarios use `roles.*` is refused by the LDAP/SOAP URL check
  until you override it**, since the profile's own value is indistinguishable from nobody
  having named a target. A `prod` run whose scenarios don't use `roles.*` is not affected,
  since that check only looks at the SOAP URL when `roles.*` is used.

To point at a different host without editing `application.yml`, set the Spring property
itself as an environment variable, using its relaxed-binding form, for example
`IDM_TEST_LDAP_URL`, `IDM_TEST_SOAP_URL` or `IDM_TEST_REST_IDM_BASEURL`. OS environment
variables outrank every profile document, so these work under any profile, including `dev`
and `prod`.

The same applies to REST targets, for three of the four. `prod` hardcodes `idm`, `sap` and
`workday`'s REST `base-url` to exactly their placeholder values, so a `prod` run whose
scenarios use one of those targets is refused until that target's
`IDM_TEST_REST_<TARGET>_BASEURL` is set, for example `IDM_TEST_REST_IDM_BASEURL`. `prod`'s
`servicenow` base-url is `https://company.service-now.com/api`, which is not its placeholder
(`https://instance.service-now.com/api`), so a `servicenow` target is not refused.

Usernames and passwords still come from the `IDM_*`, `SAP_DB_*`, `HR_DB_*` and `WORKDAY_DB_*`
variables listed below, since neither `dev` nor `prod` overrides those keys.

The runner checks, and connects to, **only what the selected scenarios use**. A repository no
scenario touches can stay as `application.yml`'s placeholder, and it is never contacted. The one
exception is the embedded `test-db` (in-memory H2 by default): it is always opened at startup,
because the scenarios module's schema initializer recreates its schema there, so never point
`test-db` at a real database.

In a real run's log, lines such as `HikariPool-test-db … url=jdbc:h2:mem:testdb` and `Executing SQL script from class path resource [schema.sql]` come from that `test-db` exception (they appear when `org.springframework.jdbc` logs at DEBUG, as the `dev` profile does). They are expected and touch only that in-memory database. Spring Boot's own anonymous H2 database (`HikariPool-1`, `jdbc:h2:mem:<random-id>`) is no longer created (ITF-32).

| Your scenarios use | You must configure (and not leave as the placeholder) |
|---|---|
| `ldap.*` | For each directory the steps use (a step without `directory:` uses the default directory): the older block's `idm.test.ldap.url` (`IDM_TEST_LDAP_URL`), `user-dn` (`IDM_TEST_LDAP_USERDN`) and `password` (`IDM_TEST_LDAP_PASSWORD`) for the directory `default`, or `idm.test.directories.<name>.url` (`IDM_TEST_DIRECTORIES_<NAME>_URL`), `user-dn` and `password` for a named one; each refused while still the placeholder (`ldap://localhost:389`, `cn=admin,dc=company,dc=com`, `secret`); a blank bind DN or password is allowed (anonymous bind). A directory that isn't configured, or a step without `directory:` when there is no default directory, is refused before anything runs |
| `db.*` with `database: <name>` | `idm.test.databases.<name>.jdbc-url`, `username`, `password`, none left as the placeholder; an in-memory URL (`jdbc:h2:mem:`, `jdbc:hsqldb:mem:`, `jdbc:derby:memory:`) is refused, since nothing provisioned by another system can be in it |
| `roles.*` | `idm.test.soap.url` (`IDM_TEST_SOAP_URL`) and SOAP credentials |
| `resources.*` | `idm.test.soap.resource-url` (`IDM_TEST_SOAP_RESOURCEURL`) and SOAP credentials |
| `rest.*` / `http.request` with `target: <name>` | `idm.test.rest.<name>.base-url`, and credentials only if the target requires authentication |

Database placeholders refused: `sap` `jdbc:postgresql://localhost:5432/sap` (`sapuser`/`sappass`),
`oracle-hr` `jdbc:oracle:thin:@localhost:1521:HRDB` (`hruser`/`hrpass`), `workday`
`jdbc:postgresql://localhost:5432/workday` (`wdayuser`/`wdaypass`). A database really at that
exact localhost URL must be named another way (for example `127.0.0.1`).

Database pools open on first use: a used database that cannot be reached fails the first step
that touches it, not the whole run. A name written as `${...}` is only known at run time and is
not checked in advance.

Credentials come from the environment variables `application.yml` already references:
`IDM_LDAP_URL`, `IDM_LDAP_USER_DN`, `IDM_LDAP_PASSWORD`, `IDM_SOAP_URL`, `IDM_SOAP_USER`,
`IDM_SOAP_PASSWORD`, `IDM_REST_URL`, `IDM_REST_USER`, `IDM_REST_PASSWORD`, `SAP_DB_*`,
`HR_DB_*`, `WORKDAY_DB_*`, and the OAuth2 clients' `SAP_CLIENT_ID`/`SAP_CLIENT_SECRET`,
`WORKDAY_CLIENT_ID`/`WORKDAY_CLIENT_SECRET` and `SERVICENOW_CLIENT_ID`/`SERVICENOW_CLIENT_SECRET`
(with `*_TOKEN_URI` for each token endpoint). The relaxed-binding names in the credentials
check's messages work too, under any profile.

The `sap`, `workday` and `servicenow` REST targets authenticate with OAuth2 client credentials;
`idm` with basic authentication. A target you add with only a `base-url` is unauthenticated.

Scenarios name their database by its key: `sap`, `oracle-hr` or `workday`. `test-db` is the
embedded H2.

A SOAP service or any other API called with `http.request` is a target too: configure its base
URL as `idm.test.rest.<name>.base-url`, or with `IDM_TEST_REST_<NAME>_BASEURL`. The REST-target
check treats it like any other REST target.

### TLS certificates for LDAPS and HTTPS

A real lab usually uses internal certificates. The connectors (LDAP over `ldaps://`, REST,
`http.request`, OAuth2 token requests and SOAP over `https://`) all trust whatever the **test
JVM's truststore** trusts. The framework has no TLS settings of its own. Put the CA certificate
that signed your servers into a truststore file and pass it on the command line:

```bash
./mvnw -pl idm-test-scenarios -am test -Dtest=RealEnvironmentScenarios -Dsurefire.failIfNoSpecifiedTests=false -Djavax.net.ssl.trustStore=/path/to/idm-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12
```

That file replaces the JDK's default truststore, so it must also hold the CAs of any public
endpoints you call. The hostname in each URL must match the server's certificate. See
[configuration: TLS certificates](../../../../../docs/reference/configuration.md#tls-certificates-ldaps-https)
for creating the truststore, hostname checks, mutual TLS and diagnosing handshake failures.

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
