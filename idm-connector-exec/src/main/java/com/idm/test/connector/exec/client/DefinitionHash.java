package com.idm.test.connector.exec.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The SHA-256 of a definition as written: what an approval holds. Every field counts, in a fixed order;
 * maps count by sorted key, so their order in the settings does not; each text is length-prefixed, so
 * {@code ["ab"]} and {@code ["a", "b"]} differ. Taken over the definition as written, so a {@code ${NAME}}
 * counts by its name and rotating a secret needs no new approval.
 */
public final class DefinitionHash {

    private DefinitionHash() {
    }

    public static String of(CommandDefinition definition) {
        StringBuilder text = new StringBuilder("idmtest-command-v1\n");
        field(text, "executable", definition.executable());
        list(text, "args", definition.args());
        map(text, "params", definition.params());
        map(text, "env", definition.env());
        field(text, "working-dir", definition.workingDir());
        field(text, "timeout", definition.timeout().toString());
        field(text, "max-output", Long.toString(definition.maxOutput()));
        list(text, "exit-codes", definition.exitCodes().stream().map(String::valueOf).toList());
        field(text, "encoding", definition.encoding());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static void field(StringBuilder text, String name, String value) {
        text.append(name).append('=');
        if (value == null) {
            text.append("-\n");
        } else {
            text.append(value.length()).append(':').append(value).append('\n');
        }
    }

    private static void list(StringBuilder text, String name, List<String> values) {
        text.append(name).append('[').append(values.size()).append("]\n");
        values.forEach(value -> field(text, " ", value));
    }

    private static void map(StringBuilder text, String name, Map<String, String> values) {
        text.append(name).append('{').append(values.size()).append("}\n");
        new TreeMap<>(values).forEach((key, value) -> {
            field(text, " key", key);
            field(text, " value", value);
        });
    }
}
