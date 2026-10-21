package com.idm.test.runner.ui.lab;

import com.idm.test.runner.ui.WorkspaceFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sets values in a workspace's {@code secrets.env} as {@link com.idm.test.runner.SecretsEnv} reads it:
 * a key's line (the last one, the one that wins, when it appears twice) gets the new value in place,
 * keeping its indentation; a new key is appended at the end. Comments, blank lines, order, each line's
 * ending and a byte order mark stay. The file is written atomically under the lock editor saves take; a
 * new file is readable by its owner only where the file system has POSIX permissions, and an existing
 * file keeps its permissions. A value with a line break is refused, as is a key that is not a variable
 * name. Messages never contain a value.
 */
public final class SecretsEnvEditor {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SecretsEnvEditor() {
    }

    /**
     * Sets each of {@code values} in {@code file}, creating it when missing.
     *
     * @throws IllegalArgumentException for a value with a line break or a malformed key; nothing is written
     */
    public static void set(Path file, Map<String, String> values) throws IOException {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!NAME.matcher(entry.getKey()).matches()) {
                throw new IllegalArgumentException("'" + entry.getKey() + "' is not a variable name");
            }
            String value = entry.getValue();
            if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("the value of " + entry.getKey() + " has a line break");
            }
        }
        WorkspaceFiles.locked(file, () -> {
            String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            WorkspaceFiles.writeAtomically(file, edit(text, values).getBytes(StandardCharsets.UTF_8),
                    PosixFilePermissions.fromString("rw-------"));
            return null;
        });
    }

    /** {@code text} with {@code values} set, everything else as it was. */
    static String edit(String text, Map<String, String> values) {
        boolean bom = text.startsWith("﻿");
        String body = bom ? text.substring(1) : text;
        List<String> lines = new ArrayList<>();
        List<String> endings = new ArrayList<>();
        String eol = null;
        int start = 0;
        while (start < body.length()) {
            int nl = body.indexOf('\n', start);
            if (nl < 0) {
                lines.add(body.substring(start));
                endings.add("");
                break;
            }
            boolean crlf = nl > start && body.charAt(nl - 1) == '\r';
            if (eol == null) {
                eol = crlf ? "\r\n" : "\n";
            }
            lines.add(body.substring(start, crlf ? nl - 1 : nl));
            endings.add(crlf ? "\r\n" : "\n");
            start = nl + 1;
        }
        if (eol == null) {
            eol = "\n";
        }
        Map<String, String> pending = new LinkedHashMap<>(values);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            int last = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (entry.getKey().equals(key(lines.get(i)))) {
                    last = i;
                }
            }
            if (last >= 0) {
                String line = lines.get(last);
                lines.set(last, line.substring(0, line.indexOf('=') + 1) + entry.getValue());
                pending.remove(entry.getKey());
            }
        }
        if (!pending.isEmpty() && !lines.isEmpty() && endings.getLast().isEmpty()) {
            endings.set(endings.size() - 1, eol);
        }
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            lines.add(entry.getKey() + "=" + entry.getValue());
            endings.add(eol);
        }
        StringBuilder out = new StringBuilder(bom ? "﻿" : "");
        for (int i = 0; i < lines.size(); i++) {
            out.append(lines.get(i)).append(endings.get(i));
        }
        return out.toString();
    }

    /** The key of a {@code KEY=VALUE} line as {@code SecretsEnv} reads it; null for comments and blank lines. */
    private static String key(String line) {
        String stripped = line.startsWith("﻿") ? line.substring(1).stripLeading() : line.stripLeading();
        if (stripped.isEmpty() || stripped.startsWith("#")) {
            return null;
        }
        int eq = stripped.indexOf('=');
        return eq < 0 ? null : stripped.substring(0, eq).trim();
    }
}
