# No Spring Boot DataSource in the scenario contexts — design

**Ticket:** ITF-32.

## 1. The problem

H2 is on the scenarios module's test classpath and no `spring.datasource.url` is set, so Spring
Boot's `DataSourceAutoConfiguration` creates its own embedded H2 database with a random name in
every scenarios context: the embedded suite, `ContainerScenarios` and real-environment runs.

- **`schema.sql` runs against it.** The `test` profile sets
  `spring.sql.init.mode: always` with `schema-locations: classpath:schema.sql`; outside `test`,
  Boot's default mode `embedded` does the same because the database is embedded H2. Either way
  Boot runs the script (DROP/CREATE/INSERT) against that throwaway database, never against the
  framework's `test-db`.
- **Boot also builds a `JdbcTemplate` bean** (and a transaction manager) on that DataSource.
- **The framework uses none of it.** `DatabaseConfiguration` builds its own named pools inside
  the `Map<String, JdbcTemplate>` bean, and `TestSchemaInitializer` creates the schema in
  `test-db` explicitly. `TestSchemaInitializer`'s javadoc says `schema.sql` "was configured but
  never ran"; in fact it ran against Boot's own database.

A real-environment run on 2026-09-27 logged
`HikariPool-1 - Added connection conn1: url=jdbc:h2:mem:<uuid>` and
`Executing SQL script from file [...schema.sql]`, which looks as if a database is being
modified. It wastes startup time and misleads readers of real-run logs.

## 2. Decision (jangulo, 2026-09-29)

**Exclude `DataSourceAutoConfiguration` on the two Spring test roots, next to the existing LDAP
exclusion.** `BaseIntegrationTest` and `RealEnvironmentScenarios` already carry
`@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class})`; `ContainerScenarios`
inherits `RealEnvironmentScenarios`'. The exclusion is visible in code, and no profile or
environment variable can undo it.

## 3. The change

- **Both annotations become**
  `@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class, DataSourceAutoConfiguration.class})`
  on `BaseIntegrationTest` and `RealEnvironmentScenarios`.
- **With no DataSource bean,** Boot's `JdbcTemplateAutoConfiguration`,
  `DataSourceTransactionManagerAutoConfiguration` and `SqlInitializationAutoConfiguration`'s
  DataSource initializer back off on their own (they are conditional on a DataSource). No
  further excludes are added; the tests in §4 prove they back off.
- **`application.yml`:** delete the `test` profile's `spring.sql.init` block (`mode: always`,
  `schema-locations: classpath:schema.sql`, `continue-on-error: false`) and its comment. It only
  ever fed Boot's own database.
- **`TestSchemaInitializer` is unchanged in behaviour.** Its javadoc is corrected: Boot's
  `spring.sql.init` ran `schema.sql` against Boot's own unused embedded H2, never against
  `test-db`; Boot's DataSource is now excluded (ITF-32), so this class is the only thing that
  runs `schema.sql`.
- **The `RealEnvironmentScenarios` javadoc** that explains `test-db` (always opened, schema
  recreated there) stays accurate and is unchanged apart from anything that mentions Boot's
  DataSource.

## 4. Testing

- **The embedded context** (a new test extending `BaseIntegrationTest`):
  - the context has no bean of type `javax.sql.DataSource`
  - no bean of type `JdbcTemplate` (only the framework's `Map<String, JdbcTemplate>` bean)
  - no bean of type `DataSourceScriptDatabaseInitializer` (Boot's SQL-init initializer) and no
    `PlatformTransactionManager`
  - `test-db` still has the schema: a query against a table `schema.sql` creates succeeds
- **The real and container runners** (structure tests):
  - `RealEnvironmentScenarios`' merged `@EnableAutoConfiguration` excludes include both
    `LdapAutoConfiguration` and `DataSourceAutoConfiguration`, and `ContainerScenarios` sees
    the same through inheritance
  - `RealEnvironmentLdapOnlyTest`, which starts the real runner's context in the default build,
    also asserts the context has no `DataSource` bean
- **Falsification:** removing `DataSourceAutoConfiguration` from `BaseIntegrationTest`'s
  exclusion fails the embedded context test; removing it from `RealEnvironmentScenarios` fails
  the structure test and the `RealEnvironmentLdapOnlyTest` assertion.
- **Also:** the full suite, the corpus (0 FAILED), `multi-directory-results.json` (0 FAILED), and
  `ContainerScenarios` (20/20) if Docker is available.

## 5. Documentation

- **The capability matrix:** the "Embedded scenario environment" row says Boot's own DataSource
  is excluded and `TestSchemaInitializer` alone creates the `test-db` schema; the
  real-environment row says no anonymous H2 database is created.
- **Any doc that says `schema.sql` initialises the databases through `spring.sql.init`** is
  corrected (excluding `docs/history/`, `docs/specs/` and `docs/plans/`, which record the past).

## 6. Out of scope

- The `test-db` exception itself (always opened at startup, its schema recreated): unchanged,
  documented in ITF-30.
- Other modules' Spring tests, which use `@SpringBootTest(classes = …)` without
  auto-configuration.
