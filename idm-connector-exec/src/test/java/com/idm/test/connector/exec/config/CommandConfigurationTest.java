package com.idm.test.connector.exec.config;

import com.idm.test.connector.exec.client.CommandDefinition;
import com.idm.test.connector.exec.client.RegisteredCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommandConfigurationTest {

    private static final String YAML = """
            idm:
              test:
                commands:
                  driver-state:
                    label: Driver state
                    executable: /opt/novell/eDirectory/bin/dxcmd
                    args: ["-user", "admin.sa.system", "-password", "${IDM_DXCMD_PASSWORD}", "-getstate", "{driver}"]
                    params:
                      driver: "[A-Za-z0-9 ._=,-]+"
                    env:
                      DXCMD_HOME: /opt/dxcmd
                      Mixed_Case: kept
                    working-dir: /tmp
                    timeout: 30s
                    max-output: 64KB
                    exit-codes: [0, 1]
                    encoding: UTF-8
                  plain:
                    executable: java
            """;

    /** The settings as the runner loads them: the YAML, then {@code secrets} as another source. */
    private static StandardEnvironment environment(Map<String, Object> secrets) throws IOException {
        return environment(YAML, secrets);
    }

    private static StandardEnvironment environment(String yaml, Map<String, Object> secrets) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("profile", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)))
                .forEach(source -> environment.getPropertySources().addLast(source));
        environment.getPropertySources().addFirst(new MapPropertySource("secrets", secrets));
        return environment;
    }

    private static Map<String, RegisteredCommand> registered(StandardEnvironment environment) {
        CommandProperties properties = Binder.get(environment).bind("idm.test", CommandProperties.class)
                .orElseGet(CommandProperties::new);
        return CommandConfiguration.registered(properties, environment);
    }

    @Test
    @DisplayName("Should bind every setting, resolving secrets for the run and keeping environment names as written")
    void testBindsEverything() throws IOException {
        CommandDefinition definition = registered(environment(Map.of("IDM_DXCMD_PASSWORD", "fake-pw-1")))
                .get("driver-state").definition();

        assertThat(definition.executable()).isEqualTo("/opt/novell/eDirectory/bin/dxcmd");
        assertThat(definition.args()).containsExactly("-user", "admin.sa.system", "-password", "fake-pw-1",
                "-getstate", "{driver}");
        assertThat(definition.params()).containsExactly(Map.entry("driver", "[A-Za-z0-9 ._=,-]+"));
        assertThat(definition.env()).containsOnlyKeys("DXCMD_HOME", "Mixed_Case");
        assertThat(definition.workingDir()).isEqualTo("/tmp");
        assertThat(definition.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(definition.maxOutput()).isEqualTo(64 * 1024);
        assertThat(definition.exitCodes()).containsExactly(0, 1);
        assertThat(definition.encoding()).isEqualTo("UTF-8");
    }

    @Test
    @DisplayName("Should apply the defaults to a command that gives only its executable")
    void testDefaults() throws IOException {
        CommandDefinition plain = registered(environment(Map.of("IDM_DXCMD_PASSWORD", "x"))).get("plain").definition();

        assertThat(plain.args()).isEmpty();
        assertThat(plain.params()).isEmpty();
        assertThat(plain.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(plain.maxOutput()).isEqualTo(64 * 1024);
        assertThat(plain.exitCodes()).containsExactly(0);
        assertThat(plain.workingDir()).isNull();
    }

    @Test
    @DisplayName("Should hash the definition as written: a new secret value keeps the approval, a new argument does not")
    void testHashIgnoresSecretValues() throws IOException {
        String one = registered(environment(Map.of("IDM_DXCMD_PASSWORD", "fake-pw-1"))).get("driver-state").hash();
        String two = registered(environment(Map.of("IDM_DXCMD_PASSWORD", "fake-pw-2"))).get("driver-state").hash();
        StandardEnvironment changed = environment(YAML.replace("\"-getstate\"", "\"-stop\""),
                Map.of("IDM_DXCMD_PASSWORD", "fake-pw-1"));
        String three = registered(changed).get("driver-state").hash();

        assertThat(one).isEqualTo(two);
        assertThat(three).isNotEqualTo(one);
    }

    @Test
    @DisplayName("Should have no commands when none are configured")
    void testNone() {
        StandardEnvironment empty = new StandardEnvironment();
        assertThat(registered(empty)).isEmpty();
        assertThat(List.copyOf(registered(empty).keySet())).isEmpty();
    }
}
