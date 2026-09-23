package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RealEnvironmentScenarios} is only useful while it lacks the three things that pin
 * {@code BaseIntegrationTest} to the embedded infrastructure. Adding any of them back would
 * look like a harmless tidy-up and would restore the exact bug ITF-12 fixed: a run that
 * appears to test a real environment while hitting the embedded one.
 * <p>
 * Stated as a test rather than a convention, in the spirit of {@code NoSpringDependencyTest},
 * because conventions erode.
 */
@DisplayName("Real-environment runner structure")
class RealEnvironmentScenariosStructureTest {

    private static final Class<?> RUNNER = RealEnvironmentScenarios.class;
    private static final Path SOURCE =
            Path.of("src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java");

    @Test
    @DisplayName("Extends nothing, so it inherits no embedded wiring")
    void testExtendsNothing() {
        assertThat(RUNNER.getSuperclass()).isEqualTo(Object.class);
    }

    @Test
    @DisplayName("Pins no profile")
    void testNoActiveProfiles() {
        assertThat(MergedAnnotations.from(RUNNER, SearchStrategy.TYPE_HIERARCHY).isPresent(ActiveProfiles.class))
                .as("@ActiveProfiles on RealEnvironmentScenarios would stop IDM_TEST_PROFILE reaching Spring")
                .isFalse();
    }

    @Test
    @DisplayName("Overrides no property")
    void testNoDynamicPropertySource() {
        assertThat(Arrays.stream(RUNNER.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(DynamicPropertySource.class))
                .map(Method::getName))
                .as("@DynamicPropertySource beats every profile, environment variable and system property")
                .isEmpty();

        assertThat(MergedAnnotations.from(RUNNER, SearchStrategy.TYPE_HIERARCHY).isPresent(TestPropertySource.class))
                .as("@TestPropertySource would override profile and environment-variable values too")
                .isFalse();

        assertThat(RUNNER.getAnnotation(SpringBootTest.class).properties())
                .as("@SpringBootTest(properties=...) would override profile and environment-variable values too")
                .isEmpty();

        assertThat(RUNNER.getAnnotation(SpringBootTest.class).args())
                .as("@SpringBootTest(args=...) would override profile and environment-variable values too")
                .isEmpty();
    }

    @Test
    @DisplayName("Starts no embedded server")
    void testNoEmbeddedServerReferences() throws IOException {
        String source = Files.readString(SOURCE);

        assertThat(source)
                .doesNotContain("EmbeddedLdapServer")
                .doesNotContain("WireMock");
    }

    @Test
    @DisplayName("Is named so the default build does not run it")
    void testNotPickedUpBySurefireDefaults() {
        // The parent POM's surefire includes are **/*Test.java and **/*Tests.java. That is
        // the only thing keeping this runner, which needs a real environment, out of CI.
        assertThat(RUNNER.getSimpleName())
                .doesNotEndWith("Test")
                .doesNotEndWith("Tests");
    }
}
