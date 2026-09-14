# ResourceServiceConnector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a SOAP connector for NetIQ Identity Applications resource grant and revoke, so the `resources.grant` and `resources.revoke` step types have an implementation beneath them.

**Architecture:** A `@Component` mirroring the existing `RoleServiceConnector` — JAX-WS generated stubs, `OperationResult` return values, conditional on a configured endpoint. The `idmprov-resource.wsdl` is already wired into `jaxws-maven-plugin` and generating 100 classes; nothing consumes them yet.

**Tech Stack:** Java 21, Spring Boot 3.5.6, JAX-WS (jaxws-rt), JAXB, JUnit 5, AssertJ, WireMock 3.10.0.

**Source spec:** `docs/specs/2026-09-13-declarative-scenario-engine-design.md` §9

## Scope: this is plan 1 of 3

The spec's §11 flags the full increment as too large for one plan. It splits into three, each delivering working software on its own:

| Plan | Delivers | Depends on |
|---|---|---|
| **1. ResourceServiceConnector** (this plan) | A working connector, usable from Java tests today | Nothing |
| **2. Scenario engine** | Engine core, expression sandbox, step types, JUnit adapter, JSON results | Plan 1 for the two resource step types |
| **3. Conversion and spec amendment** | The 27 scenarios converted to YAML; specification principle amended | Plan 2 |

Plan 1 is first because it is the only piece with no dependencies and because plan 2's `resources.grant` step binds to the exact signatures this plan produces.

## Global Constraints

- Java 21. Use records over Lombok; Lombok is not a dependency and must not be added.
- Maven wrapper is not executable: run it as `sh ./mvnw ...`, never `./mvnw ...`.
- Build sibling modules with `-am`: they are not installed in the local Maven repo, so `-pl <module>` alone fails to resolve `com.idm.test:*` dependencies. When combining `-am` with `-Dtest=`, add `-Dsurefire.failIfNoSpecifiedTests=false`, or upstream modules fail with "No tests matching pattern".
- Follow `RoleServiceConnector` as the pattern: `@Component`, `@ConditionalOnProperty`, constructor takes `SoapProperties`, every method returns `OperationResult<T>`, no exceptions escape.
- Generated resource classes live in package `com.idm.test.connector.soap.generated.resource` and are produced by `jaxws-maven-plugin` at build time. Do not commit or hand-edit them.
- Commit messages end with the trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

---

## File Structure

**Created:**
- `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/ResourceServiceConnector.java` — grant and revoke against the Identity Applications resource service. One responsibility: translate two domain calls into SOAP and wrap the outcome in `OperationResult`.
- `idm-connector-soap/src/test/java/com/idm/test/connector/soap/client/ResourceServiceConnectorTest.java` — WireMock-backed tests for both operations plus failure handling.

- `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/SoapErrorTypes.java` — maps an exception to an `ErrorType`. Extracted from `RoleServiceConnector.determineErrorType`, which is private and would otherwise be copied verbatim into every new SOAP connector.

**Modified:**
- `idm-connector-soap/src/main/java/com/idm/test/connector/soap/config/SoapProperties.java` — add `resourceUrl`.
- `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/RoleServiceConnector.java` — delegate `determineErrorType` to `SoapErrorTypes`.

**Deliberately omitted:** `getResourceAssignmentsForUser`. The WSDL offers it and `RoleServiceConnector` has the analogous `getAssignedRoles`, but nothing consumes it — the scenario vocabulary verifies provisioning through LDAP and database assertions, not through the resource service. Adding it now would be speculative. It is a two-method class until something needs more.

**Known issue left alone:** `RoleServiceConnectorTest` binds WireMock to hardcoded port 8090. The new test uses a dynamic port. Changing the existing test is out of scope for this plan.

---

## Task 1: ResourceServiceConnector with grantResource

Establishes the connector, its configuration property, and the grant operation.

**Files:**
- Modify: `idm-connector-soap/src/main/java/com/idm/test/connector/soap/config/SoapProperties.java`
- Create: `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/ResourceServiceConnector.java`
- Create: `idm-connector-soap/src/test/java/com/idm/test/connector/soap/client/ResourceServiceConnectorTest.java`

**Interfaces:**
- Consumes: `SoapProperties` (existing, `@ConfigurationProperties(prefix = "idm.test.soap")`), `OperationResult<T>` and `ErrorType` from `idm-test-core`.
- Produces `SoapErrorTypes.classify(Exception)` returning `ErrorType`, used by both SOAP connectors.
- Produces, relied on by plan 2's `resources.grant` step:
  - `OperationResult<RequestResourceGrantResponse> grantResource(String userDn, String resourceDn, String reason)` — `reason` may be `null`.
  - The bean is only created when `idm.test.soap.resource-url` is set.

- [ ] **Step 1: Confirm the generated classes exist**

```bash
sh ./mvnw -q -pl idm-connector-soap -am generate-sources 2>/dev/null; ls idm-connector-soap/target/generated-sources/jaxws/com/idm/test/connector/soap/generated/resource | grep -cE "RequestResourceGrant|IRemoteResource|ResourceService"
```

Expected: `4` — `IRemoteResource.java`, `ResourceService.java`, `RequestResourceGrantRequest.java`, `RequestResourceGrantResponse.java`. If this prints `0`, the WSDL generation is broken and nothing below will compile.

- [ ] **Step 2: Add the resourceUrl property**

In `idm-connector-soap/src/main/java/com/idm/test/connector/soap/config/SoapProperties.java`, add the field immediately after the existing `url` field:

```java
    /**
     * Endpoint for the Identity Applications resource service, e.g.
     * https://idm.company.com:8543/IDMProv/resource/service
     * Separate from {@code url}, which addresses the role service.
     */
    private String resourceUrl;
```

And the accessors, immediately after `setUrl`:

```java
    public String getResourceUrl() {
        return resourceUrl;
    }

    public void setResourceUrl(String resourceUrl) {
        this.resourceUrl = resourceUrl;
    }
```

- [ ] **Step 3: Extract the error classifier so both connectors share it**

`RoleServiceConnector.determineErrorType` is private, and the new connector needs the same logic. Create `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/SoapErrorTypes.java`:

```java
package com.idm.test.connector.soap.client;

import com.idm.test.core.model.ErrorType;

/**
 * Maps exceptions thrown by SOAP calls onto {@link ErrorType}.
 * <p>
 * Shared by the SOAP connectors so the classification does not drift between them.
 */
final class SoapErrorTypes {

    private SoapErrorTypes() {
    }

    static ErrorType classify(Exception e) {
        String message = e.getMessage();

        if (message != null) {
            if (message.contains("timeout") || message.contains("timed out")) {
                return ErrorType.TIMEOUT;
            } else if (message.contains("Connection") || message.contains("Network")) {
                return ErrorType.NETWORK_ERROR;
            } else if (message.contains("Authentication") || message.contains("Unauthorized")) {
                return ErrorType.AUTHENTICATION_ERROR;
            }
        }

        return ErrorType.UNKNOWN_ERROR;
    }
}
```

Then in `RoleServiceConnector.java`, replace the body of `determineErrorType` with a delegation, leaving its callers untouched:

```java
    private ErrorType determineErrorType(Exception e) {
        return SoapErrorTypes.classify(e);
    }
```

Verify the existing connector still behaves identically:

```bash
sh ./mvnw -pl idm-connector-soap -am test -Dtest=RoleServiceConnectorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` — unchanged from before the extraction.

- [ ] **Step 4: Write the failing test**

Create `idm-connector-soap/src/test/java/com/idm/test/connector/soap/client/ResourceServiceConnectorTest.java`:

```java
package com.idm.test.connector.soap.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.connector.soap.generated.resource.RequestResourceGrantResponse;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests ResourceServiceConnector against a WireMock stand-in for the NetIQ
 * Identity Applications resource service.
 * <p>
 * WireMock binds a dynamic port in a static initialiser so the port is known
 * before the Spring context builds and cannot collide with another test.
 */
@SpringJUnitConfig(ResourceServiceConnectorTest.TestConfig.class)
@DisplayName("ResourceServiceConnector")
class ResourceServiceConnectorTest {

    private static final String RESOURCE_PATH = "/IDMProv/resource/service";
    private static final String USER_DN = "cn=jdoe,ou=users,dc=company,dc=com";
    private static final String RESOURCE_DN = "cn=SAP_ACCESS,ou=resources,dc=company,dc=com";

    private static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
        WireMock.configureFor("localhost", WIREMOCK.port());
    }

    @Autowired
    private ResourceServiceConnector connector;

    @Configuration
    static class TestConfig {
        @Bean
        SoapProperties soapProperties() {
            SoapProperties properties = new SoapProperties();
            properties.setResourceUrl("http://localhost:" + WIREMOCK.port() + RESOURCE_PATH);
            return properties;
        }

        @Bean
        ResourceServiceConnector resourceServiceConnector(SoapProperties properties) {
            return new ResourceServiceConnector(properties);
        }
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
    @DisplayName("Should grant a resource and return the request id")
    void testGrantResourceSuccess() {
        stubFor(post(urlPathEqualTo(RESOURCE_PATH))
                .withRequestBody(containing("requestResourceGrant"))
                .withRequestBody(containing(USER_DN))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(grantResponse("REQ-4242"))));

        OperationResult<RequestResourceGrantResponse> result =
                connector.grantResource(USER_DN, RESOURCE_DN, "Onboarding");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get().getResult().getValue()).isEqualTo("REQ-4242");

        verify(postRequestedFor(urlPathEqualTo(RESOURCE_PATH))
                .withRequestBody(containing(RESOURCE_DN)));
    }

    @Test
    @DisplayName("Should grant a resource when no reason is supplied")
    void testGrantResourceWithoutReason() {
        stubFor(post(urlPathEqualTo(RESOURCE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(grantResponse("REQ-1"))));

        OperationResult<RequestResourceGrantResponse> result =
                connector.grantResource(USER_DN, RESOURCE_DN, null);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should report failure instead of throwing when the service errors")
    void testGrantResourceServerError() {
        stubFor(post(urlPathEqualTo(RESOURCE_PATH))
                .willReturn(aResponse().withStatus(500)));

        OperationResult<RequestResourceGrantResponse> result =
                connector.grantResource(USER_DN, RESOURCE_DN, "Onboarding");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).contains("Resource grant failed");
    }

    /**
     * SOAP envelope matching idmprov-resource.wsdl's target namespace.
     */
    private static String grantResponse(String requestId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "xmlns:tns=\"http://www.novell.com/resource/service\">"
                + "<soap:Body>"
                + "<tns:requestResourceGrantResponse>"
                + "<tns:result>" + requestId + "</tns:result>"
                + "</tns:requestResourceGrantResponse>"
                + "</soap:Body>"
                + "</soap:Envelope>";
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

```bash
sh ./mvnw -pl idm-connector-soap -am test -Dtest=ResourceServiceConnectorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure — `cannot find symbol: class ResourceServiceConnector`.

- [ ] **Step 6: Implement the connector**

Create `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/ResourceServiceConnector.java`:

```java
package com.idm.test.connector.soap.client;

import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.connector.soap.generated.resource.IRemoteResource;
import com.idm.test.connector.soap.generated.resource.NrfServiceException_Exception;
import com.idm.test.connector.soap.generated.resource.ObjectFactory;
import com.idm.test.connector.soap.generated.resource.RequestResourceGrantRequest;
import com.idm.test.connector.soap.generated.resource.RequestResourceGrantResponse;
import com.idm.test.connector.soap.generated.resource.ResourceService;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.ws.BindingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * NetIQ Identity Applications resource service connector.
 * Uses JAX-WS generated stubs from idmprov-resource.wsdl.
 * <p>
 * Grants and revokes resources on User objects. The verbs match the Identity
 * Applications API rather than the role service's assign/remove, because that
 * is the vocabulary consultants use for resources.
 * <p>
 * Only created when idm.test.soap.resource-url is configured, so tests run
 * without resource service infrastructure.
 */
@Component
@ConditionalOnProperty(prefix = "idm.test.soap", name = "resource-url")
public class ResourceServiceConnector {

    private static final Logger log = LoggerFactory.getLogger(ResourceServiceConnector.class);

    private final IRemoteResource resourcePort;
    private final ObjectFactory objectFactory;

    public ResourceServiceConnector(SoapProperties soapProperties) {
        this.objectFactory = new ObjectFactory();

        ResourceService service = new ResourceService();
        this.resourcePort = service.getIRemoteResourcePort();

        BindingProvider bindingProvider = (BindingProvider) resourcePort;
        bindingProvider.getRequestContext().put(
                BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
                soapProperties.getResourceUrl());

        log.info("ResourceServiceConnector initialized with endpoint: {}",
                soapProperties.getResourceUrl());
    }

    /**
     * Grants a resource to a user.
     *
     * @param userDn     the user's DN
     * @param resourceDn the resource's DN
     * @param reason     reason for the request, or {@code null}
     * @return the grant response, or a failure result; never throws
     */
    public OperationResult<RequestResourceGrantResponse> grantResource(
            String userDn, String resourceDn, String reason) {

        log.debug("Granting resource {} to user {}", resourceDn, userDn);

        try {
            RequestResourceGrantRequest request = new RequestResourceGrantRequest();
            request.setUserTarget(
                    objectFactory.createRequestResourceGrantRequestUserTarget(userDn));
            request.setResourceTarget(
                    objectFactory.createRequestResourceGrantRequestResourceTarget(resourceDn));
            if (reason != null) {
                request.setReasonForRequest(
                        objectFactory.createRequestResourceGrantRequestReasonForRequest(reason));
            }

            RequestResourceGrantResponse response = resourcePort.requestResourceGrant(request);

            log.debug("Resource grant accepted: {}", valueOf(response.getResult()));

            return OperationResult.<RequestResourceGrantResponse>builder()
                    .success(true)
                    .data(response)
                    .message("Resource granted: " + valueOf(response.getResult()))
                    .build();

        } catch (NrfServiceException_Exception e) {
            log.error("Resource grant rejected by the service: {}", e.getFaultInfo().getReason());
            return OperationResult.<RequestResourceGrantResponse>builder()
                    .success(false)
                    .message("Resource grant failed: " + e.getFaultInfo().getReason())
                    .errorType(ErrorType.SERVER_ERROR)
                    .throwable(e)
                    .build();

        } catch (Exception e) {
            log.error("Resource grant call failed: {}", e.getMessage());
            return OperationResult.<RequestResourceGrantResponse>builder()
                    .success(false)
                    .message("Resource grant failed: " + e.getMessage())
                    .errorType(SoapErrorTypes.classify(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Unwraps a JAXBElement that the generated model may leave null.
     */
    private static String valueOf(JAXBElement<String> element) {
        return element == null ? null : element.getValue();
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

```bash
sh ./mvnw -pl idm-connector-soap -am test -Dtest=ResourceServiceConnectorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`

If `testGrantResourceServerError` fails because the message differs, read the actual message before changing the assertion — a 500 should surface as a connection-level failure, and a different message may mean the exception is being caught by the wrong branch.

- [ ] **Step 8: Commit**

```bash
git add idm-connector-soap/src/main/java/com/idm/test/connector/soap/config/SoapProperties.java \
        idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/SoapErrorTypes.java \
        idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/RoleServiceConnector.java \
        idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/ResourceServiceConnector.java \
        idm-connector-soap/src/test/java/com/idm/test/connector/soap/client/ResourceServiceConnectorTest.java
git commit -m "feat: add ResourceServiceConnector with resource grant

idmprov-resource.wsdl has been generating 100 classes since the SOAP
connector was added, with nothing consuming them. Adds the grant half of
the resource operations the scenario engine's resources.grant step needs.

Uses the Identity Applications verbs grant/revoke rather than the role
service's assign/remove, matching what the WSDL itself calls them.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: revokeResource

**Files:**
- Modify: `idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/ResourceServiceConnector.java`
- Modify: `idm-connector-soap/src/test/java/com/idm/test/connector/soap/client/ResourceServiceConnectorTest.java`

**Interfaces:**
- Consumes: everything produced by Task 1.
- Produces, relied on by plan 2's `resources.revoke` step:
  - `OperationResult<RequestResourceRevokeResponse> revokeResource(String userDn, String resourceDn, String reason)` — `reason` may be `null`.

- [ ] **Step 1: Write the failing test**

Add these imports to `ResourceServiceConnectorTest.java`:

```java
import com.idm.test.connector.soap.generated.resource.RequestResourceRevokeResponse;
```

Add these two tests before the `grantResponse` helper:

```java
    @Test
    @DisplayName("Should revoke a resource and return the request id")
    void testRevokeResourceSuccess() {
        stubFor(post(urlPathEqualTo(RESOURCE_PATH))
                .withRequestBody(containing("requestResourceRevoke"))
                .withRequestBody(containing(USER_DN))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(revokeResponse("REQ-9001"))));

        OperationResult<RequestResourceRevokeResponse> result =
                connector.revokeResource(USER_DN, RESOURCE_DN, "Termination");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get().getResult().getValue()).isEqualTo("REQ-9001");

        verify(postRequestedFor(urlPathEqualTo(RESOURCE_PATH))
                .withRequestBody(containing(RESOURCE_DN)));
    }

    @Test
    @DisplayName("Should report failure instead of throwing when revoke errors")
    void testRevokeResourceServerError() {
        stubFor(post(urlPathEqualTo(RESOURCE_PATH))
                .willReturn(aResponse().withStatus(500)));

        OperationResult<RequestResourceRevokeResponse> result =
                connector.revokeResource(USER_DN, RESOURCE_DN, "Termination");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).contains("Resource revoke failed");
    }
```

And add this helper immediately after `grantResponse`:

```java
    private static String revokeResponse(String requestId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "xmlns:tns=\"http://www.novell.com/resource/service\">"
                + "<soap:Body>"
                + "<tns:requestResourceRevokeResponse>"
                + "<tns:result>" + requestId + "</tns:result>"
                + "</tns:requestResourceRevokeResponse>"
                + "</soap:Body>"
                + "</soap:Envelope>";
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
sh ./mvnw -pl idm-connector-soap -am test -Dtest=ResourceServiceConnectorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure — `cannot find symbol: method revokeResource`.

- [ ] **Step 3: Implement revokeResource**

Add these imports to `ResourceServiceConnector.java`:

```java
import com.idm.test.connector.soap.generated.resource.RequestResourceRevokeRequest;
import com.idm.test.connector.soap.generated.resource.RequestResourceRevokeResponse;
```

Add this method immediately after `grantResource`, before the `valueOf` helper:

```java
    /**
     * Revokes a resource from a user.
     *
     * @param userDn     the user's DN
     * @param resourceDn the resource's DN
     * @param reason     reason for the request, or {@code null}
     * @return the revoke response, or a failure result; never throws
     */
    public OperationResult<RequestResourceRevokeResponse> revokeResource(
            String userDn, String resourceDn, String reason) {

        log.debug("Revoking resource {} from user {}", resourceDn, userDn);

        try {
            RequestResourceRevokeRequest request = new RequestResourceRevokeRequest();
            request.setUserTarget(
                    objectFactory.createRequestResourceRevokeRequestUserTarget(userDn));
            request.setResourceTarget(
                    objectFactory.createRequestResourceRevokeRequestResourceTarget(resourceDn));
            if (reason != null) {
                request.setReasonForRequest(
                        objectFactory.createRequestResourceRevokeRequestReasonForRequest(reason));
            }

            RequestResourceRevokeResponse response = resourcePort.requestResourceRevoke(request);

            log.debug("Resource revoke accepted: {}", valueOf(response.getResult()));

            return OperationResult.<RequestResourceRevokeResponse>builder()
                    .success(true)
                    .data(response)
                    .message("Resource revoked: " + valueOf(response.getResult()))
                    .build();

        } catch (NrfServiceException_Exception e) {
            log.error("Resource revoke rejected by the service: {}", e.getFaultInfo().getReason());
            return OperationResult.<RequestResourceRevokeResponse>builder()
                    .success(false)
                    .message("Resource revoke failed: " + e.getFaultInfo().getReason())
                    .errorType(ErrorType.SERVER_ERROR)
                    .throwable(e)
                    .build();

        } catch (Exception e) {
            log.error("Resource revoke call failed: {}", e.getMessage());
            return OperationResult.<RequestResourceRevokeResponse>builder()
                    .success(false)
                    .message("Resource revoke failed: " + e.getMessage())
                    .errorType(SoapErrorTypes.classify(e))
                    .throwable(e)
                    .build();
        }
    }
```

Note the revoke request also carries an optional `instanceGuid`, used to revoke one specific assignment rather than the user's assignment to a resource. It is not set here because no caller needs it; add it when one does.

- [ ] **Step 4: Run the test to verify it passes**

```bash
sh ./mvnw -pl idm-connector-soap -am test -Dtest=ResourceServiceConnectorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Run the whole suite to confirm nothing regressed**

```bash
sh ./mvnw clean test 2>&1 | grep -E "^\[INFO\] Tests run:|BUILD"
```

Expected: `BUILD SUCCESS`, every line reporting `Failures: 0, Errors: 0, Skipped: 0`. The suite total rises from 167 to 172.

- [ ] **Step 6: Commit**

```bash
git add idm-connector-soap/src/main/java/com/idm/test/connector/soap/client/ResourceServiceConnector.java \
        idm-connector-soap/src/test/java/com/idm/test/connector/soap/client/ResourceServiceConnectorTest.java
git commit -m "feat: add resource revoke to ResourceServiceConnector

Completes the grant/revoke pair the scenario engine's resources.* steps
need. instanceGuid is deliberately not exposed: it revokes one specific
assignment rather than the user's assignment to a resource, and no caller
needs that distinction yet.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Done When

- [ ] `sh ./mvnw clean test` returns `BUILD SUCCESS` with 172 tests, 0 failures, 0 errors, 0 skipped
- [ ] `ResourceServiceConnector` exposes exactly `grantResource` and `revokeResource`, both returning `OperationResult`
- [ ] Neither method throws: a 500 response and a service fault both produce a failure result
- [ ] The connector bean is absent when `idm.test.soap.resource-url` is unset, so existing scenarios are unaffected
- [ ] No generated source under `target/generated-sources` has been committed
