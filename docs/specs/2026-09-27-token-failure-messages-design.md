# Token-failure messages: validate the token URI, show Spring's text safely — design

**Ticket:** ITF-27 (follow-up to ITF-23).

## 1. The problem

`RestAuthenticator.bearer()` has two failure paths when it fetches an OAuth2 token:

- **The Spring path** (`catch (OAuth2AuthorizationException e)`) reports `[code] description`.
- **The catch-all** (`catch (RuntimeException e)`) reports only the exception's class name, so the
  cause is hidden.

A probe against Spring Security 6.5.5 (2026-09-27) showed what reaches each path:

| `token-uri` or response | Path | Today's message ends with |
|---|---|---|
| `http://bad host/token` | catch-all | `InvalidUrlException` (Spring said `Bad authority`) |
| `not-a-url` | catch-all | `IllegalArgumentException` (`URI is not absolute`) |
| `ftp://example.org/token` | catch-all | `IllegalStateException` (`HttpURLConnection required for [ftp://…]`) |
| a 405 answer with an HTML body | Spring | `[invalid_token_response] … 405 Method Not Allowed: "<!doctype html>…"`, **the whole page** |
| an unreachable host | Spring | `[invalid_token_response] … I/O error on POST request for "http://…/token": …`, **the full URL** |
| invalid JSON, no `access_token`, `text/html` with a 200 | Spring | a converter message (short, safe) |

**Three problems follow:**
1. **A malformed token URI is reported as a bare class name.** It is also classified as
   `AUTHENTICATION_ERROR` (a rejected token), although it is a configuration mistake.
2. **The Spring path pastes response bodies without limit.**
3. **The Spring path includes the token URL.** A URL such as `https://user:pass@host/token` would
   put that password in the step message and `scenario-results.json`, which breaks ITF-23's
   secrets rule.

## 2. Decision (jangulo, 2026-09-27)

**Validate plus scrub:** check the token URI before sending, and show Spring's text on every
failure only after removing secrets and cutting it to a bounded length. The allow-list and
DEBUG-logging alternatives were rejected.

## 3. Validating the token URI

In `bearer()`, after the registration is found (the existing `findByRegistrationId` check), read
`registration.getProviderDetails().getTokenUri()`. It is valid only when all of these hold:

- `URI.create(tokenUri)` succeeds
- the URI is absolute
- its scheme is `http` or `https`, in any letter case
- its host is non-null

Otherwise `bearer()` returns this, before calling Spring's manager:

```
OAuth2 registration '<id>' (target '<target>') has a token-uri that is not an absolute http(s) URL: '<safe(tokenUri)>'. Fix its provider's token-uri (spring.security.oauth2.client.provider.<name>.token-uri).
```

- **Classification:** `ErrorType.CONFIGURATION_ERROR`. Under ITF-26 a `wait:` stops at once.
- **The URI is shown through `safe(...)`** (§4), so credentials in it are masked.
- **The provider's name is written literally as `<name>`,** because `ClientRegistration` does not
  expose which provider entry supplied the token URI.
- **A null or blank token URI** fails the same check.

## 4. `TokenFailureText.safe(text, clientSecret)`

A new package-private final class `TokenFailureText` in `com.idm.test.connector.rest.client`,
with one static method, `static String safe(String text, String clientSecret)`, applied in this
order:

1. **A null text** becomes `""`.
2. **Credentials in URLs:** every `scheme://userinfo@` becomes `scheme://****@`. The regex is
   `(?i)\b([a-z][a-z0-9+.-]*://)[^/\s"'<>]+@`, replaced with `$1****@`. `@` is not excluded from
   the class, so a greedy match runs to the **last** `@` before the first `/`, whitespace or quote,
   and a userinfo that itself contains a raw `@` (`https://svc@corp.com:pw@idp/token`) is masked
   whole. A raw `/` inside a password is not masked, but such a URL is invalid anyway (a documented
   limit).
3. **Authorization values:** `(?i)\b(Basic|Bearer)\s+[A-Za-z0-9+/=._~-]{8,}` becomes `$1 ****`.
   A token server that echoes the request would otherwise show the Basic header, which Spring
   builds from the URL-encoded id and secret, so the secret is recoverable.
4. **The client secret:** when `clientSecret` is non-null and non-empty, every literal occurrence
   of it is replaced with `****`, and so is every occurrence of its
   `URLEncoder.encode(clientSecret, UTF_8)` form when that differs. Spring URL-encodes the secret
   in both the Basic header and a `client_secret_post` form body.
5. **Whitespace:** each run of whitespace becomes a single space, and the result is trimmed.
6. **Length:** if the result is longer than `MAX_LENGTH = 300` characters, it is cut to the first
   300 and `…` (U+2026) is appended.

**Known limits,** accepted because none can occur on Spring's real request path:
- The Authorization rule also masks ordinary words after `Basic`/`Bearer` (`Basic **** is
  required`). It over-masks, and never leaks.
- A `Bearer` value with characters outside RFC 6750's token grammar is not masked. No bearer token
  exists during a token request.
- A server that re-encodes the secret differently from `URLEncoder` (`%20` for a space, lowercase
  `%7e`) is not caught. Spring sends only the `URLEncoder` form.

The URL and Authorization rules run before the secret is replaced. A trivial secret that is a
substring of a scheme (`s` inside `https`) would otherwise break the URL rule. `safe(...)` never
throws. Nothing else about the text changes.

*Amended 2026-09-27 after final review (jangulo's decision). The first version masked only the
raw secret, and stopped at the first `@`. Probes showed a secret leaking in URL-encoded form and
inside an echoed Basic header, and a password leaking after a raw `@` in userinfo.*

## 5. Where `safe(...)` is applied

| Place | Message |
|---|---|
| token-URI validation (§3) | the URI inside the message |
| the Spring path | `failed: [<code>] <safe(description)>`, or `failed: [<code>]` when the description is null or blank |
| the catch-all | `failed: <SimpleClassName>: <safe(message)>`, or `failed: <SimpleClassName>` when the message is null or blank |

- **The client secret** passed in is `registration.getClientSecret()`.
- **The `failed: ` prefix** keeps its current text:
  `OAuth2 token request for registration '<id>' (target '<target>') failed: `.
- **Classification is unchanged:** both catch paths still use `tokenFailureType(e)` from ITF-26.
  After §3, only unexpected bugs reach the catch-all.
- **Messages are built only** from the target, the registration id and Spring's text. The
  `Authorization` value and the access token are never used.

## 6. Testing

**`TokenFailureTextTest`** (unit tests, package-private access):
- a null text gives `""`
- the secret is replaced everywhere, including when it occurs more than once, when it sits
  inside a longer word, and in its URL-encoded form (`Az~s+/=` → `Az%7Es%2B%2F%3D`)
- `Basic <base64>` and `Bearer <token>` values become `Basic ****` / `Bearer ****`
- `https://svc@corp.com:pw@idp/token` becomes `https://****@idp/token`
- a null or empty secret leaves the text alone
- `https://user:pa55@host/x` becomes `https://****@host/x`; a URL with no userinfo is unchanged
- an email-like `a@b.c` with no scheme is unchanged
- whitespace, including newlines, is collapsed
- exactly 300 characters are not cut; 301 characters give 300 plus `…`

**`RestAuthenticatorTest`** (WireMock):
- **Validation:** `not-a-url`, `ftp://example.org/token` and `http://bad host/token` each give
  `CONFIGURATION_ERROR`, a message containing `token-uri`, and WireMock receives no request. A
  token URI `ftp://user:pa55@example.org/token` gives a message without `pa55`.
- **Scrubbing on the Spring path, including a token server that echoes the request:**
  - a WireMock response template echoing `{{request.body}}` and the `Authorization` header, with a
    secret containing `~ + / =`, for both `client_secret_basic` and `client_secret_post`, gives a
    message containing neither the raw nor the encoded secret, nor the Basic value
  - a 403 response whose body contains the client secret gives a message without the secret
    that contains `****`
  - `https://user:pa55@no-such-host.invalid/token` gives a message without `pa55`
  - a 405 with a 2,000-character body gives a message of at most 300 characters plus the prefix
    (assert `length() < 450`) that ends with `…`
- **The existing tests stay green,** including `testTokenRejected` (401 → `401`,
  `invalid_token_response`, no secret) and the ITF-26 classification tests.

**Falsification:** each rule in §4 (the secret, the userinfo, the length) and the validation in
§3 is disabled once, and a test must fail.

**Live:** the full default suite, 0 skips; the corpus, 0 FAILED.

## 7. Documentation

- **The capability matrix's "REST authentication" row** gains one sentence: token-failure
  messages quote Spring's cause with secrets masked and length capped, and a malformed token-uri
  is a configuration error.
- **The authoring guide** needs no change.

## 8. Out of scope

- **Guard 5 checking the token URI** in a real-environment run, whether it is malformed or still
  `application.yml`'s placeholder (e.g. `https://sap.company.com/oauth/token`). A candidate
  follow-up ticket.
- **Logging the cause at DEBUG** (rejected in §2).
- **Masking other values,** such as the client id or scope, which are not secrets.
- **Changing Spring's 401 handling,** which reports `[no body]` (ITF-23 decision).
