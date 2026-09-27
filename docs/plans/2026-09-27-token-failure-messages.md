# Token-Failure Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Token-request failures in `RestAuthenticator` report their real cause, with secrets masked and length capped, and a malformed token URI is caught up front as a configuration error.

**Architecture:**
- **A new package-private helper,** `TokenFailureText.safe(text, clientSecret)`, masks the client secret and URL credentials, collapses whitespace and truncates.
- **`RestAuthenticator.bearer()`:**
  - validates the provider's token URI before calling Spring
  - routes both failure paths' text through `safe(...)`

**Tech Stack:** Java 21, Spring Security OAuth2 Client 6.5.5, JUnit 5, AssertJ, WireMock.

**Spec:** `docs/specs/2026-09-27-token-failure-messages-design.md` (commit 5994d31). **Ticket:** ITF-27.

## Global Constraints

- **`TokenFailureText`** is `final class TokenFailureText` in package `com.idm.test.connector.rest.client` (package-private). Its only non-private member is `static String safe(String text, String clientSecret)`, plus the constant `static final int MAX_LENGTH = 300`. It never throws.
- **`safe` applies, in this order:**
  1. a null text becomes `""`
  2. a non-null, non-empty `clientSecret` is replaced literally, every occurrence, with `****`
  3. the regex `(?i)\b([a-z][a-z0-9+.-]*://)[^/\s@"'<>]+@` is replaced with `$1****@`
  4. each whitespace run becomes one space, and the result is trimmed
  5. text longer than 300 characters is cut to the first 300, and `…` (U+2026) is appended
- **A token URI is valid only if** `URI.create` succeeds, the URI is absolute, the scheme is `http`/`https` (any case), and the host is non-null.
- **The invalid-URI message is exactly:**
  `OAuth2 registration '<id>' (target '<target>') has a token-uri that is not an absolute http(s) URL: '<safe(tokenUri)>'. Fix its provider's token-uri (spring.security.oauth2.client.provider.<name>.token-uri).`
  Its type is `ErrorType.CONFIGURATION_ERROR`, and `<name>` is written literally.
- **The failure prefix stays:** `OAuth2 token request for registration '<id>' (target '<target>') failed: `.
  - The Spring path appends `[<code>] <safe(description)>`, or `[<code>]` when the description is null or blank.
  - The catch-all appends `<SimpleClassName>: <safe(message)>`, or `<SimpleClassName>` when the message is null or blank.
- **Classification is unchanged:** both catch paths keep `tokenFailureType(e)`.
- **No password, client secret, token or `Authorization` value appears in any message** (ITF-23 rule).
- **Commits** end with a blank line, `ITF-27`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. Green means 0 failures, errors and skips.

---

### Task 1: `TokenFailureText.safe`

**Files:**
- Create: `idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/TokenFailureText.java`
- Test: `idm-connector-rest/src/test/java/com/idm/test/connector/rest/client/TokenFailureTextTest.java`

**Interfaces:**
- Produces: `static String TokenFailureText.safe(String text, String clientSecret)` and `static final int TokenFailureText.MAX_LENGTH = 300`, both package-private.

- [ ] **Step 1: Write the failing tests**

```java
package com.idm.test.connector.rest.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenFailureText")
class TokenFailureTextTest {

    @Test
    @DisplayName("A null text becomes empty")
    void testNull() {
        assertThat(TokenFailureText.safe(null, "s3cret")).isEmpty();
    }

    @Test
    @DisplayName("Every occurrence of the client secret is masked, even inside a longer word")
    void testSecretMasked() {
        assertThat(TokenFailureText.safe("got s3cret and again s3cret; xs3cretx", "s3cret"))
                .isEqualTo("got **** and again ****; x****x");
    }

    @Test
    @DisplayName("A secret containing regex characters is replaced literally")
    void testSecretLiteral() {
        assertThat(TokenFailureText.safe("secret is a.b*c", "a.b*c")).isEqualTo("secret is ****");
        assertThat(TokenFailureText.safe("secret is axbbc", "a.b*c")).isEqualTo("secret is axbbc");
    }

    @Test
    @DisplayName("A null or empty secret leaves the text alone")
    void testNoSecret() {
        assertThat(TokenFailureText.safe("plain text", null)).isEqualTo("plain text");
        assertThat(TokenFailureText.safe("plain text", "")).isEqualTo("plain text");
    }

    @Test
    @DisplayName("Credentials in a URL are masked; a URL without them is unchanged")
    void testUserInfoMasked() {
        assertThat(TokenFailureText.safe("POST \"https://user:pa55@host.example/x\" failed", null))
                .isEqualTo("POST \"https://****@host.example/x\" failed");
        assertThat(TokenFailureText.safe("FTP://svc@host/x", null)).isEqualTo("FTP://****@host/x");
        assertThat(TokenFailureText.safe("https://host.example/x?a=b", null)).isEqualTo("https://host.example/x?a=b");
    }

    @Test
    @DisplayName("An email-like value with no scheme is unchanged")
    void testEmailUnchanged() {
        assertThat(TokenFailureText.safe("contact a@b.c", null)).isEqualTo("contact a@b.c");
    }

    @Test
    @DisplayName("Whitespace runs, newlines included, collapse to one space")
    void testWhitespace() {
        assertThat(TokenFailureText.safe("  <html>\n\t  <body>  hi </body>\r\n</html>  ", null))
                .isEqualTo("<html> <body> hi </body> </html>");
    }

    @Test
    @DisplayName("Exactly 300 characters are kept; 301 are cut to 300 plus an ellipsis")
    void testLength() {
        String exact = "a".repeat(TokenFailureText.MAX_LENGTH);
        assertThat(TokenFailureText.safe(exact, null)).isEqualTo(exact);
        assertThat(TokenFailureText.safe(exact + "b", null)).isEqualTo(exact + "…");
    }

    @Test
    @DisplayName("The secret is masked before truncation, so a cut can never expose part of it")
    void testMaskBeforeCut() {
        String text = "a".repeat(295) + "s3cret-value-long";
        assertThat(TokenFailureText.safe(text, "s3cret-value-long"))
                .isEqualTo("a".repeat(295) + "****")
                .doesNotContain("s3cret");
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail to compile**

Run: `./mvnw test -pl idm-connector-rest -am -Dtest=TokenFailureTextTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `cannot find symbol: class TokenFailureText`.

- [ ] **Step 3: Implement**

```java
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
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./mvnw test -pl idm-connector-rest -am -Dtest=TokenFailureTextTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Falsification**

Make each change below, re-run, confirm that the named test fails, then revert:
- Use `replaceAll(clientSecret, MASK)` (regex) instead of `replace`. `testSecretLiteral` fails.
- Move the truncation before the secret masking. `testMaskBeforeCut` fails.
- Drop the `(?i)`. `testUserInfoMasked` fails on `FTP://`.
- Change `>` to `>=` in the length check. `testLength` fails.

- [ ] **Step 6: Commit**

```bash
git add idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/TokenFailureText.java \
        idm-connector-rest/src/test/java/com/idm/test/connector/rest/client/TokenFailureTextTest.java
git commit -m "feat: mask secrets and cap length in token-failure text

ITF-27
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `RestAuthenticator` validates the token URI and reports safe causes

**Files:**
- Modify: `idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/RestAuthenticator.java` (`bearer()`, around lines 95-130)
- Modify: `docs/architecture/capabilities.md` (the "REST authentication" row)
- Test: `idm-connector-rest/src/test/java/com/idm/test/connector/rest/client/RestAuthenticatorTest.java`

**Interfaces:**
- Consumes (Task 1): `TokenFailureText.safe(String text, String clientSecret)`.
- Produces: `static boolean RestAuthenticator.isHttpUrl(String value)`, package-private.

- [ ] **Step 1: Write the failing tests**

Add to `RestAuthenticatorTest`. The helpers `oauth(name)`, `withRegistration(id)` and `withTokenUri(uri)` exist, and both registrations use the client secret `"svc-secret-value"`. `wiremock` is the test's `WireMockServer`.

```java
    @Test
    @DisplayName("A malformed or non-http token-uri is a configuration error, caught before any request")
    void testInvalidTokenUri() {
        for (String uri : new String[] {"not-a-url", "ftp://example.org/token", "http://bad host/token"}) {
            OperationResult<String> result = withTokenUri(uri).authorization("api", oauth("svc"));

            assertThat(result.isFailure()).as(uri).isTrue();
            assertThat(result.getErrorType()).as(uri).contains(ErrorType.CONFIGURATION_ERROR);
            assertThat(result.getMessage()).as(uri)
                    .startsWith("OAuth2 registration 'svc' (target 'api') has a token-uri that is not an absolute http(s) URL: '")
                    .endsWith("'. Fix its provider's token-uri (spring.security.oauth2.client.provider.<name>.token-uri).")
                    .doesNotContain("failed:");
        }
    }

    @Test
    @DisplayName("Credentials in an invalid token-uri are masked in the message")
    void testInvalidTokenUriMasksUserInfo() {
        OperationResult<String> result = withTokenUri("ftp://user:pa55@example.org/token")
                .authorization("api", oauth("svc"));

        assertThat(result.getErrorType()).contains(ErrorType.CONFIGURATION_ERROR);
        assertThat(result.getMessage()).contains("ftp://****@example.org/token").doesNotContain("pa55");
    }

    @Test
    @DisplayName("isHttpUrl accepts absolute http(s) URLs with a host, in any scheme case")
    void testIsHttpUrl() {
        assertThat(RestAuthenticator.isHttpUrl("https://idp.example/oauth/token")).isTrue();
        assertThat(RestAuthenticator.isHttpUrl("HTTP://localhost:8080/token")).isTrue();
        assertThat(RestAuthenticator.isHttpUrl(null)).isFalse();
        assertThat(RestAuthenticator.isHttpUrl(" ")).isFalse();
        assertThat(RestAuthenticator.isHttpUrl("/relative/token")).isFalse();
        assertThat(RestAuthenticator.isHttpUrl("http:///no-host")).isFalse();
    }

    @Test
    @DisplayName("A token server echoing the client secret never gets it into the message")
    void testEchoedSecretMasked() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(403)
                .withHeader("Content-Type", "text/plain")
                .withBody("forbidden for client_secret=svc-secret-value")));

        OperationResult<String> result = withRegistration("svc").authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).doesNotContain("svc-secret-value").contains("****");
    }

    @Test
    @DisplayName("Credentials in an unreachable token URL never reach the message")
    void testUnreachableUserInfoMasked() {
        OperationResult<String> result = withTokenUri("https://user:pa55@no-such-host.invalid/token")
                .authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).doesNotContain("pa55");
    }

    @Test
    @DisplayName("A long error body is capped")
    void testLongBodyCapped() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(405)
                .withHeader("Content-Type", "text/html")
                .withBody("<html>" + "x".repeat(2000) + "</html>")));

        OperationResult<String> result = withRegistration("svc").authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).endsWith("…");
        assertThat(result.getMessage().length()).isLessThan(450);
    }
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./mvnw test -pl idm-connector-rest -am -Dtest=RestAuthenticatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected:
- COMPILATION ERROR for `isHttpUrl`. Temporarily comment out `testIsHttpUrl` to see the others fail on their assertions, then restore it.
- `testEchoedSecretMasked`, `testLongBodyCapped` and the invalid-URI tests fail.
- `testUnreachableUserInfoMasked` fails if the I/O message includes the URL.

If `testUnreachableUserInfoMasked` or `testEchoedSecretMasked` **passes** before the change (Spring's message omits the URL or body in this configuration), keep it as a regression guard. Say in the report that it didn't fail first; don't weaken it.

- [ ] **Step 3: Implement in `RestAuthenticator`**

Add these imports:

```java
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import java.net.URI;
```

In `bearer()`, replace the block from `if (registrations.findByRegistrationId(registrationId) == null) {` down to the end of the method with:

```java
        ClientRegistration registration = registrations.findByRegistrationId(registrationId);
        if (registration == null) {
            return OperationResult.failure("REST target '" + target + "' names OAuth2 client registration '"
                    + registrationId + "', which is not configured under spring.security.oauth2.client.registration",
                    ErrorType.CONFIGURATION_ERROR);
        }

        String clientSecret = registration.getClientSecret();
        String tokenUri = registration.getProviderDetails().getTokenUri();
        if (!isHttpUrl(tokenUri)) {
            return OperationResult.failure("OAuth2 registration '" + registrationId + "' (target '" + target
                    + "') has a token-uri that is not an absolute http(s) URL: '"
                    + TokenFailureText.safe(tokenUri, clientSecret)
                    + "'. Fix its provider's token-uri (spring.security.oauth2.client.provider.<name>.token-uri).",
                    ErrorType.CONFIGURATION_ERROR);
        }

        String failed = "OAuth2 token request for registration '" + registrationId + "' (target '" + target + "') failed: ";
        try {
            OAuth2AuthorizedClient client = manager.authorize(
                    OAuth2AuthorizeRequest.withClientRegistrationId(registrationId).principal(target).build());
            if (client == null) {
                return OperationResult.failure("OAuth2 registration '" + registrationId + "' (target '" + target
                        + "') gave no access token; its authorization-grant-type must be client_credentials",
                        ErrorType.CONFIGURATION_ERROR);
            }
            return OperationResult.success("Bearer " + client.getAccessToken().getTokenValue(),
                    "REST target '" + target + "' uses OAuth2 registration '" + registrationId + "'");
        } catch (OAuth2AuthorizationException e) {
            OAuth2Error error = e.getError();
            String description = TokenFailureText.safe(error.getDescription(), clientSecret);
            return OperationResult.failure(failed + "[" + error.getErrorCode() + "]"
                    + (description.isEmpty() ? "" : " " + description), tokenFailureType(e));
        } catch (RuntimeException e) {
            String cause = TokenFailureText.safe(e.getMessage(), clientSecret);
            return OperationResult.failure(failed + e.getClass().getSimpleName()
                    + (cause.isEmpty() ? "" : ": " + cause), tokenFailureType(e));
        }
    }

    /**
     * Whether a token URI can be sent to: absolute, http or https in any letter case, with a
     * host. Anything else fails in Spring before a request is made, with a bare exception, so
     * it is caught here as a configuration error instead.
     */
    static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute() && uri.getHost() != null && scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
```

Keep the rest of the class (`tokenFailureType`, `present`, the basic-auth path) unchanged.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./mvnw test -pl idm-connector-rest -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips. The existing `testTokenRejected`, `testTokenRejectedWith400`, `testTokenServerError`, `testTokenServerUnreachable`, `testUnknownRegistration` and `testOAuthBearer` stay green.

- [ ] **Step 5: Falsification**

Make each change below, re-run `RestAuthenticatorTest`, confirm that the named test fails, then revert:
- Remove the `isHttpUrl` check. `testInvalidTokenUri` fails, because those URIs become `failed:` messages.
- In the Spring path, use `error.getDescription()` directly without `safe`. `testEchoedSecretMasked` and `testLongBodyCapped` fail.
- In `isHttpUrl`, drop the host check. `testIsHttpUrl` fails on `http:///no-host`.

- [ ] **Step 6: Update the capability matrix**

In `docs/architecture/capabilities.md`, the "REST authentication" row's last cell ends with `(Spring's default handling) |`. Replace that ending with:

```markdown
(Spring's default handling). Token-failure messages quote Spring's cause with the client secret and URL credentials masked and the length capped at 300 characters; a malformed token-uri is a configuration error (ITF-27) |
```

- [ ] **Step 7: Run the full suite and commit**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS, 0 failures, errors and skips. `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`. Report the total from `grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc`.

```bash
git add idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/RestAuthenticator.java \
        idm-connector-rest/src/test/java/com/idm/test/connector/rest/client/RestAuthenticatorTest.java \
        docs/architecture/capabilities.md
git commit -m "feat: validate the token-uri and report token failures with their cause, safely

ITF-27
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
