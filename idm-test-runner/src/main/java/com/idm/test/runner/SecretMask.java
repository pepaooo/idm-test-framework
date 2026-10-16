package com.idm.test.runner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Hides {@code secrets.env} values in text bound for the console or {@code run.log} (ITF-23): every
 * occurrence of a value becomes {@code ****}. Values shorter than {@link #MIN_LENGTH} characters
 * are left alone: replacing a one- or two-character value everywhere would garble unrelated text
 * (every {@code 1} in a message), and so short a value is no credential worth hiding.
 */
final class SecretMask {

    static final int MIN_LENGTH = 4;
    static final String MASK = "****";

    private final List<String> values;

    SecretMask(Collection<String> values) {
        // Longest first, so a value that contains another is replaced whole.
        this.values = values.stream()
                .filter(value -> value != null && value.length() >= MIN_LENGTH)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
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
}
