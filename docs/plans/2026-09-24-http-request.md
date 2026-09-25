# Generic `http.request` Step Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new `http.request` step sends a raw string body to a named target and captures the raw string response as `{status, headers, body}`. REST and SOAP go through the same step.

**Architecture:**
- **Connector:** `IdmRestConnector.exchangeText` uses the existing Spring `RestClient`. It sends the body as bytes in the right charset, reads the response as bytes, and decodes it with the response's charset. Every status arrives as data.
- **Engine:** `HttpRequestStep` validates the parameters, resolves the target through `RestTargets`, and captures the response.
- **Scenarios module:** registers the step, puts it in the vocabulary fixture, and extends guard 4 to `http.*` steps.

**Tech Stack:** Java 21, Spring 6.2 `RestClient`, JUnit 5, AssertJ, WireMock.

**Spec:** `docs/specs/2026-09-24-http-request-design.md`. **Ticket:** ITF-22.

## Global Constraints

- **The step:** name `http.request`. Required: `target`, `method`, `path`. Accepted: `target`, `method`, `path`, `headers`, `body`. Not retryable.
- **Methods:** `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`, in any letter case. Anything else fails the step.
- **A non-string `body` fails** with exactly: `http.request body must be a string sent as-is; to send a JSON object use rest.request`.
- **A non-map `headers` fails** with: `http.request headers must be a map of header name to value`.
- **Default `Content-Type`,** when there is a body and no `Content-Type` header (compared case-insensitively), is `text/plain; charset=UTF-8`.
- **Request body charset:** UTF-8 unless the `Content-Type` names another.
- **`Accept`:** if none is given, `Accept: */*` is sent.
- **The capture** is a map of `status` (int), `headers` (lower-case name → list of values) and `body` (a String, never null; `""` when empty).
- **Any HTTP status succeeds,** with the message `<METHOD> <url> returned <status>`. A transport failure fails with `http.request failed for <url>: <reason>`.
- **Guard 4** collects targets from steps whose type starts with `rest.` **or `http.`**.
- **No change** to `IdmRestConnector.exchange`, `rest.request`, `rest.verify` or `RestResponse`.
- **`idm-test-engine/src/main` must never import `org.springframework`.**
- Every refusal is falsification-tested.
- `-pl` is always paired with `-am`. Single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`.
- If you see "Unresolved compilation problems", that is stale IDE output. Run `./mvnw clean test` and do not change code.
- Branch: `feat/http-request` off `main`.
- Commit messages end with:
  ```
  ITF-22

  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```

Paths use `R` = `idm-connector-rest/src/main/java/com/idm/test/connector/rest`, `E` = `idm-test-engine/src/main/java/com/idm/test/engine`, `ET` = `idm-test-engine/src/test/java/com/idm/test/engine`, and `ST` = `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine`.

---

### Task 0: Branch and baseline

- [ ] **Step 1:** `git checkout main && git checkout -b feat/http-request`
- [ ] **Step 2:** Run `./mvnw clean test`, then:

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
```

Expected: `BUILD SUCCESS` and **533**, with no non-zero `failures=`, `errors=` or `skipped=`.

---

### Task 1: `exchangeText` and `HttpRequestStep`

**Files:**
- Create: `R/model/TextResponse.java`, `E/steps/HttpRequestStep.java`, `ET/steps/HttpStepsTest.java`
- Modify: `R/client/IdmRestConnector.java` (add `exchangeText`, plus one constant)

**Interfaces:**
- Consumes (existing):
  - `RestTargets.resolveBaseUrl(String stepName, String target, RestProperties)` → `Resolution`, with `isFailure()`, `error()` and `baseUrl()`
  - `StepOutcome.success(String, Object)` / `failure(String)`
  - `OperationResult` with its builder, `isSuccess()`, `getData()` and `getMessage()`
  - `IdmRestConnector`'s private `determineErrorType(Exception)`, used by `exchange`
- Produces:
  - `record TextResponse(int status, Map<String, List<String>> headers, String body)`
  - `IdmRestConnector.exchangeText(String method, String url, String body, Map<String, String> headers)` → `OperationResult<TextResponse>`
  - `HttpRequestStep(IdmRestConnector connector, RestProperties properties)`, named `http.request`

- [ ] **Step 1: Write the failing tests**

Create `ET/steps/HttpStepsTest.java`:

```java
package com.idm.test.engine.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.ValidationException;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.spi.StepOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives http.request against WireMock: raw string in, raw string out, REST and SOAP alike.
 */
@DisplayName("http.request")
class HttpStepsTest {

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
        RestProperties.RestApiConfig mock = new RestProperties.RestApiConfig();
        mock.setBaseUrl("http://localhost:" + WIREMOCK.port());
        properties.getRest().put("mock", mock);
        RestProperties.RestApiConfig down = new RestProperties.RestApiConfig();
        down.setBaseUrl("http://localhost:1");
        properties.getRest().put("down", down);
        return properties;
    }

    private static final HttpRequestStep STEP = new HttpRequestStep(CONNECTOR, PROPERTIES);

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

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> capture(StepOutcome outcome) {
        return (Map<String, Object>) outcome.value();
    }

    @Test
    @DisplayName("Sends a JSON body byte-for-byte with the author's Content-Type, in any method case")
    void testJsonBodySentAsIs() {
        stubFor(post(urlEqualTo("/users")).willReturn(aResponse().withStatus(201).withBody("{\"id\":7}")));

        StepOutcome outcome = STEP.execute(params(
                "target", "mock", "method", "post", "path", "/users",
                "headers", Map.of("Content-Type", "application/json"),
                "body", "{\"userId\":\"jdoe\"}"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(capture(outcome)).containsEntry("status", 201).containsEntry("body", "{\"id\":7}");
        verify(postRequestedFor(urlEqualTo("/users"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalTo("{\"userId\":\"jdoe\"}")));
    }

    @Test
    @DisplayName("Sends a SOAP envelope with SOAPAction and returns the XML response as raw text")
    void testSoapEnvelope() {
        String envelope = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><getUser>jdoe</getUser></soapenv:Body></soapenv:Envelope>";
        String reply = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><user>jdoe</user></soapenv:Body></soapenv:Envelope>";
        stubFor(post(urlEqualTo("/IDMProv/role/service")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "text/xml; charset=UTF-8").withBody(reply)));

        StepOutcome outcome = STEP.execute(params(
                "target", "mock", "method", "POST", "path", "/IDMProv/role/service",
                "headers", Map.of("Content-Type", "text/xml; charset=UTF-8", "SOAPAction", "urn:getUser"),
                "body", envelope), true);

        assertThat(outcome.success()).isTrue();
        assertThat(capture(outcome).get("body")).isEqualTo(reply);
        verify(postRequestedFor(urlEqualTo("/IDMProv/role/service"))
                .withHeader("SOAPAction", equalTo("urn:getUser"))
                .withHeader("Content-Type", containing("text/xml"))
                .withRequestBody(equalTo(envelope)));
    }

    @Test
    @DisplayName("Captures an error status as a successful step, with the status in the message")
    void testErrorStatusCaptured() {
        stubFor(get(urlEqualTo("/boom")).willReturn(aResponse().withStatus(500).withBody("boom")));

        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "GET", "path", "/boom"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.message()).contains("returned 500");
        assertThat(capture(outcome)).containsEntry("status", 500).containsEntry("body", "boom");
    }

    @Test
    @DisplayName("Defaults Content-Type to text/plain; charset=UTF-8 and Accept to */*")
    void testDefaultContentTypeAndAccept() {
        stubFor(post(urlEqualTo("/echo")).willReturn(aResponse().withStatus(200)));

        STEP.execute(params("target", "mock", "method", "POST", "path", "/echo", "body", "héllo"), true);

        verify(postRequestedFor(urlEqualTo("/echo"))
                .withHeader("Content-Type", containing("text/plain"))
                .withHeader("Content-Type", containing("UTF-8"))
                .withHeader("Accept", equalTo("*/*"))
                .withRequestBody(equalTo("héllo")));
    }

    @Test
    @DisplayName("Refuses a map body without sending anything")
    void testMapBodyRefused() {
        StepOutcome outcome = STEP.execute(params(
                "target", "mock", "method", "POST", "path", "/users", "body", Map.of("a", 1)), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).isEqualTo(
                "http.request body must be a string sent as-is; to send a JSON object use rest.request");
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Refuses non-map headers without sending anything")
    void testNonMapHeadersRefused() {
        StepOutcome outcome = STEP.execute(params(
                "target", "mock", "method", "GET", "path", "/x", "headers", "Accept: text/xml"), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).isEqualTo("http.request headers must be a map of header name to value");
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Lower-cases response header names")
    @SuppressWarnings("unchecked")
    void testHeaderNamesLowerCased() {
        stubFor(get(urlEqualTo("/h")).willReturn(aResponse().withStatus(200).withHeader("X-Custom-Header", "v")));

        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "GET", "path", "/h"), true);

        Map<String, List<String>> headers = (Map<String, List<String>>) capture(outcome).get("headers");
        assertThat(headers).containsEntry("x-custom-header", List.of("v")).doesNotContainKey("X-Custom-Header");
    }

    @Test
    @DisplayName("Decodes a response with its declared non-UTF-8 charset")
    void testNonUtf8ResponseDecoded() {
        stubFor(get(urlEqualTo("/latin")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/plain; charset=ISO-8859-1")
                .withBody("café".getBytes(StandardCharsets.ISO_8859_1))));

        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "GET", "path", "/latin"), true);

        assertThat(capture(outcome).get("body")).isEqualTo("café");
    }

    @Test
    @DisplayName("Captures an empty response body as an empty string, never null")
    void testEmptyBodyIsEmptyString() {
        stubFor(delete(urlEqualTo("/gone")).willReturn(aResponse().withStatus(204)));

        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "DELETE", "path", "/gone"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(capture(outcome)).containsEntry("status", 204).containsEntry("body", "");
    }

    @Test
    @DisplayName("Refuses an unknown method without sending anything")
    void testUnknownMethodRefused() {
        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "FETCH", "path", "/x"), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("FETCH");
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Fails on a transport problem")
    void testTransportFailure() {
        StepOutcome outcome = STEP.execute(params("target", "down", "method", "GET", "path", "/x"), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).startsWith("http.request failed for http://localhost:1/x");
    }

    @Test
    @DisplayName("Fails on an unknown target, naming it")
    void testUnknownTarget() {
        StepOutcome outcome = STEP.execute(params("target", "nope", "method", "GET", "path", "/x"), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("http.request").contains("'nope'");
    }

    @Test
    @DisplayName("Declares its parameters and is an action, not an assertion")
    void testContract() {
        assertThat(STEP.name()).isEqualTo("http.request");
        assertThat(STEP.requiredParams()).containsExactlyInAnyOrder("target", "method", "path");
        assertThat(STEP.acceptedParams()).containsExactlyInAnyOrder("target", "method", "path", "headers", "body");
        assertThat(STEP.retryable()).isFalse();
    }

    @Test
    @DisplayName("Rejects wait: at load time, because it is an action")
    void testWaitRejectedAtLoad() {
        ScenarioValidator validator = new ScenarioValidator(Map.of("http.request", STEP), Set.of());
        Scenario scenario = new Scenario("t", null, Map.of(), List.of(new StepSpec("http.request",
                Map.of("target", "mock", "method", "GET", "path", "/x", "wait", true))));

        assertThatThrownBy(() -> validator.validate(scenario, "t.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'wait' is only valid on assertions");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=HttpStepsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure (`HttpRequestStep` does not exist).

- [ ] **Step 3: `TextResponse`**

Create `R/model/TextResponse.java`:

```java
package com.idm.test.connector.rest.model;

import java.util.List;
import java.util.Map;

/**
 * One HTTP response exactly as it arrived: the status, the headers with lower-case names, and
 * the body as text (never null, empty when there was none).
 */
public record TextResponse(int status, Map<String, List<String>> headers, String body) {
}
```

- [ ] **Step 4: `exchangeText`**

In `R/client/IdmRestConnector.java`:
- Add these imports if missing: `com.idm.test.connector.rest.model.TextResponse`, `org.springframework.http.HttpHeaders`, `java.nio.charset.Charset`, `java.nio.charset.StandardCharsets`, `java.util.ArrayList`.
- Add this constant beside the other fields:

```java
    private static final MediaType DEFAULT_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
```

Then add this method directly after `exchange`:

```java
    /**
     * Performs one HTTP request with a raw text body and returns the raw text response.
     * <p>
     * The counterpart of {@link #exchange} for callers that write the body themselves — XML,
     * SOAP envelopes, JSON as text. Nothing is serialized or parsed: the body is sent as bytes in
     * the charset its Content-Type names (UTF-8 by default), and the response is decoded with the
     * charset it declares (UTF-8 by default). As with exchange, every HTTP status is data; only a
     * transport-level problem is an operation failure.
     *
     * @param method  the HTTP method
     * @param url     the absolute URL
     * @param body    the request body, sent as-is, or null for none
     * @param headers request headers; Content-Type defaults to text/plain; charset=UTF-8 when there
     *                is a body, and Accept to *&#47;* when none is given
     * @return the response, or a failure result; never throws
     */
    public OperationResult<TextResponse> exchangeText(
            String method, String url, String body, Map<String, String> headers) {

        log.debug("{} request to: {}", method, url);

        try {
            HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase(Locale.ROOT));
            RestClient.RequestBodySpec spec = restClient.method(httpMethod).uri(url);

            String contentType = null;
            boolean acceptGiven = false;
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey().equalsIgnoreCase(HttpHeaders.CONTENT_TYPE)) {
                        contentType = header.getValue();
                    } else {
                        acceptGiven |= header.getKey().equalsIgnoreCase(HttpHeaders.ACCEPT);
                        spec.header(header.getKey(), header.getValue());
                    }
                }
            }
            if (!acceptGiven) {
                spec.accept(MediaType.ALL);
            }

            RestClient.RequestHeadersSpec<?> requestSpec = spec;
            if (body != null) {
                MediaType mediaType = contentType != null ? MediaType.parseMediaType(contentType) : DEFAULT_TEXT;
                Charset charset = mediaType.getCharset() != null ? mediaType.getCharset() : StandardCharsets.UTF_8;
                requestSpec = spec.contentType(mediaType).body(body.getBytes(charset));
            } else if (contentType != null) {
                spec.header(HttpHeaders.CONTENT_TYPE, contentType);
            }

            ResponseEntity<byte[]> entity = requestSpec
                    .retrieve()
                    // Every status, including 4xx and 5xx, must reach us as data.
                    .onStatus(status -> true, (request, response) -> { })
                    .toEntity(byte[].class);

            // HTTP header names are case-insensitive; one lower-case spelling lets a scenario
            // read them without guessing how the server capitalised them.
            Map<String, List<String>> lowerCased = new LinkedHashMap<>();
            entity.getHeaders().forEach((name, values) -> lowerCased
                    .computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                    .addAll(values));

            MediaType responseType = entity.getHeaders().getContentType();
            Charset responseCharset = responseType != null && responseType.getCharset() != null
                    ? responseType.getCharset()
                    : StandardCharsets.UTF_8;
            byte[] bytes = entity.getBody();
            String text = bytes == null ? "" : new String(bytes, responseCharset);

            return OperationResult.<TextResponse>builder()
                    .success(true)
                    .data(new TextResponse(entity.getStatusCode().value(), lowerCased, text))
                    .message(httpMethod + " " + url + " returned " + entity.getStatusCode().value())
                    .build();

        } catch (Exception e) {
            log.error("{} request failed for URL '{}': {}", method, url, e.getMessage());
            return OperationResult.<TextResponse>builder()
                    .success(false)
                    .message("Request failed: " + e.getMessage())
                    .errorType(determineErrorType(e))
                    .throwable(e)
                    .build();
        }
    }
```

If `LinkedHashMap`, `List`, `Locale`, `HttpMethod`, `MediaType` or `ResponseEntity` are not already imported (`exchange` uses most of them), add them.

- [ ] **Step 5: `HttpRequestStep`**

Create `E/steps/HttpRequestStep.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Makes one HTTP request with a raw string body and captures the raw string response.
 * <p>
 * The generic counterpart of rest.request: nothing is serialized or parsed, so the same step
 * sends a JSON document, an XML payload or a SOAP envelope — a SOAP call is only a POST with an
 * envelope and a SOAPAction header. The capture is {status, headers, body} with lower-case
 * header names and the body as text. Any HTTP status is an outcome, never a failure; only a bad
 * parameter or a transport problem fails the step. Like rest.*, it addresses a named target, so
 * base URLs stay in per-environment configuration.
 */
public final class HttpRequestStep implements StepType {

    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private final IdmRestConnector connector;
    private final RestProperties properties;

    public HttpRequestStep(IdmRestConnector connector, RestProperties properties) {
        this.connector = connector;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "http.request";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("target", "method", "path");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("target", "method", "path", "headers", "body");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String method = String.valueOf(params.get("method")).toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            return StepOutcome.failure("http.request method '" + params.get("method")
                    + "' is not one of " + new TreeSet<>(METHODS));
        }

        Object rawHeaders = params.get("headers");
        if (rawHeaders != null && !(rawHeaders instanceof Map)) {
            return StepOutcome.failure("http.request headers must be a map of header name to value");
        }

        Object rawBody = params.get("body");
        if (rawBody != null && !(rawBody instanceof String)) {
            return StepOutcome.failure(
                    "http.request body must be a string sent as-is; to send a JSON object use rest.request");
        }

        String target = String.valueOf(params.get("target"));
        RestTargets.Resolution resolution = RestTargets.resolveBaseUrl("http.request", target, properties);
        if (resolution.isFailure()) {
            return StepOutcome.failure(resolution.error());
        }
        String url = resolution.baseUrl() + String.valueOf(params.get("path"));

        Map<String, String> headers = new LinkedHashMap<>();
        if (rawHeaders instanceof Map<?, ?> map) {
            map.forEach((key, value) -> headers.put(String.valueOf(key), String.valueOf(value)));
        }

        OperationResult<TextResponse> result = connector.exchangeText(method, url, (String) rawBody, headers);
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("http.request failed for " + url + ": " + result.getMessage());
        }

        TextResponse response = result.getData().get();
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("status", response.status());
        capture.put("headers", response.headers());
        capture.put("body", response.body());

        return StepOutcome.success(method + " " + url + " returned " + response.status(), capture);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run the Step 2 command. Expected: 14/14 pass, `BUILD SUCCESS`.

If `testDefaultContentTypeAndAccept` fails only because WireMock shows the `Content-Type` charset in another spelling (e.g. `utf-8`), keep the assertion's meaning and change `containing("UTF-8")` to a case-insensitive `matching("(?i).*charset=utf-8.*")`. Do not drop the check.

- [ ] **Step 7: Falsify**

Do each mutation one at a time. Re-run the Step 2 command after each, confirm the named test fails, then revert:

1. Remove the non-string `body` check. `testMapBodyRefused` must fail.
2. Remove the non-map `headers` check. `testNonMapHeadersRefused` must fail.
3. Remove the `METHODS` check. `testUnknownMethodRefused` must fail.
4. In `exchangeText`, use `new MediaType("text", "plain")` (no charset) as the default. `testDefaultContentTypeAndAccept` must fail.
5. Remove the `spec.accept(MediaType.ALL)` block. `testDefaultContentTypeAndAccept` must fail.
6. Replace `name.toLowerCase(Locale.ROOT)` with `name`. `testHeaderNamesLowerCased` must fail.
7. Decode the response always as UTF-8. `testNonUtf8ResponseDecoded` must fail.

Record each result.

- [ ] **Step 8: Engine module suite**

Run: `./mvnw test -pl idm-test-engine -am -Dsurefire.failIfNoSpecifiedTests=false`. It must pass, including `NoSpringDependencyTest` (the step imports no Spring).

- [ ] **Step 9: Commit**

```bash
git add idm-connector-rest idm-test-engine
git commit -F - <<'EOF'
feat: add http.request, a raw-body HTTP step for REST and SOAP

The body is sent as-is in its Content-Type's charset and the response
comes back as raw text with lower-case header names, so the same step
sends JSON, XML or a SOAP envelope. Any HTTP status is captured, never
a failure; only bad parameters or a transport problem fail the step.

ITF-22

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Register, fixture, guard 4, docs

**Files:**
- Modify: `ST/YamlScenarioTest.java` (`registry`), `idm-test-scenarios/src/test/resources/scenarios-vocabulary-fixture/full-vocabulary.yaml`, `ST/RealEnvironmentScenarios.java` (`restTargets`), `ST/RealEnvironmentGuardsTest.java`
- Modify (docs): `docs/guides/scenario-authoring.md`, `idm-test-scenarios/src/test/resources/scenarios-real/README.md`

**Interfaces:**
- Consumes (Task 1): `HttpRequestStep(IdmRestConnector, RestProperties)`, named `http.request`.
- Produces: nothing.

- [ ] **Step 1: The guard 4 test (write it first)**

In `ST/RealEnvironmentGuardsTest.java`, add after `testRestTargetsCollectedFromCleanup`:

```java
    @Test
    @DisplayName("Guard 4 also collects http.request targets")
    void testHttpTargetsCollected() {
        Scenario scenario = scenario("""
                name: http target
                steps:
                  - http.request:
                      target: roleservice
                      method: POST
                      path: /IDMProv/role/service
                      body: "<Envelope/>"
                """);

        assertThat(RealEnvironmentScenarios.restTargets(List.of(scenario))).containsExactly("roleservice");
    }
```

Run it: `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentGuardsTest -Dsurefire.failIfNoSpecifiedTests=false`. It must fail.

- [ ] **Step 2: Guard 4**

In `ST/RealEnvironmentScenarios.java`, in `restTargets`, replace:

```java
                if (!step.type().startsWith("rest.")) {
```

with:

```java
                if (!step.type().startsWith("rest.") && !step.type().startsWith("http.")) {
```

Update that method's javadoc so it says `rest.*` and `http.*`. Re-run: it passes. Falsify by reverting to `rest.` only, confirm `testHttpTargetsCollected` fails, and restore.

- [ ] **Step 3: Register the step**

In `ST/YamlScenarioTest.java`, `registry(...)`, add after `new RestVerifyStep(restConnector, restProperties)`:

```java
                new RestVerifyStep(restConnector, restProperties),
                new HttpRequestStep(restConnector, restProperties))) {
```

That line replaces the existing `new RestVerifyStep(restConnector, restProperties))) {`. Import `com.idm.test.engine.steps.HttpRequestStep`.

- [ ] **Step 4: Put it in the vocabulary fixture**

In `idm-test-scenarios/src/test/resources/scenarios-vocabulary-fixture/full-vocabulary.yaml`, add this step directly after the `rest.verify` step, still inside `steps:`:

```yaml
  - http.request:
      target: idm
      method: POST
      path: /soap/echo
      headers:
        Content-Type: "text/xml; charset=UTF-8"
        SOAPAction: "urn:echo"
      body: |
        <Envelope><Body><echo>${user.userId}</echo></Body></Envelope>
      as: soapResponse
```

Run `./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioValidationTest -Dsurefire.failIfNoSpecifiedTests=false`. `testFullVocabularyValidates` must pass. Falsify by removing the `HttpRequestStep` line from `registry`: `testFullVocabularyValidates` must fail with "unknown step type". Restore it.

- [ ] **Step 5: Docs**

In `docs/guides/scenario-authoring.md`, section `## 4. Supported steps`, add this row to the table directly after the `ldap.verify` row:

```markdown
| `http.request` | `target`, `method`, `path` | `headers`, `body`, `as` | Send a raw string body (JSON, XML, a SOAP envelope); capture `{status, headers, body}` as text; never fails on HTTP status |
```

Then, directly after the paragraph that begins `Use a nonempty \`expect\` map`, add:

````markdown
### HTTP requests with a raw body (REST and SOAP)

`http.request` sends its `body` exactly as written and captures the response as text, so the same step calls a JSON API or a SOAP service. A SOAP call is a `POST` whose body is the envelope, with a `SOAPAction` header:

```yaml
- http.request:
    target: roleservice                  # idm.test.rest.roleservice.base-url
    method: POST
    path: /IDMProv/role/service
    headers:
      Content-Type: "text/xml; charset=UTF-8"
      SOAPAction: "http://www.novell.com/role/service/getUserRequest"
    body: |
      <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
        <soapenv:Body>...${user.userId}...</soapenv:Body>
      </soapenv:Envelope>
    as: resp
```

- **Addressing:** the target is a named base URL from configuration, just as for `rest.*`.
- **`body` must be a string.** To send a JSON object built from a map, use `rest.request`.
- **Default headers:** with a body and no `Content-Type`, the request is sent as `text/plain; charset=UTF-8`. Without an `Accept`, it sends `Accept: */*`.
- **The capture:**
  - `resp.status` is a number
  - `resp.headers` uses lower-case names, e.g. `resp.headers['content-type'][0]`
  - `resp.body` is always text, `""` when empty. Read JSON with `js:{ JSON.parse(resp.body).id }`.
- **Any HTTP status is a successful step.** The status appears in the step message and the report. Checking it, and reading XML responses from JavaScript, are planned separately.
````

In `idm-test-scenarios/src/test/resources/scenarios-real/README.md`, at the end of the `## What the environment must provide` section (directly before `## Running against containers`), add:

```markdown

A SOAP service or any other API called with `http.request` is a target too: configure its base
URL as `idm.test.rest.<name>.base-url`, or with `IDM_TEST_REST_<NAME>_BASEURL`. Guard 4 checks it
like any REST target.
```

- [ ] **Step 6: Full suite**

Run `./mvnw clean test` and the Task 0 count command. Expected:
- `BUILD SUCCESS` and **548** (533 + 14 in `HttpStepsTest` + 1 in `RealEnvironmentGuardsTest`), with no non-zero failures, errors or skips.
- `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`.

Then run `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false` if Docker is running. Expect 20 testcases, all passing: the registry change must not disturb the container run. If Docker is not running, say so in your report.

- [ ] **Step 7: Commit**

```bash
git add idm-test-scenarios docs/guides/scenario-authoring.md
git commit -F - <<'EOF'
feat: register http.request and check its targets in guard 4

The step joins the registry and the vocabulary fixture, guard 4 now
collects http.* targets alongside rest.*, and the authoring guide and
real-environment README show REST and SOAP usage.

ITF-22

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```
