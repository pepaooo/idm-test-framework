package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
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
