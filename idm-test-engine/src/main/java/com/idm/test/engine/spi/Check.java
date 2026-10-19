package com.idm.test.engine.spi;

import com.idm.test.engine.result.Secrets;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One comparison a verify step made: what it looked at, the value it expected, the value it found
 * and whether they matched. A finding shows its step's checks as an expected-vs-found table.
 * <p>
 * Values are rendered text. Under a secret-looking name ({@link Secrets#isSecret}) neither value is
 * ever kept: each present one is {@value #MASK}.
 *
 * @param name     what was checked: an attribute, a column, {@code row count}, {@code status}, a body
 *                 path, or a check expression
 * @param expected the expected value as text
 * @param found    the value found as text, or null when there was none (an absent attribute, column
 *                 or field)
 * @param matched  whether the found value satisfied the expectation
 */
public record Check(String name, String expected, String found, boolean matched) {

    /** What stands in for a value a check must not show. */
    public static final String MASK = "••••";

    /**
     * A comparison of an expected value against a found one, both rendered as text; masked when
     * {@code name} looks secret.
     */
    public static Check compared(String name, Object expected, Object found, boolean matched) {
        boolean secret = Secrets.isSecret(name);
        return new Check(name, secret ? mask(expected) : text(expected), secret ? mask(found) : text(found), matched);
    }

    /**
     * A condition that should have been true: {@code expected} is {@code true}, {@code found} what
     * it gave (or the error it threw); {@code found} is masked when {@code name} looks secret.
     */
    public static Check condition(String name, String found, boolean matched) {
        return new Check(name, "true", Secrets.isSecret(name) ? mask(found) : found, matched);
    }

    /**
     * A value as text: null stays null; a map as {@code key value} pairs joined by {@code "; "}
     * (so an LDAP matcher reads {@code contains cn=a,ou=g; count 2}); anything else as
     * {@link String#valueOf}, so a list reads {@code [a, b]}.
     */
    public static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> entry.getKey() + " " + text(entry.getValue()))
                    .collect(Collectors.joining("; "));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(Check::text).collect(Collectors.joining(", ", "[", "]"));
        }
        return String.valueOf(value);
    }

    private static String mask(Object value) {
        return value == null ? null : MASK;
    }
}
