# JDK 25 and Spring Boot 4.1.1 Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The framework builds and passes on Java 25 with Spring Boot 4.1.1 and Jackson 3, with no behaviour change visible to scenario authors, results readers or Java workflow tests.

**Architecture:** Phase 1 (Task 1) raises the Java level on Boot 3.5.6 and records a baseline results file. Phase 2 moves the parent to Boot 4.1.1 and lets Boot manage the libraries it covers (Task 2), moves our Jackson code to Jackson 3 behind a shared, Jackson-2-compatible mapper setup (Task 3), clears deprecation warnings (Task 4), and updates the docs and runs the final checks (Task 5).

**Tech Stack:** Java 25, Maven 3.9.11 wrapper, Spring Boot 4.1.1 (Framework 7, Security 7), Spring LDAP 4, Jackson 3 (`tools.jackson`), JUnit 6, Testcontainers 2.

**Ticket:** ITF-45. **Spec:** `docs/specs/2026-10-09-jdk25-spring-boot-4-upgrade-design.md`.

## Global Constraints

- Phase order is fixed: Task 1 (JDK 25 on Boot 3.5.6) must be green before Task 2 starts.
- Boot manages every library it covers. Our own version pins remain only for: Rhino, UnboundID, WireMock, `jaxws-rt` / `jaxws-maven-plugin` (4.0.2), `commons-pool` (1.6).
- Jackson 3 (`tools.jackson.*`) everywhere in our code; annotations stay `com.fasterxml.jackson.annotation`. No Jackson 2 artifact declared by our modules at the end of Task 3.
- Nothing downstream changes: `scenario-results.json` keeps property order, pretty-printing (including `"status" : "FAILED"` spacing), null handling and the evidence `"kind"` field; the HTML report, evidence bodies, YAML loading and JSON body parsing behave as before. Where a Jackson 3 default differs, set the old behaviour explicitly, with a comment.
- No deprecation warnings from `com.idm.test` sources (main and test) at the end of Task 4. Generated JAX-WS sources and third parties do not count.
- No new Boot 4 / Java 25 features; no change to public signatures, YAML steps or keys, `idm.test.*` properties, ITF-43 timeouts, or the ITF-23 rule (secrets never in messages, reports or logs).
- Boot 4 facts in this plan (starter names, managed versions, Jackson 3 defaults, Testcontainers 2 names) were known at design time; confirm each against the downloaded 4.1.1 BOM / jars and record any difference in the task report.
- Commit trailer: a blank line, `ITF-45`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven: always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. Use `export JAVA_HOME=~/.sdkman/candidates/java/25.0.3-zulu` for every build in this plan.
- Full-suite check (each task that says so): delete `idm-test-scenarios/target/surefire-reports/*ContainerScenarios.*` if present, run `./mvnw clean test`, then:

  ```bash
  grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
  grep -h -o 'failures="[0-9]*"\|errors="[0-9]*"\|skipped="[0-9]*"' */target/surefire-reports/TEST-*.xml | sort | uniq -c
  grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
  grep -c '"status" : "FAILED"' idm-test-scenarios/target/multi-directory-results.json
  ```

  Expected: BUILD SUCCESS, the total (925 before this plan, plus tests added here), only zero failures/errors/skips, `0`, `0`. Known flakes: `EmbeddedLdapServerTest.testLdifImport`, `LdapStepsTest` closed connection, `SoapStepsTest` port clash — re-run once and report both runs.

---

### Task 1: Phase 1 — Java 25 on Boot 3.5.6

**Files:**
- Modify: `pom.xml` (properties, `maven-compiler-plugin` configuration)
- Modify: `.github/workflows/ci.yml` (both "Set up JDK" steps)
- Modify: `README.md` (badge line 5, prerequisites line 64, technology table "Language" row), `CLAUDE.md` ("Java 21 LTS" line), `docs/reference/running-tests.md` (line 3), `docs/guides/scenario-authoring.md` (line 3), `docs/architecture/README.md` (line 11), `docs/presentations/project-introduction.md` (line 7 "Use Java 21." and line 46 "Run from the repository root with JDK 21." only)
- Create (not committed): `.git/sdd/itf45-baseline/scenario-results.json`, `.git/sdd/itf45-baseline/multi-directory-results.json`

**Interfaces:**
- Produces: the baseline results files that Task 3 compares against.

- [ ] **Step 1: Raise the Java level in one place**

In `pom.xml` `<properties>`, change `<java.version>21</java.version>` to `<java.version>25</java.version>` and delete these two lines:

```xml
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
```

In `<pluginManagement>`, the `maven-compiler-plugin` configuration becomes (Boot's parent sets `maven.compiler.release` from `java.version`):

```xml
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                        <parameters>true</parameters>
                    </configuration>
                </plugin>
```

- [ ] **Step 2: Confirm the release level**

Run: `./mvnw -q help:evaluate -Dexpression=maven.compiler.release -DforceStdout -pl idm-test-core -am | tail -1`
Expected: `25`.

- [ ] **Step 3: Full-suite check on JDK 25**

Run the Global Constraints full-suite check. Expected: 925 tests, zero failures/errors/skips, corpus `0` / `0`.

If a library managed by Boot 3.5.6 fails on Java 25 (most likely Mockito / Byte Buddy, as `Java 25 (69) is not supported` or an inline-mock-maker error), override only that library's version property in `pom.xml` `<properties>` to the version Boot 4.1.1 manages. Find it with:

```bash
./mvnw -q dependency:get -Dartifact=org.springframework.boot:spring-boot-dependencies:4.1.1:pom
grep -E '<(byte-buddy|mockito)\.version>' ~/.m2/repository/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom
```

and add, for example:

```xml
        <!-- ITF-45 Phase 1 only: Boot 3.5.6's version fails on Java 25; removed with the Boot 4.1.1 parent -->
        <byte-buddy.version>VERSION_FROM_THE_GREP</byte-buddy.version>
```

Re-run the full-suite check. Report every override in the task report.

- [ ] **Step 4: Save the baseline results for Task 3**

```bash
mkdir -p "$(git rev-parse --git-path sdd)/itf45-baseline"
cp idm-test-scenarios/target/scenario-results.json idm-test-scenarios/target/multi-directory-results.json "$(git rev-parse --git-path sdd)/itf45-baseline/"
```

- [ ] **Step 5: CI on JDK 25**

In `.github/workflows/ci.yml`, both setup steps become:

```yaml
      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
          cache: 'maven'
```

(Keep each step's existing indentation.)

- [ ] **Step 6: Current docs say Java 25**

Replace only these "Java 21" statements of the current requirement with Java 25:
- `README.md`: `![Java](https://img.shields.io/badge/Java-21-orange.svg)` → `Java-25`; `**Prerequisites:** Java 21 and Maven 3.9+.` → `Java 25`; the table row `| Language | Java | 21 |` → `25`
- `CLAUDE.md`: `- **Java 21 LTS**:` → `- **Java 25 LTS**:`
- `docs/reference/running-tests.md` line 3, `docs/guides/scenario-authoring.md` line 3, `docs/architecture/README.md` line 11 ("Current constraints are Java 25, …")
- `docs/presentations/project-introduction.md`: "Use Java 21." → "Use Java 25."; "Run from the repository root with JDK 21." → "JDK 25". Leave the dated "verified on 2026-09-15 with Java 21" and baseline sentences as they are.

Do not change `docs/architecture/capabilities.md`, `docs/history/`, `docs/specs/`, `docs/plans/`.

Check: `grep -rn "Java 21\|JDK 21\|Java-21" README.md CLAUDE.md docs/reference docs/guides docs/architecture/README.md docs/presentations` lists only the dated records in `project-introduction.md`.

- [ ] **Step 7: Commit**

```bash
git add pom.xml .github/workflows/ci.yml README.md CLAUDE.md docs/reference/running-tests.md docs/guides/scenario-authoring.md docs/architecture/README.md docs/presentations/project-introduction.md
git commit -m "build: move to Java 25 (Boot 3.5.6 unchanged)

ITF-45

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Phase 2 — Spring Boot 4.1.1 parent, managed versions, renamed starters

**Files:**
- Modify: `pom.xml` (parent, properties, `dependencyManagement`)
- Modify: `idm-connector-rest/pom.xml` (starters)
- Modify: `idm-test-scenarios/pom.xml` (Testcontainers artifact)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ContainerScenarios.java` (imports)
- Modify, only as the compiler requires: LDAP sources in `idm-connector-ldap`, and any other source broken by Framework 7 / Security 7 / Spring LDAP 4 / JUnit 6

**Interfaces:**
- Consumes: Task 1's Java 25 build.
- Produces: a Boot 4.1.1 build that is green with our Jackson code still on Jackson 2 (Task 3 moves it). Any temporary Jackson 2 declaration added here carries the comment `<!-- ITF-45: temporary, removed in Task 3 -->`.

- [ ] **Step 1: Read the 4.1.1 BOM before editing**

```bash
./mvnw -q dependency:get -Dartifact=org.springframework.boot:spring-boot-dependencies:4.1.1:pom
BOM=~/.m2/repository/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom
grep -E '<(spring-ldap|assertj|testcontainers|commons-lang3|h2|postgresql|oracle-database|jackson-bom|jackson-2-bom|junit-jupiter|junit|mockito|byte-buddy|spring-framework|spring-security)\.version>' $BOM
grep -oE '<artifactId>spring-boot-starter-(webmvc|web|restclient|security-oauth2-client|oauth2-client)</artifactId>' $BOM | sort -u
grep -oE '<artifactId>(testcontainers-postgresql|postgresql)</artifactId>' $BOM | sort -u
```

Record the output in the task report. It decides Steps 3 and 4.

- [ ] **Step 2: Parent and pins**

In `pom.xml`:
- parent `<version>3.5.6</version>` → `<version>4.1.1</version>`
- delete these properties: `spring-boot.version`, `spring-ldap.version`, `assertj.version`, `testcontainers.version`, `commons-lang3.version`, `h2.version`, `postgresql.version`, `oracle.version`, `apacheds.version`, and any Phase 1 override from Task 1
- in `<dependencyManagement>`, delete the entries for `spring-ldap-core`, `apacheds-all`, `assertj-core`, `commons-lang3`, the `testcontainers-bom` import, `h2`, `postgresql`, `ojdbc11`
- keep `unboundid-ldapsdk.version`, `wiremock.version`, `rhino.version` and their entries

If Step 1 shows the BOM does not manage one of the deleted libraries (for example Oracle's `ojdbc11`), keep that one's property and entry and say so in the report.

- [ ] **Step 3: Renamed starters**

`idm-connector-rest/pom.xml`: the module builds its own `RestClient` (`RestClientConfiguration` calls `RestClient.builder()`) and serves no endpoints. Replace

```xml
        <!-- Spring Boot Web (includes RestClient) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring Security OAuth2 Client -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-client</artifactId>
        </dependency>
```

with

```xml
        <!-- RestClient and its HTTP message converters; no web server -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-restclient</artifactId>
        </dependency>

        <!-- Spring Security OAuth2 Client -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-client</artifactId>
        </dependency>
```

If Step 1 shows no `spring-boot-starter-restclient`, use `spring-boot-starter-webmvc` with the comment `<!-- Spring MVC starter (includes RestClient) -->`. If it shows no `spring-boot-starter-security-oauth2-client`, keep `spring-boot-starter-oauth2-client`.

- [ ] **Step 4: Testcontainers 2**

`idm-test-scenarios/pom.xml`: the Testcontainers dependency's `<artifactId>postgresql</artifactId>` (groupId `org.testcontainers`) becomes `<artifactId>testcontainers-postgresql</artifactId>`. Do not touch the `org.postgresql:postgresql` JDBC driver below it.

`ContainerScenarios.java`: `import org.testcontainers.containers.PostgreSQLContainer;` → `import org.testcontainers.postgresql.PostgreSQLContainer;`. Testcontainers 2's `PostgreSQLContainer` is not generic: change `PostgreSQLContainer<?>` (and any `new PostgreSQLContainer<>(…)`) to `PostgreSQLContainer` / `new PostgreSQLContainer(…)`. The other imports (`GenericContainer`, `Container.ExecResult`, `Wait`, `Transferable`, `MountableFile`, `DockerClientFactory`) keep their packages unless the compiler says otherwise.

- [ ] **Step 5: Compile and fix what broke**

Run: `./mvnw clean test-compile`

Fix each error at its site with the Framework 7 / Security 7 / Spring LDAP 4 / JUnit 6 replacement, keeping behaviour and public signatures. Record each fix (file, old → new, why) in the task report.

Jackson: our engine and REST connector still use `com.fasterxml.jackson.databind` here. If they no longer compile because Boot 4.1.1 does not bring or manage Jackson 2, add a temporary import to `pom.xml` `<dependencyManagement>`:

```xml
            <!-- ITF-45: temporary, removed in Task 3 -->
            <dependency>
                <groupId>com.fasterxml.jackson</groupId>
                <artifactId>jackson-bom</artifactId>
                <version>2.19.2</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
```

and, in `idm-connector-rest/pom.xml`:

```xml
        <!-- ITF-45: temporary, removed in Task 3 -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
```

Use the BOM's `jackson-2-bom.version` instead of `2.19.2` if Step 1 found that property.

- [ ] **Step 6: Full-suite check**

Run the Global Constraints full-suite check. Expected: 925 tests, zero failures/errors/skips, corpus `0` / `0`. A test may change only where Boot 4 changed an API it calls, never what it asserts. List every test edit in the report.

- [ ] **Step 7: Commit**

```bash
git add -A pom.xml */pom.xml idm-*/src
git commit -m "build: move to Spring Boot 4.1.1 and let it manage the libraries it covers

ITF-45

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Jackson 3, with unchanged output

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/spi/JsonMappers.java`
- Modify: `idm-test-engine/pom.xml`; `ScenarioLoader.java`; `result/JsonResultWriter.java`, `result/HtmlReportWriter.java`, `result/EvidenceHtml.java`; `spi/EvidenceMasking.java`, `spi/JsonText.java`; `steps/HttpVerifyStep.java` (all under `idm-test-engine/src/main/java/com/idm/test/engine/`)
- Modify: `idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/IdmRestConnector.java`, `idm-connector-rest/src/test/java/com/idm/test/connector/rest/client/IdmRestConnectorTest.java`, `idm-connector-rest/pom.xml` and `pom.xml` (remove Task 2's temporary Jackson 2 declarations, if any)
- Unchanged: `StepResult.java`, `Evidence.java`, `IdmUser.java` (annotations only)
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioLoaderTest.java`, `idm-test-engine/src/test/java/com/idm/test/engine/result/JsonResultWriterTest.java`

**Interfaces:**
- Consumes: Task 1's baseline in `.git/sdd/itf45-baseline/`.
- Produces: `public static JsonMapper.Builder JsonMappers.builder()` in `com.idm.test.engine.spi` — every engine JSON mapper starts from it.

- [ ] **Step 1: Pin today's behaviour with characterization tests (on Jackson 2)**

Add to `ScenarioLoaderTest` (it already has `stream(String)`):

```java
    @Test
    @DisplayName("Should type YAML scalars in step parameters exactly as before")
    void testScalarTyping() {
        Scenario scenario = ScenarioLoader.load(stream("""
                name: Scalars
                steps:
                  - t.step:
                      t: true
                      f: false
                      yesWord: yes
                      noWord: no
                      onWord: on
                      offWord: off
                      nul: null
                      tilde: ~
                      empty:
                      int: 42
                      neg: -7
                      big: 12345678901234567890
                      flt: 1.5
                      exp: 1.0e3
                      octal: 0755
                      hex: 0x1F
                      date: 2026-01-01
                      quoted: "42"
                      str: hello
                """), "scalars.yaml");

        Map<String, Object> p = scenario.steps().get(0).params();
        assertThat(p.get("t")).isEqualTo(Boolean.TRUE);
        assertThat(p.get("f")).isEqualTo(Boolean.FALSE);
        assertThat(p.get("yesWord")).isEqualTo("yes");
        assertThat(p.get("noWord")).isEqualTo("no");
        assertThat(p.get("onWord")).isEqualTo("on");
        assertThat(p.get("offWord")).isEqualTo("off");
        assertThat(p).containsEntry("nul", null).containsEntry("tilde", null).containsEntry("empty", null);
        assertThat(p.get("int")).isEqualTo(42);
        assertThat(p.get("neg")).isEqualTo(-7);
        assertThat(p.get("big")).isEqualTo(new java.math.BigInteger("12345678901234567890"));
        assertThat(p.get("flt")).isEqualTo(1.5);
        assertThat(p.get("exp")).isEqualTo(1000.0);
        assertThat(p.get("octal")).isEqualTo(493);
        assertThat(p.get("hex")).isEqualTo(31);
        assertThat(p.get("date")).isEqualTo("2026-01-01");
        assertThat(p.get("quoted")).isEqualTo("42");
        assertThat(p.get("str")).isEqualTo("hello");
        assertThat(p.keySet()).containsExactly("t", "f", "yesWord", "noWord", "onWord", "offWord", "nul",
                "tilde", "empty", "int", "neg", "big", "flt", "exp", "octal", "hex", "date", "quoted", "str");
    }
```

Add to `JsonResultWriterTest`:

```java
    @Test
    @DisplayName("Should keep the exact layout: declaration order, ' : ' separators, empty arrays and nulls")
    void testExactLayout(@TempDir Path dir) throws Exception {
        ScenarioResult scenario = new ScenarioResult("S", "s.yaml", true, 10, List.of(), null);

        Path target = dir.resolve("layout.json");
        JsonResultWriter.write(List.of(scenario), target);

        assertThat(Files.readString(target).replace("\r\n", "\n")).isEqualTo("""
                [ {
                  "name" : "S",
                  "sourceName" : "s.yaml",
                  "success" : true,
                  "durationMillis" : 10,
                  "steps" : [ ],
                  "cleanup" : [ ],
                  "failureDetail" : null,
                  "description" : null
                } ]""");
    }

    @Test
    @DisplayName("Should write a step's fields in declaration order")
    void testStepFieldOrder(@TempDir Path dir) throws Exception {
        StepResult step = new StepResult(0, "ldap.verify", Map.of("dn", "x"), false, 5, "m", 2, 100L, "d");
        ScenarioResult scenario = new ScenarioResult("S", "s.yaml", false, 10, List.of(step), null);

        Path target = dir.resolve("order.json");
        JsonResultWriter.write(List.of(scenario), target);

        String json = Files.readString(target);
        List<String> keys = List.of("\"index\"", "\"type\"", "\"resolvedParams\"", "\"success\" : false",
                "\"durationMillis\" : 5", "\"message\"", "\"attempts\"", "\"waitedMillis\"", "\"description\" : \"d\"");
        int last = json.indexOf("\"steps\"");
        for (String key : keys) {
            int at = json.indexOf(key, last);
            assertThat(at).as(key + " after the previous field").isGreaterThan(last);
            last = at;
        }
    }
```

These are characterization tests: they record what Jackson 2 does now. Run them before any Jackson change:

Run: `./mvnw test -pl idm-test-engine -am -Dtest='ScenarioLoaderTest,JsonResultWriterTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. If an assertion fails here, Jackson 2's actual behaviour is the baseline: change that expected value (the YAML `yes`/`no`/`on`/`off`, `octal`, `hex` and `exp` values, and the exact layout's array/whitespace details, are the likeliest) to the observed value, re-run until green, and list each correction in the report. Commit the tests on their own:

```bash
git add idm-test-engine/src/test/java/com/idm/test/engine/ScenarioLoaderTest.java idm-test-engine/src/test/java/com/idm/test/engine/result/JsonResultWriterTest.java
git commit -m "test: pin YAML scalar typing and the results layout before the Jackson 3 move

ITF-45

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 2: The shared mapper setup**

Create `idm-test-engine/src/main/java/com/idm/test/engine/spi/JsonMappers.java`:

```java
package com.idm.test.engine.spi;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where every engine JSON mapper starts. Jackson 3 changed some defaults; these settings keep
 * the results file, the report and the evidence exactly as they were under Jackson 2, which
 * their readers depend on.
 */
public final class JsonMappers {

    private JsonMappers() {
    }

    /** A builder with the engine's shared settings; callers add their own features and build. */
    public static JsonMapper.Builder builder() {
        return JsonMapper.builder()
                // Jackson 3 sorts properties alphabetically by default; ours keep declaration order.
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    }
}
```

- [ ] **Step 3: Move the engine to Jackson 3**

`idm-test-engine/pom.xml`: `<groupId>com.fasterxml.jackson.dataformat</groupId>` → `<groupId>tools.jackson.dataformat</groupId>` (artifact `jackson-dataformat-yaml`, no version).

`ScenarioLoader.java`:

```java
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.yaml.YAMLMapper;
…
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
…
        try (InputStream in = yaml) {
            root = YAML.readValue(in, Map.class);
        } catch (JacksonException | IOException e) {
            throw new ValidationException(sourceName, "could not parse YAML: " + e.getMessage(), e);
        }
```

`JsonResultWriter.java`:

```java
import com.idm.test.engine.spi.JsonMappers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
…
    private static final JsonMapper JSON = JsonMappers.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();
…
            JSON.writeValue(target.toFile(), results);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write results to " + target, e);
        } catch (JacksonException e) {
            throw new UncheckedIOException("Could not write results to " + target,
                    e.getCause() instanceof IOException io ? io : new IOException(e));
        }
```

`HtmlReportWriter.java`: `JSON = JsonMappers.builder().enable(SerializationFeature.INDENT_OUTPUT).build();` (type `JsonMapper`); `catch (JsonProcessingException e)` → `catch (JacksonException e)`; imports from `tools.jackson.*`.

`EvidenceHtml.java`: `PRETTY = JsonMappers.builder().enable(SerializationFeature.INDENT_OUTPUT).build();`, `COMPACT = JsonMappers.builder().build();` (type `JsonMapper`); both `catch (JsonProcessingException …)` → `catch (JacksonException …)`; imports from `tools.jackson.*`.

`EvidenceMasking.java`:

```java
    private static final JsonMapper JSON = JsonMappers.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
```

with `tools.jackson.databind.DeserializationFeature` and `tools.jackson.databind.json.JsonMapper`.

`JsonText.java` and `HttpVerifyStep.java`: `private static final JsonMapper JSON = JsonMappers.builder().build();` with the `tools.jackson.databind.json.JsonMapper` import (`HttpVerifyStep` also imports `com.idm.test.engine.spi.JsonMappers`). Their `catch (Exception …)` blocks stay.

`StepResult.java`, `Evidence.java`: no change (annotations).

- [ ] **Step 4: Move the REST connector to Jackson 3**

`IdmRestConnector.java`: `import com.fasterxml.jackson.databind.ObjectMapper;` → `import tools.jackson.databind.json.JsonMapper;`; the field becomes `private final JsonMapper objectMapper = JsonMapper.builder().build();`. The `catch (Exception e)` around `readValue` stays.

`IdmRestConnectorTest.java`: the same import and field change. Its `throws Exception` clauses may stay.

`IdmUser.java`: no change.

Delete Task 2's temporary Jackson 2 declarations (the `jackson-bom` 2.x import in `pom.xml` and `jackson-databind` in `idm-connector-rest/pom.xml`), if present.

- [ ] **Step 5: Run the engine and REST tests**

Run: `./mvnw test -pl idm-test-engine,idm-connector-rest -am`
Expected: PASS, including Step 1's characterization tests unchanged.

If `testExactLayout` or the existing `"x" : y` assertions fail because Jackson 3 writes `"x": y`, restore the separator in `JsonMappers.builder()`:

```java
                // Jackson 3's default pretty printer drops the space before ':'; keep "name" : value.
                .defaultPrettyPrinter(new DefaultPrettyPrinter(
                        Separators.createDefaultInstance().withObjectNameValueSpacing(Separators.Spacing.BOTH)))
```

(`tools.jackson.core.util.DefaultPrettyPrinter`, `tools.jackson.core.util.Separators`; confirm the builder method and constructor names in the downloaded Jackson 3 jar.) If empty arrays or array spacing differ, set the matching `Separators` / `DefaultPrettyPrinter` option the same way. If `testScalarTyping` fails (Jackson 3's YAML module follows YAML 1.2), set the `YAMLMapper.builder()` feature that restores the Jackson 2 result for that scalar, with a comment, and say which in the report. Never change what a characterization test expects in this step.

- [ ] **Step 6: No Jackson 2 from our declarations**

Run: `./mvnw -q dependency:tree -Dincludes=com.fasterxml.jackson.core:jackson-databind`
Expected: no output. If a third-party library brings it, the output shows the path; record it in the report (acceptable, but our code must not import it):

Run: `grep -rlE 'com\.fasterxml\.jackson\.(core|databind|dataformat)' --include='*.java' .`
Expected: no output (only `com.fasterxml.jackson.annotation` imports remain).

- [ ] **Step 7: Full suite and results parity**

Run the Global Constraints full-suite check. Then compare the shape of the results with Task 1's baseline, masking values (random fixture data, durations) but keeping keys, their order and the layout:

```bash
B="$(git rev-parse --git-path sdd)/itf45-baseline"
mask() { perl -pe 's/"(?:[^"\\]|\\.)*"(?!\s*:)/"S"/g; s/(?<![\w"])-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?(?![\w"])/N/g' "$1"; }
for f in scenario-results.json multi-directory-results.json; do
  diff <(mask "$B/$f") <(mask "idm-test-scenarios/target/$f") > /dev/null && echo "$f: same shape" || diff <(mask "$B/$f") <(mask "idm-test-scenarios/target/$f") | head -40
done
```

Expected: `scenario-results.json: same shape` and `multi-directory-results.json: same shape`. If a difference remains, find its cause. A key-order difference inside a map built with `Map.of` / `HashMap` (its order is not fixed between runs) is not a regression — show it is one by naming the source of that map. Anything else is fixed in `JsonMappers` or the mapper concerned. Put the result in the report.

- [ ] **Step 8: Commit**

```bash
git add -A pom.xml idm-test-engine idm-connector-rest
git commit -m "refactor: move the engine and REST connector to Jackson 3, keeping their output unchanged

ITF-45

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: No deprecation warnings from our code; confirm the versions

**Files:**
- Modify: whichever `com.idm.test` sources (main and test) the deprecation check lists. Likely: `idm-connector-rest/src/main/java/com/idm/test/connector/rest/config/RestClientConfiguration.java`, `idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/IdmRestConnector.java`, `idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/RestAuthenticator.java`, the Spring LDAP setup in `idm-connector-ldap`.

**Interfaces:**
- Consumes: Tasks 2–3.
- Produces: nothing new; behaviour and public signatures unchanged.

- [ ] **Step 1: List the warnings**

```bash
./mvnw clean test-compile -Dmaven.compiler.showDeprecation=true -Dmaven.compiler.showWarnings=true 2>&1 \
  | grep -E 'WARNING.*\.java' | grep -E 'deprecat|removal' | grep '/src/' | grep -v '/target/generated' | sort -u \
  | tee "$(git rev-parse --git-path sdd)/itf45-deprecations.txt"
```

Record the list in the report.

- [ ] **Step 2: Replace each use with its documented successor**

For each line: open the deprecated API's Javadoc in the downloaded jar (its `@deprecated` tag names the successor) and switch to it, keeping behaviour. Do not add `@SuppressWarnings("deprecation")` or `"removal"` anywhere. If a deprecated API has no successor that keeps behaviour, stop and report it as NEEDS_CONTEXT with the API, the site and the Javadoc text.

- [ ] **Step 3: Re-run the check**

Re-run Step 1. Expected: empty output.

- [ ] **Step 4: Confirm the versions**

```bash
./mvnw -q dependency:tree -pl idm-test-scenarios -am \
  -Dincludes=org.springframework.boot:spring-boot,org.springframework:spring-core,org.springframework.security:spring-security-core,org.springframework.ldap:spring-ldap-core,org.testcontainers,org.junit.jupiter:junit-jupiter-api,tools.jackson.core:jackson-databind \
  | grep -oE '(spring-boot|spring-core|spring-security-core|spring-ldap-core|testcontainers[a-z-]*|junit-jupiter-api|jackson-databind):jar:[0-9.]+' | sort -u
```

Expected: `spring-boot` 4.1.1, `spring-core` 7.x, `spring-security-core` 7.x, `spring-ldap-core` 4.x, `testcontainers*` 2.x, `junit-jupiter-api` 6.x, `jackson-databind` 3.x. Record the exact versions in the report (Task 5 puts them in the README).

- [ ] **Step 5: Full-suite check**

Run the Global Constraints full-suite check. Expected as before.

- [ ] **Step 6: Commit**

```bash
git add -A idm-*/src
git commit -m "refactor: replace the APIs Spring Boot 4 deprecates

ITF-45

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(If Step 1 listed nothing, skip the commit and say so.)

---

### Task 5: Docs for Boot 4.1.1, and the final checks

**Files:**
- Modify: `README.md` (badge line 6, line 11 "JUnit 5", technology table), `CLAUDE.md` (lines 7, 257, 265, 267 and the "Dependencies → Connectors" starter names), `docs/reference/specification.md` (the technology stack lines 30–52)

**Interfaces:**
- Consumes: Task 4's version list.

- [ ] **Step 1: README**

- Badge: `Spring%20Boot-3.5.6` → `Spring%20Boot-4.1.1`.
- Line 11: `Spring Boot, JUnit 5 and an embedded directory` → `Spring Boot, JUnit and an embedded directory`.
- Technology table, with the exact versions from Task 4 Step 4 (and `./mvnw -q dependency:tree -Dincludes=org.assertj:assertj-core,com.h2database:h2,org.mozilla:rhino,com.unboundid:unboundid-ldapsdk,org.wiremock:wiremock-standalone -pl idm-test-scenarios -am` for the rest):

```markdown
| Concern | Choice | Version |
|---|---|---|
| Language | Java | 25 |
| Framework | Spring Boot | 4.1.1 |
| Testing | JUnit 6, AssertJ | <junit-jupiter-api>, <assertj-core> |
| LDAP | Spring LDAP, UnboundID SDK | <spring-ldap-core>, 7.0.1 |
| Database | Spring JDBC, H2 | <h2> |
| SOAP | JAX-WS, JAXB | — |
| YAML/JSON | Jackson | <jackson-databind 3.x> |
| Expressions | Mozilla Rhino | 1.8.0 |
| HTTP | JDK HttpClient via Spring RestClient; Spring Security OAuth2 client | — |
| Containers (optional runner) | Testcontainers | <testcontainers 2.x> |
| Mocking | WireMock | 3.10.0 |
```

Replace each `<…>` with the version the dependency tree printed. Also update the test-count table if the total changed (Task 3 added three tests: the count is then 928).

- [ ] **Step 2: CLAUDE.md**

- Line 7: `built on modern Spring Boot and JUnit 5.` → `built on modern Spring Boot and JUnit.`
- `- **Spring Boot 3.5.6**: Latest stable version with Spring Framework 6.2.x` → `- **Spring Boot 4.1.1**: Latest stable version with Spring Framework 7.0.x` (use the Framework minor from Task 4).
- Reference links: `https://docs.spring.io/spring-boot/3.5.6/reference/` → `https://docs.spring.io/spring-boot/4.1/reference/`; `https://docs.spring.io/spring-framework/6.2.x/reference/` → `https://docs.spring.io/spring-framework/reference/`; `- JUnit 5 docs: https://junit.org/junit5/docs/current/user-guide/` → `- JUnit docs: https://docs.junit.org/current/user-guide/`.
- "Dependencies → Connectors": `spring-boot-starter-oauth2-client (OAuth)` → `spring-boot-starter-security-oauth2-client (OAuth)`, and add `spring-boot-starter-restclient (REST)` (or `-webmvc`, whichever Task 2 used).

- [ ] **Step 3: specification.md stack lines**

In `docs/reference/specification.md` lines 30–52, update: `**Java**: 21 (LTS) - with planned migration path to Java 25` → `**Java**: 25 (LTS)`; `**Spring Boot**: 3.5.6 (latest stable)` → `4.1.1`; `**Spring Framework**: 6.2.x (bundled with Spring Boot 3.5.6)` → `7.0.x (bundled with Spring Boot 4.1.1)`; `**JUnit 5**: 5.11.x (Jupiter)` → `**JUnit 6**: 6.x (Jupiter)`; `**Testcontainers**: 1.20.x` → `2.x`; `**JSON Processing**: Jackson 2.17.x` → `Jackson 3.x`. Use the exact minors from Task 4. Leave the rest of the document alone.

- [ ] **Step 4: Final checks**

1. The Global Constraints full-suite check. Expected: 928 tests (or the count in the report), zero failures/errors/skips, corpus `0` / `0`.
2. `ContainerScenarios` (needs Docker): `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false`. Report the result; if Docker is not available, report "not run: Docker unavailable". Afterwards delete `idm-test-scenarios/target/surefire-reports/*ContainerScenarios.*`.
3. `grep -rn "3\.5\.6\|Spring Boot 3\|JUnit 5" README.md CLAUDE.md docs/reference/specification.md` — only the specification's example `pom.xml` snippet (around line 766) may remain; report it.

- [ ] **Step 5: Commit**

```bash
git add README.md CLAUDE.md docs/reference/specification.md
git commit -m "docs: Spring Boot 4.1.1, Java 25, JUnit 6, Jackson 3 and Testcontainers 2 in the stack docs

ITF-45

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
