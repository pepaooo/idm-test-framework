package com.idm.test.connector.rest.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Makes an OAuth2 token failure's text safe to put in a step message and the results file:
 * credentials in a URL, an echoed {@code Authorization} value and the registration's client
 * secret are all masked, whitespace is collapsed, and the text is capped, because a token
 * server's error body can be a whole HTML page.
 * <p>
 * The rules run in a fixed order, and it matters:
 * <ol>
 *   <li>a null text becomes {@code ""};</li>
 *   <li>URL userinfo ({@code scheme://user:pass@}) is masked to {@code scheme://****@};</li>
 *   <li>an echoed {@code Authorization} value ({@code Basic}/{@code Bearer} plus its token) is
 *       masked to {@code Basic ****} / {@code Bearer ****};</li>
 *   <li>the literal client secret, and its {@link URLEncoder}-encoded form, are masked;</li>
 *   <li>whitespace is collapsed and trimmed;</li>
 *   <li>the result is capped to {@link #MAX_LENGTH} characters.</li>
 * </ol>
 * The URL and {@code Authorization} rules run <em>before</em> the secret rule on purpose: a
 * token server that echoes the request back can show the secret URL-encoded in the request
 * body, or recoverable from the Basic header Spring builds from the URL-encoded client id and
 * secret. Running the generic rules first catches both forms even before the literal-secret
 * rule looks at what is left; it also means a trivial secret that happens to be a substring of
 * a URL scheme (e.g. {@code "s"} inside {@code https}) cannot corrupt the URL match, since the
 * URL rule has already run. Masking happens before the length cut, so truncation can never
 * leave part of a secret behind.
 */
final class TokenFailureText {

    static final int MAX_LENGTH = 300;

    private static final String MASK = "****";
    private static final Pattern URL_USER_INFO =
            Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^/\\s\"'<>]+@");
    private static final Pattern AUTHORIZATION_VALUE =
            Pattern.compile("(?i)\\b(Basic|Bearer)\\s+[A-Za-z0-9+/=._~-]{8,}");
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
        safe = URL_USER_INFO.matcher(safe).replaceAll("$1" + MASK + "@");
        safe = AUTHORIZATION_VALUE.matcher(safe).replaceAll("$1 " + MASK);
        if (clientSecret != null && !clientSecret.isEmpty()) {
            safe = safe.replace(clientSecret, MASK);
            String encoded = URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
            if (!encoded.equals(clientSecret)) {
                safe = safe.replace(encoded, MASK);
            }
        }
        safe = WHITESPACE.matcher(safe).replaceAll(" ").trim();
        if (safe.length() > MAX_LENGTH) {
            safe = safe.substring(0, MAX_LENGTH) + "…";
        }
        return safe;
    }
}
