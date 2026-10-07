package com.idm.test.connector.ldap.client;

import java.util.List;
import java.util.Locale;

/**
 * Attribute names whose values must never appear in a message or log. Mirrors the list in the
 * engine's {@code com.idm.test.engine.result.Secrets}, which this module cannot depend on; keep
 * the two in step.
 */
final class SecretNames {

    private static final List<String> NAMES = List.of("password", "passwd", "pwd", "secret", "token",
            "authorization", "credential", "apikey", "api_key", "api-key", "cookie");

    private SecretNames() {
    }

    static boolean isSecret(String attribute) {
        String lower = attribute.toLowerCase(Locale.ROOT);
        return NAMES.stream().anyMatch(lower::contains);
    }
}
