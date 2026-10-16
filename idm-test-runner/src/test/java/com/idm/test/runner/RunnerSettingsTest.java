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
        app.setDefaultProperties(RunnerSettings.defaultProperties(ws, "lab", null));
        ApplicationContextInitializer<ConfigurableApplicationContext> fakeEnvironment = context ->
                context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("IDM_TEST_D", "env")));
        app.addInitializers(fakeEnvironment,
                new SecretsInitializer(SecretsEnv.read(ws.secretsFile())));
        return app.run(commandLineArg == null ? new String[0] : new String[] {commandLineArg});
    }
}
