# Connector Timeouts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `idm.test.soap.connection-timeout`/`read-timeout` and `idm.test.rest.<target>.connect-timeout`/`read-timeout` take effect; `soap.message-logging` and the executor's `max-retries`, `retry-delay-ms` and `rest-provision-timeout-seconds` are removed; the docs say what is true.

**Architecture:** A package-private `SoapTimeouts` sets the JAX-WS RI connect and request timeouts on both SOAP ports (rejecting non-positive values at startup). A new `Timeouts` record and `RestClientConfiguration.requestFactory(connect, read)` let `IdmRestConnector` cache one `RestClient` per distinct timeout pair; `RestTargets.resolveBaseUrl` also validates and returns the target's timeouts, which the four REST/HTTP steps pass on.

**Tech Stack:** Java 21, JAX-WS RI (`jaxws-rt`), Spring `RestClient` on the JDK `HttpClient`, WireMock, JUnit 5, AssertJ.

**Ticket:** ITF-43. **Spec:** `docs/specs/2026-10-07-connector-timeouts-design.md`.

## Global Constraints

- SOAP: request-context properties `com.sun.xml.ws.connect.timeout` = `connection-timeout`, `com.sun.xml.ws.request.timeout` = `read-timeout` (milliseconds, `Integer`); defaults 10 000 / 30 000; a non-positive value throws at connector construction `idm.test.soap.<key> must be a positive number of milliseconds`.
- REST: `Timeouts(Duration connect, Duration read)`, `Timeouts.DEFAULT` = 10 s / 30 s; one `RestClient` per distinct `Timeouts`, built lazily and thread-safely; `DEFAULT` and `null` use the connector's own client; a missing target value takes the default; a non-positive value is a final failure before sending, with no evidence: `<step> target '<target>' has a read-timeout that is not a positive number of milliseconds` (or `connect-timeout`).
- A timed-out request fails through the existing failure paths (REST: retried under `wait:`; evidence shows the request and the error).
- Removed: `soap.message-logging`; `executor.max-retries`, `retry-delay-ms`, `rest-provision-timeout-seconds`. Kept: `default-timeout-seconds`, `default-poll-interval-seconds`, `ldap-provision-timeout-seconds`, `database-provision-timeout-seconds`.
- Secrets never appear in step messages, reports or logs (ITF-23 rule).
- Commit trailer: a blank line, `ITF-43`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven: always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. If `./mvnw` finds no JVM: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.11-zulu`.

---

### Task 1: SOAP timeouts, and remove message-logging

**Files:**
- Create: `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/SoapTimeouts.java`
- Modify: `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/RoleServiceConnector.java`, `ResourceServiceConnector.java` (constructors, after `SoapCredentials.apply(...)`)
- Modify: `idm-connector-soap/src/main/java/com/idm/test/connector/soap/config/SoapProperties.java` (remove `messageLogging`; Javadoc of the two timeouts)
- Modify: `idm-connector-soap/src/test/resources/application.yml`, `idm-test-scenarios/src/test/resources/application.yml` (remove every `message-logging:` line)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/config/ConfigurationTest.java` (remove the `isMessageLogging()` print, ~line 128)
- Modify: `docs/reference/configuration.md` (SOAP section)
- Test: `idm-connector-soap/src/test/java/com/idm/test/connector/soap/client/SoapTimeoutsTest.java`

**Interfaces:**
- Produces: `static void SoapTimeouts.apply(BindingProvider port, SoapProperties properties)` (package-private class).

- [ ] **Step 1: Write the failing test**

```java
package com.idm.test.connector.soap.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SOAP connector timeouts")
class SoapTimeoutsTest {

    private static final String ROLE_PATH = "/IDMProv/role/service";
    private static final String RESOURCE_PATH = "/IDMProv/resource/service";
    private static final String USER_DN = "cn=jdoe,ou=users,dc=company,dc=com";

    private static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIREMOCK.resetAll();
    }

    private static SoapProperties properties(int connectionTimeout, int readTimeout) {
        SoapProperties properties = new SoapProperties();
        properties.setUrl("http://localhost:" + WIREMOCK.port() + ROLE_PATH);
        properties.setResourceUrl("http://localhost:" + WIREMOCK.port() + RESOURCE_PATH);
        properties.setConnectionTimeout(connectionTimeout);
        properties.setReadTimeout(readTimeout);
        return properties;
    }

    @Test
    @DisplayName("The Role service read timeout applies: a slow service fails the call quickly")
    void roleReadTimeout() {
        WIREMOCK.stubFor(post(urlEqualTo(ROLE_PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
        RoleServiceConnector connector = new RoleServiceConnector(properties(10_000, 500));

        long started = System.currentTimeMillis();
        OperationResult<?> result = connector.assignRoles(USER_DN, new String[]{"cn=r"}, null);
        long elapsed = System.currentTimeMillis() - started;

        assertThat(result.isSuccess()).isFalse();
        assertThat(elapsed).isLessThan(1_500);
        assertThat(result.getMessage().toLowerCase()).containsAnyOf("timed out", "timeout");
    }

    @Test
    @DisplayName("The Resource service read timeout applies too")
    void resourceReadTimeout() {
        WIREMOCK.stubFor(post(urlEqualTo(RESOURCE_PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
        ResourceServiceConnector connector = new ResourceServiceConnector(properties(10_000, 500));

        long started = System.currentTimeMillis();
        OperationResult<?> result = connector.grantResource(USER_DN, "cn=res", null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(System.currentTimeMillis() - started).isLessThan(1_500);
    }

    @Test
    @DisplayName("A non-positive timeout fails at startup, naming the property")
    void nonPositiveRejected() {
        assertThatThrownBy(() -> new RoleServiceConnector(properties(10_000, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("idm.test.soap.read-timeout must be a positive number of milliseconds");
        assertThatThrownBy(() -> new ResourceServiceConnector(properties(-1, 30_000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("idm.test.soap.connection-timeout must be a positive number of milliseconds");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl idm-connector-soap -am -Dtest=SoapTimeoutsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `roleReadTimeout` fails (the call takes ~2 s and succeeds or fails slowly); `nonPositiveRejected` fails (no exception).

- [ ] **Step 3: Implement**

`SoapTimeouts.java`:

```java
package com.idm.test.connector.soap.client;

import com.idm.test.connector.soap.config.SoapProperties;
import jakarta.xml.ws.BindingProvider;

/**
 * Applies {@code idm.test.soap.connection-timeout} and {@code read-timeout} to a JAX-WS port, shared
 * by the role and resource connectors, so a hung service fails a step instead of blocking it on
 * the stack's defaults. The property names are the JAX-WS reference implementation's.
 */
final class SoapTimeouts {

    static final String CONNECT_TIMEOUT = "com.sun.xml.ws.connect.timeout";
    static final String REQUEST_TIMEOUT = "com.sun.xml.ws.request.timeout";

    private SoapTimeouts() {
    }

    static void apply(BindingProvider port, SoapProperties properties) {
        requirePositive("connection-timeout", properties.getConnectionTimeout());
        requirePositive("read-timeout", properties.getReadTimeout());
        port.getRequestContext().put(CONNECT_TIMEOUT, properties.getConnectionTimeout());
        port.getRequestContext().put(REQUEST_TIMEOUT, properties.getReadTimeout());
    }

    private static void requirePositive(String key, int millis) {
        if (millis <= 0) {
            throw new IllegalStateException(
                    "idm.test.soap." + key + " must be a positive number of milliseconds");
        }
    }
}
```

In both connectors' constructors, right after `SoapCredentials.apply(bindingProvider, soapProperties);`, add:

```java
        SoapTimeouts.apply(bindingProvider, soapProperties);
```

(keep it before `SoapCapture.installOn(...)` if that line follows; order with the capture handler does not matter).

`SoapProperties`: delete the `messageLogging` field, its getter and setter. Update the Javadoc on `connectionTimeout` / `readTimeout` to say they are applied to both services' ports, in milliseconds, and must be positive.

Remove every `message-logging:` line (with its trailing comment) from `idm-connector-soap/src/test/resources/application.yml` and `idm-test-scenarios/src/test/resources/application.yml`. In `ConfigurationTest`, delete the line printing `isMessageLogging()`.

`docs/reference/configuration.md`, SOAP section:
- in the YAML example, add `connection-timeout: 10000` and `read-timeout: 30000` lines after `password:`
- in the table, replace the row `| \`connection-timeout\`, \`read-timeout\`, \`message-logging\` | — | accepted but **not applied** … |` with:

  `| \`connection-timeout\`, \`read-timeout\` | both services | milliseconds to connect, and to wait for a response (defaults 10 000 and 30 000); must be positive, or startup fails. A call that exceeds them fails its step |`

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl idm-connector-soap -am`, then `./mvnw test -pl idm-test-scenarios -am -Dtest=ConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS, 0 failures/errors/skips. If the RI reports the timeout with other wording, keep the `elapsed` assertion and adapt only the message assertion to the RI's actual text (report it).

- [ ] **Step 5: Commit**

```bash
git add idm-connector-soap idm-test-scenarios/src/test/resources/application.yml idm-test-scenarios/src/test/java/com/idm/test/scenarios/config/ConfigurationTest.java docs/reference/configuration.md
git commit -m "fix: apply the SOAP connect and read timeouts, and drop the unused message-logging setting

ITF-43

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: REST per-target timeouts

**Files:**
- Create: `idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/Timeouts.java`
- Modify: `idm-connector-rest/src/main/java/com/idm/test/connector/rest/config/RestClientConfiguration.java` (new `requestFactory(Duration, Duration)`)
- Modify: `idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/IdmRestConnector.java` (`exchange`, `exchangeText` overloads, client cache)
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestTargets.java`, `HttpExchange.java`, `RestExchange.java`, `RestRequestStep.java`, `RestVerifyStep.java`
- Modify: `docs/reference/configuration.md` (REST section)
- Test: `idm-connector-rest/src/test/java/com/idm/test/connector/rest/client/RestTimeoutsTest.java`, `idm-test-engine/src/test/java/com/idm/test/engine/steps/RestStepsTest.java`, `HttpStepsTest.java`

**Interfaces:**
- Produces:
  - `public record Timeouts(Duration connect, Duration read)` with `public static final Timeouts DEFAULT` and `public static Timeouts ofMillis(Integer connectMillis, Integer readMillis)` (a null takes the default component; does not validate)
  - `public static ClientHttpRequestFactory RestClientConfiguration.requestFactory(Duration connect, Duration read)`
  - `IdmRestConnector.exchange(String method, String url, Object body, Map<String, String> headers, Timeouts timeouts)` and `exchangeText(String method, String url, String body, Map<String, String> headers, Timeouts timeouts)`; package-private `int timeoutClientCount()`
  - `RestTargets.Resolution` gains `Timeouts timeouts` (null on failure)

- [ ] **Step 1: Write the failing tests**

`RestTimeoutsTest.java` (connector-rest):

```java
package com.idm.test.connector.rest.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.rest.config.RestClientConfiguration;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("REST per-target timeouts")
class RestTimeoutsTest {

    private static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withBody("ok").withFixedDelay(2_000)));
    }

    private static IdmRestConnector connector() {
        return new IdmRestConnector(RestClient.builder()
                .requestFactory(RestClientConfiguration.requestFactory()).build());
    }

    private static String slow() {
        return "http://localhost:" + WIREMOCK.port() + "/slow";
    }

    @Test
    @DisplayName("A short read timeout fails fast; the default client still succeeds")
    void readTimeoutApplies() {
        IdmRestConnector connector = connector();
        Timeouts quick = new Timeouts(Duration.ofSeconds(10), Duration.ofMillis(500));

        long started = System.currentTimeMillis();
        OperationResult<TextResponse> timedOut = connector.exchangeText("GET", slow(), null, Map.of(), quick);
        assertThat(timedOut.isSuccess()).isFalse();
        assertThat(System.currentTimeMillis() - started).isLessThan(1_500);

        assertThat(connector.exchangeText("GET", slow(), null, Map.of(), Timeouts.DEFAULT).isSuccess()).isTrue();
        assertThat(connector.exchange("GET", slow(), null, Map.of(), quick).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("Equal timeouts share one client; different ones get their own; the default needs none")
    void clientsCachedPerPair() {
        IdmRestConnector connector = connector();
        WIREMOCK.stubFor(get(urlEqualTo("/fast")).willReturn(aResponse().withStatus(200)));
        String fast = "http://localhost:" + WIREMOCK.port() + "/fast";

        connector.exchangeText("GET", fast, null, Map.of(), Timeouts.DEFAULT);
        connector.exchangeText("GET", fast, null, Map.of(), null);
        assertThat(connector.timeoutClientCount()).isZero();

        connector.exchangeText("GET", fast, null, Map.of(), new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)));
        connector.exchangeText("GET", fast, null, Map.of(), new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertThat(connector.timeoutClientCount()).isEqualTo(1);

        connector.exchange("GET", fast, null, Map.of(), new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(6)));
        assertThat(connector.timeoutClientCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("ofMillis fills a missing value with the default")
    void ofMillis() {
        assertThat(Timeouts.ofMillis(null, null)).isEqualTo(Timeouts.DEFAULT);
        assertThat(Timeouts.ofMillis(2_000, null)).isEqualTo(new Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(30)));
    }
}
```

`RestStepsTest` — add a `slow` and a `broken` target to `properties()` (alongside `idm`):

```java
        RestProperties.RestApiConfig slow = new RestProperties.RestApiConfig();
        slow.setBaseUrl("http://localhost:" + WIREMOCK.port());
        slow.setReadTimeout(500);
        properties.getRest().put("slow", slow);
        RestProperties.RestApiConfig broken = new RestProperties.RestApiConfig();
        broken.setBaseUrl("http://localhost:" + WIREMOCK.port());
        broken.setReadTimeout(0);
        properties.getRest().put("broken", broken);
```

and the tests:

```java
    @Test
    @DisplayName("A target's read-timeout applies to rest.request; a default target still waits")
    void testTargetReadTimeout() {
        stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}").withFixedDelay(2_000)));
        RestRequestStep step = new RestRequestStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth());

        long started = System.currentTimeMillis();
        StepOutcome slow = step.execute(Map.of("target", "slow", "method", "GET", "path", "/users/1"), false);
        assertThat(slow.success()).isFalse();
        assertThat(System.currentTimeMillis() - started).isLessThan(1_500);
        assertThat(slow.evidence()).isNotNull();

        assertThat(step.execute(Map.of("target", "idm", "method", "GET", "path", "/users/1"), false).success()).isTrue();
    }

    @Test
    @DisplayName("A non-positive timeout is a final failure, before anything is sent")
    void testNonPositiveTimeoutFinal() {
        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "broken", "path", "/users/1", "expect", Map.of("status", 200)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.isFinal()).isTrue();
        assertThat(outcome.message())
                .isEqualTo("rest.verify target 'broken' has a read-timeout that is not a positive number of milliseconds");
        assertThat(outcome.evidence()).isNull();
        WIREMOCK.verify(0, com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));
    }
```

`HttpStepsTest` — add a `slow` target to its `properties()` (base URL the WireMock port, `setReadTimeout(500)`), and:

```java
    @Test
    @DisplayName("A target's read-timeout applies to http.request")
    void testTargetReadTimeout() {
        stubFor(get(urlEqualTo("/x")).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));

        long started = System.currentTimeMillis();
        StepOutcome outcome = STEP.execute(params("target", "slow", "method", "GET", "path", "/x"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(System.currentTimeMillis() - started).isLessThan(1_500);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-connector-rest,idm-test-engine -am -Dtest='RestTimeoutsTest,RestStepsTest,HttpStepsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`Timeouts` missing).

- [ ] **Step 3: Implement**

`Timeouts.java`:

```java
package com.idm.test.connector.rest.client;

import java.time.Duration;

/**
 * A REST target's connect and read timeouts. Requests with the same pair share one HTTP client.
 *
 * @param connect how long to wait for the connection
 * @param read    the overall deadline for the response (see RestClientConfiguration#requestFactory)
 */
public record Timeouts(Duration connect, Duration read) {

    /** The timeouts of a target that sets none: 10 s to connect, 30 s for the response. */
    public static final Timeouts DEFAULT = new Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(30));

    /** From configuration's milliseconds; a missing value takes the default. Does not validate. */
    public static Timeouts ofMillis(Integer connectMillis, Integer readMillis) {
        return new Timeouts(
                connectMillis == null ? DEFAULT.connect() : Duration.ofMillis(connectMillis),
                readMillis == null ? DEFAULT.read() : Duration.ofMillis(readMillis));
    }
}
```

`RestClientConfiguration` — replace `requestFactory(Duration readTimeout)`'s body to delegate, and add the two-argument overload holding the existing builder code:

```java
    public static ClientHttpRequestFactory requestFactory(Duration readTimeout) {
        return requestFactory(Duration.ofSeconds(10), readTimeout);
    }

    /**
     * {@link #requestFactory()} with both timeouts given, for a REST target's
     * {@code connect-timeout} and {@code read-timeout}.
     *
     * @param connectTimeout how long to wait for the connection
     * @param readTimeout    the overall response deadline (see {@link #requestFactory(Duration)})
     * @return a new request factory
     */
    public static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
```

`IdmRestConnector` (imports `com.idm.test.connector.rest.config.RestClientConfiguration`, `java.util.concurrent.ConcurrentHashMap`):

```java
    /** One client per distinct timeout pair other than the default, created when first needed. */
    private final Map<Timeouts, RestClient> timeoutClients = new ConcurrentHashMap<>();

    private RestClient clientFor(Timeouts timeouts) {
        if (timeouts == null || Timeouts.DEFAULT.equals(timeouts)) {
            return restClient;
        }
        return timeoutClients.computeIfAbsent(timeouts, pair -> RestClient.builder()
                .requestFactory(RestClientConfiguration.requestFactory(pair.connect(), pair.read()))
                .build());
    }

    /** For tests: how many non-default clients exist. */
    int timeoutClientCount() {
        return timeoutClients.size();
    }
```

Rename the bodies of the existing `exchange(method, url, body, headers)` and `exchangeText(method, url, body, headers)` into the new five-argument overloads, replacing `restClient` in their bodies with `clientFor(timeouts)`; make the four-argument methods delegate with `Timeouts.DEFAULT`:

```java
    public OperationResult<RestResponse> exchange(
            String method, String url, Object body, Map<String, String> headers) {
        return exchange(method, url, body, headers, Timeouts.DEFAULT);
    }

    /**
     * {@link #exchange(String, String, Object, Map)} with the target's timeouts.
     */
    public OperationResult<RestResponse> exchange(
            String method, String url, Object body, Map<String, String> headers, Timeouts timeouts) {
        // ... the existing body, with `restClient` replaced by `clientFor(timeouts)`
    }
```

and the same for `exchangeText`. (Do not leave the `// ...` comment; it marks where the existing body moves unchanged.) `Timeouts.DEFAULT` maps to the connector's own client, so the four-argument methods behave exactly as before.

`RestTargets` (engine) — add `Timeouts timeouts` to `Resolution` (`ok(String baseUrl, Timeouts timeouts)`, `failed` passes null) and, in `resolveBaseUrl`, after the base-URL check:

```java
        String timeoutProblem = timeoutProblem(stepName, target, "connect-timeout", config.getConnectTimeout());
        if (timeoutProblem == null) {
            timeoutProblem = timeoutProblem(stepName, target, "read-timeout", config.getReadTimeout());
        }
        if (timeoutProblem != null) {
            return Resolution.failed(timeoutProblem);
        }
        return Resolution.ok(baseUrl, Timeouts.ofMillis(config.getConnectTimeout(), config.getReadTimeout()));
    }

    private static String timeoutProblem(String stepName, String target, String key, Integer millis) {
        return millis != null && millis <= 0
                ? stepName + " target '" + target + "' has a " + key + " that is not a positive number of milliseconds"
                : null;
    }
```

`HttpExchange` — add `Timeouts timeouts` as the last component of `Prepared` (`failed` passes null; `prepare` passes `resolution.timeouts()`), and in `send` call `connector.exchangeText(request.method(), request.url(), request.body(), request.headers(), request.timeouts())`.

`RestExchange.send` — add a trailing `Timeouts timeouts` parameter and call `connector.exchange(method, url, body, headers, timeouts)`. `RestRequestStep` and `RestVerifyStep` pass `resolution.timeouts()`.

(Each existing failure of `resolveBaseUrl` stays final in `rest.verify` and `http.*`, and a plain failure in `rest.request`, as today; the new timeout message follows the same path.)

`docs/reference/configuration.md`, REST section: replace the paragraph "`connect-timeout` and `read-timeout` are accepted but **not applied** …" with:

```markdown
`connect-timeout` and `read-timeout` (milliseconds; defaults 10 000 and 30 000) apply to every
`rest.*` and `http.*` step on that target. `read-timeout` is the overall deadline for the response.
A request that exceeds it fails like an unreachable server (retried under `wait:`); a value that is
not positive fails the step at once, before anything is sent.
```

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl idm-connector-rest,idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures/errors/skips (includes `HttpAuthStepsTest`, `HttpVerifyStepsTest`, `IdmRestConnectorTest`). Known flake: `SoapStepsTest` port clash — re-run once and report both runs.

- [ ] **Step 5: Commit**

```bash
git add idm-connector-rest idm-test-engine docs/reference/configuration.md
git commit -m "fix: apply each REST target's connect and read timeouts

ITF-43

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Remove the unused executor keys; docs; full suite

**Files:**
- Modify: `idm-test-core/src/main/java/com/idm/test/core/config/TestExecutorProperties.java` (remove `maxRetries`, `retryDelayMs`, `restProvisionTimeoutSeconds` and their getters/setters, including `getRetryDelay()` and `getRestProvisionTimeout()`; trim the Javadoc YAML example)
- Modify: `idm-test-scenarios/src/test/resources/application.yml` (remove `max-retries`, `retry-delay-ms`, `rest-provision-timeout-seconds` and their comments)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/config/ConfigurationTest.java` (remove the assertions and prints for the removed keys, ~lines 137-145)
- Modify: `docs/reference/configuration.md` (executor section), `docs/reference/writing-scenarios.md` (§7 at-a-glance table)

**Interfaces:**
- Consumes: Tasks 1–2 docs edits (do not reintroduce removed rows).

- [ ] **Step 1: Remove and check nothing else used them**

Run: `grep -rn "getMaxRetries\|getRetryDelay\|RestProvisionTimeout\|max-retries\|retry-delay-ms\|rest-provision-timeout" --include='*.java' --include='*.yml' --include='*.md' . | grep -v "/target/\|docs/plans\|docs/specs"`
Expected after the edits: no output.

- [ ] **Step 2: Docs**

`docs/reference/configuration.md`, executor section: in the YAML example delete the `max-retries`, `retry-delay-ms` and `rest-provision-timeout-seconds` lines, and replace the paragraph after it ("Only `default-timeout-seconds` and `default-poll-interval-seconds` affect YAML scenarios … The other keys are bound but not used by the YAML runners (the Java workflow tests pass their own timeouts).") with:

```markdown
`default-timeout-seconds` and `default-poll-interval-seconds` are the wait used by YAML
scenarios' `wait: true`, and by a `wait:` map that leaves `timeout` or `poll` out (the shipped
`test` profile sets them to 60 s and 1 s). `ldap-provision-timeout-seconds` and
`database-provision-timeout-seconds` are how long the Java workflow tests wait for provisioning to
reach the directory and the databases.
```

`docs/reference/writing-scenarios.md`, §7 "Every setting that affects a scenario, at a glance" table: add, after the `soap.username` row,

```markdown
| `soap.connection-timeout`, `soap.read-timeout` | `roles.*`, `resources.*` | milliseconds to connect and to wait for the response (10 000 / 30 000); a call that exceeds them fails its step |
```

and after the `rest.<target>.use-oauth` row,

```markdown
| `rest.<target>.connect-timeout`, `read-timeout` | `rest.*`, `http.*` | milliseconds to connect and to wait for the response (10 000 / 30 000); a slow response fails the attempt |
```

Also change the sentence below the table "Not settable from a scenario, by design: URLs, credentials and pool sizes." to "Not settable from a scenario, by design: URLs, credentials, timeouts and pool sizes."

- [ ] **Step 3: Full suite and corpus**

Delete stale `idm-test-scenarios/target/surefire-reports/*ContainerScenarios.*` files if present. Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures/errors/skips. Then

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
grep -c '"status" : "FAILED"' idm-test-scenarios/target/multi-directory-results.json
```

Expected: a total, `0`, `0`. If Docker is available: `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false` passes 21/21. Known flakes: `EmbeddedLdapServerTest.testLdifImport`, `LdapStepsTest` closed connection, `SoapStepsTest` port clash — re-run once and report both runs.

- [ ] **Step 4: Commit**

```bash
git add idm-test-core idm-test-scenarios/src/test/resources/application.yml idm-test-scenarios/src/test/java/com/idm/test/scenarios/config/ConfigurationTest.java docs/reference/configuration.md docs/reference/writing-scenarios.md
git commit -m "fix: remove the executor settings nothing used, and document the timeouts

ITF-43

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
