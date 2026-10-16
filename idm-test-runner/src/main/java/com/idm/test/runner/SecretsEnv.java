package com.idm.test.runner;

import com.idm.test.engine.spi.Message;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads {@code secrets.env}: {@code KEY=VALUE} lines, {@code #} comments and blank lines. */
public final class SecretsEnv {

    private SecretsEnv() {
    }

    /** The values of {@code file}; empty when it does not exist. Errors name the line, never its content. */
    public static Map<String, String> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("﻿")) {
                line = line.substring(1);
            }
            String stripped = line.stripLeading();
            if (stripped.isBlank() || stripped.startsWith("#")) {
                continue;
            }
            int eq = stripped.indexOf('=');
            String key = eq < 0 ? "" : stripped.substring(0, eq).trim();
            if (key.isEmpty()) {
                throw new UsageException(Message.of("runner.secrets.malformed", String.valueOf(i + 1)));
            }
            values.put(key, stripped.substring(eq + 1));
        }
        return values;
    }
}
