# Upgrade to JDK 25 and Spring Boot 4.1.1, with Jackson 3 — design

**Ticket:** ITF-45 (task, under ITF-4).

## 1. The goal

Move the framework from Java 21 / Spring Boot 3.5.6 to Java 25 / Spring Boot 4.1.1 (released
2026-08-20; the 4.1 line is supported until 2027-07-31), without changing any behaviour a scenario
author, a results reader or a Java workflow test can see.

## 2. Decisions (jangulo, 2026-10-09)

1. **One ticket, two ordered phases on one branch:** JDK 25 first (Boot stays on 3.5.6, which
   supports Java 25), then Boot 4.1.1. Each phase ends green and has its own commits.
2. **Migrate to Jackson 3** (`tools.jackson`), Boot 4's default, rather than pinning Jackson 2.
3. **Boot manages every library it covers;** our own version pins remain only for libraries Boot
   does not manage.
4. **No deprecation warnings from our own code** after the upgrade. New Boot 4 or Java 25 features
   are not adopted unless the upgrade requires them.

Version facts about Boot 4 below (renamed starters, Jackson 3 defaults, Testcontainers 2 artifact
names) come from the Boot 4.0/4.1 migration notes as known at design time; the implementer confirms
each against the 4.1.1 release notes and records any difference in the task report.

## 3. Phase 1: JDK 25 on Boot 3.5.6

**Build** (`pom.xml`):
- `java.version` = `25`.
- Remove `maven.compiler.source` / `maven.compiler.target` and the `<source>` / `<target>` lines in
  the `maven-compiler-plugin` configuration; Boot's parent derives `maven.compiler.release` from
  `java.version`, so the level is set in one place. Keep `<parameters>true</parameters>`.
- If a library managed by Boot 3.5.6 fails on Java 25 (most likely Mockito / Byte Buddy inline
  mocking), override only that library's version property to the version Boot 4.1.1 manages, with a
  comment naming this ticket. Phase 2 removes the override.

**CI** (`.github/workflows/ci.yml`): `java-version: '25'` (Temurin) in both jobs; step names read
"Set up JDK 25".

**Docs that describe the current requirement** say Java 25:
- `README.md`: the badge, the prerequisites line and the technology table
- `CLAUDE.md`: "Java 25 LTS"
- `docs/reference/running-tests.md`, `docs/guides/scenario-authoring.md`,
  `docs/architecture/README.md` ("Current constraints are Java 25, …")
- `docs/presentations/project-introduction.md`: only the "Use Java …" / "Run … with JDK …"
  instructions, not the dated verification records

Dated records stay as written: the baselines in `docs/architecture/capabilities.md`, `docs/history/`,
older specs and plans.

**Local convention:** `export JAVA_HOME=~/.sdkman/candidates/java/25.0.3-zulu`.

**Gate before Phase 2:** the full suite on JDK 25 passes with 925 tests, 0 failures/errors/skips, and
both corpus checks report `0` FAILED.

## 4. Phase 2: Spring Boot 4.1.1

### 4.1 Parent and version pins (`pom.xml`)

- Parent `spring-boot-starter-parent` 4.1.1; remove the redundant `spring-boot.version` property.
- Remove these properties and their `dependencyManagement` entries so Boot manages them:
  `spring-ldap.version`, `assertj.version`, `testcontainers.version` (with the
  `testcontainers-bom` import), `commons-lang3.version`, `h2.version`, `postgresql.version`,
  `oracle.version`.
- Remove the unused `apacheds.version` property and the `apacheds-all` entry (no module depends on
  it).
- Keep our own pins for libraries Boot does not manage: Rhino, UnboundID, WireMock, `jaxws-rt` (the
  dependency and the `jaxws-maven-plugin` and its dependency, 4.0.2) and `commons-pool` 1.6. Each is
  confirmed to build and pass on Jakarta EE 11 and Java 25; a pin is raised (to its latest patch
  release of the same major) only if it does not.
- Remove any Phase 1 overrides.

### 4.2 Starters

- `idm-connector-rest`: `spring-boot-starter-web` → `spring-boot-starter-webmvc` (or the narrower
  Boot 4 starter that provides `RestClient` and the HTTP message converters, if that is sufficient —
  the module serves no web endpoints); `spring-boot-starter-oauth2-client` →
  `spring-boot-starter-security-oauth2-client`.
- The other starters (`spring-boot-starter`, `-jdbc`, `-test`, `spring-boot-configuration-processor`)
  keep their names. Add a Boot 4 module test starter only where a test needs one; the project uses no
  test slices, MockMvc or `@MockBean`.

### 4.3 Library majors

- **Spring LDAP 4:** fix whatever moved in `EdirectoryConnector`, its configuration and the LDAP
  steps.
- **Testcontainers 2:** `org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql`;
  `ContainerScenarios` uses the new packages. It still starts containers only when selected.
- **JUnit 6** (through Boot): only what the compiler reports.

### 4.4 Deprecations

`./mvnw clean test-compile` with deprecation warnings shown (`-Dmaven.compiler.showDeprecation=true`,
or `-Xlint:deprecation` in the compiler arguments for the check) reports no warnings from
`com.idm.test` sources, main and test. Each deprecated use is replaced with its documented successor.
Likely sites: the `RestClient` and request-factory builders in `RestClientConfiguration` and
`IdmRestConnector`, the OAuth2 authorized-client manager in `RestAuthenticator`, the Spring LDAP
context-source and template setup. Deprecation warnings from generated JAX-WS sources or from third
parties do not count.

### 4.5 Unchanged

Public signatures of connectors and the engine; every YAML step type and key; every `idm.test.*`
property; the ITF-43 timeout behaviour; the ITF-23 rule that secrets never appear in messages,
reports or logs.

## 5. Jackson 3

**Where:** the engine (`ScenarioLoader`, `JsonResultWriter`, `HtmlReportWriter`, `EvidenceHtml`,
`StepResult`, `Evidence`, `EvidenceMasking`, `JsonText`, `HttpVerifyStep`), the REST connector
(`IdmRestConnector`, `IdmUser`) and `IdmRestConnectorTest`.

**Change:**
- Imports `com.fasterxml.jackson.{core,databind,dataformat.yaml}` → `tools.jackson.*`. The
  annotations (`@JsonProperty`, `@JsonTypeInfo`, `@JsonSubTypes`, `@JsonInclude`,
  `@JsonIgnoreProperties`) keep `com.fasterxml.jackson.annotation`.
- `new ObjectMapper()` → immutable mappers from builders: `JsonMapper.builder()…build()`, and
  `YAMLMapper.builder()…build()` instead of `new ObjectMapper(new YAMLFactory())`. Features such as
  `INDENT_OUTPUT` move into the builder.
- `JsonProcessingException` (checked) → `JacksonException` (unchecked); catch blocks remain so every
  failure still yields the same step or loader message.
- `idm-test-engine/pom.xml`: `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` →
  `tools.jackson.dataformat:jackson-dataformat-yaml`, version managed by Boot.
- Our modules declare no Jackson 2 artifact; `./mvnw dependency:tree` shows no
  `com.fasterxml.jackson.core:jackson-databind` brought in by our own declarations. A Jackson 2 jar
  arriving transitively through a third party is acceptable but unused by our code.

**Binding rule — nothing downstream changes.** Where a Jackson 3 default differs from Jackson 2 and
affects us, the mapper sets the Jackson 2 behaviour explicitly, with a comment:
- `scenario-results.json`: property order as declared (Jackson 3 sorts alphabetically by default),
  the same pretty-printing including the `"status" : "FAILED"` spacing the corpus check greps for,
  the same treatment of nulls and empty values, and the evidence `"kind"` field.
- The HTML report and evidence bodies: `JsonText` pretty-printing, truncation and masking give the
  same results.
- The YAML loader: the same scenarios produce the same model; unknown or invalid keys are still
  rejected; loader error messages stay recognisable (tests asserting fragments of Jackson's own
  wording change only if that wording changed).
- `http.verify` and `IdmRestConnector`: response bodies parse the same way (numbers, nulls, big
  integers).

## 6. Verification

**Each phase:**
- Delete stale `idm-test-scenarios/target/surefire-reports/*ContainerScenarios.*`, then
  `./mvnw clean test` on JDK 25: BUILD SUCCESS; at least 925 tests (plus any added here); 0
  failures/errors/skips.
- `grep -c '"status" : "FAILED"'` on `scenario-results.json` and `multi-directory-results.json`: both
  `0`.
- Known flakes (`EmbeddedLdapServerTest.testLdifImport`, the `LdapStepsTest` closed connection, the
  `SoapStepsTest` port clash) are re-run once, and both runs are reported.

**Phase 2 also:**
- The deprecation check in §4.4 reports nothing from our sources.
- `./mvnw dependency:tree` shows Spring Boot 4.1.1, Spring Framework 7.x, Spring Security 7.x,
  Spring LDAP 4.x, Testcontainers 2.x, JUnit 6.x and no Jackson 2 databind from our declarations.
- **JSON parity:** `scenario-results.json` from a corpus run before Phase 2 (end of Phase 1) and
  after it, with timestamps and durations masked, differ in no structure, field order or
  formatting.
- The existing writer, loader and evidence tests pass without changes to what they assert.
- `ContainerScenarios` (needs Docker) is run once and reported; if Docker is unavailable the report
  says so. It does not block the merge — the default build never uses Docker.
- CI runs on JDK 25; the user confirms the first green CI run after pushing.

## 7. Documentation (Phase 2)

- `README.md`: the Spring Boot version, the technology table, the dependency notes, the test-count
  table if the count changes.
- `CLAUDE.md`: "Spring Boot 4.1.1 / Spring Framework 7.x", the reference-doc links (Boot 4.1,
  Framework 7.0), the dependency list with the renamed starters.
- `docs/reference/specification.md` and `docs/architecture/README.md`: the stack lines.

Dated history and baseline records stay unchanged.

## 8. Out of scope

- Adopting new Boot 4 or Java 25 features (virtual threads, structured concurrency, gRPC, …).
- WireMock 4.x.
- The per-profile configuration split (ITF-44).
- Timeouts for the OAuth token request.
