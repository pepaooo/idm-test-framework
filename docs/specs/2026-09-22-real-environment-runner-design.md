# Running scenarios against a real environment — design

**Ticket:** ITF-12.

## 1. The problem

`BaseIntegrationTest` carries this comment:

> Point the connectors at the embedded infrastructure. Everything else comes from
> src/test/resources/application.yml, so switching to a real directory in phase (b) is a
> **profile change rather than a code change**.

That is not true. **Three** separate mechanisms pin every test in the module to the embedded
infrastructure, and a profile change defeats none of them:

1. **`@ActiveProfiles("test")`** is hardcoded on the class, so `IDM_TEST_PROFILE=dev` never
   reaches Spring at all.
2. **`@DynamicPropertySource`** then overrides `idm.test.ldap.url`, `base-dn`, `user-dn`,
   `password`, `soap.url` and `soap.resource-url` with the embedded servers' values.
   `@DynamicPropertySource` has the highest precedence in Spring's environment — it beats
   profile YAML, environment variables and system properties.
3. **The embedded LDAP and WireMock servers are `static final` fields** initialised in a static
   initialiser, so they start at class load, before any Spring machinery runs and before any
   profile is known.

The consequence is the dangerous kind: setting `IDM_TEST_PROFILE=dev` today runs green against
the embedded directory while appearing to test the real hosts the `dev` profile names.

## 2. What this delivers, and what it does not

**Goal:** the team can run YAML scenarios against a real OpenText IdM / eDirectory.

Explicitly **not** a CLI. A consultant running their own scenarios against their own IdM is a
different user with a different need — argument parsing, connector wiring outside Spring Boot's
test context, exit codes, console output. `ScenarioEngine` was built to allow it (its javadoc
says "that is what lets a CLI reuse it"), but it is not this ticket.

**Nothing existing changes.** No file is moved, renamed, restructured or deleted.
`BaseIntegrationTest` is not touched. The seven classes that extend it are not touched. The 18
converted YAML scenarios keep running against the embedded infrastructure. CI is unaffected.

## 3. The shape: one new class, no refactor

```java
package com.idm.test.scenarios.engine;

@SpringBootTest
@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class})
public class RealEnvironmentScenarios {

    @Autowired private EdirectoryConnector edirectoryConnector;
    // ...and the other eight connector/utility beans

    @TestFactory
    Stream<DynamicTest> scenarios() { ... }
}
```

It extends nothing. It declares **no** `@ActiveProfiles`, **no** `@DynamicPropertySource`, and
starts no embedded server, so every `idm.test.*` property resolves from `application.yml` and
the active profile — which is the whole point.

It lives in `com.idm.test.scenarios.engine`, beside `YamlScenarioTest`, so it can call that
class's existing package-private statics `registry(...)` and `buildTests(...)` directly. Those
already exist and are already reused by `YamlScenarioValidationTest`.

**An earlier draft of this design proposed extracting a shared parent class** —
`AbstractScenarioSupport` — hoisting roughly 230 of `BaseIntegrationTest`'s 363 lines so both
the embedded tests and this runner could inherit them. **That was wrong, and measuring killed
it.** `YamlScenarioTest` references only the nine `@Autowired` fields. It uses none of the
tracking lists (`createdLdapDns`, `databaseCleanupTasks`, `assignedRoles`), none of
`trackLdapDn` / `trackDatabaseRecord` / `trackRoleAssignment`, none of the three cleanup
methods, none of `assertDataPresent`, and neither `setupTest` nor `cleanupTest`.

That machinery exists for the **Java** workflow tests, which track by hand what they create.
The YAML engine does its own cleanup through the undos each action step registers. So the
genuinely shared surface between the two is nine field declarations — well below the cost of an
abstraction, and coupling them would bind two things that change for opposite reasons: the Java
tests will keep wanting tracking helpers, this runner will never want them.

The only repetition is those nine `@Autowired` lines and an eleven-line engine construction
block. If the latter is worth removing, it becomes one more package-private static in
`YamlScenarioTest` alongside the two that are already there — not a restructuring of
`BaseIntegrationTest`.

## 4. Not running by default

`RealEnvironmentScenarios` is named **without a `Test` suffix**. Surefire is configured in the
parent POM with `<includes>` of `**/*Test.java` and `**/*Tests.java` only, so the default build
does not see it. No Maven profile is needed to exclude it.

Run it explicitly:

```bash
IDM_TEST_PROFILE=dev ./mvnw test -pl idm-test-scenarios -am \
  -Dtest=RealEnvironmentScenarios -Dsurefire.failIfNoSpecifiedTests=false
```

**Why not `@EnabledIfSystemProperty`.** It would leave a permanently skipped test in every CI
run. Green in this project was defined as *every test actually runs* — zero skips — and that
definition has held since the phase (a) baseline. A disabled-by-default test breaks it.

## 5. Failing loudly instead of silently

The risk is not a connection error; that is visible. The risk is connecting to the **embedded**
infrastructure, or to nothing at all, and reporting green. That is the same family as the eight
vacuous-assertion defects this project has found and fixed.

Three guards, all before any scenario runs:

1. **Refuse the embedded profile.** If the resolved active profile is `test`, fail naming it.
   Running "real environment" scenarios against the embedded configuration is never intended.
2. **Require the target explicitly.** Fail if `idm.test.ldap.url` or `idm.test.soap.url` is
   blank **or still equals the placeholder `application.yml` falls back to** —
   `ldap://localhost:389` and `https://idm.company.com:8543/IDMProv/role/service`.

   *Amended while planning.* This guard originally said "unset". But `application.yml` writes
   both properties as `${IDM_LDAP_URL:ldap://localhost:389}` and
   `${IDM_SOAP_URL:https://idm.company.com:...}`, so they always resolve to *something* and
   an "unset" check could never fire — a vacuous guard, the defect class it exists to
   prevent. Comparing against the fallbacks is what actually catches "nobody named a target".
   A test pins the two constants to the text of `application.yml`, so if the fallbacks change
   the guard fails its test rather than silently going stale.
3. **Refuse an empty scenario directory.** If no `.yaml` is found, fail. A run that discovers
   nothing, executes nothing and reports success is precisely the failure mode this codebase
   treats as its worst — and since the directory ships empty (§7), it is the most likely thing
   to happen on someone's first attempt.

4. **Require every REST target the run uses to be named** *(added by ITF-15; see
   `docs/specs/2026-09-23-rest-target-guard-design.md`)*. For the `target:` of each `rest.*`
   step in the selected scenarios, fail if the target is not configured, or its `base-url` is
   blank or still its `application.yml` placeholder. A run with no REST steps makes no REST
   demand.

Each guard's message names what was wrong and what to set, because the person reading it is
configuring an environment, not debugging Java.

## 6. Verification, honestly scoped

**We have no real eDirectory, so this design cannot prove it reaches one.** What it can prove,
in CI, without any real environment:

- **A structural test** — in the spirit of the existing `NoSpringDependencyTest`, which already
  walks source to enforce a rule — asserting `RealEnvironmentScenarios` declares no
  `@ActiveProfiles` and no `@DynamicPropertySource`, and references no embedded server type.
  This is what stops a later change from helpfully wiring the embedded servers back in and
  restoring the exact bug this ticket exists to fix.
- **Each of the three guards fires**, with its own test.

Because nothing existing is modified, there is no regression surface to re-verify beyond the
suite continuing to pass unchanged.

The remaining gap — that nothing exercises the configuration-driven path against a genuine
network service — is closed separately by ITF-14, not here.

## 7. The scenario directory

`RealEnvironmentScenarios` reads from `idm-test-scenarios/src/test/resources/scenarios-real/`,
**not** the `scenarios/` directory the embedded corpus uses.

It ships containing only a `README.md` explaining what belongs there. It is deliberately empty
of scenarios, because the 18 converted ones write **directly** to LDAP and the database — they
simulate IdM rather than driving it. Pointed at a real directory they would create entries IdM
does not know about, which is worse than useless.

The first real scenario gets written when there is an environment to point at. It will look
different from the corpus: driving `rest.request` against IdM and then verifying what IdM
provisioned downstream — which is the part of the twelve-step vocabulary the corpus never
exercises (`rest.*` and `resources.*` are untouched by all 18; see
`docs/reference/scenario-conversion-findings.md`).

Guard 3 in §5 exists precisely because this directory ships empty.

## 8. Out of scope

- **A CLI** (§2).
- **Any change to `BaseIntegrationTest` or its subclasses** (§3).
- **Testcontainers** — ITF-14. Dropped in phase (a) for a version clash, not on merit:
  `Status 400: client version 1.32 is too old. Minimum supported API version is 1.40`, recorded
  in `docs/specs/2026-09-11-suite-green-baseline-design.md`. The BOM is still in the parent POM
  at 1.20.4 and `idm-connector-database` still declares `testcontainers:postgresql`, though no
  source uses it. Upgrading past the docker-java clash would let a containerised OpenLDAP and
  PostgreSQL exercise this runner against genuine network services. It would **not** provide
  OpenText IdM, which has no public image, so the SOAP role service and IdM REST API remain
  unexercised either way.
- **Removing the dead `testcontainers:postgresql` dependency.** Left in place pending ITF-14,
  since that is the work which would use it.
- **Writing a real scenario** (§7).
- **TLS trust configuration** for the `prod` profile's `ldaps://` endpoint. Not covered anywhere
  today; surfaces when someone first targets `prod`.

## 9. Configuration a real run needs

Already present in `idm-test-scenarios/src/test/resources/application.yml`, which defines
`default`, `dev`, `test` and `prod` profiles with real hostnames, and both JDBC drivers
(`postgresql`, `ojdbc11`) are on the classpath. What the operator must supply:

- Credentials through the environment variables the YAML already references: `IDM_LDAP_URL`,
  `IDM_SOAP_USER` / `IDM_SOAP_PASSWORD`, `SAP_DB_USER` / `SAP_DB_PASS`, `HR_DB_*`.
- **`idm.test.soap.resource-url` must be set.** `ResourceServiceConnector` is
  `@ConditionalOnProperty` on it, so omitting it removes the bean and `resources.grant` /
  `resources.revoke` have no connector — surfacing as a context startup failure rather than a
  clear message. `RoleServiceConnector` has carried the same pattern since before this work, so
  it is consistent rather than a regression, but both must be set.
- **Every configured database must be reachable, not only the ones a scenario uses.**
  *Found while planning.* `DatabaseConfiguration` builds each pool with
  `new HikariDataSource(config)`, which opens a connection immediately and throws if it
  cannot. So `sap`, `oracle-hr` and `workday` must all accept a connection or the Spring
  context fails to start, before any guard or scenario runs. That failure is loud, so it is
  safe, but it is a real barrier for a team that only has a directory to point at. Changing it
  means touching a connector and is out of scope here. The `scenarios-real/README.md`
  documents it.
- Scenarios name their database. The corpus says `database: test-db` (H2); real scenarios would
  say `sap`, `oracle-hr` or `workday`.
