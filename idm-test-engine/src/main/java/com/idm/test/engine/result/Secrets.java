package com.idm.test.engine.result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one rule for what counts as a secret, shared by the report's parameter masking and by step
 * evidence, so the two can never disagree.
 */
public final class Secrets {

    public static final String MASK = "***";

    private static final List<String> SECRET_NAMES = List.of("password", "passwd", "pwd", "secret", "token",
            "authorization", "credential", "apikey", "api_key", "api-key", "cookie");

    private Secrets() {
    }

    /** Whether a key, header, column or attribute name looks like it holds a secret. */
    public static boolean isSecret(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return SECRET_NAMES.stream().anyMatch(lower::contains);
    }

    /** A copy with every value under a secret-looking key, at any depth, replaced by {@value #MASK}. */
    public static Object maskByKey(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, inner) -> {
                String name = String.valueOf(key);
                copy.put(name, isSecret(name) ? MASK : maskByKey(inner));
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(inner -> copy.add(maskByKey(inner)));
            return copy;
        }
        return value;
    }
}
