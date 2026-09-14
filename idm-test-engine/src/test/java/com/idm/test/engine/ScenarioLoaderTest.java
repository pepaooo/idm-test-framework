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
}
