package com.idm.test.connector.soap.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.soap.config.SoapProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SOAP basic authentication")
class SoapCredentialsTest {

    private static WireMockServer wiremock;

    @BeforeAll
    static void start() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stop() {
        wiremock.stop();
    }

    @BeforeEach
    void reset() {
        wiremock.resetAll();
        wiremock.stubFor(post(urlPathEqualTo("/IDMProv/role/service")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(createRequestRolesAssignmentResponse("REQ-1"))));
    }

    private static SoapProperties properties(String username, String password) {
        SoapProperties properties = new SoapProperties();
        properties.setUrl(wiremock.baseUrl() + "/IDMProv/role/service");
        properties.setResourceUrl(wiremock.baseUrl() + "/IDMProv/resource/service");
        properties.setUsername(username);
        properties.setPassword(password);
        return properties;
    }

    private static void assignRole(SoapProperties properties) {
        new RoleServiceConnector(properties).assignRole("cn=jdoe,ou=users", "cn=r,ou=roles");
    }

    @Test
    @DisplayName("Role service requests carry Basic credentials when both are set")
    void testRoleServiceSendsBasic() {
        assignRole(properties("svc", "s3cret"));

        wiremock.verify(postRequestedFor(urlPathEqualTo("/IDMProv/role/service"))
                .withHeader("Authorization", equalTo("Basic c3ZjOnMzY3JldA==")));
    }

    @Test
    @DisplayName("Without credentials, no Authorization header is sent")
    void testNoCredentialsNoHeader() {
        assignRole(properties(null, null));

        wiremock.verify(postRequestedFor(urlPathEqualTo("/IDMProv/role/service")).withoutHeader("Authorization"));
    }

    @Test
    @DisplayName("Resource service requests carry Basic credentials when both are set")
    void testResourceServiceSendsBasic() {
        wiremock.stubFor(post(urlPathEqualTo("/IDMProv/resource/service"))
                .willReturn(aResponse().withStatus(500).withBody("")));

        new ResourceServiceConnector(properties("svc", "s3cret")).grantResource("cn=jdoe,ou=users", "cn=res,ou=resources", "test");

        wiremock.verify(postRequestedFor(urlPathEqualTo("/IDMProv/resource/service"))
                .withHeader("Authorization", equalTo("Basic c3ZjOnMzY3JldA==")));
    }

    @Test
    @DisplayName("Only one credential set refuses construction, naming the missing property and not the value")
    void testHalfSetRefused() {
        assertThatThrownBy(() -> new RoleServiceConnector(properties("svc", " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.soap.password");
        assertThatThrownBy(() -> new ResourceServiceConnector(properties(null, "s3cret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.soap.username")
                .hasMessageNotContaining("s3cret");
    }

    /**
     * Creates a SOAP response for requestRolesAssignment operation based on NetIQ WSDL.
     * Test fixture copied from RoleServiceConnectorTest.
     */
    private static String createRequestRolesAssignmentResponse(String requestId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "xmlns:tns=\"http://www.novell.com/role/service\">" +
                "<soap:Body>" +
                "<tns:requestRolesAssignmentResponse>" +
                "<tns:result>" + requestId + "</tns:result>" +
                "</tns:requestRolesAssignmentResponse>" +
                "</soap:Body>" +
                "</soap:Envelope>";
    }
}
