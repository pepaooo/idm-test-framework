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

    /**
     * Default properties: the config locations are the runner's built-in {@code application.yml} and
     * the workspace's {@code config/} folder, and nothing else. They replace Spring Boot's defaults,
     * which would also read {@code ./} and {@code ./config/} of the current folder: another
     * workspace's settings when the run names {@code --workspace}.
     */
    public static Map<String, Object> defaultProperties(Workspace ws) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.config.location",
                "optional:classpath:/,optional:file:" + ws.config().toAbsolutePath().normalize() + "/");
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
