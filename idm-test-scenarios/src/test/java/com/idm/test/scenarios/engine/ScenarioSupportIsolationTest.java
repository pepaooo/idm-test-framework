package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real-environment and container runners reuse the scenario helpers without touching the
 * embedded test base. {@code BaseIntegrationTest}'s static initialisers start two embedded LDAP
 * servers and WireMock, so a runner that merely loaded it would start them too (ITF-17).
 * <p>
 * Checked in a fresh class loader, because in the shared test JVM other tests have usually
 * loaded {@code BaseIntegrationTest} already.
 */
@DisplayName("Scenario helpers load no embedded test base")
class ScenarioSupportIsolationTest {

    private static final String PACKAGE = "com.idm.test.scenarios.engine.";
    private static final String BASE = "com.idm.test.scenarios.base.BaseIntegrationTest";
    private static final String YAML_TEST = PACKAGE + "YamlScenarioTest";

    /** A class loader over the test classpath that shares no application class with this JVM's. */
    private static final class IsolatedLoader extends URLClassLoader {

        IsolatedLoader() {
            super(classpath(), ClassLoader.getPlatformClassLoader());
        }

        boolean hasLoaded(String name) {
            return findLoadedClass(name) != null;
        }

        private static URL[] classpath() {
            return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                    .filter(entry -> !entry.isBlank())
                    .map(entry -> {
                        try {
                            return Path.of(entry).toUri().toURL();
                        } catch (MalformedURLException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toArray(URL[]::new);
        }
    }

    @Test
    @DisplayName("The runners' helper path never loads BaseIntegrationTest or YamlScenarioTest")
    void testHelperPathLoadsNoEmbeddedBase() throws Exception {
        try (IsolatedLoader loader = new IsolatedLoader()) {
            Class.forName(PACKAGE + "RealEnvironmentScenarios", true, loader);
            Class.forName(PACKAGE + "ContainerScenarios", true, loader);

            Class<?> support = Class.forName(PACKAGE + "ScenarioSupport", true, loader);
            List<?> files = (List<?>) invoke(support, "scenarioFiles", new Class<?>[]{String.class}, "scenarios");
            invoke(support, "select", new Class<?>[]{List.class, String.class, String.class}, files, null, "scenarios");

            Class<?> restProperties = Class.forName("com.idm.test.connector.rest.config.RestProperties", true, loader);
            Class<?> authenticator = Class.forName("com.idm.test.connector.rest.client.RestAuthenticator", true, loader);
            Object properties = restProperties.getConstructor().newInstance();
            Object withoutOAuth = authenticator.getMethod("withoutOAuth").invoke(null);
            Method registry = Arrays.stream(support.getDeclaredMethods())
                    .filter(method -> method.getName().equals("registry"))
                    .findFirst().orElseThrow();
            registry.setAccessible(true);
            registry.invoke(null, null, null, null, null, null, properties, withoutOAuth);

            assertThat(files).as("the helper path really ran").isNotEmpty();
            assertThat(loader.hasLoaded(BASE)).as("BaseIntegrationTest loaded").isFalse();
            assertThat(loader.hasLoaded(YAML_TEST)).as("YamlScenarioTest loaded").isFalse();
        }
    }

    @Test
    @DisplayName("Positive control: loading YamlScenarioTest does load BaseIntegrationTest")
    void testCheckCanFail() throws Exception {
        try (IsolatedLoader loader = new IsolatedLoader()) {
            // initialize = false: loading resolves the superclass without running its static
            // initialisers, so no embedded server starts here.
            Class.forName(YAML_TEST, false, loader);

            assertThat(loader.hasLoaded(BASE)).isTrue();
        }
    }

    @Test
    @DisplayName("Neither runner, nor ScenarioSupport, refers to the embedded test base in its bytecode")
    void testBytecodeReferencesNoEmbeddedBase() throws IOException {
        for (String runner : List.of("RealEnvironmentScenarios", "ContainerScenarios", "ScenarioSupport")) {
            String bytecode = classBytes(PACKAGE + runner);

            assertThat(bytecode).as(runner)
                    .doesNotContain("com/idm/test/scenarios/base/BaseIntegrationTest")
                    .doesNotContain("com/idm/test/scenarios/engine/YamlScenarioTest");
        }
    }

    private static Object invoke(Class<?> type, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    /** A class file's bytes as ISO-8859-1 text, so constant-pool class names can be searched. */
    private static String classBytes(String className) throws IOException {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream in = ScenarioSupportIsolationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
