# Capability baseline

Reviewed on **2026-09-15** against Git revision `104e388a555f9e22d0c415290ff2edc606402a59`.
The checkout initially contained only the untracked documentation plan. This review changes documentation, not framework behavior.

**Implemented** means supported by inspected source; execution evidence is recorded separately below. **Approved target** means described by the approved engine design but absent from the current adapter. **Future** means deferred and not delivered. These labels do not claim readiness for a real IdM deployment.

## Capabilities

| Capability | Implementation status | Approved intent | Source evidence | Limitation |
|---|---|---|---|---|
| Java scenario authoring | Implemented | Keep explicit Java workflows alongside YAML | [Java scenarios][scenarios], [test base][base] | Embedded examples simulate provisioning; they do not run a real IdM engine |
| YAML discovery and execution | Implemented | Consultants author data files | [JUnit adapter][adapter] | Discovers sorted `.yaml` files directly in the classpath directory; no standalone CLI or packaged-JAR discovery guarantee |
| YAML action `ldap.create` | Implemented and registered | Create LDAP users | [Create step][create], [adapter][adapter] | DN is relative to configured base; directory schema still constrains attributes |
| YAML assertion `ldap.verify` | Implemented and registered | Assert directory attributes with optional polling | [Verify step][verify] | Supply meaningful nonempty expectations; presence checks do not constitute full nested schema validation |
| YAML `set` | Implemented engine keyword | Bind derived variables | [Engine][engine], [validator][validator] | Bindings are processed in declaration order; duplicate/shadowing names are rejected by validation |
| YAML `as` | Implemented engine parameter | Capture successful step output | [Engine][engine], [steps][create] | Create captures a DN string; verify captures a selected attribute view after a follow-up read. Requested null captures fail |
| Fixtures | Implemented | Generate or supply scenario data | [Fixture factory][fixtures] | Only `random-user` is registered; `values` are literal overrides, not evaluated expressions |
| `${path}` interpolation | Implemented | Resolve fixture/variable properties | [Interpolator][interpolator] | Traverses maps by dot-separated keys and substitutes strings; it is not JavaScript or typed object capture |
| Whole-value `js:{ ... }` expressions | Implemented | Compute values without connector or host access | [Evaluator][evaluator], [conversion][conversion], [expression tests][expression-tests] | Plain Rhino ES6, no helper object; result must convert to plain values. Regex literals containing structural characters can confuse the outer expression parser |
| Expression restrictions | Implemented controls and tests | Deny Java classes and bound execution | [Evaluator][evaluator], [conversion][conversion] | Default instruction threshold 100,000; conversion depth 100, array length/node budgets 1,000,000. These are not a process isolation or hard wall-clock/memory guarantee |
| Up-front validation | Implemented | Find mistakes before long-running steps | [Validator][validator], [adapter validation tests][adapter-tests] | Compiles expressions without evaluating them; arbitrary JS variable existence and nested parameter types are not fully checked at load |
| Assertion waiting | Implemented | Retry assertions until success or timeout | [Engine][engine], [WaitSpec][wait], [TestExecutor][executor] | Parameters resolve once before polling; actions cannot declare `wait`. Duration parsing does not enforce positive values |
| First-failure behavior | Implemented | Stop later steps and return diagnostics | [Engine][engine], [engine tests][engine-tests] | Handles runtime exceptions on normal execution paths; does not promise recovery from JVM termination or all `Error` conditions |
| YAML cleanup | Implemented | Run an author-written top-level `cleanup:` block after `steps:`, pass or fail | [Engine][engine], [ITF-19 design][cleanup-design] | No step registers undo; cleanup is explicit YAML. Each entry is SUCCEEDED, FAILED or SKIPPED (unbound capture); a FAILED entry fails the scenario and is reported in structured results |
| Java cleanup | Partially implemented | Test base tracks created resources | [Test base][base] | LDAP/database cleanup exists; role cleanup currently logs intent rather than revoking roles. It is not the YAML undo stack |
| Structured results | Implemented | Separate execution from rendering | [Result model][results], [JSON writer][writer], [adapter][adapter] | JSON includes resolved parameters without redaction; retries expose attempts/time and message rather than a separate last-observation field |
| JUnit integration | Implemented | IDE/Maven execution and test reports | [Adapter][adapter], [parent POM][pom] | One dynamic test per filename; adapter writes accumulated JSON after each result. Result-file I/O failure can still fail the test |
| LDAP Java connector | Implemented | LDAP CRUD and verification | [EdirectoryConnector][ldap] | Tested substrate is embedded UnboundID with selected eDirectory-like schema additions, not complete vendor equivalence |
| JDBC Java connector | Implemented | Named databases, raw SQL, verification | [MultiDatabaseConnector][db], [DB configuration][db-config] | Embedded H2 tests do not establish Oracle/PostgreSQL production compatibility |
| REST Java connector | Implemented | Spring client with thin operations and IdM helpers | [IdmRestConnector][rest], [REST client configuration][rest-config] | Located in `idm-connector-rest`; generic client has fixed 10s/30s timeouts. Endpoint/auth properties are not automatically applied by this client configuration |
| SOAP roles | Implemented | WSDL-based role assignment/removal | [Role connector][roles] | Uses generated JAX-WS stubs, not WebServiceTemplate; constructor applies endpoint only, not declared credential/timeout properties |
| SOAP resources | Implemented Java connector | Resource grants/revokes | [Resource connector][resources], [resource tests][resource-tests] | Conditional on `idm.test.soap.resource-url`; no corresponding YAML step or default resource endpoint |
| Dedicated `idm-connector-idm` module | Placeholder | Potential IdM-specific composition | [Module POM][idm-pom] | No main Java sources; existing IdmRestConnector belongs to the REST module |
| Additional YAML steps | Approved target | `ldap.update`, `ldap.delete`, `db.execute`, `db.verify`, `roles.grant`, `roles.revoke`, `resources.grant`, `resources.revoke`, `rest.request`, `rest.verify` | [Design §7.3][design], [current registry][adapter] | Java connector availability does not make a YAML step available |
| Convert existing Java scenarios to YAML | Approved target | Migrate suitable workflows while retaining Java tier | [Design §8.3][design], [scenario sources][scenarios] | Existing Java workflows remain; only the current demonstration YAML is supplied |
| Spring OAuth2 integration | Design intent / dependency present | Use Spring-managed token handling | [REST POM][rest-pom], [configuration][config], [REST client configuration][rest-config] | Client registrations/dependencies exist, but no OAuth interceptor/authorized-client wiring is installed on the provided RestClient |
| Configuration profiles | Implemented templates | Environment-specific settings | [application.yml][config] | `dev`, `test`, and `prod` documents exist. They contain example endpoints, not verified deployed environments |
| Embedded scenario environment | Implemented | Run without Docker or vendor systems | [Test base][base], [schema initializer][schema] | Test base forces `test` profile and dynamically overrides LDAP/role SOAP endpoints; schema initializer targets `test-db` |
| Real IdM execution | Not validated in this baseline | Exercise actual provisioning and target propagation | [Original design flow][legacy], [test base][base] | Requires separate test wiring, auth/endpoint work and real workflow validation; a profile switch alone is insufficient |
| CLI, frontend, HTML report | Future / deferred | Reuse engine and structured results | [Design non-goals][design] | No shipped entry point or renderer for these surfaces |
| Parallel scenarios and scenario includes | Future / deferred | Explicitly outside current increment | [Design non-goals][design] | Do not infer support from JUnit capabilities |

## Reconciled differences

1. **Orchestration:** the original Java-only principle is scoped to Java tests. YAML scenarios explicitly list their execution order; expressions compute values and steps invoke connectors. This amendment is recorded in design §10.
2. **Expressions:** README's ITF-7 future-work wording is stale. Expressions, `set`, and `as` are present, including in the demonstration scenario. The later implementation plan deliberately removes the helper object proposed in the design.
3. **Runtime independence:** engine main sources do not import Spring; the module still depends on Spring-using core/connector libraries transitively. [The architecture test][independence] checks imports, not a Spring-free dependency graph.
4. **SOAP technology:** current connectors use generated Jakarta/JAX-WS ports. Original WebServiceTemplate examples describe an earlier design.
5. **REST location and authentication:** the REST connector is implemented in `idm-connector-rest`; automatic OAuth token attachment is an intention rather than delivered wiring.
6. **Test environment:** missing required connector beans fail setup instead of silently skipping scenarios. Embedded infrastructure replaces old fixed-port assumptions.
7. **Profiles:** profile templates actually exist, including `IDM_TEST_PROFILE` activation in the YAML. The incorrect implication is that these templates alone move `BaseIntegrationTest` to real services.
8. **Cleanup:** YAML teardown (ITF-19) is an explicit, author-written `cleanup:` block, not inferred undo; a FAILED cleanup entry fails the scenario. Java role cleanup is a placeholder. Neither supports a blanket guarantee that all external side effects are removed.

The maintained [specification](../reference/specification.md) incorporates these findings. The README, configuration and running guide have been updated to use the same baseline. Historical design documents retain their original intent.

## Verification evidence

Executed on **2026-09-15** with **Zulu Java 21.0.11**, **Maven wrapper 3.9.11**, and macOS. The full reactor completed successfully in approximately 91 seconds:

```bash
JAVA_HOME=/Users/pepaoo/.sdkman/candidates/java/21.0.11-zulu ./mvnw clean test
```

The `JAVA_HOME` path is specific to the review machine; other users should select their own JDK 21 installation.

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Core | 34 | 0 | 0 | 0 |
| Utils | 51 | 0 | 0 | 0 |
| LDAP | 14 | 0 | 0 | 0 |
| Engine | 146 | 0 | 0 | 0 |
| Database | 21 | 0 | 0 | 0 |
| REST | 12 | 0 | 0 | 0 |
| SOAP | 13 | 0 | 0 | 0 |
| Scenarios | 29 | 0 | 0 | 0 |
| **Total** | **320** | **0** | **0** | **0** |

Counts come from module-local `target/surefire-reports/TEST-*.xml`, not README badges. Reports are generated build artifacts and will be replaced by later test runs.

Two initial sandboxed attempts (the default JDK 25 and then JDK 21) failed during Mockito/Byte Buddy agent attachment in the core context test. Repeating the JDK 21 run outside the sandbox passed without code changes. This establishes that the initial failure was tied to the restricted execution environment, not a documentation-induced regression. No real IdM or external application environment was tested.

### Documentation example checks

The documented `YamlScenarioTest` command passed on Java 21 on 2026-09-15. The overview/specification's identical minimal YAML example was also inserted temporarily into the scenario resources and executed successfully alongside the bundled example; the temporary source and copied resource were then removed. The Java authoring excerpt is taken from the full-suite-tested provisioning workflow. Illustrative external configuration and slide excerpts are labeled as such, not presented as standalone runnable tests.

All five diagrams rendered from their Mermaid sources. The eight-slide PowerPoint passed package/layout/import checks; the slides and PDF pages were visually reviewed. No production source or test implementation changes were needed.

[adapter]: ../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java
[adapter-tests]: ../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioValidationTest.java
[base]: ../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/base/BaseIntegrationTest.java
[scenarios]: ../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/
[engine]: ../../idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java
[engine-tests]: ../../idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java
[validator]: ../../idm-test-engine/src/main/java/com/idm/test/engine/ScenarioValidator.java
[create]: ../../idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapCreateStep.java
[verify]: ../../idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java
[fixtures]: ../../idm-test-engine/src/main/java/com/idm/test/engine/FixtureFactory.java
[interpolator]: ../../idm-test-engine/src/main/java/com/idm/test/engine/Interpolator.java
[evaluator]: ../../idm-test-engine/src/main/java/com/idm/test/engine/expr/ExpressionEvaluator.java
[conversion]: ../../idm-test-engine/src/main/java/com/idm/test/engine/expr/JsValues.java
[expression-tests]: ../../idm-test-engine/src/test/java/com/idm/test/engine/expr/ExpressionEvaluatorTest.java
[wait]: ../../idm-test-engine/src/main/java/com/idm/test/engine/model/WaitSpec.java
[executor]: ../../idm-test-core/src/main/java/com/idm/test/core/engine/TestExecutor.java
[results]: ../../idm-test-engine/src/main/java/com/idm/test/engine/result/ScenarioResult.java
[writer]: ../../idm-test-engine/src/main/java/com/idm/test/engine/result/JsonResultWriter.java
[ldap]: ../../idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/EdirectoryConnector.java
[db]: ../../idm-connector-database/src/main/java/com/idm/test/connector/database/client/MultiDatabaseConnector.java
[db-config]: ../../idm-connector-database/src/main/java/com/idm/test/connector/database/config/DatabaseConfiguration.java
[rest]: ../../idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/IdmRestConnector.java
[rest-config]: ../../idm-connector-rest/src/main/java/com/idm/test/connector/rest/config/RestClientConfiguration.java
[rest-pom]: ../../idm-connector-rest/pom.xml
[roles]: ../../idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/RoleServiceConnector.java
[resources]: ../../idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/ResourceServiceConnector.java
[resource-tests]: ../../idm-connector-soap/src/test/java/com/idm/test/connector/soap/client/ResourceServiceConnectorTest.java
[idm-pom]: ../../idm-connector-idm/pom.xml
[pom]: ../../pom.xml
[config]: ../../idm-test-scenarios/src/test/resources/application.yml
[schema]: ../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/config/TestSchemaInitializer.java
[independence]: ../../idm-test-engine/src/test/java/com/idm/test/engine/NoSpringDependencyTest.java
[design]: ../specs/2026-09-13-declarative-scenario-engine-design.md
[legacy]: ../history/specification-v2-before-2026-09-15.md
[cleanup-design]: ../specs/2026-09-24-explicit-cleanup-design.md
