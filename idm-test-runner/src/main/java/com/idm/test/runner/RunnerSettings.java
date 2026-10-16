package com.idm.test.runner;

import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings the runner hands to Spring. Precedence, highest first: command-line options, real
 * environment variables, {@code secrets.env}, {@code config/application-<profile>.yml},
 * {@code config/application.yml}, built-in defaults.
 */
public final class RunnerSettings {

    /** Name of the property source holding the {@code secrets.env} values. */
    public static final String SECRETS_SOURCE_NAME = "secretsEnv";

    private RunnerSettings() {
    }

    /** Default properties: the workspace's {@code config/} folder, the profile and (optionally) the locale. */
    public static Map<String, Object> defaultProperties(Workspace ws, String profile, String locale) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.config.additional-location",
                "optional:file:" + ws.config().toAbsolutePath().normalize() + "/");
        if (profile != null) {
            properties.put("spring.profiles.active", profile);
        }
        if (locale != null) {
            properties.put("idm.test.report.locale", locale);
        }
        return properties;
    }

    /** The {@code secrets.env} values as a property source, to sit just after the environment variables. */
    public static PropertySource<?> secrets(Map<String, String> values) {
        return new MapPropertySource(SECRETS_SOURCE_NAME, new LinkedHashMap<String, Object>(values));
    }
}
