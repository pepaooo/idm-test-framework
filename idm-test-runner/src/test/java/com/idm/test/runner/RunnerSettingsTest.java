package com.idm.test.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Runner settings precedence")
class RunnerSettingsTest {

    @Configuration
    static class Empty {
    }

    @Test
    @DisplayName("Should layer environment over secrets.env over the profile file over application.yml")
    void testPrecedence(@TempDir Path temp) throws Exception {
        Workspace ws = workspace(temp, "idm.test.b: profile\nidm.test.c: ${IDM_TEST_C:profile}\n"
                + "idm.test.d: ${IDM_TEST_D:profile}\n");

        try (ConfigurableApplicationContext context = start(ws, null)) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("idm.test.a")).isEqualTo("base");
            assertThat(env.getProperty("idm.test.b")).isEqualTo("profile");
            assertThat(env.getProperty("idm.test.c")).isEqualTo("secret");
            assertThat(env.getProperty("idm.test.d")).isEqualTo("env");
        }
    }

    @Test
    @DisplayName("Should let the command-line locale beat the profile file")
    void testCommandLineLocale(@TempDir Path temp) throws Exception {
        Workspace ws = workspace(temp, "idm.test.report.locale: en\n");

        try (ConfigurableApplicationContext context = start(ws, "--idm.test.report.locale=es")) {
            assertThat(context.getEnvironment().getProperty("idm.test.report.locale")).isEqualTo("es");
        }
    }

    @Test
    @DisplayName("Should keep --profile above SPRING_PROFILES_ACTIVE and spring.profiles.active in application.yml")
    void testProfileBeatsEnvironmentAndConfig(@TempDir Path temp) throws Exception {
        Workspace ws = workspace(temp, "idm.test.b: profile\n");
        Files.writeString(ws.config().resolve("application.yml"),
                "spring.profiles.active: other\nidm.test.a: base\nidm.test.b: base\n");

        try (ConfigurableApplicationContext context = start(ws, null)) {
            assertThat(context.getEnvironment().getProperty("idm.test.b")).isEqualTo("profile");
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("lab");
        }
    }

    @Test
    @DisplayName("Should read settings only from the built-in defaults and the workspace's config/ folder")
    void testConfigLocation(@TempDir Path temp) throws Exception {
        Workspace ws = workspace(temp, "idm.test.b: profile\n");

        assertThat(RunnerSettings.defaultProperties(ws))
                .containsExactly(Map.entry("spring.config.location", "optional:classpath:/,optional:file:"
                        + ws.config().toAbsolutePath().normalize() + "/"));
    }

    @Test
    @DisplayName("Should ignore the current folder's config/ and application.yml when --workspace names another folder")
    void testCurrentFolderIgnored(@TempDir Path temp) throws Exception {
        Path a = Files.createDirectories(temp.resolve("A"));
        Files.createDirectories(a.resolve("scenarios"));
        Files.createDirectories(a.resolve("config"));
        Files.writeString(a.resolve("config/application-lab.yml"), "idm.test.b: from-A\nidm.test.only-a: leaked\n");
        Files.writeString(a.resolve("config/application.yml"), "idm.test.a: from-A\n");
        Files.writeString(a.resolve("application.yml"), "idm.test.root-a: leaked\n");
        Files.writeString(a.resolve("application-lab.yml"), "idm.test.root-lab-a: leaked\n");
        Path b = Files.createDirectories(temp.resolve("B"));
        Files.createDirectories(b.resolve("scenarios"));
        Files.createDirectories(b.resolve("config"));
        Files.writeString(b.resolve("config/application-lab.yml"), "idm.test.b: from-B\n");

        // The current folder is fixed for a JVM, so the run starts in A as a child process.
        Path out = temp.resolve("probe.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classPath(), SettingsProbe.class.getName(), "../B")
                .directory(a.toFile())
                .redirectErrorStream(true)
                .redirectOutput(out.toFile())
                .start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        String output = Files.readString(out);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output.lines().filter(line -> line.startsWith("probe ")).toList()).containsExactly(
                "probe idm.test.b=from-B",
                "probe idm.test.a=null",
                "probe idm.test.only-a=null",
                "probe idm.test.root-a=null",
                "probe idm.test.root-lab-a=null");
    }

    /**
     * This JVM's class path without empty entries: Surefire's ends with a separator, and an empty
     * entry would put the child's current folder on its class path.
     */
    private static String classPath() {
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    /** Starts Spring as the runner does, over the workspace in {@code args[0]}, and prints what it reads. */
    static final class SettingsProbe {

        public static void main(String[] args) {
            SpringApplication app = new SpringApplication(Empty.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
            app.setDefaultProperties(RunnerSettings.defaultProperties(Workspace.open(Path.of(args[0]))));
            try (ConfigurableApplicationContext context = app.run(RunnerSettings.commandLineArgs("lab", null))) {
                for (String key : java.util.List.of("idm.test.b", "idm.test.a", "idm.test.only-a", "idm.test.root-a",
                        "idm.test.root-lab-a")) {
                    System.out.println("probe " + key + "=" + context.getEnvironment().getProperty(key));
                }
            }
        }
    }

    private static Workspace workspace(Path temp, String profileYaml) throws Exception {
        Path dir = Files.createDirectories(temp.resolve("My Tests"));
        Files.createDirectories(dir.resolve("scenarios"));
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "idm.test.a: base\nidm.test.b: base\n");
        Files.writeString(dir.resolve("config/application-lab.yml"), profileYaml);
        Files.writeString(dir.resolve("secrets.env"), "IDM_TEST_C=secret\nIDM_TEST_D=secret\n");
        return Workspace.open(dir);
    }

    private static ConfigurableApplicationContext start(Workspace ws, String commandLineArg) {
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        app.setDefaultProperties(RunnerSettings.defaultProperties(ws));
        ApplicationContextInitializer<ConfigurableApplicationContext> fakeEnvironment = context ->
                context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("IDM_TEST_D", "env", "SPRING_PROFILES_ACTIVE", "other")));
        app.addInitializers(fakeEnvironment,
                new SecretsInitializer(SecretsEnv.read(ws.secretsFile())));
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(RunnerSettings.commandLineArgs("lab", null)));
        if (commandLineArg != null) {
            args.add(commandLineArg);
        }
        return app.run(args.toArray(String[]::new));
    }
}
