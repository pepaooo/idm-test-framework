# Scenario helpers without the embedded test base — design

**Ticket:** ITF-17.

## 1. The problem

`RealEnvironmentScenarios` (ITF-12) and `ContainerScenarios` (ITF-14) reuse the scenario helpers
by calling `YamlScenarioTest`'s package-private statics: `registry`, `buildTests`,
`selectedScenarioFiles`, `select`, `scenarioFiles` and `SCENARIO_PROPERTY`.

`YamlScenarioTest extends BaseIntegrationTest`, whose static initialisers start the embedded
infrastructure: since ITF-34, two UnboundID LDAP servers (`default` and `second`) and a WireMock
server. The first call to any helper initialises `YamlScenarioTest`, then `BaseIntegrationTest`,
and so starts all three servers, even in a real-environment or container run that never uses
them.

**Why it's harmless today:** the servers bind ephemeral ports, their only configuration route is
`BaseIntegrationTest`'s `@DynamicPropertySource` (which doesn't apply to the runners), and the
initialisation happens after the Spring context is bound. No connector is ever pointed at them.

**Why fix it:**
- it wastes startup time on every real-environment and container run
- a broken embedded schema or LDIF would break a real run
- `RealEnvironmentScenariosStructureTest` can only claim "Source references no embedded
  server", because at run time the runner does start them

## 2. Decisions (jangulo, 2026-09-29)

1. **Move the helpers into a class that extends nothing,** as the ticket suggests.
2. **Prove it at run time in an isolated class loader:** the runners' helper path must not even
   load `BaseIntegrationTest`.

## 3. The move

- **`ScenarioSupport`** (new, `com.idm.test.scenarios.engine`, test sources): `final`, extends
  nothing, private constructor. It takes over, with bodies and javadoc unchanged:
  - `static final String SCENARIO_PROPERTY = "scenario"`
  - `registry(LdapDirectories, MultiDatabaseConnector, RoleServiceConnector,
    ResourceServiceConnector, IdmRestConnector, RestProperties, RestAuthenticator)`
  - both `buildTests(...)` overloads
  - `selectedScenarioFiles(String)`, `select(List<String>, String, String)`,
    `scenarioFiles(String)`
  - the private `resource(...)` and `stripYaml(...)`
- **Class-loader references** that used `YamlScenarioTest.class` become `ScenarioSupport.class`.
- **`YamlScenarioTest`** keeps only its `@TestFactory` (and the `SCENARIO_DIR` / `RESULTS`
  constants it uses), still extends `BaseIntegrationTest`, and calls `ScenarioSupport`.
- **Every other caller switches to `ScenarioSupport`:** `RealEnvironmentScenarios` (and through
  it `ContainerScenarios`), `ScenarioFilterTest`, `ScenarioReferenceTest`,
  `YamlScenarioValidationTest`, `MultiDirectoryScenarioTest`, `RealEnvironmentGuardsTest`, and
  any other `YamlScenarioTest.<helper>` reference found by search.
- **No behaviour changes:** the same selection rules, messages, results files and
  `-Dscenario` handling.

## 4. The runtime proof

`ScenarioSupportIsolationTest`, a plain JUnit test with no Spring context:

- **An isolated loader:** a `URLClassLoader` subclass over the test JVM's classpath (the
  entries of `java.class.path`, which include the manifest `Class-Path` of Surefire's booter jar
  when that is used), with the platform class loader as parent, so it shares no application
  class with the rest of the test run. The subclass exposes `findLoadedClass(String)`.
- **The runners' helper path, run by reflection inside that loader:**
  - initialise `RealEnvironmentScenarios` and `ContainerScenarios`
    (`Class.forName(name, true, loader)`)
  - call `ScenarioSupport.scenarioFiles("scenarios")` and
    `ScenarioSupport.select(files, null, "scenarios")`
  - call `ScenarioSupport.registry(null, null, null, null, null, <a RestProperties>,
    <RestAuthenticator.withoutOAuth()>)`, with the last two created in the same loader
- **The assertion:** the loader never loaded
  `com.idm.test.scenarios.base.BaseIntegrationTest` or
  `com.idm.test.scenarios.engine.YamlScenarioTest`. Not loaded implies not initialised, so none
  of the embedded servers could have started.
- **Positive control:** in a second isolated loader, initialising `YamlScenarioTest` does load
  `BaseIntegrationTest`. This proves the check can fail. The loader is closed afterwards, and
  the servers its initialisers start bind ephemeral ports and die with the JVM.
- **Falsification:** making `ScenarioSupport` reference `YamlScenarioTest` (for example
  `YamlScenarioTest.class.getClassLoader()` in `resource`) fails the test.

`RealEnvironmentScenariosStructureTest` keeps its source check ("Source references no embedded
server") and its javadoc points to `ScenarioSupportIsolationTest` for the run-time guarantee.

## 5. Documentation

- Wherever docs name `YamlScenarioTest`'s helpers, they name `ScenarioSupport`.
- The capability matrix's real-environment and container rows say these runners start no
  embedded server.

## 6. Testing

- `ScenarioSupportIsolationTest` (§4), with the falsification recorded.
- `ScenarioFilterTest`, `ScenarioReferenceTest`, `YamlScenarioValidationTest`,
  `RealEnvironmentGuardsTest` and the structure tests pass unchanged apart from the class name.
- The full suite, the corpus (0 FAILED), `multi-directory-results.json` (0 FAILED), and
  `ContainerScenarios` (20/20) if Docker is available.

## 7. Out of scope

- Boot's own embedded DataSource re-running `schema.sql`, and the H2 lines in real-environment
  logs (ITF-32).
- Changing what `BaseIntegrationTest` starts for the embedded runners.
