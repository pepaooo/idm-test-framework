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
