# SOAP Envelope Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `roles.grant`, `roles.revoke`, `resources.grant` and `resources.revoke` record the SOAP request and response envelopes they exchanged, as `HttpEvidence`, in the JSON results and the HTML report.

**Architecture:** A JAX-WS `SOAPHandler` (`SoapCapture`) on each SOAP port copies the outgoing and incoming envelopes, the endpoint, `SOAPAction` and HTTP status into a per-thread `SoapExchange`; the connectors clear it at the start of each operation and expose `lastExchange()`. The steps turn the exchange into `HttpEvidence` (from ITF-37), which already masks XML secrets, sanitises URLs, cuts bodies and renders in the report.

**Tech Stack:** Java 21, JAX-WS RI (`jaxws-rt`), WireMock, JUnit 5, AssertJ.

**Ticket:** ITF-40. **Spec:** `docs/specs/2026-10-06-soap-evidence-design.md`.

## Global Constraints

- Reuse `HttpEvidence`: method `POST`, URL = endpoint, request headers = `{"SOAPAction": [action]}` (empty map when null), request body = request envelope, status, response headers = null, response body = response envelope, `durationMillis`, `error` = the connector's failure message when `status` is null, else null. No new evidence kind.
- `SoapCapture` never throws and never blocks the call (`handleMessage`/`handleFault` return `true`); an unreadable envelope is recorded as `(could not read envelope: <exception class simple name>)`.
- `SoapCapture` records no HTTP headers; the HTTP Basic credentials never enter a `SoapExchange`.
- The connectors clear the slot at the start of every public operation and expose `Optional<SoapExchange> lastExchange()`; signatures of existing methods and `OperationResult` are unchanged.
- Steps attach evidence after the connector call, on success and failure; none when nothing was sent (no connector, empty `roles` / `assignments`, no exchange recorded). Messages, pass/fail and `as:` captures unchanged.
- The connector module (`idm-connector-soap`) does not depend on the engine; the engine stays Spring-free.
- Commit trailer: a blank line, `ITF-40`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven: always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. If `./mvnw` finds no JVM: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.11-zulu`.

**Controller clarification beyond the spec:** when the service answers with an HTTP error whose body is not a SOAP message (e.g. a bare 500), JAX-WS may not run the inbound handler, so the exchange has no status or response and the evidence shows "no response" with the connector's error. The docs say so.

---

## File Structure

| File | Responsibility |
|---|---|
| Create `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/SoapExchange.java` | One captured exchange |
| Create `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/SoapCapture.java` | The JAX-WS handler and per-thread slot |
| Modify `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/RoleServiceConnector.java`, `ResourceServiceConnector.java` | Install the handler, clear per operation, `lastExchange()` |
| Create `idm-connector-soap/src/test/java/com/idm/test/connector/soap/client/SoapCaptureTest.java` | Connector-level tests |
| Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/SoapExchanges.java` | `SoapExchange` → `HttpEvidence` |
| Modify `RolesGrantStep.java`, `RolesRevokeStep.java`, `ResourcesGrantStep.java`, `ResourcesRevokeStep.java` | Attach evidence |
| Modify `idm-test-engine/src/test/java/com/idm/test/engine/steps/SoapStepsTest.java` | Step tests |
| Modify `docs/reference/writing-scenarios.md` §9, `docs/architecture/capabilities.md`, `README.md` | Docs |

---

### Task 1: Capture envelopes in the SOAP connectors

**Files:**
- Create: `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/SoapExchange.java`
- Create: `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/SoapCapture.java`
- Modify: `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/RoleServiceConnector.java` (constructor; start of `assignRoles`, `removeRoles`, `getAssignedRoles`; new `lastExchange()`)
- Modify: `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/ResourceServiceConnector.java` (constructor; start of `grantResource`, `revokeResource`; new `lastExchange()`)
- Test: `idm-connector-soap/src/test/java/com/idm/test/connector/soap/client/SoapCaptureTest.java`

**Interfaces:**
- Produces:
  - `public record SoapExchange(String endpoint, String soapAction, String requestEnvelope, Integer status, String responseEnvelope, long durationMillis)`
  - `final class SoapCapture implements SOAPHandler<SOAPMessageContext>` with `static SoapCapture installOn(BindingProvider port)`, `void clear()`, `Optional<SoapExchange> last()`
  - `public Optional<SoapExchange> RoleServiceConnector.lastExchange()` and `public Optional<SoapExchange> ResourceServiceConnector.lastExchange()`

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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SOAP exchange capture")
class SoapCaptureTest {

    private static final String ROLE_PATH = "/IDMProv/role/service";
    private static final String USER_DN = "cn=jdoe,ou=users,dc=company,dc=com";
    private static final String ROLE_DN = "cn=SAP_USER,ou=roles,dc=company,dc=com";
    private static final String ASSIGNMENT_DN = "cn=assignment-1,ou=requests,dc=company,dc=com";

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

    private static RoleServiceConnector connector(String url, String username, String password) {
        SoapProperties properties = new SoapProperties();
        properties.setUrl(url);
        properties.setUsername(username);
        properties.setPassword(password);
        return new RoleServiceConnector(properties);
    }

    private static String roleUrl() {
        return "http://localhost:" + WIREMOCK.port() + ROLE_PATH;
    }

    private static void stubAssignment() {
        WIREMOCK.stubFor(post(urlEqualTo(ROLE_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                        + "xmlns:tns=\"http://www.novell.com/role/service\">"
                        + "<soap:Body><tns:requestRolesAssignmentResponse>"
                        + "<tns:result><tns:dnstring><tns:dn>" + ASSIGNMENT_DN + "</tns:dn></tns:dnstring></tns:result>"
                        + "</tns:requestRolesAssignmentResponse></soap:Body></soap:Envelope>")));
    }

    private static String unquoted(String value) {
        return value == null ? null : value.replaceAll("^\"|\"$", "");
    }

    @Test
    @DisplayName("Nothing is captured before a call")
    void emptyBeforeCall() {
        assertThat(connector(roleUrl(), null, null).lastExchange()).isEmpty();
    }

    @Test
    @DisplayName("A call records the endpoint, SOAPAction, both envelopes, the status and the time")
    void capturesExchange() {
        stubAssignment();
        RoleServiceConnector connector = connector(roleUrl(), null, null);

        OperationResult<?> result = connector.assignRoles(USER_DN, new String[]{ROLE_DN}, "testing");

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        SoapExchange exchange = connector.lastExchange().orElseThrow();
        assertThat(exchange.endpoint()).isEqualTo(roleUrl());
        String sentAction = WIREMOCK.getAllServeEvents().get(0).getRequest().getHeader("SOAPAction");
        assertThat(unquoted(exchange.soapAction())).isEqualTo(unquoted(sentAction));
        assertThat(exchange.requestEnvelope()).contains("requestRolesAssignment").contains(USER_DN);
        assertThat(exchange.status()).isEqualTo(200);
        assertThat(exchange.responseEnvelope()).contains(ASSIGNMENT_DN);
        assertThat(exchange.durationMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("A SOAP fault records status 500 and the fault envelope")
    void capturesFault() {
        WIREMOCK.stubFor(post(urlEqualTo(ROLE_PATH)).willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                        + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode>"
                        + "<faultstring>Invalid name</faultstring></soap:Fault></soap:Body></soap:Envelope>")));
        RoleServiceConnector connector = connector(roleUrl(), null, null);

        OperationResult<?> result = connector.assignRoles(USER_DN, new String[]{ROLE_DN}, null);

        assertThat(result.isSuccess()).isFalse();
        SoapExchange exchange = connector.lastExchange().orElseThrow();
        assertThat(exchange.status()).isEqualTo(500);
        assertThat(exchange.responseEnvelope()).contains("Invalid name");
    }

    @Test
    @DisplayName("An unreachable endpoint records the request with no status or response")
    void capturesUnreachable() {
        RoleServiceConnector connector = connector("http://localhost:1" + ROLE_PATH, null, null);

        OperationResult<?> result = connector.assignRoles(USER_DN, new String[]{ROLE_DN}, null);

        assertThat(result.isSuccess()).isFalse();
        SoapExchange exchange = connector.lastExchange().orElseThrow();
        assertThat(exchange.requestEnvelope()).contains(USER_DN);
        assertThat(exchange.status()).isNull();
        assertThat(exchange.responseEnvelope()).isNull();
    }

    @Test
    @DisplayName("The next call replaces the exchange")
    void nextCallReplaces() {
        stubAssignment();
        RoleServiceConnector connector = connector(roleUrl(), null, null);
        connector.assignRoles(USER_DN, new String[]{ROLE_DN}, null);
        WIREMOCK.resetAll();

        connector.removeRoles(new String[]{ASSIGNMENT_DN});

        SoapExchange exchange = connector.lastExchange().orElseThrow();
        assertThat(exchange.requestEnvelope()).contains("removeRoles").doesNotContain("requestRolesAssignment");
    }

    @Test
    @DisplayName("Basic credentials are sent but never captured")
    void credentialsNotCaptured() {
        stubAssignment();
        RoleServiceConnector connector = connector(roleUrl(), "svc-user", "S3cret-pw");

        connector.assignRoles(USER_DN, new String[]{ROLE_DN}, null);

        assertThat(WIREMOCK.getAllServeEvents().get(0).getRequest().getHeader("Authorization")).startsWith("Basic ");
        String captured = connector.lastExchange().orElseThrow().toString();
        String basic = Base64.getEncoder().encodeToString("svc-user:S3cret-pw".getBytes(StandardCharsets.UTF_8));
        assertThat(captured).doesNotContain("S3cret-pw").doesNotContain(basic);
    }

    @Test
    @DisplayName("The resource connector captures too")
    void resourceConnectorCaptures() {
        WIREMOCK.stubFor(post(urlEqualTo("/IDMProv/resource/service")).willReturn(aResponse().withStatus(500)));
        SoapProperties properties = new SoapProperties();
        properties.setResourceUrl("http://localhost:" + WIREMOCK.port() + "/IDMProv/resource/service");
        ResourceServiceConnector connector = new ResourceServiceConnector(properties);

        connector.grantResource(USER_DN, "cn=SAP_ACCESS,ou=resources,dc=company,dc=com", null);

        assertThat(connector.lastExchange().orElseThrow().requestEnvelope()).contains(USER_DN);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl idm-connector-soap -am -Dtest=SoapCaptureTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`lastExchange()`, `SoapExchange`, `SoapCapture` not found).

- [ ] **Step 3: Implement**

`SoapExchange.java`:

```java
package com.idm.test.connector.soap.client;

/**
 * One SOAP call as it went over the wire: what was sent and what came back. Holds no HTTP
 * headers, so the Basic credentials are never part of it.
 *
 * @param endpoint         the address the request went to
 * @param soapAction       the SOAPAction sent, or null
 * @param requestEnvelope  the outgoing envelope as text
 * @param status           the HTTP status, or null when no SOAP response arrived
 * @param responseEnvelope the incoming envelope, a normal response or a fault, or null
 * @param durationMillis   from sending to receiving, or to when the call ended without a response
 */
public record SoapExchange(String endpoint, String soapAction, String requestEnvelope, Integer status,
                           String responseEnvelope, long durationMillis) {
}
```

`SoapCapture.java`:

```java
package com.idm.test.connector.soap.client;

import jakarta.xml.soap.MimeHeaders;
import jakarta.xml.ws.Binding;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.handler.Handler;
import jakarta.xml.ws.handler.MessageContext;
import jakarta.xml.ws.handler.soap.SOAPHandler;
import jakarta.xml.ws.handler.soap.SOAPMessageContext;

import javax.xml.namespace.QName;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Copies each SOAP call's envelopes, endpoint, SOAPAction and HTTP status, so a step can show
 * what it exchanged. Kept per thread: JAX-WS calls are synchronous and run handlers on the
 * calling thread, so the thread that made a call reads its own exchange. Never throws and never
 * stops a message, and records no HTTP headers, so the Basic credentials are never captured.
 */
final class SoapCapture implements SOAPHandler<SOAPMessageContext> {

    private final ThreadLocal<SoapExchange> last = new ThreadLocal<>();
    private final ThreadLocal<Long> started = new ThreadLocal<>();

    /** Adds a capture to the end of the port's handler chain and returns it. */
    @SuppressWarnings("rawtypes")
    static SoapCapture installOn(BindingProvider port) {
        SoapCapture capture = new SoapCapture();
        Binding binding = port.getBinding();
        List<Handler> chain = new ArrayList<>(binding.getHandlerChain());
        chain.add(capture);
        binding.setHandlerChain(chain);
        return capture;
    }

    /** Forgets the last exchange; called at the start of every connector operation. */
    void clear() {
        last.remove();
        started.remove();
    }

    /**
     * The last exchange on this thread. When no response arrived, its duration runs to now, which
     * is when the connector call ended.
     */
    Optional<SoapExchange> last() {
        SoapExchange exchange = last.get();
        Long start = started.get();
        if (exchange != null && exchange.status() == null && exchange.responseEnvelope() == null && start != null) {
            exchange = new SoapExchange(exchange.endpoint(), exchange.soapAction(), exchange.requestEnvelope(),
                    null, null, System.currentTimeMillis() - start);
        }
        return Optional.ofNullable(exchange);
    }

    @Override
    public Set<QName> getHeaders() {
        return Set.of();
    }

    @Override
    public boolean handleMessage(SOAPMessageContext context) {
        record(context);
        return true;
    }

    @Override
    public boolean handleFault(SOAPMessageContext context) {
        record(context);
        return true;
    }

    @Override
    public void close(MessageContext context) {
        // nothing held per message
    }

    private void record(SOAPMessageContext context) {
        try {
            boolean outbound = Boolean.TRUE.equals(context.get(MessageContext.MESSAGE_OUTBOUND_PROPERTY));
            String envelope = envelope(context);
            if (outbound) {
                started.set(System.currentTimeMillis());
                last.set(new SoapExchange(stringOf(context.get(BindingProvider.ENDPOINT_ADDRESS_PROPERTY)),
                        soapAction(context), envelope, null, null, 0));
                return;
            }
            SoapExchange request = last.get();
            if (request == null) {
                return;
            }
            Long start = started.get();
            Object code = context.get(MessageContext.HTTP_RESPONSE_CODE);
            last.set(new SoapExchange(request.endpoint(), request.soapAction(), request.requestEnvelope(),
                    code instanceof Integer status ? status : null, envelope,
                    start == null ? 0 : System.currentTimeMillis() - start));
        } catch (RuntimeException e) {
            // Capturing is a convenience for the report; it must never break the call.
        }
    }

    private static String envelope(SOAPMessageContext context) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            context.getMessage().writeTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(could not read envelope: " + e.getClass().getSimpleName() + ")";
        }
    }

    /** The SOAPAction property, else the SOAPAction MIME header of the message, else the HTTP header. */
    @SuppressWarnings("unchecked")
    private static String soapAction(SOAPMessageContext context) {
        Object property = context.get(BindingProvider.SOAPACTION_URI_PROPERTY);
        if (property != null && !String.valueOf(property).isEmpty()) {
            return String.valueOf(property);
        }
        try {
            MimeHeaders mime = context.getMessage().getMimeHeaders();
            String[] values = mime == null ? null : mime.getHeader("SOAPAction");
            if (values != null && values.length > 0) {
                return values[0];
            }
        } catch (RuntimeException ignored) {
            // fall through to the HTTP headers
        }
        Object headers = context.get(MessageContext.HTTP_REQUEST_HEADERS);
        if (headers instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("SOAPAction".equalsIgnoreCase(String.valueOf(entry.getKey()))
                        && entry.getValue() instanceof List<?> values && !values.isEmpty()) {
                    return String.valueOf(values.get(0));
                }
            }
        }
        return null;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
```

If `capturesExchange` shows the RI does not expose the SOAPAction through any of these three sources while WireMock did receive one, set it from the port's generated `@WebMethod(action = …)` is not reachable here — instead report it as a concern and leave `soapAction` null; the test then needs `sentAction` to be checked as absent-or-equal. Do not weaken the test silently.

`RoleServiceConnector` — add a field and install in the constructor, after `SoapCredentials.apply(...)`:

```java
    private final SoapCapture capture;
```

```java
        this.capture = SoapCapture.installOn(bindingProvider);
```

Add as the first statement of `assignRoles`, `removeRoles` and `getAssignedRoles` (before the `log.debug` line, so even a failure there leaves no stale exchange):

```java
        capture.clear();
```

(`assignRole` and `removeRole` delegate to these, so they need nothing.) Add:

```java
    /**
     * The envelopes, endpoint and status of this thread's last call, for a step's evidence; empty
     * before any call, or when the last call failed before anything was sent.
     */
    public Optional<SoapExchange> lastExchange() {
        return capture.last();
    }
```

`ResourceServiceConnector` — the same: a `capture` field installed in the constructor after `SoapCredentials.apply(...)`, `capture.clear();` as the first statement of `grantResource` and `revokeResource`, and the same `lastExchange()` method. Import `java.util.Optional` in both.

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl idm-connector-soap -am`
Expected: BUILD SUCCESS, 0 failures/errors/skips (includes the existing `RoleServiceConnectorTest`, `ResourceServiceConnectorTest`, `SoapCredentialsTest`).

- [ ] **Step 5: Commit**

```bash
git add idm-connector-soap
git commit -m "feat: capture each SOAP call's envelopes, endpoint and status in the connectors

ITF-40

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: SOAP steps record evidence, docs

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/SoapExchanges.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/RolesGrantStep.java`, `RolesRevokeStep.java`, `ResourcesGrantStep.java`, `ResourcesRevokeStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/SoapStepsTest.java`
- Docs: `docs/reference/writing-scenarios.md` (§9 evidence table), `docs/architecture/capabilities.md` (the `HTML report` row), `README.md` (the evidence sentence after the HTML report sentence, ~line 181)

**Interfaces:**
- Consumes: `Optional<SoapExchange> lastExchange()` on both connectors and `SoapExchange(endpoint, soapAction, requestEnvelope, status, responseEnvelope, durationMillis)` (Task 1); `HttpEvidence(String method, String url, Map<String, List<String>> requestHeaders, Object requestBody, Integer status, Map<String, List<String>> responseHeaders, Object responseBody, long durationMillis, String error)` and `StepOutcome.withEvidence(Evidence)` (ITF-37).
- Produces: `static HttpEvidence SoapExchanges.evidence(Optional<SoapExchange> exchange, OperationResult<?> result)` — null when the exchange is empty.

- [ ] **Step 1: Write the failing tests**

Add to `SoapStepsTest` (imports `com.idm.test.engine.spi.HttpEvidence`, `com.idm.test.connector.soap.config.SoapProperties` if not already imported):

```java
    private static HttpEvidence evidence(StepOutcome outcome) {
        return (HttpEvidence) outcome.evidence();
    }

    @Test
    @DisplayName("roles.grant records the SOAP request and response as evidence")
    void testGrantEvidence() {
        stubAssignmentReturning(ASSIGNMENT_DN);

        StepOutcome outcome = new RolesGrantStep(roleConnector).execute(Map.of(
                "user", USER_DN, "roles", List.of(ROLE_DN)), false);

        assertThat(outcome.success()).as(outcome.message()).isTrue();
        HttpEvidence evidence = evidence(outcome);
        assertThat(evidence.method()).isEqualTo("POST");
        assertThat(evidence.url()).endsWith(ROLE_PATH);
        assertThat((String) evidence.requestBody()).contains("requestRolesAssignment").contains(USER_DN);
        assertThat(evidence.status()).isEqualTo(200);
        assertThat((String) evidence.responseBody()).contains(ASSIGNMENT_DN);
        assertThat(evidence.error()).isNull();
    }

    @Test
    @DisplayName("A SOAP fault fails the step as before and its evidence shows the fault")
    void testFaultEvidence() {
        stubFor(post(urlEqualTo(ROLE_PATH)).willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                        + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode>"
                        + "<faultstring>Invalid name</faultstring></soap:Fault></soap:Body></soap:Envelope>")));

        StepOutcome outcome = new RolesGrantStep(roleConnector).execute(Map.of(
                "user", USER_DN, "roles", List.of(ROLE_DN)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).startsWith("roles.grant failed for " + USER_DN + ": ");
        assertThat(evidence(outcome).status()).isEqualTo(500);
        assertThat((String) evidence(outcome).responseBody()).contains("Invalid name");
    }

    @Test
    @DisplayName("An unreachable service records the request and the error, with no response")
    void testUnreachableEvidence() {
        SoapProperties properties = new SoapProperties();
        properties.setUrl("http://localhost:1" + ROLE_PATH);

        StepOutcome outcome = new RolesGrantStep(new RoleServiceConnector(properties)).execute(Map.of(
                "user", USER_DN, "roles", List.of(ROLE_DN)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(evidence(outcome).status()).isNull();
        assertThat(evidence(outcome).error()).isNotBlank();
        assertThat((String) evidence(outcome).requestBody()).contains(USER_DN);
    }

    @Test
    @DisplayName("roles.revoke, resources.grant and resources.revoke record evidence too")
    void testOtherStepsEvidence() {
        stubRemovalReturning(ASSIGNMENT_DN);
        HttpEvidence revoke = evidence(new RolesRevokeStep(roleConnector).execute(Map.of(
                "assignments", List.of(ASSIGNMENT_DN)), false));
        assertThat((String) revoke.requestBody()).contains("removeRoles");

        stubResourceGrantReturning(REQUEST_ID);
        HttpEvidence grant = evidence(new ResourcesGrantStep(resourceConnector).execute(Map.of(
                "user", USER_DN, "resource", RESOURCE_DN), false));
        assertThat(grant.url()).endsWith(RESOURCE_PATH);
        assertThat(grant.status()).isEqualTo(200);

        stubResourceRevokeReturning(REQUEST_ID);
        HttpEvidence resourceRevoke = evidence(new ResourcesRevokeStep(resourceConnector).execute(Map.of(
                "user", USER_DN, "resource", RESOURCE_DN), false));
        assertThat((String) resourceRevoke.responseBody()).contains(REQUEST_ID);
    }

    @Test
    @DisplayName("Nothing sent, no evidence: an empty roles list")
    void testNoEvidenceWhenNothingSent() {
        StepOutcome outcome = new RolesGrantStep(roleConnector).execute(Map.of(
                "user", USER_DN, "roles", List.of()), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.evidence()).isNull();
    }

    @Test
    @DisplayName("A secret-named element in an envelope is masked in the evidence")
    void testEnvelopeSecretMasked() {
        stubFor(post(urlEqualTo(ROLE_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(assignmentEnvelope(ASSIGNMENT_DN).replace("</tns:result>",
                        "</tns:result><tns:password>p4ss</tns:password>"))));

        StepOutcome outcome = new RolesGrantStep(roleConnector).execute(Map.of(
                "user", USER_DN, "roles", List.of(ROLE_DN)), false);

        assertThat((String) evidence(outcome).responseBody())
                .contains("<tns:password>***</tns:password>").doesNotContain("p4ss");
    }
```

If JAXB rejects the extra `<tns:password>` element (the step fails), keep the test: it asserts only on the evidence.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=SoapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the new tests fail (`evidence()` is null). Known flake: a WireMock port clash with a local helper on 127.0.0.1 — re-run once if every test in the class errors at startup.

- [ ] **Step 3: Implement**

`SoapExchanges.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.SoapExchange;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.HttpEvidence;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a SOAP call captured by a connector into HTTP evidence: a SOAP call is a POST of an
 * envelope, so it is shown, masked and cut like any other HTTP exchange.
 */
final class SoapExchanges {

    private SoapExchanges() {
    }

    /** The call's evidence, or null when nothing was sent. */
    static HttpEvidence evidence(Optional<SoapExchange> captured, OperationResult<?> result) {
        if (captured.isEmpty()) {
            return null;
        }
        SoapExchange exchange = captured.get();
        Map<String, List<String>> headers = exchange.soapAction() == null
                ? Map.of()
                : Map.of("SOAPAction", List.of(exchange.soapAction()));
        String error = exchange.status() == null && !result.isSuccess() ? result.getMessage() : null;
        return new HttpEvidence("POST", exchange.endpoint(), headers, exchange.requestEnvelope(),
                exchange.status(), null, exchange.responseEnvelope(), exchange.durationMillis(), error);
    }
}
```

`RolesGrantStep.execute`, from the connector call on (import `com.idm.test.engine.spi.HttpEvidence`):

```java
        OperationResult<RequestRolesAssignmentResponse> result = connector.assignRoles(
                user, roles.toArray(new String[0]),
                reason == null ? null : String.valueOf(reason));
        HttpEvidence evidence = SoapExchanges.evidence(connector.lastExchange(), result);

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "roles.grant failed for " + user + ": " + result.getMessage()).withEvidence(evidence);
        }
```

then append `.withEvidence(evidence)` to the "returned no assignment DNs" failure and to the final success, keeping their messages and the existing comment.

`RolesRevokeStep.execute`: after `connector.removeRoles(...)`, add `HttpEvidence evidence = SoapExchanges.evidence(connector.lastExchange(), result);` and append `.withEvidence(evidence)` to the failure and the success that follow.

`ResourcesGrantStep.execute`: after `connector.grantResource(...)`, the same line, and `.withEvidence(evidence)` on the failure and the success.

`ResourcesRevokeStep.execute`: after `connector.revokeResource(...)`, the same line, and `.withEvidence(evidence)` on the failure and the success.

The "cannot run: no … service is configured" and empty-list failures stay without evidence.

- [ ] **Step 4: Docs**

`docs/reference/writing-scenarios.md` §9, in the evidence table, add a row after the HTTP row:

```markdown
| `roles.grant`, `roles.revoke`, `resources.grant`, `resources.revoke` | the endpoint, the `SOAPAction`, the request and response envelopes, the status and the time, shown like an HTTP exchange; on a SOAP fault, the fault envelope; when the service could not be reached, or answered with an error that is not a SOAP message, the request and the error |
```

At the end of the same section's **Masking** bullet, add the sentence: `The Role and Resource service credentials (sent as an HTTP header) are never recorded.`

`docs/architecture/capabilities.md`, the `HTML report` row: replace `; SOAP envelopes are ITF-40` with `, and for the SOAP role and resource steps (ITF-40)`.

`README.md`: in the evidence sentence after the HTML report sentence (~line 181), change `the HTTP exchange, the SQL and its rows, or the LDAP entry` to `the HTTP or SOAP exchange, the SQL and its rows, or the LDAP entry`.

- [ ] **Step 5: Run tests, full suite and corpus**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures/errors/skips. Then

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
grep -c '"status" : "FAILED"' idm-test-scenarios/target/multi-directory-results.json
```

Expected: a total, `0`, `0`. If Docker is available: `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false` passes 21/21. Known flakes: `EmbeddedLdapServerTest.testLdifImport`, `LdapStepsTest` closed connection, `SoapStepsTest` port clash — re-run once and report both runs if one alone fails.

- [ ] **Step 6: Commit**

```bash
git add idm-test-engine docs/reference/writing-scenarios.md docs/architecture/capabilities.md README.md
git commit -m "feat: SOAP role and resource steps record their envelopes as evidence

ITF-40

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
