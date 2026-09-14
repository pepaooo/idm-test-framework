package com.idm.test.engine.model;

import java.time.Duration;
import java.util.Map;

/**
 * How long an assertion should keep retrying.
 * <p>
 * Waiting is a property of assertions rather than a step of its own: you never wait for its
 * own sake, you wait for something you were going to assert anyway.
 */
public record WaitSpec(Duration timeout, Duration poll) {

    /**
     * Reads the {@code wait} parameter.
     *
     * @param raw            {@code null} for no wait, {@link Boolean#TRUE} for the defaults,
     *                       or a map with {@code timeout} and/or {@code poll}
     * @param defaultTimeout used for {@code wait: true} and for an unspecified timeout
     * @param defaultPoll    used for {@code wait: true} and for an unspecified poll interval
     * @return the parsed spec, or {@code null} when no wait was declared
     */
    public static WaitSpec parse(Object raw, Duration defaultTimeout, Duration defaultPoll) {
        if (raw == null || Boolean.FALSE.equals(raw)) {
            return null;
        }
        if (Boolean.TRUE.equals(raw)) {
            return new WaitSpec(defaultTimeout, defaultPoll);
        }
        if (raw instanceof Map<?, ?> map) {
            return new WaitSpec(
                    duration(map.get("timeout"), defaultTimeout),
                    duration(map.get("poll"), defaultPoll));
        }
        throw new IllegalArgumentException(
                "wait must be true or a map with timeout and poll, but was: " + raw);
    }

    private static Duration duration(Object value, Duration fallback) {
        return value == null ? fallback : parseDuration(value.toString());
    }

    /**
     * Parses durations written the way a consultant would write them: 30s, 2m, 1h.
     */
    static Duration parseDuration(String text) {
        String trimmed = text.trim();
        if (trimmed.length() < 2) {
            throw new IllegalArgumentException("Not a duration: '" + text + "'");
        }

        char unit = trimmed.charAt(trimmed.length() - 1);
        String number = trimmed.substring(0, trimmed.length() - 1);

        long amount;
        try {
            amount = Long.parseLong(number);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Not a duration: '" + text + "'. Use a number followed by s, m or h.", e);
        }

        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            default -> throw new IllegalArgumentException(
                    "Unknown duration unit '" + unit + "' in '" + text + "'. Use s, m or h.");
        };
    }
}
