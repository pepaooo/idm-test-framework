package com.idm.test.connector.rest.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Normalises HTTP response header names so a scenario can read them without guessing how a
 * server, or the HTTP client, spelled them.
 */
public final class HeaderNames {

    private HeaderNames() {
    }

    /**
     * Returns a new map whose names are lower-cased ({@link Locale#ROOT}), with the values of names
     * that differ only in case merged in encounter order, and with HTTP/2 pseudo-headers (names
     * starting with {@code :}, such as {@code :status}) dropped. Entry order follows the first
     * occurrence of each name.
     *
     * @param headers the headers as received
     * @return a new {@link LinkedHashMap}; the input is not modified
     */
    public static Map<String, List<String>> normalize(Map<String, List<String>> headers) {
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name.startsWith(":")) {
                return;
            }
            normalized.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                    .addAll(values);
        });
        return normalized;
    }
}
