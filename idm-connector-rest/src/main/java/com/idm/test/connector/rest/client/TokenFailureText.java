package com.idm.test.connector.rest.client;

import java.util.regex.Pattern;

/**
 * Makes an OAuth2 token failure's text safe to put in a step message and the results file:
 * the registration's client secret and any credentials in a URL are masked, whitespace is
 * collapsed, and the text is capped, because a token server's error body can be a whole HTML
 * page. Masking happens before the cut, so truncation can never leave part of a secret behind.
 */
final class TokenFailureText {

    static final int MAX_LENGTH = 300;

    private static final String MASK = "****";
    private static final Pattern URL_USER_INFO =
            Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^/\\s@\"'<>]+@");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TokenFailureText() {
    }

    /**
     * @param text         the text to make safe; null gives ""
     * @param clientSecret the secret to mask, or null/empty when there is none
     * @return the masked, collapsed and capped text; never null
     */
    static String safe(String text, String clientSecret) {
        if (text == null) {
            return "";
        }
        String safe = text;
        if (clientSecret != null && !clientSecret.isEmpty()) {
            safe = safe.replace(clientSecret, MASK);
        }
        safe = URL_USER_INFO.matcher(safe).replaceAll("$1" + MASK + "@");
        safe = WHITESPACE.matcher(safe).replaceAll(" ").trim();
        if (safe.length() > MAX_LENGTH) {
            safe = safe.substring(0, MAX_LENGTH) + "…";
        }
        return safe;
    }
}
