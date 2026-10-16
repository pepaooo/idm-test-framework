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
