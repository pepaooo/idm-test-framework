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

    /** Default properties: the workspace's {@code config/} folder as an additional config location. */
    public static Map<String, Object> defaultProperties(Workspace ws) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.config.additional-location",
                "optional:file:" + ws.config().toAbsolutePath().normalize() + "/");
        return properties;
    }

    /**
     * The command-line options that carry {@code --profile} and {@code --locale}; as command-line
     * arguments they beat environment variables and every config file.
     */
    public static String[] commandLineArgs(String profile, String locale) {
        java.util.List<String> args = new java.util.ArrayList<>();
        if (profile != null) {
            args.add("--spring.profiles.active=" + profile);
        }
        if (locale != null) {
            args.add("--idm.test.report.locale=" + locale);
        }
        return args.toArray(String[]::new);
    }

    /** The {@code secrets.env} values as a property source, to sit just after the environment variables. */
    public static PropertySource<?> secrets(Map<String, String> values) {
        return new MapPropertySource(SECRETS_SOURCE_NAME, new LinkedHashMap<String, Object>(values));
    }
}
