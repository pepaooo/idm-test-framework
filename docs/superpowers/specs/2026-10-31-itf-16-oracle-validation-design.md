# ITF-16: Oracle connection validation

## Intent

Reproduce the hardcoded `SELECT 1` connection validation failure against Oracle
21c XE and verify the fix with a repeatable integration test. Database pools are
already lazy; the failure to demonstrate is on first use, not Spring startup.

## Goals and scope

- Exercise `DatabaseConfiguration` and the project's Oracle JDBC driver against
  a real Oracle 21c XE container.
- Remove the hardcoded connection test query so Hikari uses JDBC
  `Connection.isValid()` for every database.
- Preserve lazy pool creation, pool cleanup, and existing connection settings.
- Keep the default Maven test suite independent of Docker.
- Do not add per-database validation SQL settings or change scenario runners.
- Do not treat H2 or PostgreSQL results as evidence of Oracle compatibility.

## Approaches

1. **Repeatable Oracle integration test (selected by the user).** Add an explicitly
   invoked container test in `idm-connector-database`. This adds test infrastructure
   but retains the ability to verify Oracle compatibility later.
2. **One-time container reproduction.** Smaller infrastructure change, but future
   verification would require repeating a manual procedure. Not selected.

## Proposed implementation

Use the existing Maven/JUnit conventions and add Testcontainers as a test
dependency in the database connector module. Pin an Oracle 21c XE image version,
such as `gvenzl/oracle-xe:21.3.0-slim`, rather than using `latest`.

Name the integration test `OracleConnectionValidationIT` so the current Surefire
includes do not run it by default. Document an explicit invocation:

```sh
./mvnw test -pl idm-connector-database -am -Dtest=OracleConnectionValidationIT -Dsurefire.failIfNoSpecifiedTests=false
```

The test owns its container and closes database pools before stopping the
container, including on failure. Use disposable test credentials, a dynamically
mapped port, readiness detection, and a bounded startup timeout. An explicit run
must fail clearly if Docker or Oracle cannot start; it must not silently skip.

Connect through the production configuration and execute `SELECT 1 FROM DUAL`.
Before the fix this must fail because the pool runs its configured `SELECT 1`;
capture the actual Oracle error to distinguish it from startup or connectivity
failures. After removing `setConnectionTestQuery("SELECT 1")`, the same test must
pass. Add a default-suite regression check that the production pool leaves the
validation query unset, and retain existing H2 and lazy-connection tests.

## Constraints and verification

The available Docker daemon reports `aarch64`. Oracle 21c XE requires an x86
environment. Execute the Oracle test on a compatible x86 Docker daemon or a
verified compatible VM; selecting or provisioning that environment remains open.
Do not substitute another Oracle release or claim verification from an
architecture or container startup failure. If no compatible environment is
available, report the real Oracle before/after run as outstanding.

Verify the connector module and its reactor dependencies with the default tests,
then record the explicit Oracle before/after results and reproduction command.

## Builder decisions

The builder chooses the Testcontainers API compatible with the project's managed
dependencies, the precise readiness check and timeout, and test fixture details.
The image pin, test name, and JDBC validation choice above are assistant proposals
for approval. No product code has been changed at design time.
