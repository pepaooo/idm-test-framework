# Scenario Engine Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run a YAML scenario end to end — parse, validate, execute, retry an assertion, clean up, report — with the smallest vocabulary that proves the architecture.

**Architecture:** A new `idm-test-engine` module with no Spring dependency. The engine resolves `${...}` in a step's parameters *before* calling it and owns the retry loop, so step implementations receive fully resolved values and never think about waiting. Step types are registered explicitly by name; the JUnit adapter in `idm-test-scenarios` builds that registry from its Spring-injected connectors.

**Tech Stack:** Java 21, Jackson 2.19.2 with `jackson-dataformat-yaml`, JUnit 5, AssertJ, UnboundID in-memory LDAP.

**Tracking:** ITF-6, subtask 1 of 4 under ITF-4.
**Source spec:** `docs/specs/2026-09-13-declarative-scenario-engine-design.md` §6, §7.1, §7.2, §7.6, §7.7, §8.1, §8.2

## Global Constraints

- Java 21. Use records over Lombok; Lombok is not a dependency and must not be added.
- Maven wrapper is not executable: run it as `sh ./mvnw ...`, never `./mvnw ...`.
- Build sibling modules with `-am`: they are not installed in the local Maven repo, so `-pl <module>` alone fails to resolve `com.idm.test:*` dependencies. When combining `-am` with `-Dtest=`, add `-Dsurefire.failIfNoSpecifiedTests=false`.
- **`idm-test-engine` must not depend on Spring.** Spec §6.2 requires the engine not create contexts or read configuration, so a CLI can be added later. This is a build constraint, not a convention: if someone reaches for `@Component` in the engine, it must not compile.
- Step types register explicitly via a name → implementation map passed to the engine. No classpath scanning, no annotations.
- Reserved keys never share a namespace with user data (spec §7.3).
- Only two step types in this plan: `ldap.create` and `ldap.verify`. The other ten are ITF-8 and ITF-9.
- Expressions (`js:{ }`), the `set:` step and `as:` capture are ITF-7. This plan implements `${fixture.path}` interpolation only.
- Commit messages reference `ITF-6` on its own line before the trailers, and end with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

---

## File Structure

**New module `idm-test-engine`**, package root `com.idm.test.engine`.

| File | Responsibility |
|---|---|
| `model/Scenario.java` | Record: the parsed scenario — name, description, fixtures, steps |
| `model/FixtureSpec.java` | Record: how one fixture is produced — `generate` and/or literal `values` |
| `model/StepSpec.java` | Record: one step — its type name and its raw parameter map |
| `model/WaitSpec.java` | Record: timeout and poll interval, plus parsing of `wait: true` and `wait: {timeout, poll}` |
| `ScenarioLoader.java` | YAML bytes → `Scenario`. Parsing only; no validation |
| `ScenarioValidator.java` | Rejects unknown step types, missing required parameters, unresolvable `${...}` |
| `ValidationException.java` | Carries the file, step index and message |
| `Interpolator.java` | Resolves `${path}` against a variable map. Property paths only |
| `FixtureFactory.java` | Builds fixture values from `FixtureSpec` using `TestDataGenerator` |
| `spi/StepType.java` | The interface a step implements: `name()`, `execute(params)`, `retryable()` |
| `spi/StepOutcome.java` | Record: success, message, and an optional undo the engine registers for cleanup |
| `steps/LdapCreateStep.java` | `ldap.create` — wraps `EdirectoryConnector.create`, undo is a delete |
| `steps/LdapVerifyStep.java` | `ldap.verify` — wraps `EdirectoryConnector.verifyAttributes`, retryable |
| `ScenarioEngine.java` | Executes a scenario: interpolate, run in order, retry assertions, fail fast, unwind cleanup |
| `result/ScenarioResult.java` | Record: name, file, status, duration, ordered step results |
| `result/StepResult.java` | Record: index, type, resolved params, status, duration, error, and wait diagnostics |
| `result/JsonResultWriter.java` | Serialises results to a JSON file. The only thing that writes output |

**Modified:**
- `pom.xml` — add the `idm-test-engine` module and manage `jackson-dataformat-yaml`
- `idm-test-scenarios/pom.xml` — depend on `idm-test-engine`

**Created in `idm-test-scenarios`:**
- `src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java` — the `@TestFactory` adapter
- `src/test/resources/scenarios/user-provisioning.yaml` — the first real scenario

**Deliberately omitted, with reasons:**
- The explicit `cleanup:` block (spec §7.6). `ldap.create` has an obvious undo, so nothing yet needs a manual one. It arrives with `db.execute` in ITF-8.
- `as:` capture and the `set:` step — ITF-7.
- Any CLI entry point — spec §4 non-goal.

---

## Task 1: Module, scenario model, and YAML loading

**Files:**
- Modify: `pom.xml`
- Create: `idm-test-engine/pom.xml`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/model/Scenario.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/model/FixtureSpec.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/model/StepSpec.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/model/WaitSpec.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioLoader.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioLoaderTest.java`
- Create: `idm-test-engine/src/test/resources/scenarios/minimal.yaml`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces, relied on by every later task:
  - `record Scenario(String name, String description, Map<String, FixtureSpec> fixtures, List<StepSpec> steps)`
  - `record FixtureSpec(String generate, Map<String, Object> values)`
  - `record StepSpec(String type, Map<String, Object> params)`
  - `record WaitSpec(Duration timeout, Duration poll)` with `static WaitSpec parse(Object raw, Duration defaultTimeout, Duration defaultPoll)` returning `null` when `raw` is `null`
  - `ScenarioLoader.load(InputStream yaml, String sourceName)` returning `Scenario`, throwing `ValidationException` on malformed YAML

- [ ] **Step 1: Register the module in the parent POM**

In `pom.xml`, add to `<modules>` immediately after `<module>idm-test-utils</module>`:

```xml
        <module>idm-test-engine</module>
```

And in `<dependencyManagement><dependencies>`, immediately after the `idm-test-utils` entry:

```xml
            <dependency>
                <groupId>com.idm.test</groupId>
                <artifactId>idm-test-engine</artifactId>
                <version>${project.version}</version>
            </dependency>
```

`jackson-dataformat-yaml` needs no version: Spring Boot's parent manages it.

- [ ] **Step 2: Create the module POM**

Create `idm-test-engine/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.idm.test</groupId>
        <artifactId>idm-test-framework</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>idm-test-engine</artifactId>
    <name>IdM Test Engine</name>
    <description>Executes declarative YAML scenarios against the connectors</description>

    <!--
      This module deliberately has NO Spring dependency. The engine must not create
      application contexts or read configuration files, so that a CLI can wrap it later
      without rework. See the design spec, section 6.2.
    -->
    <dependencies>
        <dependency>
            <groupId>com.idm.test</groupId>
            <artifactId>idm-test-core</artifactId>
        </dependency>

        <dependency>
            <groupId>com.idm.test</groupId>
            <artifactId>idm-test-utils</artifactId>
        </dependency>

        <dependency>
            <groupId>com.idm.test</groupId>
            <artifactId>idm-connector-ldap</artifactId>
        </dependency>

        <dependency>
            <groupId>com.fasterxml.jackson.dataformat</groupId>
            <artifactId>jackson-dataformat-yaml</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>

        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

`idm-connector-ldap` pulls Spring LDAP transitively, which is unavoidable — the step implementations call the connector. The constraint is that *this module's own code* never imports `org.springframework.context` or declares Spring stereotypes. Task 6 adds a test that enforces it.

- [ ] **Step 3: Write the failing loader test**

Create `idm-test-engine/src/test/resources/scenarios/minimal.yaml`:

```yaml
name: Minimal scenario
description: One fixture and two steps

fixtures:
  user:
    generate: random-user
    values:
      department: Engineering

steps:
  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      from: user

  - ldap.verify:
      dn: "cn=${user.userId},ou=users"
      expect:
        givenName: ${user.firstName}
      wait:
        timeout: 30s
        poll: 2s
```

Create `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioLoaderTest.java`:

```java
package com.idm.test.engine;

import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.WaitSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScenarioLoader")
class ScenarioLoaderTest {

    private static InputStream classpath(String name) {
        return ScenarioLoaderTest.class.getClassLoader().getResourceAsStream(name);
    }

    @Test
    @DisplayName("Should load name, description and fixtures")
    void testLoadsHeaderAndFixtures() throws Exception {
        Scenario scenario = ScenarioLoader.load(classpath("scenarios/minimal.yaml"), "minimal.yaml");

        assertThat(scenario.name()).isEqualTo("Minimal scenario");
        assertThat(scenario.description()).isEqualTo("One fixture and two steps");
        assertThat(scenario.fixtures()).containsKey("user");
        assertThat(scenario.fixtures().get("user").generate()).isEqualTo("random-user");
        assertThat(scenario.fixtures().get("user").values())
                .containsEntry("department", "Engineering");
    }

    @Test
    @DisplayName("Should turn each single-key step map into a typed StepSpec, in order")
    void testLoadsStepsInOrder() throws Exception {
        Scenario scenario = ScenarioLoader.load(classpath("scenarios/minimal.yaml"), "minimal.yaml");

        assertThat(scenario.steps()).hasSize(2);
        assertThat(scenario.steps().get(0).type()).isEqualTo("ldap.create");
        assertThat(scenario.steps().get(0).params())
                .containsEntry("dn", "cn=${user.userId},ou=users")
                .containsEntry("from", "user");
        assertThat(scenario.steps().get(1).type()).isEqualTo("ldap.verify");
    }

    @Test
    @DisplayName("Should reject a step that is not a single-key map")
    void testRejectsMalformedStep() {
        String yaml = """
                name: Broken
                steps:
                  - ldap.create:
                      dn: "cn=x,ou=users"
                    ldap.verify:
                      dn: "cn=x,ou=users"
                """;

        assertThatThrownBy(() -> ScenarioLoader.load(stream(yaml), "broken.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("broken.yaml")
                .hasMessageContaining("exactly one step type");
    }

    @Test
    @DisplayName("Should report the source file when the YAML itself is malformed")
    void testRejectsMalformedYaml() {
        assertThatThrownBy(() -> ScenarioLoader.load(stream("name: [unclosed"), "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml");
    }

    @Test
    @DisplayName("Should default an absent fixtures or steps section to empty")
    void testDefaultsEmptySections() throws Exception {
        Scenario scenario = ScenarioLoader.load(stream("name: Bare"), "bare.yaml");

        assertThat(scenario.fixtures()).isEmpty();
        assertThat(scenario.steps()).isEmpty();
    }

    @Test
    @DisplayName("WaitSpec should read an explicit timeout and poll interval")
    void testWaitSpecExplicit() {
        WaitSpec wait = WaitSpec.parse(
                Map.of("timeout", "5m", "poll", "10s"),
                Duration.ofSeconds(30), Duration.ofSeconds(2));

        assertThat(wait.timeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(wait.poll()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("WaitSpec should fall back to supplied defaults for 'wait: true'")
    void testWaitSpecTrueUsesDefaults() {
        WaitSpec wait = WaitSpec.parse(Boolean.TRUE, Duration.ofSeconds(30), Duration.ofSeconds(2));

        assertThat(wait.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(wait.poll()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("WaitSpec should be null when no wait is declared")
    void testWaitSpecAbsent() {
        assertThat(WaitSpec.parse(null, Duration.ofSeconds(30), Duration.ofSeconds(2))).isNull();
    }

    private static InputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

```bash
sh ./mvnw -pl idm-test-engine -am test -Dtest=ScenarioLoaderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure — `package com.idm.test.engine.model does not exist`.

- [ ] **Step 5: Create the model records**

`idm-test-engine/src/main/java/com/idm/test/engine/model/Scenario.java`:

```java
package com.idm.test.engine.model;

import java.util.List;
import java.util.Map;

/**
 * A parsed scenario file. Produced by ScenarioLoader, checked by ScenarioValidator,
 * executed by ScenarioEngine.
 */
public record Scenario(
        String name,
        String description,
        Map<String, FixtureSpec> fixtures,
        List<StepSpec> steps) {
}
```

`model/FixtureSpec.java`:

```java
package com.idm.test.engine.model;

import java.util.Map;

/**
 * How one fixture is produced.
 *
 * @param generate a generator name such as "random-user", or null for literals only
 * @param values   literal values, which override anything the generator produced
 */
public record FixtureSpec(String generate, Map<String, Object> values) {
}
```

`model/StepSpec.java`:

```java
package com.idm.test.engine.model;

import java.util.Map;

/**
 * One step as written in the file: its type name and its raw, uninterpolated parameters.
 * The engine resolves {@code ${...}} in these parameters before handing them to a step.
 */
public record StepSpec(String type, Map<String, Object> params) {
}
```

`model/WaitSpec.java`:

```java
package com.idm.test.engine.model;

import java.time.Duration;
import java.util.Map;

/**
 * How long an assertion should keep retrying.
 * <p>
 * Waiting is a property of assertions rather than a step of its own: you never wait for its
 * own sake, you wait for something you were going to assert anyway.
 */
public record WaitSpec(Duration timeout, Duration poll) {

    /**
     * Reads the {@code wait} parameter.
     *
     * @param raw            {@code null} for no wait, {@link Boolean#TRUE} for the defaults,
     *                       or a map with {@code timeout} and/or {@code poll}
     * @param defaultTimeout used for {@code wait: true} and for an unspecified timeout
     * @param defaultPoll    used for {@code wait: true} and for an unspecified poll interval
     * @return the parsed spec, or {@code null} when no wait was declared
     */
    public static WaitSpec parse(Object raw, Duration defaultTimeout, Duration defaultPoll) {
        if (raw == null || Boolean.FALSE.equals(raw)) {
            return null;
        }
        if (Boolean.TRUE.equals(raw)) {
            return new WaitSpec(defaultTimeout, defaultPoll);
        }
        if (raw instanceof Map<?, ?> map) {
            return new WaitSpec(
                    duration(map.get("timeout"), defaultTimeout),
                    duration(map.get("poll"), defaultPoll));
        }
        throw new IllegalArgumentException(
                "wait must be true or a map with timeout and poll, but was: " + raw);
    }

    private static Duration duration(Object value, Duration fallback) {
        return value == null ? fallback : parseDuration(value.toString());
    }

    /**
     * Parses durations written the way a consultant would write them: 30s, 2m, 1h.
     */
    static Duration parseDuration(String text) {
        String trimmed = text.trim();
        if (trimmed.length() < 2) {
            throw new IllegalArgumentException("Not a duration: '" + text + "'");
        }

        char unit = trimmed.charAt(trimmed.length() - 1);
        String number = trimmed.substring(0, trimmed.length() - 1);

        long amount;
        try {
            amount = Long.parseLong(number);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Not a duration: '" + text + "'. Use a number followed by s, m or h.", e);
        }

        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            default -> throw new IllegalArgumentException(
                    "Unknown duration unit '" + unit + "' in '" + text + "'. Use s, m or h.");
        };
    }
}
```

- [ ] **Step 6: Create ValidationException and the loader**

`idm-test-engine/src/main/java/com/idm/test/engine/ValidationException.java`:

```java
package com.idm.test.engine;

/**
 * A scenario could not be loaded or is not valid.
 * <p>
 * Always names the source file, and the step when one is implicated, because the reader is
 * often not a Java developer and cannot infer position from a stack trace.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String sourceName, String message) {
        super(sourceName + ": " + message);
    }

    public ValidationException(String sourceName, int stepIndex, String stepType, String message) {
        super(sourceName + ", step " + (stepIndex + 1) + " (" + stepType + "): " + message);
    }

    public ValidationException(String sourceName, String message, Throwable cause) {
        super(sourceName + ": " + message, cause);
    }
}
```

`idm-test-engine/src/main/java/com/idm/test/engine/ScenarioLoader.java`:

```java
package com.idm.test.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a scenario file into the model. Parsing only — see ScenarioValidator for checks.
 */
public final class ScenarioLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ScenarioLoader() {
    }

    /**
     * @param yaml       the scenario file's contents; closed by this method
     * @param sourceName the file name, used in error messages
     */
    @SuppressWarnings("unchecked")
    public static Scenario load(InputStream yaml, String sourceName) {
        if (yaml == null) {
            throw new ValidationException(sourceName, "scenario file not found");
        }

        Map<String, Object> root;
        try (InputStream in = yaml) {
            root = YAML.readValue(in, Map.class);
        } catch (IOException e) {
            throw new ValidationException(sourceName, "could not parse YAML: " + e.getMessage(), e);
        }

        if (root == null) {
            throw new ValidationException(sourceName, "file is empty");
        }

        String name = asString(root.get("name"));
        if (name == null || name.isBlank()) {
            throw new ValidationException(sourceName, "'name' is required");
        }

        return new Scenario(
                name,
                asString(root.get("description")),
                readFixtures(root.get("fixtures"), sourceName),
                readSteps(root.get("steps"), sourceName));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, FixtureSpec> readFixtures(Object raw, String sourceName) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> fixtures)) {
            throw new ValidationException(sourceName, "'fixtures' must be a map of name to fixture");
        }

        Map<String, FixtureSpec> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : fixtures.entrySet()) {
            String fixtureName = entry.getKey().toString();
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                throw new ValidationException(sourceName,
                        "fixture '" + fixtureName + "' must be a map");
            }
            Object values = body.get("values");
            result.put(fixtureName, new FixtureSpec(
                    asString(body.get("generate")),
                    values == null ? Map.of() : (Map<String, Object>) values));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<StepSpec> readSteps(Object raw, String sourceName) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> steps)) {
            throw new ValidationException(sourceName, "'steps' must be a list");
        }

        List<StepSpec> result = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Object element = steps.get(i);
            if (!(element instanceof Map<?, ?> step) || step.size() != 1) {
                throw new ValidationException(sourceName,
                        "step " + (i + 1) + " must be a map with exactly one step type, "
                                + "for example '- ldap.create:'");
            }

            Map.Entry<?, ?> only = step.entrySet().iterator().next();
            Object params = only.getValue();
            result.add(new StepSpec(
                    only.getKey().toString(),
                    params == null ? Map.of() : (Map<String, Object>) params));
        }
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

```bash
sh ./mvnw -pl idm-test-engine -am test -Dtest=ScenarioLoaderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 8: Commit**

```bash
git add pom.xml idm-test-engine
git commit -m "feat: add idm-test-engine module with scenario model and YAML loader

First piece of the declarative scenario engine. Parses a scenario file
into records, turning each single-key step map into a typed StepSpec.
Duration strings are written the way a consultant would write them: 30s,
2m, 1h.

The module deliberately has no Spring dependency, so a CLI can wrap the
engine later without rework.

ITF-6

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Interpolation and fixtures

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/Interpolator.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/FixtureFactory.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/InterpolatorTest.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/FixtureFactoryTest.java`

**Interfaces:**
- Consumes: `FixtureSpec` from Task 1.
- Produces:
  - `Interpolator.resolve(Object value, Map<String, Object> scope)` returning `Object` — walks maps and lists, replacing `${path}` in strings. Throws `IllegalArgumentException` naming the reference when a path is unresolvable.
  - `Interpolator.references(Object value)` returning `Set<String>` — every `${path}` root name found, used by Task 3's validator.
  - `FixtureFactory(TestDataGenerator generator)` with `Map<String, Object> build(Map<String, FixtureSpec> fixtures)` returning fixture name → attribute map.

- [ ] **Step 1: Write the failing interpolation test**

Create `idm-test-engine/src/test/java/com/idm/test/engine/InterpolatorTest.java`:

```java
package com.idm.test.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Interpolator")
class InterpolatorTest {

    private static final Map<String, Object> SCOPE = Map.of(
            "user", Map.of("userId", "test.123", "firstName", "Ada"));

    @Test
    @DisplayName("Should replace a reference embedded in a larger string")
    void testReplacesEmbeddedReference() {
        Object result = Interpolator.resolve("cn=${user.userId},ou=users", SCOPE);

        assertThat(result).isEqualTo("cn=test.123,ou=users");
    }

    @Test
    @DisplayName("Should replace several references in one string")
    void testReplacesMultipleReferences() {
        Object result = Interpolator.resolve("${user.firstName} <${user.userId}>", SCOPE);

        assertThat(result).isEqualTo("Ada <test.123>");
    }

    @Test
    @DisplayName("Should walk nested maps and lists")
    void testWalksNestedStructures() {
        Object result = Interpolator.resolve(
                Map.of("expect", Map.of("givenName", "${user.firstName}"),
                       "roles", List.of("${user.userId}")),
                SCOPE);

        assertThat(result).isEqualTo(
                Map.of("expect", Map.of("givenName", "Ada"),
                       "roles", List.of("test.123")));
    }

    @Test
    @DisplayName("Should leave values without references untouched")
    void testLeavesPlainValues() {
        assertThat(Interpolator.resolve("ou=users", SCOPE)).isEqualTo("ou=users");
        assertThat(Interpolator.resolve(42, SCOPE)).isEqualTo(42);
        assertThat(Interpolator.resolve(null, SCOPE)).isNull();
    }

    @Test
    @DisplayName("Should name the offending reference when it cannot be resolved")
    void testFailsOnUnknownReference() {
        assertThatThrownBy(() -> Interpolator.resolve("cn=${missing.id}", SCOPE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${missing.id}");
    }

    @Test
    @DisplayName("Should name the offending reference when an intermediate path is missing")
    void testFailsOnUnknownNestedPath() {
        assertThatThrownBy(() -> Interpolator.resolve("${user.absent}", SCOPE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${user.absent}");
    }

    @Test
    @DisplayName("Should collect the root name of every reference for validation")
    void testCollectsReferenceRoots() {
        assertThat(Interpolator.references(
                Map.of("dn", "cn=${user.userId}", "note", "${created.body.id}")))
                .containsExactlyInAnyOrder("user", "created");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sh ./mvnw -pl idm-test-engine -am test -Dtest=InterpolatorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure — `cannot find symbol: class Interpolator`.

- [ ] **Step 3: Implement the interpolator**

Create `idm-test-engine/src/main/java/com/idm/test/engine/Interpolator.java`:

```java
package com.idm.test.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${path}} references against the scenario's variables.
 * <p>
 * Property paths only — no arithmetic, no conditionals, no function calls. That limit is
 * deliberate: it is what lets a form-based UI represent every value. When a scenario needs
 * computation it belongs in the Java tier, not in a larger expression language here.
 * <p>
 * The {@code js:{ }} escape hatch is ITF-7 and is not handled by this class.
 */
public final class Interpolator {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private Interpolator() {
    }

    /**
     * Replaces every reference in {@code value}, walking maps and lists.
     *
     * @throws IllegalArgumentException naming the reference that could not be resolved
     */
    public static Object resolve(Object value, Map<String, Object> scope) {
        if (value instanceof String text) {
            return resolveString(text, scope);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(k.toString(), resolve(v, scope)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            list.forEach(element -> resolved.add(resolve(element, scope)));
            return resolved;
        }
        return value;
    }

    /**
     * @return the root name of every reference found, for example "user" in "${user.email}"
     */
    public static Set<String> references(Object value) {
        Set<String> roots = new LinkedHashSet<>();
        collectReferences(value, roots);
        return roots;
    }

    private static void collectReferences(Object value, Set<String> roots) {
        if (value instanceof String text) {
            Matcher matcher = REFERENCE.matcher(text);
            while (matcher.find()) {
                roots.add(matcher.group(1).split("\\.", 2)[0]);
            }
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectReferences(v, roots));
        } else if (value instanceof List<?> list) {
            list.forEach(element -> collectReferences(element, roots));
        }
    }

    private static Object resolveString(String text, Map<String, Object> scope) {
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String path = matcher.group(1);
            Object value = lookup(path, scope, matcher.group());
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private static Object lookup(String path, Map<String, Object> scope, String reference) {
        Object current = scope;

        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                throw new IllegalArgumentException(
                        "cannot resolve " + reference + " — no value for '" + segment + "'");
            }
            current = map.get(segment);
        }

        return current;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
sh ./mvnw -pl idm-test-engine -am test -Dtest=InterpolatorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Write the failing fixture test**

Create `idm-test-engine/src/test/java/com/idm/test/engine/FixtureFactoryTest.java`:

```java
package com.idm.test.engine;

import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FixtureFactory")
class FixtureFactoryTest {

    private final FixtureFactory factory = new FixtureFactory(new TestDataGenerator());

    @Test
    @DisplayName("Should generate a user with the attributes scenarios reference")
    void testGeneratesRandomUser() {
        Map<String, Object> built = factory.build(
                Map.of("user", new FixtureSpec("random-user", Map.of())));

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) built.get("user");

        assertThat(user).containsKeys(
                "userId", "firstName", "lastName", "email", "department", "jobTitle");
        assertThat(user.get("userId")).asString().isNotBlank();
    }

    @Test
    @DisplayName("Should give each generated user a distinct id")
    void testGeneratedUsersAreDistinct() {
        Map<String, FixtureSpec> spec = Map.of("user", new FixtureSpec("random-user", Map.of()));

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) factory.build(spec).get("user");
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) factory.build(spec).get("user");

        assertThat(first.get("userId")).isNotEqualTo(second.get("userId"));
    }

    @Test
    @DisplayName("Should let literal values override generated ones")
    void testLiteralsOverrideGenerated() {
        Map<String, Object> built = factory.build(Map.of("user",
                new FixtureSpec("random-user", Map.of("department", "Engineering"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) built.get("user");

        assertThat(user).containsEntry("department", "Engineering");
    }

    @Test
    @DisplayName("Should support a fixture of literals with no generator")
    void testLiteralsOnly() {
        Map<String, Object> built = factory.build(Map.of("role",
                new FixtureSpec(null, Map.of("dn", "cn=SAP_USER,ou=roles"))));

        assertThat(built).containsEntry("role", Map.of("dn", "cn=SAP_USER,ou=roles"));
    }

    @Test
    @DisplayName("Should name the unknown generator and list what is available")
    void testRejectsUnknownGenerator() {
        assertThatThrownBy(() -> factory.build(
                Map.of("user", new FixtureSpec("random-widget", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("random-widget")
                .hasMessageContaining("random-user");
    }
}
```

- [ ] **Step 6: Implement the fixture factory**

Create `idm-test-engine/src/main/java/com/idm/test/engine/FixtureFactory.java`:

```java
package com.idm.test.engine;

import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.utils.data.TestDataGenerator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Turns fixture declarations into the values a scenario interpolates.
 * <p>
 * Only one generator exists — "random-user" — because only one is needed. Adding generators
 * speculatively would grow a vocabulary nothing exercises.
 */
public final class FixtureFactory {

    private static final String RANDOM_USER = "random-user";
    private static final Set<String> GENERATORS = Set.of(RANDOM_USER);

    private final TestDataGenerator generator;

    public FixtureFactory(TestDataGenerator generator) {
        this.generator = generator;
    }

    /**
     * @param fixtures fixture name to declaration
     * @return fixture name to its attribute map, ready for interpolation
     */
    public Map<String, Object> build(Map<String, FixtureSpec> fixtures) {
        Map<String, Object> scope = new LinkedHashMap<>();
        fixtures.forEach((name, spec) -> scope.put(name, buildOne(spec)));
        return scope;
    }

    private Map<String, Object> buildOne(FixtureSpec spec) {
        Map<String, Object> values = new LinkedHashMap<>();

        if (spec.generate() != null) {
            if (!GENERATORS.contains(spec.generate())) {
                throw new IllegalArgumentException(
                        "unknown generator '" + spec.generate() + "'. Available: " + GENERATORS);
            }
            values.putAll(randomUser());
        }

        // Literals win: a scenario that pins a department means it.
        values.putAll(spec.values());
        return values;
    }

    private Map<String, Object> randomUser() {
        String firstName = generator.generateFirstName();
        String lastName = generator.generateLastName();

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", generator.generateUserId());
        user.put("firstName", firstName);
        user.put("lastName", lastName);
        user.put("email", generator.generateEmail(firstName, lastName));
        user.put("department", generator.generateDepartment());
        user.put("jobTitle", generator.generateJobTitle());
        user.put("phoneNumber", generator.generatePhoneNumber());
        user.put("employeeId", generator.generateEmployeeId());
        return user;
    }
}
```

- [ ] **Step 7: Run both tests**

```bash
sh ./mvnw -pl idm-test-engine -am test -Dtest='InterpolatorTest,FixtureFactoryTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`

If `generateEmail(String, String)` does not exist with that signature, check `TestDataGenerator` — it has two `generateEmail` overloads — and use the one taking first and last name.

- [ ] **Step 8: Commit**

```bash
git add idm-test-engine
git commit -m "feat: resolve \${path} references and build fixtures

Interpolation is property paths only: no arithmetic, conditionals or
function calls. That limit is what lets a form-based UI represent every
value later; computation belongs in the Java tier.

One generator, random-user, because one is all any scenario needs. Literal
values override generated ones.

ITF-6

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: Step SPI and the two LDAP steps

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/spi/StepType.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/spi/StepOutcome.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapCreateStep.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java`

**Interfaces:**
- Consumes: `EdirectoryConnector.create(LdapUser)`, `EdirectoryConnector.verifyAttributes(String, Map<String, Object>)`, `EdirectoryConnector.delete(String)` — all returning `OperationResult`.
- Produces, relied on by Task 4 and by ITF-8 and ITF-9:
  - `interface StepType { String name(); boolean retryable(); StepOutcome execute(Map<String, Object> params); }` — `retryable()` defaults to `false`; `params` arrive **already interpolated**.
  - `record StepOutcome(boolean success, String message, Runnable undo)` with factories `success(String)`, `success(String, Runnable)` and `failure(String)`.
  - `LdapCreateStep(EdirectoryConnector)` registering as `ldap.create`.
  - `LdapVerifyStep(EdirectoryConnector)` registering as `ldap.verify`, `retryable()` true.

- [ ] **Step 1: Write the failing steps test**

Create `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the LDAP step types against an embedded directory.
 * <p>
 * Builds the LdapTemplate directly rather than through Spring, because this module has no
 * Spring context by design.
 */
@DisplayName("LDAP step types")
class LdapStepsTest {

    private static final String BASE_DN = "dc=test,dc=com";
    private static final String BIND_DN = "cn=admin,dc=test,dc=com";
    private static final String BIND_PASSWORD = "test";

    private static EmbeddedLdapServer server;
    private static EdirectoryConnector connector;

    @BeforeAll
    static void startDirectory() throws Exception {
        server = EmbeddedLdapServer.start(
                BASE_DN, BIND_DN, BIND_PASSWORD, 0, "scenarios/engine-test.ldif");

        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(server.getUrl());
        contextSource.setBase(BASE_DN);
        contextSource.setUserDn(BIND_DN);
        contextSource.setPassword(BIND_PASSWORD);
        contextSource.afterPropertiesSet();

        connector = new EdirectoryConnector(new LdapTemplate(contextSource));
    }

    @AfterAll
    static void stopDirectory() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("ldap.create should create an entry and return an undo that deletes it")
    void testCreateAndUndo() {
        LdapCreateStep create = new LdapCreateStep(connector);
        String dn = "cn=ada.create,ou=users";

        StepOutcome outcome = create.execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.create", "sn", "Lovelace", "givenName", "Ada")));

        assertThat(outcome.success()).isTrue();
        assertThat(connector.read(dn).isSuccess()).isTrue();

        outcome.undo().run();
        assertThat(connector.read(dn).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("ldap.create should report failure rather than throw on a schema violation")
    void testCreateFailure() {
        LdapCreateStep create = new LdapCreateStep(connector);

        // inetOrgPerson requires sn; omitting it must fail
        StepOutcome outcome = create.execute(Map.of(
                "dn", "cn=broken,ou=users",
                "attributes", Map.of("cn", "broken")));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).containsIgnoringCase("failed");
    }

    @Test
    @DisplayName("ldap.verify should pass when the attributes match")
    void testVerifyMatches() {
        String dn = "cn=ada.verify,ou=users";
        new LdapCreateStep(connector).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.verify", "sn", "Lovelace", "givenName", "Ada")));

        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Ada", "sn", "Lovelace")));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("ldap.verify should fail and say what it saw when an attribute differs")
    void testVerifyMismatch() {
        String dn = "cn=grace.verify,ou=users";
        new LdapCreateStep(connector).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "grace.verify", "sn", "Hopper", "givenName", "Grace")));

        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Ada")));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains(dn);
    }

    @Test
    @DisplayName("ldap.verify should fail when the entry does not exist")
    void testVerifyMissingEntry() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", "cn=absent,ou=users",
                "expect", Map.of("givenName", "Ada")));

        assertThat(outcome.success()).isFalse();
    }

    @Test
    @DisplayName("Only the assertion step is retryable")
    void testRetryability() {
        assertThat(new LdapCreateStep(connector).retryable()).isFalse();
        assertThat(new LdapVerifyStep(connector).retryable()).isTrue();
        assertThat(new LdapCreateStep(connector).name()).isEqualTo("ldap.create");
        assertThat(new LdapVerifyStep(connector).name()).isEqualTo("ldap.verify");
    }
}
```

Create `idm-test-engine/src/test/resources/scenarios/engine-test.ldif`:

```ldif
dn: ou=users,dc=test,dc=com
objectClass: top
objectClass: organizationalUnit
ou: users
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sh ./mvnw -pl idm-test-engine -am test -Dtest=LdapStepsTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure — `cannot find symbol: class LdapCreateStep`.

- [ ] **Step 3: Create the step SPI**

`idm-test-engine/src/main/java/com/idm/test/engine/spi/StepOutcome.java`:

```java
package com.idm.test.engine.spi;

/**
 * What happened when a step ran.
 *
 * @param success whether the step did what it was asked
 * @param message human-readable detail, shown in reports and failure messages
 * @param undo    how to reverse this step, or {@code null} when there is nothing to reverse.
 *                The engine collects these and unwinds them in reverse order.
 */
public record StepOutcome(boolean success, String message, Runnable undo) {

    public static StepOutcome success(String message) {
        return new StepOutcome(true, message, null);
    }

    public static StepOutcome success(String message, Runnable undo) {
        return new StepOutcome(true, message, undo);
    }

    public static StepOutcome failure(String message) {
        return new StepOutcome(false, message, null);
    }
}
```

`idm-test-engine/src/main/java/com/idm/test/engine/spi/StepType.java`:

```java
package com.idm.test.engine.spi;

import java.util.Map;

/**
 * One kind of step a scenario can contain.
 * <p>
 * Implementations receive parameters that are <em>already interpolated</em> — the engine
 * resolves {@code ${...}} before calling — and never handle retrying. Retrying belongs to
 * the engine so the policy lives in one place and steps stay trivially testable.
 */
public interface StepType {

    /**
     * @return the name used in scenario files, for example "ldap.create"
     */
    String name();

    /**
     * @return true when this step is an assertion the engine may retry under a {@code wait}
     */
    default boolean retryable() {
        return false;
    }

    /**
     * Runs the step. Must not throw: a failure is an outcome, not an exception.
     */
    StepOutcome execute(Map<String, Object> params);
}
```

- [ ] **Step 4: Implement the two LDAP steps**

`idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapCreateStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates an LDAP entry.
 * <p>
 * DNs are relative to the configured base, matching the connector's contract.
 */
public final class LdapCreateStep implements StepType {

    private final EdirectoryConnector connector;

    public LdapCreateStep(EdirectoryConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "ldap.create";
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params) {
        String dn = String.valueOf(params.get("dn"));
        Map<String, Object> attributes =
                (Map<String, Object>) params.getOrDefault("attributes", Map.of());

        LdapUser user = toLdapUser(dn, attributes);
        OperationResult<LdapUser> result = connector.create(user);

        if (!result.isSuccess()) {
            return StepOutcome.failure("ldap.create failed for " + dn + ": " + result.getMessage());
        }

        return StepOutcome.success("created " + dn, () -> connector.delete(dn));
    }

    /**
     * Maps the well-known attribute names onto LdapUser; anything else becomes a custom
     * attribute, which is how scenarios carry directory-specific fields.
     */
    private static LdapUser toLdapUser(String dn, Map<String, Object> attributes) {
        LdapUser user = new LdapUser();
        user.setDn(dn);

        Map<String, Object> remaining = new LinkedHashMap<>(attributes);
        user.setCn(take(remaining, "cn"));
        user.setSn(take(remaining, "sn"));
        user.setGivenName(take(remaining, "givenName"));
        user.setMail(take(remaining, "mail"));
        user.setUid(take(remaining, "uid"));
        user.setTelephoneNumber(take(remaining, "telephoneNumber"));
        user.setTitle(take(remaining, "title"));
        user.setOu(take(remaining, "ou"));
        user.setDescription(take(remaining, "description"));
        user.setUserPassword(take(remaining, "userPassword"));

        remaining.forEach(user::addCustomAttribute);
        return user;
    }

    private static String take(Map<String, Object> attributes, String key) {
        Object value = attributes.remove(key);
        return value == null ? null : value.toString();
    }
}
```

`idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;

/**
 * Asserts that an LDAP entry carries the expected attribute values.
 * <p>
 * Retryable: provisioning is asynchronous, so the engine may call this repeatedly under a
 * {@code wait} until it passes or the timeout expires.
 */
public final class LdapVerifyStep implements StepType {

    private final EdirectoryConnector connector;

    public LdapVerifyStep(EdirectoryConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "ldap.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params) {
        String dn = String.valueOf(params.get("dn"));
        Map<String, Object> expected =
                (Map<String, Object>) params.getOrDefault("expect", Map.of());

        OperationResult<Boolean> result = connector.verifyAttributes(dn, expected);

        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "ldap.verify could not read " + dn + ": " + result.getMessage());
        }

        if (!Boolean.TRUE.equals(result.getDataOrDefault(Boolean.FALSE))) {
            return StepOutcome.failure(
                    "ldap.verify failed for " + dn + ": expected " + expected
                            + " but the entry did not match. " + result.getMessage());
        }

        return StepOutcome.success("verified " + dn);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
sh ./mvnw -pl idm-test-engine -am test -Dtest=LdapStepsTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`

If `testCreateFailure` passes when it should fail, the embedded directory is not enforcing schema. `EmbeddedLdapServer.start` without a schema argument uses the standard schema, which requires `sn` on `inetOrgPerson` — check that the five-argument overload is being called, not one that disables validation.

- [ ] **Step 6: Commit**

```bash
git add idm-test-engine
git commit -m "feat: add step SPI and the ldap.create and ldap.verify steps

Steps receive parameters already interpolated and never handle retrying:
the engine resolves \${...} before calling and owns the retry loop, so
policy lives in one place and steps stay trivially testable.

A step reports failure as an outcome rather than throwing. ldap.create
returns an undo that deletes what it made, which the engine unwinds
during cleanup.

ITF-6

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: Validation

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioValidator.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioValidatorTest.java`

**Interfaces:**
- Consumes: `Scenario`, `StepSpec` (Task 1), `Interpolator.references` (Task 2), `StepType` (Task 3).
- Produces:
  - `ScenarioValidator(Map<String, StepType> registry)` with `void validate(Scenario scenario, String sourceName)` throwing `ValidationException` on the first problem found.

- [ ] **Step 1: Write the failing validator test**

Create `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioValidatorTest.java`:

```java
package com.idm.test.engine;

import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScenarioValidator")
class ScenarioValidatorTest {

    private static final StepType ACTION = new StepType() {
        public String name() { return "ldap.create"; }
        public StepOutcome execute(Map<String, Object> params) { return StepOutcome.success("ok"); }
    };

    private static final StepType ASSERTION = new StepType() {
        public String name() { return "ldap.verify"; }
        public boolean retryable() { return true; }
        public StepOutcome execute(Map<String, Object> params) { return StepOutcome.success("ok"); }
    };

    private final ScenarioValidator validator = new ScenarioValidator(
            Map.of("ldap.create", ACTION, "ldap.verify", ASSERTION));

    private static Scenario scenario(StepSpec... steps) {
        return new Scenario("Test", null,
                Map.of("user", new FixtureSpec("random-user", Map.of())),
                List.of(steps));
    }

    @Test
    @DisplayName("Should accept a scenario whose steps and references are all known")
    void testAcceptsValidScenario() {
        Scenario valid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=${user.userId},ou=users")),
                new StepSpec("ldap.verify", Map.of("dn", "cn=${user.userId},ou=users",
                        "wait", Boolean.TRUE)));

        assertThatCode(() -> validator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should name the unknown step type and list the available ones")
    void testRejectsUnknownStepType() {
        Scenario invalid = scenario(new StepSpec("ldap.summon", Map.of("dn", "cn=x")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.summon")
                .hasMessageContaining("ldap.create");
    }

    @Test
    @DisplayName("Should reject a step with no dn")
    void testRejectsMissingDn() {
        Scenario invalid = scenario(new StepSpec("ldap.create", Map.of()));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("dn");
    }

    @Test
    @DisplayName("Should reject a reference to a fixture that does not exist")
    void testRejectsUnknownReference() {
        Scenario invalid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=${absent.userId},ou=users")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("Should reject wait on an action, since only assertions retry")
    void testRejectsWaitOnAction() {
        Scenario invalid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=x,ou=users", "wait", Boolean.TRUE)));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("wait")
                .hasMessageContaining("ldap.create");
    }

    @Test
    @DisplayName("Should reject a scenario with no steps")
    void testRejectsEmptyScenario() {
        assertThatThrownBy(() -> validator.validate(scenario(), "empty.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one step");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sh ./mvnw -pl idm-test-engine -am test -Dtest=ScenarioValidatorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure — `cannot find symbol: class ScenarioValidator`.

- [ ] **Step 3: Implement the validator**

Create `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioValidator.java`:

```java
package com.idm.test.engine;

import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks a scenario before anything executes.
 * <p>
 * Validating up front matters more here than in most systems: a scenario can spend minutes
 * waiting for provisioning, and discovering a typo after that wait is expensive. Every
 * message names the file and the step, because the reader may not be a Java developer.
 */
public final class ScenarioValidator {

    private final Map<String, StepType> registry;

    public ScenarioValidator(Map<String, StepType> registry) {
        this.registry = registry;
    }

    /**
     * @throws ValidationException on the first problem found
     */
    public void validate(Scenario scenario, String sourceName) {
        if (scenario.steps().isEmpty()) {
            throw new ValidationException(sourceName, "a scenario needs at least one step");
        }

        Set<String> known = scenario.fixtures().keySet();

        for (int i = 0; i < scenario.steps().size(); i++) {
            StepSpec step = scenario.steps().get(i);
            StepType type = registry.get(step.type());

            if (type == null) {
                throw new ValidationException(sourceName, i, step.type(),
                        "unknown step type. Available: " + new TreeSet<>(registry.keySet()));
            }

            if (!step.params().containsKey("dn") && !step.params().containsKey("database")) {
                throw new ValidationException(sourceName, i, step.type(),
                        "missing required parameter 'dn'");
            }

            if (step.params().containsKey("wait") && !type.retryable()) {
                throw new ValidationException(sourceName, i, step.type(),
                        "'wait' is only valid on assertions, and this step is an action");
            }

            for (String reference : Interpolator.references(step.params())) {
                if (!known.contains(reference)) {
                    throw new ValidationException(sourceName, i, step.type(),
                            "references '${" + reference + "...}' but no fixture named '"
                                    + reference + "' is declared");
                }
            }
        }
    }
}
```

The `database` alternative in the required-parameter check exists so ITF-8's `db.*` steps, which address a database rather than a DN, do not need this class changed. It is the one piece of forward accommodation in this plan, and it is two words rather than an abstraction.

- [ ] **Step 4: Run the test to verify it passes**

```bash
sh ./mvnw -pl idm-test-engine -am test -Dtest=ScenarioValidatorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Commit**

```bash
git add idm-test-engine
git commit -m "feat: validate scenarios before executing anything

A scenario can spend minutes waiting for provisioning, so discovering a
typo afterwards is expensive. Rejects unknown step types, missing dn,
references to undeclared fixtures, and wait on an action. Every message
names the file and the step.

ITF-6

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: The engine — execution, retry, cleanup, results

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/StepResult.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/ScenarioResult.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–4, plus `TestExecutor.waitUntil(Supplier<Boolean>, Duration, Duration)` and `TestExecutorProperties.getDefaultTimeout()` / `getDefaultPollInterval()`.
- Produces:
  - `record StepResult(int index, String type, Map<String, Object> resolvedParams, boolean success, long durationMillis, String message, Integer attempts, Long waitedMillis)` — the last two are `null` for steps that did not wait.
  - `record ScenarioResult(String name, String sourceName, boolean success, long durationMillis, List<StepResult> steps)` with `String failureSummary()` returning a report-ready message, or `null` when successful.
  - `ScenarioEngine(Map<String, StepType> registry, FixtureFactory fixtures, TestExecutor executor, Duration defaultTimeout, Duration defaultPoll)` with `ScenarioResult run(Scenario scenario, String sourceName)`.

- [ ] **Step 1: Write the failing engine test**

Create `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java`:

```java
package com.idm.test.engine;

import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScenarioEngine")
class ScenarioEngineTest {

    private final List<String> events = new ArrayList<>();

    /** Records that it ran, and registers an undo that records too. */
    private StepType recordingAction(String name) {
        return new StepType() {
            public String name() { return name; }
            public StepOutcome execute(Map<String, Object> params) {
                events.add("run:" + name + ":" + params.get("dn"));
                return StepOutcome.success("ok", () -> events.add("undo:" + name));
            }
        };
    }

    private StepType failingAction(String name) {
        return new StepType() {
            public String name() { return name; }
            public StepOutcome execute(Map<String, Object> params) {
                events.add("run:" + name);
                return StepOutcome.failure(name + " went wrong");
            }
        };
    }

    /** Fails the first {@code failures} calls, then succeeds. */
    private StepType eventuallyPassing(String name, int failures) {
        AtomicInteger calls = new AtomicInteger();
        return new StepType() {
            public String name() { return name; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params) {
                return calls.getAndIncrement() < failures
                        ? StepOutcome.failure("not yet")
                        : StepOutcome.success("arrived");
            }
        };
    }

    private ScenarioEngine engineWith(StepType... types) {
        Map<String, StepType> registry = new java.util.LinkedHashMap<>();
        for (StepType type : types) {
            registry.put(type.name(), type);
        }
        return new ScenarioEngine(
                registry,
                new FixtureFactory(new TestDataGenerator()),
                new TestExecutor(),
                Duration.ofSeconds(2),
                Duration.ofMillis(50));
    }

    private static Scenario scenario(StepSpec... steps) {
        return new Scenario("Test", null,
                Map.of("user", new FixtureSpec("random-user", Map.of())),
                List.of(steps));
    }

    @Test
    @DisplayName("Should run steps in order and interpolate their parameters")
    void testRunsInOrderWithInterpolation() {
        ScenarioEngine engine = engineWith(recordingAction("a"), recordingAction("b"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", Map.of("dn", "first-${user.userId}")),
                new StepSpec("b", Map.of("dn", "second"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).hasSize(2);
        assertThat(events.get(0)).startsWith("run:a:first-");
        assertThat(events.get(1)).isEqualTo("run:b:second");
        assertThat(result.steps().get(0).resolvedParams().get("dn").toString())
                .doesNotContain("${");
    }

    @Test
    @DisplayName("Should stop at the first failure and skip the rest")
    void testStopsAtFirstFailure() {
        ScenarioEngine engine = engineWith(failingAction("a"), recordingAction("b"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", Map.of("dn", "x")),
                new StepSpec("b", Map.of("dn", "y"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(events).containsExactly("run:a");
        assertThat(result.failureSummary()).contains("a went wrong").contains("t.yaml");
    }

    @Test
    @DisplayName("Should unwind cleanup in reverse order, including after a failure")
    void testCleanupRunsInReverse() {
        ScenarioEngine engine = engineWith(
                recordingAction("a"), recordingAction("b"), failingAction("c"));

        engine.run(scenario(
                new StepSpec("a", Map.of("dn", "x")),
                new StepSpec("b", Map.of("dn", "y")),
                new StepSpec("c", Map.of("dn", "z"))), "t.yaml");

        assertThat(events).containsExactly(
                "run:a:x", "run:b:y", "run:c", "undo:b", "undo:a");
    }

    @Test
    @DisplayName("Should retry a failing assertion until it passes")
    void testRetriesAssertion() {
        ScenarioEngine engine = engineWith(eventuallyPassing("check", 2));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait", Boolean.TRUE))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(result.steps().get(0).attempts()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Should give up on an assertion that never passes and report what it saw")
    void testAssertionTimeout() {
        ScenarioEngine engine = engineWith(eventuallyPassing("check", Integer.MAX_VALUE));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait",
                        Map.of("timeout", "2s", "poll", "1s")))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).attempts()).isGreaterThan(0);
        assertThat(result.steps().get(0).waitedMillis()).isNotNull();
        assertThat(result.failureSummary()).contains("not yet");
    }

    @Test
    @DisplayName("Should keep the original failure when a cleanup action also fails")
    void testCleanupFailureDoesNotMaskOriginal() {
        StepType breaksOnUndo = new StepType() {
            public String name() { return "a"; }
            public StepOutcome execute(Map<String, Object> params) {
                return StepOutcome.success("ok", () -> {
                    throw new IllegalStateException("undo exploded");
                });
            }
        };
        ScenarioEngine engine = engineWith(breaksOnUndo, failingAction("b"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", Map.of("dn", "x")),
                new StepSpec("b", Map.of("dn", "y"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("b went wrong");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sh ./mvnw -pl idm-test-engine -am test -Dtest=ScenarioEngineTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure — `cannot find symbol: class ScenarioEngine`.

- [ ] **Step 3: Create the result records**

`idm-test-engine/src/main/java/com/idm/test/engine/result/StepResult.java`:

```java
package com.idm.test.engine.result;

import java.util.Map;

/**
 * What one step did.
 *
 * @param resolvedParams parameters after interpolation, so a report shows the values that
 *                       were actually used rather than the references that produced them
 * @param attempts       how many times a retried assertion ran, or null if it did not wait
 * @param waitedMillis   how long a retried assertion waited, or null if it did not wait
 */
public record StepResult(
        int index,
        String type,
        Map<String, Object> resolvedParams,
        boolean success,
        long durationMillis,
        String message,
        Integer attempts,
        Long waitedMillis) {
}
```

`idm-test-engine/src/main/java/com/idm/test/engine/result/ScenarioResult.java`:

```java
package com.idm.test.engine.result;

import java.util.List;

/**
 * The outcome of one scenario. The engine produces this and renders nothing; every output
 * format — JUnit failures, the JSON file, a future HTML report — is an adapter over it.
 */
public record ScenarioResult(
        String name,
        String sourceName,
        boolean success,
        long durationMillis,
        List<StepResult> steps) {

    /**
     * @return a message naming the file, the step and what went wrong, or null when successful
     */
    public String failureSummary() {
        if (success) {
            return null;
        }

        StepResult failed = steps.isEmpty() ? null : steps.get(steps.size() - 1);
        if (failed == null) {
            return sourceName + ": scenario failed before any step ran";
        }

        StringBuilder summary = new StringBuilder()
                .append(sourceName)
                .append(", step ").append(failed.index() + 1)
                .append(" (").append(failed.type()).append("): ")
                .append(failed.message());

        if (failed.attempts() != null) {
            summary.append(" — gave up after ").append(failed.attempts())
                    .append(" attempts over ").append(failed.waitedMillis()).append("ms");
        }

        return summary.toString();
    }
}
```

- [ ] **Step 4: Implement the engine**

Create `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java`:

```java
package com.idm.test.engine;

import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.model.WaitSpec;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a scenario: interpolate each step's parameters, execute in order, retry assertions
 * that declare a wait, stop at the first failure, then unwind cleanup in reverse.
 * <p>
 * Deliberately knows nothing about Spring, JUnit, files or output formats. It takes its
 * dependencies by constructor and returns a result object. That is what lets a CLI reuse it.
 */
public final class ScenarioEngine {

    private static final Logger log = LoggerFactory.getLogger(ScenarioEngine.class);

    private final Map<String, StepType> registry;
    private final FixtureFactory fixtures;
    private final TestExecutor executor;
    private final Duration defaultTimeout;
    private final Duration defaultPoll;

    public ScenarioEngine(
            Map<String, StepType> registry,
            FixtureFactory fixtures,
            TestExecutor executor,
            Duration defaultTimeout,
            Duration defaultPoll) {
        this.registry = registry;
        this.fixtures = fixtures;
        this.executor = executor;
        this.defaultTimeout = defaultTimeout;
        this.defaultPoll = defaultPoll;
    }

    public ScenarioResult run(Scenario scenario, String sourceName) {
        long started = System.currentTimeMillis();
        Map<String, Object> scope = new LinkedHashMap<>(fixtures.build(scenario.fixtures()));

        List<StepResult> results = new ArrayList<>();
        Deque<Runnable> undos = new ArrayDeque<>();
        boolean success = true;

        for (int i = 0; i < scenario.steps().size() && success; i++) {
            StepResult result = runStep(scenario.steps().get(i), i, scope, undos);
            results.add(result);
            success = result.success();
        }

        unwind(undos);

        return new ScenarioResult(scenario.name(), sourceName, success,
                System.currentTimeMillis() - started, results);
    }

    @SuppressWarnings("unchecked")
    private StepResult runStep(
            StepSpec step, int index, Map<String, Object> scope, Deque<Runnable> undos) {

        long started = System.currentTimeMillis();
        StepType type = registry.get(step.type());

        Map<String, Object> resolved = (Map<String, Object>) Interpolator.resolve(
                step.params(), scope);

        WaitSpec wait = WaitSpec.parse(resolved.remove("wait"), defaultTimeout, defaultPoll);

        if (wait == null) {
            StepOutcome outcome = type.execute(resolved);
            register(undos, outcome);
            return new StepResult(index, step.type(), resolved, outcome.success(),
                    System.currentTimeMillis() - started, outcome.message(), null, null);
        }

        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<StepOutcome> last = new AtomicReference<>();

        boolean passed = executor.waitUntil(() -> {
            attempts.incrementAndGet();
            StepOutcome outcome = type.execute(resolved);
            last.set(outcome);
            return outcome.success();
        }, wait.timeout(), wait.poll());

        StepOutcome outcome = last.get();
        register(undos, outcome);

        long elapsed = System.currentTimeMillis() - started;
        String message;
        if (outcome == null) {
            // waitUntil never evaluated the condition, which happens when the timeout is
            // already spent. Report that rather than failing with a NullPointerException.
            message = "wait timed out before the assertion ran once";
        } else if (passed) {
            message = outcome.message();
        } else {
            message = outcome.message() + " (last observed value after retrying)";
        }

        return new StepResult(index, step.type(), resolved, passed, elapsed, message,
                attempts.get(), elapsed);
    }

    private static void register(Deque<Runnable> undos, StepOutcome outcome) {
        if (outcome != null && outcome.undo() != null) {
            undos.push(outcome.undo());
        }
    }

    /**
     * Runs every undo in reverse order. A cleanup failure is logged but never replaces the
     * scenario's own result: what broke matters more than what could not be tidied.
     */
    private static void unwind(Deque<Runnable> undos) {
        while (!undos.isEmpty()) {
            try {
                undos.pop().run();
            } catch (Exception e) {
                log.warn("Cleanup action failed, continuing: {}", e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
sh ./mvnw -pl idm-test-engine -am test -Dtest=ScenarioEngineTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 6: Commit**

```bash
git add idm-test-engine
git commit -m "feat: execute scenarios with retry, fail-fast and reverse cleanup

The engine interpolates a step's parameters before calling it and owns
the retry loop, so steps stay simple. Cleanup unwinds in reverse on both
success and failure, and a cleanup failure never replaces the scenario's
own error.

Results record the resolved parameters and, for a step that waited, how
many attempts it made and how long it waited. 'Timed out after 5m' is
useless; 'timed out after 5m, 60 attempts, last saw 0 rows' is not.

ITF-6

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: JSON results and the JUnit adapter

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/JsonResultWriter.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/result/JsonResultWriterTest.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/NoSpringDependencyTest.java`
- Modify: `idm-test-scenarios/pom.xml`
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java`
- Create: `idm-test-scenarios/src/test/resources/scenarios/user-provisioning.yaml`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces:
  - `JsonResultWriter.write(List<ScenarioResult> results, Path target)` — creates parent directories, writes pretty-printed JSON.

- [ ] **Step 1: Write the failing JSON writer test**

Create `idm-test-engine/src/test/java/com/idm/test/engine/result/JsonResultWriterTest.java`:

```java
package com.idm.test.engine.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsonResultWriter")
class JsonResultWriterTest {

    @Test
    @DisplayName("Should write the fields a report or UI would need")
    void testWritesResults(@TempDir Path dir) throws Exception {
        StepResult step = new StepResult(
                0, "ldap.verify", Map.of("dn", "cn=ada,ou=users"),
                false, 1500, "did not match", 12, 30000L);
        ScenarioResult scenario = new ScenarioResult(
                "Provision Ada", "user-provisioning.yaml", false, 1600, List.of(step));

        Path target = dir.resolve("nested/results.json");
        JsonResultWriter.write(List.of(scenario), target);

        String json = Files.readString(target);
        assertThat(json)
                .contains("\"name\" : \"Provision Ada\"")
                .contains("\"sourceName\" : \"user-provisioning.yaml\"")
                .contains("\"type\" : \"ldap.verify\"")
                .contains("\"attempts\" : 12")
                .contains("\"waitedMillis\" : 30000")
                .contains("cn=ada,ou=users");
    }

    @Test
    @DisplayName("Should create parent directories that do not exist")
    void testCreatesDirectories(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("a/b/c/results.json");

        JsonResultWriter.write(List.of(), target);

        assertThat(Files.exists(target)).isTrue();
    }
}
```

- [ ] **Step 2: Implement the writer**

Create `idm-test-engine/src/main/java/com/idm/test/engine/result/JsonResultWriter.java`:

```java
package com.idm.test.engine.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes scenario results as JSON.
 * <p>
 * The only thing in the engine that produces output. An HTML renderer, CLI console output
 * and any future UI all read this same shape, which is why its structure is pinned by test.
 */
public final class JsonResultWriter {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonResultWriter() {
    }

    public static void write(List<ScenarioResult> results, Path target) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JSON.writeValue(target.toFile(), results);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write results to " + target, e);
        }
    }
}
```

- [ ] **Step 3: Add the test that enforces the no-Spring constraint**

Create `idm-test-engine/src/test/java/com/idm/test/engine/NoSpringDependencyTest.java`:

```java
package com.idm.test.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine must not reference Spring. Spec section 6.2 requires it to stay free of
 * application contexts and configuration loading so a CLI can wrap it later without rework.
 * <p>
 * Stated as a test rather than a convention, because conventions erode.
 */
@DisplayName("Engine independence")
class NoSpringDependencyTest {

    @Test
    @DisplayName("No engine source file should import org.springframework")
    void testNoSpringImports() throws IOException {
        Path sources = Path.of("src/main/java");

        try (Stream<Path> files = Files.walk(sources)) {
            List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(NoSpringDependencyTest::importsSpring)
                    .map(Path::toString)
                    .toList();

            assertThat(offenders)
                    .as("engine sources importing Spring; see spec section 6.2")
                    .isEmpty();
        }
    }

    private static boolean importsSpring(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .anyMatch(line -> line.startsWith("import org.springframework"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

Add the import `java.io.UncheckedIOException` to that file.

- [ ] **Step 4: Run the engine module's whole suite**

```bash
sh ./mvnw -pl idm-test-engine -am test
```

Expected: `Tests run: 35, Failures: 0, Errors: 0, Skipped: 0` for the engine module — 8 loader, 7 interpolator, 5 fixture, 6 steps, 6 validator, 6 engine, 2 writer, 1 independence. If the total differs, count the tests actually present before assuming a failure.

- [ ] **Step 5: Wire the engine into the scenarios module**

In `idm-test-scenarios/pom.xml`, add immediately after the `idm-test-utils` dependency:

```xml
        <dependency>
            <groupId>com.idm.test</groupId>
            <artifactId>idm-test-engine</artifactId>
        </dependency>
```

Create `idm-test-scenarios/src/test/resources/scenarios/user-provisioning.yaml`:

```yaml
name: User provisioning to eDirectory
description: Create a user and verify the directory entry carries its attributes

fixtures:
  user:
    generate: random-user
    values:
      department: Engineering

steps:
  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      attributes:
        cn: ${user.userId}
        sn: ${user.lastName}
        givenName: ${user.firstName}
        mail: ${user.email}

  - ldap.verify:
      dn: "cn=${user.userId},ou=users"
      expect:
        givenName: ${user.firstName}
        sn: ${user.lastName}
        mail: ${user.email}
      wait:
        timeout: 30s
        poll: 2s
```

- [ ] **Step 6: Create the JUnit adapter**

Create `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java`:

```java
package com.idm.test.scenarios.engine;

import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.result.JsonResultWriter;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.StepType;
import com.idm.test.engine.steps.LdapCreateStep;
import com.idm.test.engine.steps.LdapVerifyStep;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every YAML scenario under src/test/resources/scenarios as its own test.
 * <p>
 * This is the adapter between the engine and JUnit. It is the only place that knows about
 * both: the engine takes its connectors by constructor, and Spring supplies them here.
 */
@DisplayName("YAML scenarios")
class YamlScenarioTest extends BaseIntegrationTest {

    private static final String SCENARIO_DIR = "scenarios";
    private static final Path RESULTS = Path.of("target", "scenario-results.json");

    @TestFactory
    @DisplayName("Each scenario file runs as its own test")
    Stream<DynamicTest> scenarios() throws Exception {
        Map<String, StepType> registry = registry();
        ScenarioValidator validator = new ScenarioValidator(registry);
        ScenarioEngine engine = new ScenarioEngine(
                registry,
                new FixtureFactory(testDataGenerator),
                testExecutor,
                executorProperties.getDefaultTimeout(),
                executorProperties.getDefaultPollInterval());

        List<ScenarioResult> collected = Collections.synchronizedList(new ArrayList<>());

        return scenarioFiles().stream().map(file -> DynamicTest.dynamicTest(file, () -> {
            Scenario scenario = ScenarioLoader.load(resource(file), file);
            validator.validate(scenario, file);

            ScenarioResult result = engine.run(scenario, file);
            collected.add(result);
            JsonResultWriter.write(List.copyOf(collected), RESULTS);

            assertThat(result.success())
                    .as(result.failureSummary())
                    .isTrue();
        }));
    }

    private Map<String, StepType> registry() {
        Map<String, StepType> registry = new LinkedHashMap<>();
        for (StepType type : List.of(
                new LdapCreateStep(edirectoryConnector),
                new LdapVerifyStep(edirectoryConnector))) {
            registry.put(type.name(), type);
        }
        return registry;
    }

    private static InputStream resource(String file) {
        return YamlScenarioTest.class.getClassLoader()
                .getResourceAsStream(SCENARIO_DIR + "/" + file);
    }

    /**
     * Lists the scenario file names on the classpath.
     */
    private static List<String> scenarioFiles() throws URISyntaxException {
        var url = YamlScenarioTest.class.getClassLoader().getResource(SCENARIO_DIR);
        if (url == null) {
            return List.of();
        }

        Path dir = Path.of(url.toURI());
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(path -> path.toString().endsWith(".yaml"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list scenarios in " + dir, e);
        }
    }
}
```

- [ ] **Step 7: Run the scenario through JUnit**

```bash
sh ./mvnw -pl idm-test-scenarios -am test -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` — one dynamic test, named `user-provisioning.yaml`.

Then confirm the results file was written:

```bash
cat idm-test-scenarios/target/scenario-results.json
```

Expected: JSON containing `"name" : "User provisioning to eDirectory"`, both step entries, and `"success" : true`.

- [ ] **Step 8: Run the whole suite**

```bash
sh ./mvnw clean test 2>&1 | grep -E "^\[INFO\] Tests run:|BUILD"
```

Expected: `BUILD SUCCESS`, every line reporting `Failures: 0, Errors: 0, Skipped: 0`. The total rises from 172 to 208 — 35 engine tests plus the one dynamic scenario.

- [ ] **Step 9: Commit**

```bash
git add idm-test-engine idm-test-scenarios
git commit -m "feat: write JSON results and run YAML scenarios as JUnit tests

The engine emits structured results and renders nothing; the JUnit
adapter and the JSON writer are both adapters over the same shape, which
is what makes an HTML report or a UI cheap to add later.

A test enforces that no engine source imports Spring, because that
constraint is what keeps a CLI a wrapper rather than a rewrite, and
conventions erode where tests do not.

ITF-6

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Done When

- [ ] `sh ./mvnw clean test` returns `BUILD SUCCESS` with every line reporting `Failures: 0, Errors: 0, Skipped: 0`
- [ ] `idm-test-scenarios/target/scenario-results.json` exists after a run and contains the scenario name, both steps, and their resolved parameters
- [ ] A deliberately broken scenario — unknown step type, missing `dn`, or a reference to an undeclared fixture — fails at load with a message naming the file and step, before any LDAP call is made
- [ ] `NoSpringDependencyTest` passes, so no engine source imports Spring
- [ ] `ldap.create` entries are removed by cleanup: running the suite twice in a row succeeds both times
