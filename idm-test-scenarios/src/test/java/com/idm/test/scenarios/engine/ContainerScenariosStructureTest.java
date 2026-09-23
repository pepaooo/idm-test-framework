package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ContainerScenarios} is worth running only because it reaches its containers through
 * {@link RealEnvironmentScenarios}' configuration path, not around it. These checks keep it
 * that way, and keep it out of the Docker-free default build. None of them needs Docker.
 */
@DisplayName("Container runner structure")
class ContainerScenariosStructureTest {

    private static final Class<?> RUNNER = ContainerScenarios.class;

    @Test
    @DisplayName("Is named so the default build does not run it")
    void testNotPickedUpBySurefireDefaults() {
        assertThat(RUNNER.getSimpleName()).doesNotEndWith("Test").doesNotEndWith("Tests");
    }

    @Test
    @DisplayName("Extends the real-environment runner, so its guards and wiring apply")
    void testExtendsRealEnvironmentRunner() {
        assertThat(RUNNER.getSuperclass()).isEqualTo(RealEnvironmentScenarios.class);
    }

    @Test
    @DisplayName("Pins no profile and injects no properties through Spring's test annotations")
    void testNoSpringPropertyOverrides() {
        MergedAnnotations annotations = MergedAnnotations.from(RUNNER, SearchStrategy.TYPE_HIERARCHY);
        assertThat(annotations.isPresent(ActiveProfiles.class)).isFalse();
        assertThat(annotations.isPresent(TestPropertySource.class)).isFalse();
        assertThat(Arrays.stream(RUNNER.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(DynamicPropertySource.class))
                .map(Method::getName))
                .as("configuration must arrive as system properties, the way an operator supplies it")
                .isEmpty();

        SpringBootTest springBootTest = annotations.get(SpringBootTest.class).synthesize();
        assertThat(springBootTest.properties())
                .as("@SpringBootTest(properties=...) would override profile and environment-variable values too")
                .isEmpty();
        assertThat(springBootTest.args())
                .as("@SpringBootTest(args=...) would override profile and environment-variable values too")
                .isEmpty();
    }

    @Test
    @DisplayName("Evicts any context cached by another runner before its own @BeforeAll runs")
    void testDirtiesContextBeforeClass() {
        MergedAnnotations annotations = MergedAnnotations.from(RUNNER, SearchStrategy.TYPE_HIERARCHY);
        assertThat(annotations.isPresent(DirtiesContext.class)).isTrue();
        assertThat(annotations.get(DirtiesContext.class).synthesize().classMode())
                .isEqualTo(DirtiesContext.ClassMode.BEFORE_CLASS);
    }

    @Test
    @DisplayName("requireBoundTo accepts a matching URL")
    void testRequireBoundToMatching() {
        assertThatCode(() -> ContainerScenarios.requireBoundTo("ldap://a:1", "ldap://a:1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("requireBoundTo refuses a mismatched URL")
    void testRequireBoundToMismatch() {
        assertThatThrownBy(() -> ContainerScenarios.requireBoundTo("ldap://real:389", "ldap://localhost:5000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ldap://real:389")
                .hasMessageContaining("ldap://localhost:5000")
                .hasMessageContaining("was not built from the containers");
    }

    @Test
    @DisplayName("requireBoundTo refuses a null published URL")
    void testRequireBoundToNullPublished() {
        assertThatThrownBy(() -> ContainerScenarios.requireBoundTo("ldap://a:1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was not built from the containers");
    }

    @Test
    @DisplayName("Runs the embedded corpus")
    void testRunsTheCorpus() {
        assertThat(new ContainerScenarios().scenarioDirectory()).isEqualTo("scenarios");
    }

    @Test
    @DisplayName("Without Docker, fails with a plain message")
    void testDockerUnavailableRefused() {
        assertThatThrownBy(() -> ContainerScenarios.requireDocker(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Docker is not available. ContainerScenarios needs a running Docker daemon;"
                        + " the default suite does not.");
    }

    @Test
    @DisplayName("With Docker, does not refuse")
    void testDockerAvailablePasses() {
        assertThatCode(() -> ContainerScenarios.requireDocker(true)).doesNotThrowAnyException();
    }
}
