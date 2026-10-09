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
                .containsKey("attributes");
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
    @DisplayName("Should reject a fixture with scalar 'values' instead of a map")
    void testRejectsFixtureWithScalarValues() {
        String yaml = """
                name: BadFixture
                fixtures:
                  user:
                    generate: random-user
                    values: department
                """;

        assertThatThrownBy(() -> ScenarioLoader.load(stream(yaml), "bad-fixture.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad-fixture.yaml")
                .hasMessageContaining("'values' must be a map");
    }

    @Test
    @DisplayName("Should reject a step with scalar parameters instead of a map")
    void testRejectsStepWithScalarParams() {
        String yaml = """
                name: BadStep
                steps:
                  - ldap.noop: "just a string"
                """;

        assertThatThrownBy(() -> ScenarioLoader.load(stream(yaml), "bad-step.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad-step.yaml")
                .hasMessageContaining("parameters must be a map");
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

    private static InputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Should load a cleanup block after the steps")
    void testLoadsCleanup() {
        Scenario scenario = ScenarioLoader.load(yaml("""
                name: With cleanup
                steps:
                  - ldap.create:
                      dn: cn=x
                cleanup:
                  - ldap.delete:
                      dn: cn=x
                """), "c.yaml");

        assertThat(scenario.cleanup()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo("ldap.delete");
            assertThat(entry.params()).containsEntry("dn", "cn=x");
        });
    }

    @Test
    @DisplayName("Should default to an empty cleanup block")
    void testCleanupDefaultsToEmpty() {
        Scenario scenario = ScenarioLoader.load(yaml("""
                name: No cleanup
                steps:
                  - ldap.create:
                      dn: cn=x
                """), "c.yaml");

        assertThat(scenario.cleanup()).isEmpty();
    }

    @Test
    @DisplayName("Should reject a cleanup block that is not a list, naming the section")
    void testRejectsNonListCleanup() {
        assertThatThrownBy(() -> ScenarioLoader.load(yaml("""
                name: Bad cleanup
                steps:
                  - ldap.create:
                      dn: cn=x
                cleanup: nope
                """), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'cleanup' must be a list");
    }

    @Test
    @DisplayName("Should reject a cleanup entry that is not a single-key map")
    void testRejectsCleanupEntryThatIsNotAMap() {
        assertThatThrownBy(() -> ScenarioLoader.load(yaml("""
                name: Bad cleanup entry
                steps:
                  - ldap.create:
                      dn: cn=x
                cleanup:
                  - just-a-string
                """), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cleanup entry 1 must be a map");
    }

    @Test
    @DisplayName("Should reject a cleanup entry whose parameters are a scalar")
    void testRejectsCleanupEntryWithScalarParams() {
        assertThatThrownBy(() -> ScenarioLoader.load(yaml("""
                name: Bad cleanup params
                steps:
                  - ldap.create:
                      dn: cn=x
                cleanup:
                  - ldap.delete: nope
                """), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cleanup entry 1 (ldap.delete)")
                .hasMessageContaining("parameters must be a map");
    }

    @Test
    @DisplayName("Should type YAML scalars in step parameters by YAML 1.2 rules")
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
        // Under Jackson 2 (YAML 1.1) yes/no/on/off were booleans, ~ was null and 0755/0x1F were numbers;
        // ITF-45 deliberately accepts YAML 1.2, where they stay as text.
        assertThat(p.get("yesWord")).isEqualTo("yes");
        assertThat(p.get("noWord")).isEqualTo("no");
        assertThat(p.get("onWord")).isEqualTo("on");
        assertThat(p.get("offWord")).isEqualTo("off");
        assertThat(p).containsEntry("nul", null).containsEntry("empty", null);
        assertThat(p.get("tilde")).isEqualTo("~");
        assertThat(p.get("int")).isEqualTo(42);
        assertThat(p.get("neg")).isEqualTo(-7);
        assertThat(p.get("big")).isEqualTo(new java.math.BigInteger("12345678901234567890"));
        assertThat(p.get("flt")).isEqualTo(1.5);
        assertThat(p.get("exp")).isEqualTo(1000.0);
        assertThat(p.get("octal")).isEqualTo("0755");
        assertThat(p.get("hex")).isEqualTo("0x1F");
        assertThat(p.get("date")).isEqualTo("2026-01-01");
        assertThat(p.get("quoted")).isEqualTo("42");
        assertThat(p.get("str")).isEqualTo("hello");
        assertThat(p.keySet()).containsExactly("t", "f", "yesWord", "noWord", "onWord", "offWord", "nul",
                "tilde", "empty", "int", "neg", "big", "flt", "exp", "octal", "hex", "date", "quoted", "str");
    }

    @Test
    @DisplayName("Should load the first document of a multi-document YAML file")
    void testMultiDocumentLoadsFirst() {
        Scenario scenario = ScenarioLoader.load(stream("name: First\n---\nname: Second\n"), "multi.yaml");

        assertThat(scenario.name()).isEqualTo("First");
    }
}
