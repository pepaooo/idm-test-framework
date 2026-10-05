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
 * The one rule for what counts as a secret, shared by the report's parameter masking and by step
 * evidence, so the two can never disagree.
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
}
