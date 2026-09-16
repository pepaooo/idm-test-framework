# SOAP and REST Step Types Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the six step types `roles.grant`, `roles.revoke`, `resources.grant`, `resources.revoke`, `rest.request` and `rest.verify` to the declarative scenario engine.

**Architecture:** Each step type is a small final class implementing `StepType`, constructed with the connector it wraps and registered by name. Two prerequisites come first, because `rest.request` cannot be built without them: `IdmRestConnector` currently discards HTTP status and headers and turns every non-2xx into an exception, and `Interpolator` stringifies a whole-value reference so a request body can never be a real object.

**Tech Stack:** Java 21, Maven multi-module, JUnit 5, AssertJ, WireMock, Spring `RestClient`, JAX-WS/JAXB generated from NetIQ WSDLs.

**Ticket:** ITF-9 (subtask of ITF-4). **Spec:** `docs/specs/2026-09-13-declarative-scenario-engine-design.md` §2, §7.1, §7.3, §7.5.

## Global Constraints

- `idm-test-engine/src/main` must **never** import `org.springframework`. `NoSpringDependencyTest` enforces this by walking the source tree; it is not advisory. This is why status and headers must reach the engine as plain Java types — `ResponseEntity` and `HttpHeaders` are unusable there.
- Expressions compute values, they never perform actions. No connector access, no filesystem, no network, no Java class access.
- No Lombok. Use records and plain classes with explicit constructors.
- Java 21. Prefer records, pattern matching, switch expressions.
- A `StepType.execute` implementation **must never throw**. A failure is a returned `StepOutcome.failure(...)`, not an exception.
- Returning a `null` `StepOutcome.value()` while `captureRequested` is true fails the scenario.
- `StepType` has a **single** abstract method: `StepOutcome execute(Map<String, Object> params, boolean captureRequested)`. Do not add overloads.
- The engine strips `as` and `wait` from `params` before calling `execute`. Neither is ever present in `params`.
- Reserved keys never share a namespace with user data (spec §7.3).
- An `expect:` block that is empty, or that carries an unrecognized key, must **fail**. This defect class has appeared three times in this project; every new assertion step guards it from the start.
- Every Maven command that names a module with `-pl` **must** also pass `-am`.
- Run a single test class with `-Dsurefire.failIfNoSpecifiedTests=false`.
- When a full-suite run reports "Unresolved compilation problems", that is the IDE's compiler writing a broken `.class` into `target/`, not a real break. Run `./mvnw clean test` and do not change code in response.
- Commit messages reference the ticket id on its own line before the trailers, and end with exactly:
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`

## Verified Facts

Measured against the running systems, not inferred. Do not re-litigate these.

- `IdmRestConnector` calls `.body(responseType)` / `.toBodilessEntity()`, discarding the `ResponseEntity`. **Status and headers are unavailable through every existing method.**
- Every existing method registers `onStatus(4xx)` and `onStatus(5xx)` handlers that `throw new RuntimeException("Client error: " + status)`. A 404 is therefore an operation *failure* whose status survives only inside a message string, which `determineErrorType` then matches with `message.contains("404")`.
- `Interpolator.resolveString` always returns a String. Proven: `${user}` over a Map yields the String `{userId=jdoe, firstName=Ada}` (Java's `Map.toString`, not JSON); `${count}` over the Integer 5 yields the String `"5"`.
- `Interpolator.lookup` navigates Maps only — no list indexing.
- `RoleServiceConnector.assignRoles(String userDn, String[] roleDns, String reason)` returns `OperationResult<RequestRolesAssignmentResponse>`; `getResult()` is `JAXBElement<DNStringArray>` → `getValue().getDnstring()` → `List<DNString>` → `getDn()`.
- `RoleServiceConnector.removeRoles(String[] roleDns)` takes **role assignment DNs, not role definition DNs** (its own javadoc says so).
- `RoleServiceConnector.getAssignedRoles(userDn)` returns `User.getAssociatedRoles()` — role *definition* DNs. **Nothing in this API maps (user, role definition) → assignment DN**, which is why `roles.revoke` cannot be symmetric with `roles.grant`.
- `ResourceServiceConnector.grantResource(String userDn, String resourceDn, String reason)` and `revokeResource(String userDn, String resourceDn, String reason)` are symmetric. Both responses' `getResult()` is `JAXBElement<String>`.
- `SoapProperties` is a plain POJO: `setUrl(String)` (role service), `setResourceUrl(String)`, plus credentials and timeouts. It can be constructed directly in a test with no Spring context.
- `RestProperties.getApiConfig(String apiName)` returns a `RestApiConfig` with `getBaseUrl()`. The scenarios module configures targets `idm`, `sap`, `workday`, and a single `RestClient` bean built with default settings (no base URL).
- The WireMock pattern to copy is `ResourceServiceConnectorTest`: a `WireMockServer` on a dynamic port started in a **static initialiser**, so the port is known before the context builds and cannot collide.
- Engine-module tests construct connectors directly rather than through Spring (see `DbStepsTest`, `LdapStepsTest`). Spring imports are fine in `src/test`, forbidden only in `src/main`.
- `idm-test-engine/pom.xml` depends on `idm-test-core`, `idm-test-utils`, `idm-connector-ldap`, `idm-connector-database`. It does **not** depend on `idm-connector-rest` or `idm-connector-soap`. Task 3 adds both.
- `ErrorType` values: `NETWORK_ERROR, AUTHENTICATION_ERROR, NOT_FOUND, VALIDATION_ERROR, TIMEOUT, SERVER_ERROR, DATABASE_ERROR, LDAP_ERROR, CONFIGURATION_ERROR, UNKNOWN_ERROR`.

## Decisions Already Made

Settled with the project owner. Do not re-open.

1. **The REST connector gap is a prerequisite task inside this branch** (Task 1), not a separate ticket.
2. **`roles.grant` registers NO undo.** The mechanism would be plausible — hand back the DNs the server returned — but it cannot be validated against anything but WireMock until a live eDirectory exists. Scenarios pair a grant with an explicit `roles.revoke`.
3. **`resources.grant` DOES register an undo.** `revokeResource(userDn, resourceDn, reason)` is a complete inverse built from inputs already in hand, with no server-returned DN involved, so the "undo only when it is faithful" rule that excluded `roles.grant` includes this one. This mirrors ITF-8, where `ldap.create` auto-undoes and `ldap.delete` does not.
4. **`roles.revoke` takes assignment DNs, not (user, role).** Forced by the API, not chosen.
5. **`bodyFrom` takes a reference (`bodyFrom: "${user}"`), not a bare name.** A step never receives scenario scope, so a bare name is unresolvable by the step; and a bare name would get no load-time check that the variable exists, while a `${...}` reference inherits the validator's forward-reference checking for free. **This amends spec §7.1, which shows `bodyFrom: user`** — record it for ITF-5, which already amends the specification.

## File Structure

**Create:**
- `idm-connector-rest/src/main/java/com/idm/test/connector/rest/model/RestResponse.java` — the plain-Java response envelope. Must expose no Spring type, because the engine consumes it.
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/RolesGrantStep.java`
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/RolesRevokeStep.java`
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/ResourcesGrantStep.java`
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/ResourcesRevokeStep.java`
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestRequestStep.java`
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestVerifyStep.java`
- `idm-test-engine/src/main/java/com/idm/test/engine/steps/SoapDns.java` — unwraps `JAXBElement<DNStringArray>` into `List<String>`, shared by the roles steps.
- `idm-test-engine/src/test/java/com/idm/test/engine/steps/SoapStepsTest.java`
- `idm-test-engine/src/test/java/com/idm/test/engine/steps/RestStepsTest.java`

**Modify:**
- `idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/IdmRestConnector.java` — add `exchange`.
- `idm-connector-rest/src/test/java/com/idm/test/connector/rest/client/IdmRestConnectorTest.java` — tests for `exchange`.
- `idm-test-engine/src/main/java/com/idm/test/engine/Interpolator.java` — whole-value type preservation.
- `idm-test-engine/src/test/java/com/idm/test/engine/InterpolatorTest.java`
- `idm-test-engine/pom.xml` — add `idm-connector-rest`, `idm-connector-soap`, and WireMock (test scope).
- `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java` — register the six new types.

---

### Task 1: A status-aware REST exchange

**Files:**
- Create: `idm-connector-rest/src/main/java/com/idm/test/connector/rest/model/RestResponse.java`
- Modify: `idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/IdmRestConnector.java`
- Test: `idm-connector-rest/src/test/java/com/idm/test/connector/rest/client/IdmRestConnectorTest.java`

**Interfaces:**
- Produces:
  - `record RestResponse(int status, Map<String, List<String>> headers, Object body)` in `com.idm.test.connector.rest.model`.
  - `IdmRestConnector.exchange(String method, String url, Object body, Map<String, String> headers)` → `OperationResult<RestResponse>`.

**The central semantic, and the whole point of this task:** an HTTP error status is an *observable outcome*, not an operation failure. `exchange` returns `success = true` for a 404 or a 500, carrying the status in `RestResponse.status`. Only a transport-level problem — connection refused, timeout, DNS failure — produces `OperationResult` failure. Without this, no scenario could ever assert "the user is gone, so GET returns 404", which is a core IdM test pattern. The existing `get`/`post`/`put`/`delete` methods keep their throw-on-error behaviour; they are not touched.

- [ ] **Step 1: Write the failing tests**

Add to `IdmRestConnectorTest`. It already runs a `WireMockServer` on port 8089 with `baseUrl` and an `@Autowired connector`; reuse them. The class uses `@TestMethodOrder(OrderAnnotation.class)` — leave these un-annotated so they sort last.

```java
    @Test
    @DisplayName("exchange should return status, headers and a parsed body")
    void testExchangeReturnsStatusHeadersAndBody() {
        stubFor(get(urlEqualTo("/api/users/1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withHeader("X-Request-Id", "abc123")
                        .withBody("{\"id\":1,\"userId\":\"jdoe\"}")));

        OperationResult<RestResponse> result =
                connector.exchange("GET", baseUrl + "/api/users/1", null, null);

        assertThat(result.isSuccess()).isTrue();
        RestResponse response = result.getData().orElseThrow();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers()).containsKey("X-Request-Id");
        assertThat(response.headers().get("X-Request-Id")).containsExactly("abc123");
        assertThat(response.body()).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) response.body()).containsEntry("userId", "jdoe");
    }

    @Test
    @DisplayName("exchange should report a 404 as a successful call carrying status 404")
    void testExchangeTreatsHttpErrorAsAnOutcome() {
        stubFor(get(urlEqualTo("/api/users/missing"))
                .willReturn(aResponse().withStatus(404)));

        OperationResult<RestResponse> result =
                connector.exchange("GET", baseUrl + "/api/users/missing", null, null);

        // The request itself succeeded; 404 is what the server said. Reporting this as an
        // operation failure is what makes "expect status 404" impossible to write.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().orElseThrow().status()).isEqualTo(404);
    }

    @Test
    @DisplayName("exchange should report a 500 as a successful call carrying status 500")
    void testExchangeTreatsServerErrorAsAnOutcome() {
        stubFor(get(urlEqualTo("/api/boom"))
                .willReturn(aResponse().withStatus(500)));

        OperationResult<RestResponse> result =
                connector.exchange("GET", baseUrl + "/api/boom", null, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().orElseThrow().status()).isEqualTo(500);
    }

    @Test
    @DisplayName("exchange should send a JSON body and custom headers")
    void testExchangeSendsBodyAndHeaders() {
        stubFor(post(urlEqualTo("/api/users"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"id\":7}")));

        OperationResult<RestResponse> result = connector.exchange(
                "POST", baseUrl + "/api/users",
                Map.of("userId", "jdoe", "department", "Engineering"),
                Map.of("X-Correlation-Id", "corr-1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().orElseThrow().status()).isEqualTo(201);

        verify(postRequestedFor(urlEqualTo("/api/users"))
                .withHeader("X-Correlation-Id", equalTo("corr-1"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo("jdoe"))));
    }

    @Test
    @DisplayName("exchange should return a null body for an empty response")
    void testExchangeEmptyBodyIsNull() {
        stubFor(delete(urlEqualTo("/api/users/1"))
                .willReturn(aResponse().withStatus(204)));

        OperationResult<RestResponse> result =
                connector.exchange("DELETE", baseUrl + "/api/users/1", null, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().orElseThrow().status()).isEqualTo(204);
        assertThat(result.getData().orElseThrow().body()).isNull();
    }

    @Test
    @DisplayName("exchange should return a non-JSON body as plain text")
    void testExchangeNonJsonBodyIsText() {
        stubFor(get(urlEqualTo("/api/health"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                        .withBody("OK")));

        OperationResult<RestResponse> result =
                connector.exchange("GET", baseUrl + "/api/health", null, null);

        assertThat(result.getData().orElseThrow().body()).isEqualTo("OK");
    }

    @Test
    @DisplayName("exchange should fail for a transport error, not return a status")
    void testExchangeTransportFailure() {
        // Nothing is listening on this port, so there is no HTTP status to report — this is
        // the one case that is genuinely an operation failure rather than an outcome.
        OperationResult<RestResponse> result =
                connector.exchange("GET", "http://localhost:1/api/nothing", null, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).isEmpty();
    }

    @Test
    @DisplayName("exchange should reject an unsupported HTTP method rather than throw")
    void testExchangeRejectsBadMethod() {
        OperationResult<RestResponse> result =
                connector.exchange("FETCH", baseUrl + "/api/users/1", null, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("FETCH");
    }
```

Add imports as needed: `com.idm.test.connector.rest.model.RestResponse`, `java.util.Map`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl idm-connector-rest -am test -Dtest=IdmRestConnectorTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL to compile — `RestResponse` and `exchange` do not exist.

- [ ] **Step 3: Create the RestResponse record**

Create `idm-connector-rest/src/main/java/com/idm/test/connector/rest/model/RestResponse.java`:

```java
package com.idm.test.connector.rest.model;

import java.util.List;
import java.util.Map;

/**
 * One HTTP response, in plain Java.
 * <p>
 * Deliberately carries no Spring type. The scenario engine consumes this through
 * {@code rest.request}, and {@code idm-test-engine/src/main} is forbidden from importing
 * {@code org.springframework} — so {@code ResponseEntity} and {@code HttpHeaders} cannot
 * cross that boundary and are converted here instead.
 *
 * @param status  the HTTP status code, including 4xx and 5xx — an error status is an
 *                observable outcome a scenario may assert on, not a failure
 * @param headers response headers, never null; a header may legitimately repeat, so each maps
 *                to a list of values
 * @param body    the parsed JSON body as a Map, List, String, Number or Boolean; the raw text
 *                for a non-JSON response; or null when the response had no body
 */
public record RestResponse(int status, Map<String, List<String>> headers, Object body) {
}
```

- [ ] **Step 4: Add exchange to IdmRestConnector**

Insert before `determineErrorType`:

```java
    /**
     * Performs one HTTP request and reports exactly what came back.
     * <p>
     * Unlike {@link #get}, {@link #post}, {@link #put} and {@link #delete}, this never treats
     * an HTTP error status as a failure: a 404 or a 500 returns a successful
     * {@link OperationResult} whose {@link RestResponse#status()} carries the code. That is
     * the point — a scenario asserting "the account is gone, so this returns 404" cannot be
     * written against a method that turns 404 into an exception. Only a transport-level
     * problem (connection refused, timeout, DNS failure) is an operation failure here.
     *
     * @param method  GET, POST, PUT, PATCH, DELETE or HEAD, case-insensitive
     * @param url     the absolute URL
     * @param body    request body, serialized as JSON, or null for none
     * @param headers extra request headers, or null for none
     * @return the response, or a failure result; never throws
     */
    public OperationResult<RestResponse> exchange(
            String method, String url, Object body, Map<String, String> headers) {

        log.debug("{} request to: {}", method, url);

        HttpMethod httpMethod;
        try {
            httpMethod = HttpMethod.valueOf(method.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OperationResult.<RestResponse>builder()
                    .success(false)
                    .message("Unsupported HTTP method: " + method)
                    .errorType(ErrorType.VALIDATION_ERROR)
                    .throwable(e)
                    .build();
        }

        try {
            RestClient.RequestBodySpec spec = restClient
                    .method(httpMethod)
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON);

            if (headers != null) {
                headers.forEach(spec::header);
            }

            RestClient.ResponseSpec responseSpec = (body == null
                    ? spec
                    : spec.contentType(MediaType.APPLICATION_JSON).body(body))
                    .retrieve()
                    // Suppress the default error handling entirely: every status, including
                    // 4xx and 5xx, must reach us as data rather than as an exception.
                    .onStatus(status -> true, (request, response) -> { });

            ResponseEntity<String> entity = responseSpec.toEntity(String.class);

            return OperationResult.<RestResponse>builder()
                    .success(true)
                    .data(new RestResponse(
                            entity.getStatusCode().value(),
                            Map.copyOf(entity.getHeaders()),
                            parseBody(entity)))
                    .message(httpMethod + " " + url + " returned " + entity.getStatusCode().value())
                    .build();

        } catch (Exception e) {
            log.error("{} request failed for URL '{}': {}", method, url, e.getMessage());
            return OperationResult.<RestResponse>builder()
                    .success(false)
                    .message("Request failed: " + e.getMessage())
                    .errorType(determineErrorType(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * JSON becomes Maps, Lists and scalars so a scenario can walk it with
     * {@code ${created.body.id}}. Anything else is returned as its raw text rather than
     * guessed at, and an empty body is null rather than an empty string.
     */
    private Object parseBody(ResponseEntity<String> entity) {
        String text = entity.getBody();
        if (text == null || text.isBlank()) {
            return null;
        }

        MediaType contentType = entity.getHeaders().getContentType();
        boolean json = contentType != null
                && (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                    || contentType.getSubtype().endsWith("+json"));
        if (!json) {
            return text;
        }

        try {
            return objectMapper.readValue(text, Object.class);
        } catch (Exception e) {
            // The server said JSON and sent something else. Returning the raw text loses
            // nothing and lets the scenario assert on it; throwing here would turn a server
            // bug into an unrelated-looking connector failure.
            log.warn("Response claimed JSON but could not be parsed, returning raw text: {}",
                    e.getMessage());
            return text;
        }
    }
```

Add these fields and imports. The `ObjectMapper` is a field because it is thread-safe and reusing one avoids rebuilding it per request:

```java
    private final ObjectMapper objectMapper = new ObjectMapper();
```

Imports to add: `com.fasterxml.jackson.databind.ObjectMapper`, `com.idm.test.connector.rest.model.RestResponse`, `org.springframework.http.HttpMethod`, `org.springframework.http.ResponseEntity`, `java.util.Locale`, `java.util.Map`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -pl idm-connector-rest -am test -Dtest=IdmRestConnectorTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

If `Map.copyOf(entity.getHeaders())` does not compile or produces the wrong shape, build the map explicitly instead — the requirement is a plain `Map<String, List<String>>` containing every header, with no Spring type leaking into `RestResponse`:

```java
    Map<String, List<String>> plainHeaders = new LinkedHashMap<>();
    entity.getHeaders().forEach(plainHeaders::put);
```

- [ ] **Step 6: Commit**

```bash
git add idm-connector-rest/src/main/java/com/idm/test/connector/rest/model/RestResponse.java \
        idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/IdmRestConnector.java \
        idm-connector-rest/src/test/java/com/idm/test/connector/rest/client/IdmRestConnectorTest.java
git commit -m "feat: add a status-aware REST exchange

Every existing method discards the ResponseEntity and converts non-2xx
into RuntimeException, so status and headers never surface and a
scenario could not assert 'the account is gone, so GET returns 404' —
the status survived only inside an exception message that
determineErrorType then string-matched.

exchange reports an HTTP error status as an outcome: 404 and 500 come
back as successful results carrying the code. Only a transport failure
is an operation failure. RestResponse carries no Spring type, because
the engine module may not import org.springframework.

ITF-9

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Whole-value interpolation preserves the referenced type

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/Interpolator.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/InterpolatorTest.java`

**Interfaces:**
- Produces: `Interpolator.resolve` returns the referenced object itself when a string is *exactly* one reference (`"${user}"`), instead of its `toString`. A mixed string (`"cn=${user.userId},ou=users"`) still produces a String, unchanged.

**Why:** proven above — `${user}` over a Map yields `{userId=jdoe, firstName=Ada}`, Java's `Map.toString`, which is not JSON and useless as a request body; `${count}` over the Integer 5 yields the String `"5"`, which already loses type in `db.execute` params today. `rest.request` cannot send a body without this.

**Risk:** the only behaviour that changes is a whole-value reference to a non-String, which previously produced a stringification nobody could have wanted. A whole-value reference to a String is unchanged, so every existing scenario — all of which reference Strings — behaves identically.

- [ ] **Step 1: Write the failing tests**

Add to `InterpolatorTest`:

```java
    @Test
    @DisplayName("A whole-value reference to a map should preserve the map")
    void testWholeValueReferencePreservesMap() {
        Map<String, Object> user = Map.of("userId", "jdoe", "firstName", "Ada");
        Object resolved = Interpolator.resolve("${user}", Map.of("user", user), EVALUATOR);

        // Previously this produced the String "{userId=jdoe, firstName=Ada}" — Java's
        // Map.toString, which is not JSON and cannot be sent as a request body.
        assertThat(resolved).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) resolved).containsEntry("userId", "jdoe");
    }

    @Test
    @DisplayName("A whole-value reference to a number should preserve the number")
    void testWholeValueReferencePreservesNumber() {
        Object resolved = Interpolator.resolve("${count}", Map.of("count", 5), EVALUATOR);

        assertThat(resolved).isInstanceOf(Integer.class).isEqualTo(5);
    }

    @Test
    @DisplayName("A whole-value reference to a list should preserve the list")
    void testWholeValueReferencePreservesList() {
        Object resolved = Interpolator.resolve(
                "${roles}", Map.of("roles", List.of("cn=SAP,ou=roles")), EVALUATOR);

        assertThat(resolved).isInstanceOf(List.class);
        assertThat((List<?>) resolved).containsExactly("cn=SAP,ou=roles");
    }

    @Test
    @DisplayName("A reference embedded in a larger string should still produce a string")
    void testEmbeddedReferenceStillStringifies() {
        Object resolved = Interpolator.resolve(
                "cn=${user.userId},ou=users",
                Map.of("user", Map.of("userId", "jdoe")), EVALUATOR);

        assertThat(resolved).isInstanceOf(String.class).isEqualTo("cn=jdoe,ou=users");
    }

    @Test
    @DisplayName("Two references in one value should still produce a string")
    void testTwoReferencesStillStringify() {
        Object resolved = Interpolator.resolve(
                "${a}-${b}", Map.of("a", 1, "b", 2), EVALUATOR);

        assertThat(resolved).isInstanceOf(String.class).isEqualTo("1-2");
    }

    @Test
    @DisplayName("A whole-value reference with surrounding whitespace is not a whole value")
    void testReferenceWithWhitespaceStillStringifies() {
        // Only an exact match is a whole value. Trimming here would silently change what a
        // scenario asked for.
        Object resolved = Interpolator.resolve(" ${count} ", Map.of("count", 5), EVALUATOR);

        assertThat(resolved).isInstanceOf(String.class).isEqualTo(" 5 ");
    }
```

If `InterpolatorTest` has no `EVALUATOR` constant, read the file and use whatever evaluator instance the existing tests pass to `resolve`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=InterpolatorTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL — the first three fail because the resolved value is a String; the last three already pass and must keep passing.

- [ ] **Step 3: Add whole-value handling**

In `Interpolator`, add the pattern beside the existing `REFERENCE`:

```java
    /**
     * A value that is exactly one reference and nothing else. Such a value resolves to the
     * referenced object itself rather than its text, so a map stays a map and a number stays
     * a number. Anything else — a reference inside a larger string, or two references — is
     * text by construction and goes through {@link #resolveString}.
     */
    private static final Pattern WHOLE_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
```

Then change `resolveString`'s caller in `resolve`, replacing `return resolveString(text, scope);` with:

```java
            Matcher whole = WHOLE_REFERENCE.matcher(text);
            if (whole.matches()) {
                // Deliberately no trimming: " ${x} " is a string with a reference in it, not
                // a whole-value reference, and treating it as one would silently discard the
                // spaces the author wrote.
                return lookup(whole.group(1), scope, text);
            }
            return resolveString(text, scope);
```

`lookup` is already private static in this class and throws the same "cannot resolve" message, so an unknown name behaves identically either way.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=InterpolatorTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 5: Run the whole suite — this changes a shared component**

Run: `./mvnw clean test`

Expected: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped. Every existing scenario references Strings, so nothing should change. If a test fails because a value is now a non-String, **that test was asserting the old stringification** — report it in your task report with the reasoning before changing it.

- [ ] **Step 6: Commit**

```bash
git add idm-test-engine/src/main/java/com/idm/test/engine/Interpolator.java \
        idm-test-engine/src/test/java/com/idm/test/engine/InterpolatorTest.java
git commit -m "feat: preserve the referenced type for a whole-value reference

A value that is exactly one reference now resolves to the referenced
object rather than its text: a map stays a map, a number stays a
number. Previously \${user} over a map produced Java's Map.toString,
which is not JSON and cannot be sent as a request body, and \${count}
over an integer produced a string that already lost its type in
db.execute params.

Mixed strings are untouched — 'cn=\${user.userId},ou=users' is text by
construction.

ITF-9

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: roles.grant and roles.revoke

**Files:**
- Modify: `idm-test-engine/pom.xml`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/SoapDns.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/RolesGrantStep.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/RolesRevokeStep.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/steps/SoapStepsTest.java`

**Interfaces:**
- Consumes: `RoleServiceConnector.assignRoles(String userDn, String[] roleDns, String reason)`, `RoleServiceConnector.removeRoles(String[] roleDns)`.
- Produces:
  - `SoapDns.of(JAXBElement<DNStringArray> result)` → `List<String>`, package-private static.
  - `new RolesGrantStep(RoleServiceConnector)`, `name()` = `"roles.grant"`, requires `user` and `roles`, also accepts `reason`, **no undo**, `value()` is the `List<String>` of assignment DNs the service returned.
  - `new RolesRevokeStep(RoleServiceConnector)`, `name()` = `"roles.revoke"`, requires `assignments`, no undo, `value()` is the `List<String>` of removed DNs.

YAML shape:

```yaml
- roles.grant:
    user: "cn=${user.userId},ou=users"
    roles: ["cn=SAP_USER,ou=roles"]
    reason: "Automated test"
    as: granted

- roles.revoke:
    assignments: "${granted}"
```

`roles.revoke` takes **assignment DNs**, not (user, role). `removeRoles` consumes role assignment DNs, and nothing in the role service maps a role definition DN back to an assignment DN — `getAssignedRoles` returns definition DNs. So a symmetric revoke is not implementable, and the DSL says what the API means. `assignments: "${granted}"` works because Task 2 preserves the list.

- [ ] **Step 1: Add the connector dependencies**

In `idm-test-engine/pom.xml`, beside the existing `idm-connector-database` dependency:

```xml
        <dependency>
            <groupId>com.idm.test</groupId>
            <artifactId>idm-connector-soap</artifactId>
        </dependency>

        <dependency>
            <groupId>com.idm.test</groupId>
            <artifactId>idm-connector-rest</artifactId>
        </dependency>
```

And beside the existing H2 test dependency:

```xml
        <!-- WireMock for the SOAP and REST step types' tests -->
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Write the failing tests**

Create `idm-test-engine/src/test/java/com/idm/test/engine/steps/SoapStepsTest.java`. Copy the WireMock pattern from `ResourceServiceConnectorTest` — a dynamic port in a **static initialiser** — but construct the connectors directly, with no Spring context, as `DbStepsTest` and `LdapStepsTest` do.

```java
package com.idm.test.engine.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.engine.spi.StepOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the SOAP step types against a WireMock stand-in for the NetIQ role service.
 * <p>
 * Builds the connectors directly rather than through Spring, because this module has no
 * Spring context by design.
 */
@DisplayName("SOAP step types")
class SoapStepsTest {

    private static final String ROLE_PATH = "/IDMProv/role/service";
    private static final String USER_DN = "cn=jdoe,ou=users,dc=company,dc=com";
    private static final String ROLE_DN = "cn=SAP_USER,ou=roles,dc=company,dc=com";
    private static final String ASSIGNMENT_DN = "cn=assignment-1,ou=requests,dc=company,dc=com";

    private static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
        WireMock.configureFor("localhost", WIREMOCK.port());
    }

    private static RoleServiceConnector roleConnector;

    static {
        SoapProperties properties = new SoapProperties();
        properties.setUrl("http://localhost:" + WIREMOCK.port() + ROLE_PATH);
        roleConnector = new RoleServiceConnector(properties);
    }

    @AfterAll
    static void stopWireMock() {
        if (WIREMOCK.isRunning()) {
            WIREMOCK.stop();
        }
    }

    @BeforeEach
    void resetStubs() {
        WIREMOCK.resetAll();
    }

    /**
     * A SOAP envelope carrying a requestRolesAssignment result of one DN. Read
     * RoleServiceConnectorTest first and copy the exact envelope shape it stubs — the
     * namespaces must match what JAX-WS expects or the response will not unmarshal.
     */
    private static void stubAssignmentReturning(String dn) {
        stubFor(post(urlEqualTo(ROLE_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(assignmentEnvelope(dn))));
    }

    @Test
    @DisplayName("roles.grant should assign roles and capture the returned assignment DNs")
    void testGrantCapturesAssignmentDns() {
        stubAssignmentReturning(ASSIGNMENT_DN);
        RolesGrantStep grant = new RolesGrantStep(roleConnector);

        StepOutcome outcome = grant.execute(Map.of(
                "user", USER_DN,
                "roles", List.of(ROLE_DN),
                "reason", "Automated test"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(List.of(ASSIGNMENT_DN));
    }

    @Test
    @DisplayName("roles.grant should register no undo")
    void testGrantRegistersNoUndo() {
        stubAssignmentReturning(ASSIGNMENT_DN);
        RolesGrantStep grant = new RolesGrantStep(roleConnector);

        StepOutcome outcome = grant.execute(Map.of(
                "user", USER_DN, "roles", List.of(ROLE_DN)), false);

        // Deliberate: removeRoles needs assignment DNs whose real-server semantics cannot be
        // validated against WireMock, so scenarios pair a grant with an explicit revoke.
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("roles.grant should accept a single role written as a bare string")
    void testGrantAcceptsSingleRoleString() {
        stubAssignmentReturning(ASSIGNMENT_DN);
        RolesGrantStep grant = new RolesGrantStep(roleConnector);

        StepOutcome outcome = grant.execute(Map.of("user", USER_DN, "roles", ROLE_DN), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("roles.grant should fail rather than throw when the service errors")
    void testGrantServiceErrorFails() {
        stubFor(post(urlEqualTo(ROLE_PATH)).willReturn(aResponse().withStatus(500)));
        RolesGrantStep grant = new RolesGrantStep(roleConnector);

        StepOutcome outcome = grant.execute(Map.of(
                "user", USER_DN, "roles", List.of(ROLE_DN)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("roles.grant should reject an empty roles list")
    void testGrantRejectsEmptyRoles() {
        RolesGrantStep grant = new RolesGrantStep(roleConnector);

        // Granting nothing is always an authoring mistake, and succeeding would hide it.
        StepOutcome outcome = grant.execute(Map.of("user", USER_DN, "roles", List.of()), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("roles");
    }

    @Test
    @DisplayName("roles.revoke should remove the given assignment DNs")
    void testRevokeRemovesAssignments() {
        stubRemovalReturning(ASSIGNMENT_DN);
        RolesRevokeStep revoke = new RolesRevokeStep(roleConnector);

        StepOutcome outcome = revoke.execute(
                Map.of("assignments", List.of(ASSIGNMENT_DN)), false);

        assertThat(outcome.success()).isTrue();
        verify(postRequestedFor(urlEqualTo(ROLE_PATH)));
    }

    @Test
    @DisplayName("roles.revoke should reject an empty assignments list")
    void testRevokeRejectsEmptyAssignments() {
        RolesRevokeStep revoke = new RolesRevokeStep(roleConnector);

        StepOutcome outcome = revoke.execute(Map.of("assignments", List.of()), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("assignments");
    }
}
```

**Write `assignmentEnvelope(String dn)` and `stubRemovalReturning(String dn)` using the shape below.**

Take the namespaces from `RoleServiceConnectorTest` — `soap` is `http://schemas.xmlsoap.org/soap/envelope/` and `tns` is `http://www.novell.com/role/service` — but **do not copy its `createRequestRolesAssignmentResponse` body.** That helper emits `<tns:result>REQ-12345</tns:result>`, a plain string, while the generated type is `JAXBElement<DNStringArray>`. JAXB unmarshals that into an array with **zero** DNs, which is invisible to `testAssignRolesSuccess` because it only asserts `getResult()` is not null — but it would make the capture here come back empty. Use the repeating `dnstring` shape instead, which `createGetUserResponse` in that same class already demonstrates for `associatedRoles`:

```java
    private static String assignmentEnvelope(String dn) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "xmlns:tns=\"http://www.novell.com/role/service\">"
                + "<soap:Body>"
                + "<tns:requestRolesAssignmentResponse>"
                + "<tns:result><tns:dnstring><tns:dn>" + dn + "</tns:dn></tns:dnstring></tns:result>"
                + "</tns:requestRolesAssignmentResponse>"
                + "</soap:Body></soap:Envelope>";
    }

    private static void stubRemovalReturning(String dn) {
        stubFor(post(urlEqualTo(ROLE_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                        + "xmlns:tns=\"http://www.novell.com/role/service\">"
                        + "<soap:Body>"
                        + "<tns:removeRolesResponse>"
                        + "<tns:result><tns:dnstring><tns:dn>" + dn + "</tns:dn></tns:dnstring></tns:result>"
                        + "</tns:removeRolesResponse>"
                        + "</soap:Body></soap:Envelope>")));
    }
```

If `testGrantCapturesAssignmentDns` still sees an empty list, the element names are wrong rather than the approach — print `result.getData().get().getResult().getValue()` and adjust, and say so in your task report. Note the connector stubs use `urlPathEqualTo`; `urlEqualTo` is equivalent here because the path carries no query string.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=SoapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL to compile — `RolesGrantStep` and `RolesRevokeStep` do not exist.

- [ ] **Step 4: Write SoapDns**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/SoapDns.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.soap.generated.role.DNString;
import com.idm.test.connector.soap.generated.role.DNStringArray;

import jakarta.xml.bind.JAXBElement;
import java.util.List;

/**
 * Unwraps the {@code JAXBElement<DNStringArray>} both role operations return.
 * <p>
 * Three levels of optionality — the element, its value, and the list — each of which JAXB may
 * leave null. One implementation so {@code roles.grant} and {@code roles.revoke} cannot
 * disagree about what "no DNs" looks like.
 */
final class SoapDns {

    private SoapDns() {
    }

    /**
     * @return every DN in the result, or an empty list when the service returned none
     */
    static List<String> of(JAXBElement<DNStringArray> result) {
        if (result == null || result.getValue() == null
                || result.getValue().getDnstring() == null) {
            return List.of();
        }
        return result.getValue().getDnstring().stream()
                .map(DNString::getDn)
                .filter(dn -> dn != null)
                .toList();
    }
}
```

If the generated classes use `javax.xml.bind` rather than `jakarta.xml.bind`, match whatever `RoleServiceConnector` itself imports.

- [ ] **Step 5: Write RolesGrantStep**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/RolesGrantStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.connector.soap.generated.role.RequestRolesAssignmentResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grants one or more roles to a user.
 * <p>
 * <b>Registers no undo, deliberately.</b> The inverse, {@code removeRoles}, consumes role
 * assignment DNs rather than role definition DNs, and nothing in the role service maps a user
 * and a role back to an assignment DN. Handing back the DNs this call returned would be
 * plausible, but it cannot be validated against anything but a stub until a live eDirectory
 * exists — so a scenario that needs cleanup writes an explicit {@code roles.revoke} using the
 * DNs captured here through {@code as:}.
 */
public final class RolesGrantStep implements StepType {

    private final RoleServiceConnector connector;

    public RolesGrantStep(RoleServiceConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "roles.grant";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("user", "roles");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("user", "roles", "reason");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the assignment DNs come back from the call itself, so
        // there is no extra work to skip when nobody asked to capture them.
        String user = String.valueOf(params.get("user"));
        List<String> roles = StepValues.toStringList(params.get("roles"));
        Object reason = params.get("reason");

        if (roles.isEmpty()) {
            return StepOutcome.failure(
                    "roles.grant for " + user + " has no roles to grant");
        }

        OperationResult<RequestRolesAssignmentResponse> result = connector.assignRoles(
                user, roles.toArray(new String[0]),
                reason == null ? null : String.valueOf(reason));

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "roles.grant failed for " + user + ": " + result.getMessage());
        }

        List<String> assignments = SoapDns.of(result.getData().get().getResult());

        return StepOutcome.success(
                "granted " + roles + " to " + user, null, assignments);
    }
}
```

- [ ] **Step 6: Write RolesRevokeStep**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/RolesRevokeStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.connector.soap.generated.role.RemoveRolesResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes role assignments.
 * <p>
 * Takes assignment DNs rather than a user and a role, because that is what the underlying
 * {@code removeRoles} operation consumes and nothing in the role service maps a role
 * definition DN back to an assignment DN. Capture them from {@code roles.grant} with
 * {@code as:} and pass them here.
 */
public final class RolesRevokeStep implements StepType {

    private final RoleServiceConnector connector;

    public RolesRevokeStep(RoleServiceConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "roles.revoke";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("assignments");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        List<String> assignments = StepValues.toStringList(params.get("assignments"));

        if (assignments.isEmpty()) {
            // Revoking nothing is always an authoring mistake — most likely a capture that
            // came back empty — and succeeding would hide it.
            return StepOutcome.failure("roles.revoke has no assignments to remove");
        }

        OperationResult<RemoveRolesResponse> result =
                connector.removeRoles(assignments.toArray(new String[0]));

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("roles.revoke failed for " + assignments
                    + ": " + result.getMessage());
        }

        return StepOutcome.success("revoked " + assignments, null,
                SoapDns.of(result.getData().get().getResult()));
    }
}
```

- [ ] **Step 7: Write StepValues**

Both steps above use it, so create `idm-test-engine/src/main/java/com/idm/test/engine/steps/StepValues.java`:

```java
package com.idm.test.engine.steps;

import java.util.List;
import java.util.Map;

/**
 * Small conversions every step needs on YAML input.
 */
final class StepValues {

    private StepValues() {
    }

    /**
     * Accepts a list or a single value, so a scenario granting one role may write
     * {@code roles: "cn=SAP,ou=roles"} instead of a one-element list. A null or an empty
     * list both become an empty list; callers decide whether that is an error, because for
     * some steps it is and for others it would not be.
     */
    static List<String> toStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(raw));
    }

    /**
     * @return the map under {@code key}, or an empty map when absent
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> toMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=SoapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 9: Confirm the engine still has no Spring in src/main**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=NoSpringDependencyTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. If it fails you have imported a Spring type into `src/main` — remove it; do not weaken the test.

- [ ] **Step 10: Commit**

```bash
git add idm-test-engine/pom.xml \
        idm-test-engine/src/main/java/com/idm/test/engine/steps/SoapDns.java \
        idm-test-engine/src/main/java/com/idm/test/engine/steps/StepValues.java \
        idm-test-engine/src/main/java/com/idm/test/engine/steps/RolesGrantStep.java \
        idm-test-engine/src/main/java/com/idm/test/engine/steps/RolesRevokeStep.java \
        idm-test-engine/src/test/java/com/idm/test/engine/steps/SoapStepsTest.java
git commit -m "feat: add roles.grant and roles.revoke step types

roles.revoke takes assignment DNs rather than a user and a role: that
is what removeRoles consumes, and nothing in the role service maps a
role definition DN back to an assignment DN — getAssignedRoles returns
definition DNs. The DSL says what the API means rather than inventing a
lookup that would not work.

roles.grant registers no undo. Handing back the DNs the service
returned would be plausible but unverifiable against anything but a
stub, so scenarios pair a grant with an explicit revoke.

ITF-9

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: resources.grant and resources.revoke

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/ResourcesGrantStep.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/ResourcesRevokeStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/SoapStepsTest.java`

**Interfaces:**
- Consumes: `ResourceServiceConnector.grantResource(String userDn, String resourceDn, String reason)` and `revokeResource(String userDn, String resourceDn, String reason)`, both → `OperationResult<...>` whose `getResult()` is `JAXBElement<String>`.
- Produces:
  - `new ResourcesGrantStep(ResourceServiceConnector)`, `name()` = `"resources.grant"`, requires `user` and `resource`, also accepts `reason`, **registers an undo** calling `revokeResource` with the same arguments, `value()` is the returned request identifier as a String.
  - `new ResourcesRevokeStep(ResourceServiceConnector)`, `name()` = `"resources.revoke"`, requires `user` and `resource`, also accepts `reason`, **no undo**, `value()` is the returned request identifier.

**Why this pair has an undo when roles does not:** `revokeResource` takes exactly the arguments `grantResource` was given — user, resource, reason. Nothing is derived and no server-returned DN is involved, so the inverse is faithful rather than plausible. That is the same rule that gave `ldap.create` an undo and denied one to `ldap.delete`.

`resources.revoke` registers no undo of its own: re-granting a resource the scenario deliberately revoked would fight the test rather than clean up after it.

- [ ] **Step 1: Write the failing tests**

Add to `SoapStepsTest`. Add a `ResourceServiceConnector` beside the role connector in the existing static initialiser, pointing at the same WireMock on a resource path:

```java
    private static final String RESOURCE_PATH = "/IDMProv/resource/service";
    private static final String RESOURCE_DN = "cn=SAP_ACCESS,ou=resources,dc=company,dc=com";
    private static final String REQUEST_ID = "20250916-request-42";

    private static ResourceServiceConnector resourceConnector;
```

and in the initialiser that builds `roleConnector`:

```java
        properties.setResourceUrl("http://localhost:" + WIREMOCK.port() + RESOURCE_PATH);
        resourceConnector = new ResourceServiceConnector(properties);
```

Then the tests:

```java
    @Test
    @DisplayName("resources.grant should grant and register an undo that revokes")
    void testResourceGrantRegistersRevokingUndo() {
        stubResourceGrantReturning(REQUEST_ID);
        ResourcesGrantStep grant = new ResourcesGrantStep(resourceConnector);

        StepOutcome outcome = grant.execute(Map.of(
                "user", USER_DN,
                "resource", RESOURCE_DN,
                "reason", "Automated test"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(REQUEST_ID);
        assertThat(outcome.undo()).isNotNull();

        // revokeResource takes exactly what grantResource was given, so the inverse is
        // faithful rather than guessed — which is why this pair has an undo and roles
        // does not.
        stubResourceRevokeReturning(REQUEST_ID);
        outcome.undo().run();
        verify(postRequestedFor(urlEqualTo(RESOURCE_PATH)));
    }

    @Test
    @DisplayName("resources.grant should fail rather than throw when the service errors")
    void testResourceGrantServiceErrorFails() {
        stubFor(post(urlEqualTo(RESOURCE_PATH)).willReturn(aResponse().withStatus(500)));
        ResourcesGrantStep grant = new ResourcesGrantStep(resourceConnector);

        StepOutcome outcome = grant.execute(
                Map.of("user", USER_DN, "resource", RESOURCE_DN), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("resources.revoke should revoke and register no undo")
    void testResourceRevokeRegistersNoUndo() {
        stubResourceRevokeReturning(REQUEST_ID);
        ResourcesRevokeStep revoke = new ResourcesRevokeStep(resourceConnector);

        StepOutcome outcome = revoke.execute(
                Map.of("user", USER_DN, "resource", RESOURCE_DN), true);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(REQUEST_ID);

        // Re-granting a resource the scenario deliberately revoked would fight the test
        // rather than clean up after it.
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("resources.grant should work without a reason")
    void testResourceGrantWithoutReason() {
        stubResourceGrantReturning(REQUEST_ID);
        ResourcesGrantStep grant = new ResourcesGrantStep(resourceConnector);

        StepOutcome outcome = grant.execute(
                Map.of("user", USER_DN, "resource", RESOURCE_DN), false);

        assertThat(outcome.success()).isTrue();
    }
```

**Write `stubResourceGrantReturning` and `stubResourceRevokeReturning` by copying the exact envelopes from `ResourceServiceConnectorTest`**, which already stubs both operations successfully. Do not invent namespaces.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=SoapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL to compile — the two step classes do not exist.

- [ ] **Step 3: Write ResourcesGrantStep**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/ResourcesGrantStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.generated.resource.RequestResourceGrantResponse;
import com.idm.test.connector.soap.generated.resource.RequestResourceRevokeResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import jakarta.xml.bind.JAXBElement;
import java.util.Map;
import java.util.Set;

/**
 * Grants a resource to a user.
 * <p>
 * Registers an undo, unlike {@code roles.grant}: {@code revokeResource} takes exactly the
 * arguments {@code grantResource} was given, so the inverse is built from inputs already in
 * hand rather than from a server-returned DN whose semantics cannot be checked. That is the
 * same rule that gave {@code ldap.create} an undo and denied one to {@code ldap.delete}.
 */
public final class ResourcesGrantStep implements StepType {

    private final ResourceServiceConnector connector;

    public ResourcesGrantStep(ResourceServiceConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "resources.grant";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("user", "resource");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("user", "resource", "reason");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the request identifier comes back from the call
        // itself, so there is no extra work to skip.
        String user = String.valueOf(params.get("user"));
        String resource = String.valueOf(params.get("resource"));
        Object rawReason = params.get("reason");
        String reason = rawReason == null ? null : String.valueOf(rawReason);

        OperationResult<RequestResourceGrantResponse> result =
                connector.grantResource(user, resource, reason);

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "resources.grant failed for " + user + ": " + result.getMessage());
        }

        String requestId = valueOf(result.getData().get().getResult());

        return StepOutcome.success(
                "granted " + resource + " to " + user,
                () -> {
                    OperationResult<RequestResourceRevokeResponse> revoked =
                            connector.revokeResource(user, resource, reason);
                    if (!revoked.isSuccess()) {
                        throw new IllegalStateException("cleanup failed to revoke " + resource
                                + " from " + user + ": " + revoked.getMessage());
                    }
                },
                requestId);
    }

    /**
     * The service returns a request identifier wrapped in a JAXBElement, which may be null at
     * either level. An absent identifier becomes an empty string rather than null, because a
     * null value with a capture requested fails the scenario — and "the grant succeeded but
     * returned no identifier" is not a failure.
     */
    static String valueOf(JAXBElement<String> result) {
        if (result == null || result.getValue() == null) {
            return "";
        }
        return result.getValue();
    }
}
```

- [ ] **Step 4: Write ResourcesRevokeStep**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/ResourcesRevokeStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.generated.resource.RequestResourceRevokeResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;

/**
 * Revokes a resource from a user.
 * <p>
 * Registers no undo: re-granting a resource the scenario deliberately revoked would fight the
 * test rather than clean up after it. A scenario that wants the resource back grants it again
 * explicitly.
 */
public final class ResourcesRevokeStep implements StepType {

    private final ResourceServiceConnector connector;

    public ResourcesRevokeStep(ResourceServiceConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "resources.revoke";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("user", "resource");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("user", "resource", "reason");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String user = String.valueOf(params.get("user"));
        String resource = String.valueOf(params.get("resource"));
        Object rawReason = params.get("reason");

        OperationResult<RequestResourceRevokeResponse> result = connector.revokeResource(
                user, resource, rawReason == null ? null : String.valueOf(rawReason));

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "resources.revoke failed for " + user + ": " + result.getMessage());
        }

        return StepOutcome.success(
                "revoked " + resource + " from " + user, null,
                ResourcesGrantStep.valueOf(result.getData().get().getResult()));
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=SoapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 6: Commit**

```bash
git add idm-test-engine/src/main/java/com/idm/test/engine/steps/ResourcesGrantStep.java \
        idm-test-engine/src/main/java/com/idm/test/engine/steps/ResourcesRevokeStep.java \
        idm-test-engine/src/test/java/com/idm/test/engine/steps/SoapStepsTest.java
git commit -m "feat: add resources.grant and resources.revoke step types

resources.grant registers an undo where roles.grant does not:
revokeResource takes exactly the arguments grantResource was given, so
the inverse is built from inputs already in hand rather than a
server-returned DN whose semantics cannot be checked. Same rule that
gave ldap.create an undo and denied one to ldap.delete.

resources.revoke registers none: re-granting a resource the scenario
deliberately revoked would fight the test, not clean up after it.

ITF-9

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: rest.request

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestRequestStep.java`
- Create: `idm-test-engine/src/test/java/com/idm/test/engine/steps/RestStepsTest.java`

**Interfaces:**
- Consumes: `IdmRestConnector.exchange(String, String, Object, Map<String,String>)` → `OperationResult<RestResponse>` (Task 1); `RestProperties.getApiConfig(String)` → `RestApiConfig` with `getBaseUrl()`.
- Produces: `new RestRequestStep(IdmRestConnector, RestProperties)`, `name()` = `"rest.request"`, requires `target`, `method` and `path`, also accepts `body`, `bodyFrom` and `headers`, not retryable, `value()` is a `Map` of `{status, headers, body}`.

YAML shape (spec §7.1, with decision 5 applied):

```yaml
- rest.request:
    target: idm
    method: POST
    path: /users
    bodyFrom: "${user}"
    as: created
```

`${created.body.id}` then resolves, because `Interpolator.lookup` walks Maps and the capture is a Map of Maps.

**`body` and `bodyFrom` are mutually exclusive.** They mean the same thing now that Task 2 makes `body: "${user}"` work; `bodyFrom` is kept because the spec names it. Supplying both is an authoring mistake and must fail rather than silently picking one.

- [ ] **Step 1: Write the failing tests**

Create `idm-test-engine/src/test/java/com/idm/test/engine/steps/RestStepsTest.java`:

```java
package com.idm.test.engine.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.engine.spi.StepOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the REST step types against WireMock.
 * <p>
 * Builds the connector directly rather than through Spring, because this module has no Spring
 * context by design.
 */
@DisplayName("REST step types")
class RestStepsTest {

    private static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
        WireMock.configureFor("localhost", WIREMOCK.port());
    }

    private static final IdmRestConnector CONNECTOR =
            new IdmRestConnector(RestClient.builder().build());

    private static final RestProperties PROPERTIES = properties();

    private static RestProperties properties() {
        RestProperties properties = new RestProperties();
        RestProperties.RestApiConfig config = new RestProperties.RestApiConfig();
        config.setBaseUrl("http://localhost:" + WIREMOCK.port());
        properties.getRest().put("idm", config);
        return properties;
    }

    @AfterAll
    static void stopWireMock() {
        if (WIREMOCK.isRunning()) {
            WIREMOCK.stop();
        }
    }

    @BeforeEach
    void resetStubs() {
        WIREMOCK.resetAll();
    }

    @Test
    @DisplayName("rest.request should capture status, headers and body")
    void testRequestCapturesFullResponse() {
        stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":1,\"userId\":\"jdoe\"}")));

        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/1"), true);

        assertThat(outcome.success()).isTrue();
        Map<?, ?> captured = (Map<?, ?>) outcome.value();
        assertThat(captured.get("status")).isEqualTo(200);
        assertThat(captured).containsKey("headers");
        assertThat((Map<?, ?>) captured.get("body")).containsEntry("userId", "jdoe");
    }

    @Test
    @DisplayName("rest.request should send bodyFrom as a JSON object")
    void testRequestSendsBodyFrom() {
        stubFor(post(urlEqualTo("/users")).willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":7}")));

        // Task 2 makes "${user}" resolve to the map itself; before that it arrived as
        // Java's Map.toString and could never have been sent as JSON.
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "method", "POST", "path", "/users",
                "bodyFrom", Map.of("userId", "jdoe", "department", "Engineering")), false);

        assertThat(outcome.success()).isTrue();
        verify(postRequestedFor(urlEqualTo("/users"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo("jdoe"))));
    }

    @Test
    @DisplayName("rest.request should succeed and capture the status for a 404")
    void testRequestCapturesErrorStatus() {
        stubFor(get(urlEqualTo("/users/missing"))
                .willReturn(aResponse().withStatus(404)));

        // The request was made and answered; 404 is the answer. Only a transport failure is
        // a step failure.
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/missing"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(((Map<?, ?>) outcome.value()).get("status")).isEqualTo(404);
    }

    @Test
    @DisplayName("rest.request should fail for an unknown target")
    void testRequestUnknownTargetFails() {
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "nope", "method", "GET", "path", "/users/1"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("nope");
    }

    @Test
    @DisplayName("rest.request should reject body and bodyFrom together")
    void testRequestRejectsBothBodyForms() {
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "method", "POST", "path", "/users",
                "body", Map.of("a", 1),
                "bodyFrom", Map.of("b", 2)), false);

        // They mean the same thing, so supplying both says nothing about which was intended.
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("body").contains("bodyFrom");
    }

    @Test
    @DisplayName("rest.request should send custom headers")
    void testRequestSendsHeaders() {
        stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse().withStatus(200)));

        new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/1",
                "headers", Map.of("X-Correlation-Id", "corr-9")), false);

        verify(getRequestedFor(urlEqualTo("/users/1"))
                .withHeader("X-Correlation-Id", equalTo("corr-9")));
    }

    @Test
    @DisplayName("rest.request should fail rather than throw on a transport error")
    void testRequestTransportFailure() {
        RestProperties dead = new RestProperties();
        RestProperties.RestApiConfig config = new RestProperties.RestApiConfig();
        config.setBaseUrl("http://localhost:1");
        dead.getRest().put("idm", config);

        StepOutcome outcome = new RestRequestStep(CONNECTOR, dead).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/1"), false);

        assertThat(outcome.success()).isFalse();
    }
}
```

If `RestProperties.RestApiConfig` has no public no-arg constructor or no `setBaseUrl`, read the class and build it however it allows; the requirement is a `RestProperties` whose `idm` target points at WireMock.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=RestStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL to compile — `RestRequestStep` does not exist.

- [ ] **Step 3: Write RestRequestStep**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestRequestStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.RestResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Makes one HTTP request against a configured target.
 * <p>
 * An HTTP error status is an outcome, not a failure: a 404 produces a successful step whose
 * captured {@code status} is 404, so a scenario can assert that an account is gone. Only a
 * transport-level problem fails the step.
 */
public final class RestRequestStep implements StepType {

    private final IdmRestConnector connector;
    private final RestProperties properties;

    public RestRequestStep(IdmRestConnector connector, RestProperties properties) {
        this.connector = connector;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "rest.request";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("target", "method", "path");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("target", "method", "path", "body", "bodyFrom", "headers");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String target = String.valueOf(params.get("target"));

        if (params.containsKey("body") && params.containsKey("bodyFrom")) {
            // They mean the same thing, so supplying both says nothing about which was
            // intended. Guessing would make the scenario's behaviour depend on our ordering.
            return StepOutcome.failure("rest.request supplies both 'body' and 'bodyFrom'; "
                    + "they mean the same thing, so give only one");
        }

        String baseUrl = baseUrlOf(target);
        if (baseUrl == null) {
            return StepOutcome.failure("rest.request names unknown target '" + target
                    + "'. Configured targets: " + new TreeSet<>(properties.getRest().keySet()));
        }

        String url = baseUrl + String.valueOf(params.get("path"));
        Object body = params.containsKey("bodyFrom")
                ? params.get("bodyFrom")
                : params.get("body");

        Map<String, String> headers = new LinkedHashMap<>();
        StepValues.toMap(params.get("headers"))
                .forEach((key, value) -> headers.put(key, String.valueOf(value)));

        OperationResult<RestResponse> result = connector.exchange(
                String.valueOf(params.get("method")), url, body, headers);

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "rest.request failed for " + url + ": " + result.getMessage());
        }

        RestResponse response = result.getData().get();

        return StepOutcome.success(
                String.valueOf(params.get("method")) + " " + url + " returned " + response.status(),
                null,
                captureOf(response));
    }

    /**
     * @return the target's base URL, or null when the target is not configured
     */
    private String baseUrlOf(String target) {
        RestProperties.RestApiConfig config = properties.getRest().get(target);
        return config == null ? null : config.getBaseUrl();
    }

    /**
     * The {status, headers, body} view a scenario sees through {@code as:}, as a plain Map so
     * {@code ${created.body.id}} resolves — Interpolator.lookup walks Maps.
     */
    private static Map<String, Object> captureOf(RestResponse response) {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("status", response.status());
        capture.put("headers", response.headers());
        capture.put("body", response.body());
        return capture;
    }
}
```

Note `captureOf` puts `body` even when it is null: the capture itself is a non-null Map, so the step satisfies the "null value with a capture requested fails the scenario" rule. A scenario referencing `${created.body}` on a bodiless response gets the same "cannot resolve" error it would get for any absent value, which is the honest outcome.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=RestStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 5: Commit**

```bash
git add idm-test-engine/src/main/java/com/idm/test/engine/steps/RestRequestStep.java \
        idm-test-engine/src/test/java/com/idm/test/engine/steps/RestStepsTest.java
git commit -m "feat: add rest.request step type

Captures {status, headers, body} as a plain map so \${created.body.id}
resolves. An HTTP error status is an outcome rather than a failure: a
404 is a successful step whose captured status is 404, which is what
lets a scenario assert that an account is gone.

body and bodyFrom mean the same thing now that a whole-value reference
preserves its type, so supplying both fails rather than silently
picking one.

ITF-9

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: rest.verify

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestVerifyStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/RestStepsTest.java`

**Interfaces:**
- Consumes: the same `exchange` and `RestProperties`.
- Produces: `new RestVerifyStep(IdmRestConnector, RestProperties)`, `name()` = `"rest.verify"`, requires `target`, `path` and `expect`, also accepts `method` (default GET), `headers` and `wait`, **retryable**, `value()` is the same `{status, headers, body}` map.

YAML shape:

```yaml
- rest.verify:
    target: idm
    path: "/users/${user.userId}"
    expect:
      status: 200
      body:
        department: Engineering
    wait: { timeout: 30s, poll: 2s }
```

`expect` accepts only `status` and `body` — reserved keys that never share a namespace with the payload, so a response with a field named `status` cannot change how the scenario parses. An empty `expect`, or an unknown key inside it, fails: this defect class has now appeared three times in this project.

`body` matches **a subset** of the response body: only the keys named are compared, so a scenario need not restate an entire payload. Nested maps are compared recursively by the same rule.

- [ ] **Step 1: Write the failing tests**

Add to `RestStepsTest`:

```java
    @Test
    @DisplayName("rest.verify should pass when status and body both match")
    void testVerifyMatches() {
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"userId\":\"jdoe\",\"department\":\"Engineering\"}")));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("status", 200,
                        "body", Map.of("department", "Engineering"))), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("rest.verify should match a subset of the body")
    void testVerifyMatchesBodySubset() {
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"userId\":\"jdoe\",\"department\":\"Engineering\",\"extra\":1}")));

        // A scenario should not have to restate a whole payload to assert one field.
        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("body", Map.of("userId", "jdoe"))), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("rest.verify should assert a 404 as an expected status")
    void testVerifyExpectsNotFound() {
        stubFor(get(urlEqualTo("/users/gone")).willReturn(aResponse().withStatus(404)));

        // The pattern this whole task exists for: after a deprovision, the account is gone.
        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "path", "/users/gone",
                "expect", Map.of("status", 404)), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("rest.verify should fail on a status mismatch and name both codes")
    void testVerifyStatusMismatchFails() {
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse().withStatus(500)));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("status", 200)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("200").contains("500");
    }

    @Test
    @DisplayName("rest.verify should fail on a body mismatch and name the field")
    void testVerifyBodyMismatchFails() {
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"department\":\"Sales\"}")));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("body", Map.of("department", "Engineering"))), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("department")
                .contains("Engineering").contains("Sales");
    }

    @Test
    @DisplayName("rest.verify should reject an expect block that asserts nothing")
    void testVerifyRejectsEmptyExpect() {
        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of()), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("status").contains("body");
    }

    @Test
    @DisplayName("rest.verify should reject an unknown key inside expect")
    void testVerifyRejectsUnknownExpectKey() {
        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("staus", 200)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("staus");
    }

    @Test
    @DisplayName("rest.verify should be retryable so the engine can wait on provisioning")
    void testVerifyIsRetryable() {
        assertThat(new RestVerifyStep(CONNECTOR, PROPERTIES).retryable()).isTrue();
    }

    @Test
    @DisplayName("rest.verify should default to GET")
    void testVerifyDefaultsToGet() {
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse().withStatus(200)));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("status", 200)), false);

        assertThat(outcome.success()).isTrue();
        verify(getRequestedFor(urlEqualTo("/users/jdoe")));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=RestStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL to compile — `RestVerifyStep` does not exist.

- [ ] **Step 3: Write RestVerifyStep**

Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestVerifyStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.RestResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Asserts that an HTTP call returns the expected status and body.
 * <p>
 * Retryable: provisioning is asynchronous, so the engine may call this repeatedly under a
 * {@code wait} until it passes or the timeout expires.
 * <p>
 * {@code status} and {@code body} are reserved keys inside {@code expect}, never merged with
 * the payload, so a response with its own field named {@code status} cannot change how a
 * scenario parses (spec §7.3).
 */
public final class RestVerifyStep implements StepType {

    private static final String STATUS = "status";
    private static final String BODY = "body";
    private static final Set<String> EXPECT_KEYS = Set.of(STATUS, BODY);

    private final IdmRestConnector connector;
    private final RestProperties properties;

    public RestVerifyStep(IdmRestConnector connector, RestProperties properties) {
        this.connector = connector;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "rest.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("target", "path", "expect");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("target", "method", "path", "headers", "expect", "wait");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String target = String.valueOf(params.get("target"));
        Map<String, Object> expect = (Map<String, Object>) params.get("expect");

        StepOutcome rejection = rejectMalformedExpect(expect, target);
        if (rejection != null) {
            return rejection;
        }

        RestProperties.RestApiConfig config = properties.getRest().get(target);
        if (config == null) {
            return StepOutcome.failure("rest.verify names unknown target '" + target
                    + "'. Configured targets: " + new TreeSet<>(properties.getRest().keySet()));
        }

        String url = config.getBaseUrl() + String.valueOf(params.get("path"));
        Object rawMethod = params.get("method");
        String method = rawMethod == null ? "GET" : String.valueOf(rawMethod);

        Map<String, String> headers = new LinkedHashMap<>();
        StepValues.toMap(params.get("headers"))
                .forEach((key, value) -> headers.put(key, String.valueOf(value)));

        OperationResult<RestResponse> result =
                connector.exchange(method, url, null, headers);

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "rest.verify failed for " + url + ": " + result.getMessage());
        }

        RestResponse response = result.getData().get();

        if (expect.containsKey(STATUS)) {
            Object expectedStatus = expect.get(STATUS);
            if (!String.valueOf(expectedStatus).equals(String.valueOf(response.status()))) {
                return StepOutcome.failure("rest.verify on " + url + " expected status "
                        + expectedStatus + " but got " + response.status());
            }
        }

        if (expect.containsKey(BODY)) {
            String mismatch = mismatchIn(
                    StepValues.toMap(expect.get(BODY)), response.body(), "");
            if (mismatch != null) {
                return StepOutcome.failure("rest.verify on " + url + " " + mismatch);
            }
        }

        String message = method + " " + url + " verified, status " + response.status();
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("status", response.status());
        capture.put("headers", response.headers());
        capture.put("body", response.body());

        return captureRequested
                ? StepOutcome.success(message, null, capture)
                : StepOutcome.success(message);
    }

    /**
     * Compares only the fields the scenario named, recursing into nested maps, so a scenario
     * need not restate a whole payload to assert one field.
     *
     * @return a description of the first mismatch, or null when everything named matches
     */
    @SuppressWarnings("unchecked")
    private static String mismatchIn(Map<String, Object> expected, Object actual, String path) {
        if (!(actual instanceof Map<?, ?> actualMap)) {
            return "expected an object at '" + (path.isEmpty() ? "body" : path)
                    + "' but the response held: " + actual;
        }

        for (Map.Entry<String, Object> field : expected.entrySet()) {
            String where = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
            Object actualValue = ((Map<String, Object>) actualMap).get(field.getKey());

            if (field.getValue() instanceof Map<?, ?> nested) {
                String deeper = mismatchIn((Map<String, Object>) nested, actualValue, where);
                if (deeper != null) {
                    return deeper;
                }
                continue;
            }

            // Compared as text so a JSON number and a YAML number agree without either side
            // having to know which boxed type Jackson chose.
            if (!String.valueOf(field.getValue()).equals(String.valueOf(actualValue))) {
                return "found a mismatch for '" + where + "': expected="
                        + field.getValue() + ", actual=" + actualValue;
            }
        }
        return null;
    }

    /**
     * An {@code expect} that asserts nothing, or one whose keys are typos, would pass having
     * compared nothing at all — the vacuous-assertion trap, now caught three times in this
     * project. Checked before the request is made, since neither problem depends on the
     * response.
     */
    private static StepOutcome rejectMalformedExpect(Map<String, Object> expect, String target) {
        if (expect == null || expect.isEmpty()) {
            return StepOutcome.failure("rest.verify on '" + target
                    + "' has an empty expect: block. Assert " + STATUS + ", " + BODY
                    + ", or both — an empty expect would verify nothing.");
        }

        Set<String> unknown = new TreeSet<>(expect.keySet());
        unknown.removeAll(EXPECT_KEYS);
        if (!unknown.isEmpty()) {
            return StepOutcome.failure("rest.verify on '" + target
                    + "' has unknown key(s) in expect: " + unknown + ". expect accepts: "
                    + new TreeSet<>(EXPECT_KEYS));
        }

        return null;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl idm-test-engine -am test -Dtest=RestStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors.

- [ ] **Step 5: Commit**

```bash
git add idm-test-engine/src/main/java/com/idm/test/engine/steps/RestVerifyStep.java \
        idm-test-engine/src/test/java/com/idm/test/engine/steps/RestStepsTest.java
git commit -m "feat: add rest.verify step type

status and body are reserved keys inside expect, never merged with the
payload, so a response with its own field named status cannot change
how a scenario parses. body matches a subset so a scenario need not
restate a whole payload to assert one field.

An empty or misspelled expect: fails rather than verifying nothing —
the same trap caught on ldap.verify and db.verify.

ITF-9

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Register the six step types

**Files:**
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioValidationTest.java` (call site only)
- Create: `idm-test-scenarios/src/test/resources/scenarios-vocabulary-fixture/full-vocabulary.yaml`

**Interfaces:**
- Consumes: all six step classes, plus `RoleServiceConnector`, `ResourceServiceConnector`, `IdmRestConnector` and `RestProperties`.
- Produces: a registry containing all twelve step types.

**Deliberate limitation, matching the ticket's own framing.** ITF-8 ended with a YAML scenario that really ran, because the embedded directory and H2 were already wired into `BaseIntegrationTest`. Here there is nothing to run against: `BaseIntegrationTest` does not inject `IdmRestConnector` at all, and the SOAP and REST targets in `application.yml` point at `idm.company.com`, which does not resolve. Standing up WireMock inside the scenarios module to fake a whole IdM would be a larger piece of work than the six step types themselves, and it would test the fake.

So this task registers the types and proves the **authoring contract** — that a scenario using all twelve parses and passes `ScenarioValidator` — without executing the SOAP and REST steps. Those are already driven against WireMock by `SoapStepsTest` and `RestStepsTest`. As ITF-9's own description says, these step types "will get their first real exercise when the framework is pointed at a real environment."

- [ ] **Step 1: Check what BaseIntegrationTest already provides**

Run:

```bash
grep -n "Autowired" -A2 idm-test-scenarios/src/test/java/com/idm/test/scenarios/base/BaseIntegrationTest.java
```

Expected: `edirectoryConnector`, `databaseConnector`, `roleServiceConnector`, `testExecutor`, `executorProperties`, `testDataGenerator`. There is **no** `IdmRestConnector`, `ResourceServiceConnector` or `RestProperties`. Add the three that are missing as `@Autowired` fields alongside the others, following the existing style exactly.

If adding `@Autowired IdmRestConnector` breaks the Spring context because no `RestClient` bean is in scope, add `RestClientConfiguration` and `RestProperties` to whatever `@SpringBootTest` configuration the class uses. Report in your task report what you had to add.

- [ ] **Step 2: Widen the registry**

In `YamlScenarioTest`, change the `registry` method to take the new connectors and register all twelve types:

```java
    static Map<String, StepType> registry(
            EdirectoryConnector edirectoryConnector,
            MultiDatabaseConnector databaseConnector,
            RoleServiceConnector roleServiceConnector,
            ResourceServiceConnector resourceServiceConnector,
            IdmRestConnector restConnector,
            RestProperties restProperties) {
        Map<String, StepType> registry = new LinkedHashMap<>();
        for (StepType type : List.of(
                new LdapCreateStep(edirectoryConnector),
                new LdapVerifyStep(edirectoryConnector),
                new LdapUpdateStep(edirectoryConnector),
                new LdapDeleteStep(edirectoryConnector),
                new DbExecuteStep(databaseConnector),
                new DbVerifyStep(databaseConnector),
                new RolesGrantStep(roleServiceConnector),
                new RolesRevokeStep(roleServiceConnector),
                new ResourcesGrantStep(resourceServiceConnector),
                new ResourcesRevokeStep(resourceServiceConnector),
                new RestRequestStep(restConnector, restProperties),
                new RestVerifyStep(restConnector, restProperties))) {
            registry.put(type.name(), type);
        }
        return registry;
    }
```

Update both call sites — `YamlScenarioTest.scenarios()` and `YamlScenarioValidationTest` — and add the imports.

- [ ] **Step 3: Add a fixture scenario covering the whole vocabulary**

Create `idm-test-scenarios/src/test/resources/scenarios-vocabulary-fixture/full-vocabulary.yaml`.

**A new directory, deliberately.** `scenarios-validation-fixture/` cannot take a third file: `YamlScenarioValidationTest` asserts `containsExactlyInAnyOrder("valid-user.yaml", "broken-wait.yaml")`, so adding one there would break a test that is about something else entirely. And it must not go in `scenarios/`, which really executes its files:

```yaml
name: Full vocabulary
description: Every step type, used only to prove the authoring contract validates

fixtures:
  user:
    generate: random-user

steps:
  - set:
      accountPath: "js:{ '/users/' + user.userId }"

  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      attributes:
        cn: "${user.userId}"
        sn: "${user.lastName}"

  - ldap.update:
      dn: "cn=${user.userId},ou=users"
      attributes:
        title: Engineer

  - ldap.verify:
      dn: "cn=${user.userId},ou=users"
      expect:
        title: Engineer

  - db.execute:
      database: test-db
      statement: "INSERT INTO sap_users (username, status) VALUES (?, ?)"
      params: ["${user.userId}", "ACTIVE"]

  - db.verify:
      database: test-db
      query: "SELECT status FROM sap_users WHERE username = ?"
      params: ["${user.userId}"]
      expect:
        rowCount: 1
        row: { status: ACTIVE }
      wait: true

  - roles.grant:
      user: "cn=${user.userId},ou=users"
      roles: ["cn=SAP_USER,ou=roles"]
      as: granted

  - roles.revoke:
      assignments: "${granted}"

  - resources.grant:
      user: "cn=${user.userId},ou=users"
      resource: "cn=SAP_ACCESS,ou=resources"
      reason: Automated test

  - resources.revoke:
      user: "cn=${user.userId},ou=users"
      resource: "cn=SAP_ACCESS,ou=resources"

  - rest.request:
      target: idm
      method: POST
      path: /users
      bodyFrom: "${user}"
      as: created

  - rest.verify:
      target: idm
      path: "${accountPath}"
      expect:
        status: 200
        body:
          userId: "${user.userId}"
      wait: { timeout: 30s, poll: 2s }

  - ldap.delete:
      dn: "cn=${user.userId},ou=users"
```

- [ ] **Step 4: Write the validation test**

Add to `YamlScenarioValidationTest`. That class extends `BaseIntegrationTest`, so the connectors are inherited fields. Load the file directly with `ScenarioLoader.load(InputStream, String)` and drive `ScenarioValidator` yourself — do **not** route this through `YamlScenarioTest.buildTests`, which executes the scenarios it builds, and this one has no IdM to execute against.

```java
    @Test
    @DisplayName("A scenario using every step type should load and validate")
    void testFullVocabularyValidates() {
        Map<String, StepType> registry = YamlScenarioTest.registry(
                edirectoryConnector, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties);
        ScenarioValidator validator =
                new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS);

        Scenario scenario;
        try (InputStream yaml = getClass().getClassLoader()
                .getResourceAsStream("scenarios-vocabulary-fixture/full-vocabulary.yaml")) {
            assertThat(yaml).isNotNull();
            scenario = ScenarioLoader.load(yaml, "full-vocabulary.yaml");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Proves the authoring contract: every step name is registered, every parameter is
        // accepted, and every ${...} reference resolves in order. The SOAP and REST steps are
        // exercised for real against WireMock in SoapStepsTest and RestStepsTest.
        assertThatCode(() -> validator.validate(scenario, "full-vocabulary.yaml"))
                .doesNotThrowAnyException();
    }
```

Add imports: `com.idm.test.engine.ScenarioLoader`, `com.idm.test.engine.model.Scenario`, `java.io.InputStream`, `java.io.IOException`, `java.io.UncheckedIOException`. `assertThatCode` and `assertThat` are already imported in that class.

- [ ] **Step 5: Run the scenarios module**

Run: `./mvnw -pl idm-test-scenarios -am test -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, 0 failures, 0 errors. The existing `user-provisioning.yaml` and `database-provisioning.yaml` must still run and pass.

- [ ] **Step 6: Run the whole suite**

Run: `./mvnw clean test`

Expected: BUILD SUCCESS, 0 failures, 0 errors, **0 skipped**. Record the exact total in your task report.

- [ ] **Step 7: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java \
        idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioValidationTest.java \
        idm-test-scenarios/src/test/java/com/idm/test/scenarios/base/BaseIntegrationTest.java \
        idm-test-scenarios/src/test/resources/scenarios-vocabulary-fixture/full-vocabulary.yaml
git commit -m "feat: register the SOAP and REST step types

All twelve step types are now registered, and a fixture scenario using
every one of them proves the authoring contract validates: every name
registered, every parameter accepted, every reference resolving in
order.

The SOAP and REST steps are not executed here. BaseIntegrationTest has
no IdM to talk to and the configured targets do not resolve; faking a
whole IdM inside the scenarios module would test the fake. They run
against WireMock in SoapStepsTest and RestStepsTest, and get their
first real exercise against a live environment.

ITF-9

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage**

| Spec requirement | Task |
|---|---|
| §7.3 `roles.grant` / `roles.revoke` | Task 3 |
| §7.3 `resources.grant` / `resources.revoke` | Task 4 |
| §7.3 `rest.request` | Task 5 |
| §7.3 `rest.verify` (assertion, retryable) | Task 6 |
| §7.5 `as:` binds `{status, headers, body}` for `rest.request` | Tasks 1 and 5 |
| §7.2 `wait` on assertions | Task 6 (`retryable()` true) |
| §7.3 reserved keys never share a namespace with user data | Tasks 5 and 6 (`bodyFrom` sibling key; `status`/`body` inside `expect`) |
| §7.6 action steps register their undo | Task 4; Task 3 is the owner-approved exception |
| §7.1 `bodyFrom: user` as a bare name | **Amended** — takes a reference; see decision 5 |

**2. Placeholder scan.** No TBD/TODO. One place asks the implementer to copy from an existing file rather than reproducing it here — the resource-service envelopes in `ResourceServiceConnectorTest`, whose namespaces must match what JAX-WS unmarshals. The role-service envelopes are given in full instead, because the existing helper there does **not** match the generated schema and copying it would produce an empty capture (Task 3 explains why). The validation test in Task 7 is likewise given in full, because `YamlScenarioValidationTest` has no fixture-loading helper to reuse.

**3. Type consistency.** `StepOutcome.success(String)`, `success(String, Runnable, Object)` and `failure(String)` are the only factories used. `execute(Map<String, Object>, boolean)` is the single abstract method throughout. `StepValues.toStringList` / `toMap` are defined in Task 3 and used in Tasks 3, 5 and 6. `SoapDns.of` is defined in Task 3 and used in Task 3 only. `ResourcesGrantStep.valueOf` is package-private static and reused by `ResourcesRevokeStep` in Task 4. `RestResponse(int, Map<String,List<String>>, Object)` is defined in Task 1 and consumed in Tasks 5 and 6.

**4. Known gaps, recorded rather than hidden.**

- The SOAP and REST step types are never executed through a YAML scenario, only validated (Task 7's stated limitation). Their execution coverage is `SoapStepsTest` and `RestStepsTest` against WireMock.
- `roles.grant`'s captured assignment DNs are assumed to be what `removeRoles` consumes. The shapes match and the mechanism is tested end to end against a stub, but the real eDirectory semantics are unproven until a live environment exists. This is why the grant registers no undo.
- `expect`-block contents are still validated when a step runs, not at load, for `db.verify` and now `rest.verify` too. Spec §7.7 wants load-time. Closing it needs a validation hook on `StepType`; the case for adding one is stronger now that three step types hand-roll the same guard.
- `rest.verify` compares values as text, so `200` and `"200"` match. That is deliberate — JSON and YAML disagree about number types — but it means a scenario cannot assert that a field is genuinely numeric.
- `RoleServiceConnectorTest.createRequestRolesAssignmentResponse` stubs `<tns:result>` as a plain string while the generated type is `JAXBElement<DNStringArray>`. Its own assertions never look inside the result, so the mismatch is invisible there — but it is a stub that does not match the schema it stands in for. Not fixed here (it is ITF-1/ITF-6 territory and changing it risks that suite for no gain in this one); worth a follow-up ticket if the SOAP tests are ever extended to assert on returned DNs.
