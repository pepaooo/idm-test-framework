package com.idm.test.runner;

import com.idm.test.engine.result.CleanupResult;
import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.Secrets;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.SqlEvidence;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hides secret values in text bound for the console, {@code run.log} and the reports (ITF-23): every
 * occurrence of a value becomes {@code ****}. Values shorter than {@link #MIN_LENGTH} characters
 * are left alone: replacing a one- or two-character value everywhere would garble unrelated text
 * (every {@code 1} in a message), and so short a value is no credential worth hiding.
 */
final class SecretMask {

    static final int MIN_LENGTH = 4;
    static final String MASK = "****";

    /**
     * Variables the shell keeps that the key-name rule would call secret ({@code pwd}) but that hold
     * the current folder: masking them would hide every path in the log.
     */
    private static final Set<String> NOT_SECRET = Set.of("PWD", "OLDPWD");

    private final List<String> values;

    SecretMask(Collection<String> values) {
        // Longest first, so a value that contains another is replaced whole.
        this.values = values.stream()
                .filter(value -> value != null && value.length() >= MIN_LENGTH)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    /**
     * The mask for a run: each {@code secrets.env} key's value as the run's settings resolve it (an
     * environment variable or a system property of the same name wins, as in Spring) and the file's
     * own value, plus every environment variable whose name looks secret ({@link Secrets#isSecret}).
     */
    static SecretMask of(Map<String, String> secretsEnv, Map<String, String> environment) {
        // The run's layers in Spring's order, highest first; secrets.env sits just below the environment.
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new PropertiesPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                System.getProperties()));
        sources.addLast(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new LinkedHashMap<String, Object>(environment)));
        sources.addLast(RunnerSettings.secrets(secretsEnv));
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);
        resolver.setIgnoreUnresolvableNestedPlaceholders(true);

        List<String> values = new ArrayList<>(secretsEnv.values());
        for (String key : secretsEnv.keySet()) {
            values.add(resolver.getProperty(key));
        }
        environment.forEach((name, value) -> {
            if (Secrets.isSecret(name) && !NOT_SECRET.contains(name.toUpperCase(Locale.ROOT))) {
                values.add(value);
            }
        });
        return new SecretMask(values);
    }

    String apply(String text) {
        if (text == null) {
            return null;
        }
        String masked = text;
        for (String value : values) {
            masked = masked.replace(value, MASK);
        }
        return masked;
    }

    /** The stack trace of {@code e}, masked. */
    String stackTrace(Throwable e) {
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        return apply(trace.toString());
    }

    /**
     * {@code result} with every text in it masked: failure detail and its arguments, each step's
     * and cleanup entry's message, arguments, description, resolved parameters and evidence.
     * Names and step types stay as they are.
     */
    ScenarioResult result(ScenarioResult result) {
        if (values.isEmpty() || result == null) {
            return result;
        }
        List<StepResult> steps = result.steps() == null ? null
                : result.steps().stream().map(this::step).toList();
        List<CleanupResult> cleanup = result.cleanup().stream().map(this::cleanup).toList();
        return new ScenarioResult(result.name(), result.sourceName(), result.success(), result.durationMillis(),
                steps, cleanup, apply(result.failureDetail()), apply(result.description()),
                result.failureDetailKey(), list(result.failureDetailArgs()));
    }

    /** {@code run} with its endpoint URLs masked. */
    RunInfo runInfo(RunInfo run) {
        if (values.isEmpty() || run == null) {
            return run;
        }
        Map<String, String> endpoints = new LinkedHashMap<>();
        run.endpoints().forEach((label, url) -> endpoints.put(label, apply(url)));
        return new RunInfo(run.runner(), run.startedAt(), run.profiles(), apply(run.scenarioFilter()), endpoints,
                run.locale());
    }

    private StepResult step(StepResult step) {
        return new StepResult(step.index(), step.type(), map(step.resolvedParams()), step.success(),
                step.durationMillis(), apply(step.message()), step.attempts(), step.waitedMillis(),
                apply(step.description()), evidence(step.evidence()), step.messageKey(), list(step.messageArgs()),
                step.failureKind());
    }

    private CleanupResult cleanup(CleanupResult entry) {
        return new CleanupResult(entry.index(), entry.type(), map(entry.resolvedParams()), entry.status(),
                entry.durationMillis(), apply(entry.message()), apply(entry.description()), entry.messageKey(),
                list(entry.messageArgs()), entry.failureKind());
    }

    /** The evidence rebuilt from masked parts; its constructor masks and cuts it again as usual. */
    private Evidence evidence(Evidence evidence) {
        return switch (evidence) {
            case null -> null;
            case HttpEvidence http -> new HttpEvidence(http.method(), apply(http.url()), headers(http.requestHeaders()),
                    value(http.requestBody()), http.status(), headers(http.responseHeaders()),
                    value(http.responseBody()), http.durationMillis(), apply(http.error()));
            case SqlEvidence sql -> new SqlEvidence(sql.database(), apply(sql.sql()), list(sql.params()),
                    rows(sql.rows()), sql.totalRows(), sql.updateCount());
            case LdapEvidence ldap -> new LdapEvidence(ldap.directory(), apply(ldap.dn()), ldap.operation(),
                    map(ldap.attributes()), ldap.present());
        };
    }

    private Map<String, List<String>> headers(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, List<String>> masked = new LinkedHashMap<>();
        headers.forEach((name, values) -> masked.put(name, values == null ? null
                : values.stream().map(this::apply).toList()));
        return masked;
    }

    private List<Map<String, Object>> rows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<Map<String, Object>> masked = new ArrayList<>(rows.size());
        rows.forEach(row -> masked.add(map(row)));
        return masked;
    }

    private Map<String, Object> map(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        map.forEach((key, value) -> masked.put(key, value(value)));
        return Collections.unmodifiableMap(masked);
    }

    private List<Object> list(List<Object> list) {
        if (list == null) {
            return null;
        }
        List<Object> masked = new ArrayList<>(list.size());
        list.forEach(value -> masked.add(value(value)));
        return Collections.unmodifiableList(masked);
    }

    /** Text masked, at any depth of maps, collections and message arguments; other values as they are. */
    private Object value(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> apply(text);
            case Message message -> new Message(message.key(), list(message.args()));
            case Map<?, ?> map -> {
                Map<Object, Object> masked = new LinkedHashMap<>();
                map.forEach((key, inner) -> masked.put(key, value(inner)));
                yield Collections.unmodifiableMap(masked);
            }
            case Collection<?> collection -> {
                List<Object> masked = new ArrayList<>(collection.size());
                collection.forEach(inner -> masked.add(value(inner)));
                yield Collections.unmodifiableList(masked);
            }
            default -> value;
        };
    }
}
