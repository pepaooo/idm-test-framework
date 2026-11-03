package com.idm.test.engine.result;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one rule for what counts as a secret, shared by the results file's and the report's parameter
 * masking and by step evidence, so they can never disagree.
 */
public final class Secrets {

    public static final String MASK = "***";

    /** A name is a secret when, lower-cased, it contains one of these. */
    public static final List<String> NAMES = List.of("password", "passwd", "pwd", "secret", "token",
            "authorization", "credential", "apikey", "api_key", "api-key", "cookie");

    private Secrets() {
    }

    /** Whether a key, header, column or attribute name looks like it holds a secret. */
    public static boolean isSecret(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return NAMES.stream().anyMatch(lower::contains);
    }

    /**
     * An unmodifiable deep copy with every value under a secret-looking key, at any depth, replaced
     * by {@value #MASK}. Maps stay maps; lists, sets, other collections and arrays (except
     * {@code byte[]}) become lists. Other values are returned as they are.
     */
    public static Object maskByKey(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, inner) -> {
                String name = String.valueOf(key);
                copy.put(name, isSecret(name) ? MASK : maskByKey(inner));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(inner -> copy.add(maskByKey(inner)));
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray() && !(value instanceof byte[])) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                copy.add(maskByKey(Array.get(value, i)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /**
     * {@code result} with every resolved parameter under a secret-looking key, at any depth, in its
     * steps, cleanup entries and skipped cleanup entries replaced by {@value #MASK}; everything else
     * as it is. Null stays null.
     */
    public static ScenarioResult paramsMasked(ScenarioResult result) {
        if (result == null) {
            return null;
        }
        return new ScenarioResult(result.name(), result.sourceName(), result.success(), result.durationMillis(),
                result.steps() == null ? null : result.steps().stream().map(Secrets::paramsMasked).toList(),
                result.cleanup().stream().map(Secrets::paramsMasked).toList(), result.failureDetail(),
                result.description(), result.failureDetailKey(), result.failureDetailArgs(), result.stopped(),
                result.skippedCleanup().stream().map(Secrets::paramsMasked).toList());
    }

    /** {@code step} with its resolved parameters masked by key, as in {@link #paramsMasked(ScenarioResult)}. */
    public static StepResult paramsMasked(StepResult step) {
        return new StepResult(step.index(), step.type(), byKey(step.resolvedParams()), step.success(),
                step.durationMillis(), step.message(), step.attempts(), step.waitedMillis(), step.description(),
                step.evidence(), step.messageKey(), step.messageArgs(), step.failureKind(), step.waitHistory(),
                step.checks());
    }

    /** {@code entry} with its resolved parameters masked by key, as in {@link #paramsMasked(ScenarioResult)}. */
    public static CleanupResult paramsMasked(CleanupResult entry) {
        return new CleanupResult(entry.index(), entry.type(), byKey(entry.resolvedParams()), entry.status(),
                entry.durationMillis(), entry.message(), entry.description(), entry.messageKey(),
                entry.messageArgs(), entry.failureKind());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> byKey(Map<String, Object> params) {
        return params == null ? null : (Map<String, Object>) maskByKey(params);
    }
}
