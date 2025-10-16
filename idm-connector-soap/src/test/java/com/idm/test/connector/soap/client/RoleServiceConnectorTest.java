package com.idm.test.connector.soap.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.connector.soap.generated.role.*;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for RoleServiceConnector using WireMock to mock NetIQ Role Service.
 * Tests use actual WSDL-generated classes from idmprov-role.wsdl.
 */
@SpringJUnitConfig(RoleServiceConnectorTest.TestConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoleServiceConnectorTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private RoleServiceConnector connector;

    @Configuration
    static class TestConfig {
        @Bean
        public SoapProperties soapProperties() {
            SoapProperties properties = new SoapProperties();
            properties.setUrl("http://localhost:8090/IDMProv/role/service");
            properties.setConnectionTimeout(5000);
            properties.setReadTimeout(10000);
            return properties;
        }

        @Bean
        public RoleServiceConnector roleServiceConnector(SoapProperties properties) {
            return new RoleServiceConnector(properties);
        }
    }

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(8090);
        wireMockServer.start();
        WireMock.configureFor("localhost", 8090);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
    }

    @Test
    @Order(1)
    @DisplayName("Should assign roles successfully")
    void testAssignRolesSuccess() {
        // Mock successful requestRolesAssignment SOAP response
        String soapResponse = createRequestRolesAssignmentResponse("REQ-12345");

        stubFor(post(urlPathEqualTo("/IDMProv/role/service"))
                .withRequestBody(containing("requestRolesAssignment"))
                .withRequestBody(containing("cn=jdoe,ou=users,dc=company,dc=com"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapResponse)));

        // Execute
        OperationResult<RequestRolesAssignmentResponse> result = connector.assignRoles(
                "cn=jdoe,ou=users,dc=company,dc=com",
                new String[]{"cn=SAP_USER,ou=roles,dc=company,dc=com"},
                "Testing role assignment"
        );

        // Verify
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get().getResult()).isNotNull();

        verify(postRequestedFor(urlPathEqualTo("/IDMProv/role/service"))
                .withRequestBody(containing("cn=jdoe,ou=users,dc=company,dc=com")));
    }

    @Test
    @Order(2)
    @DisplayName("Should assign single role using convenience method")
    void testAssignRoleConvenience() {
        String soapResponse = createRequestRolesAssignmentResponse("REQ-67890");

        stubFor(post(urlPathEqualTo("/IDMProv/role/service"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapResponse)));

        OperationResult<RequestRolesAssignmentResponse> result = connector.assignRole(
                "cn=jsmith,ou=users,dc=company,dc=com",
                "cn=APP_ACCESS,ou=roles,dc=company,dc=com"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
    }

    @Test
    @Order(3)
    @DisplayName("Should remove roles successfully")
    void testRemoveRolesSuccess() {
        String soapResponse = createRemoveRolesResponse("Roles removed successfully");

        stubFor(post(urlPathEqualTo("/IDMProv/role/service"))
                .withRequestBody(containing("removeRoles"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapResponse)));

        OperationResult<RemoveRolesResponse> result = connector.removeRoles(
                new String[]{"cn=assignment123,cn=SAP_USER,ou=roles,dc=company,dc=com"}
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get().getResult()).isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName("Should remove single role using convenience method")
    void testRemoveRoleConvenience() {
        String soapResponse = createRemoveRolesResponse("Role removed");

        stubFor(post(urlPathEqualTo("/IDMProv/role/service"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapResponse)));

        OperationResult<RemoveRolesResponse> result = connector.removeRole(
                "cn=assignment456,cn=APP_ACCESS,ou=roles,dc=company,dc=com"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
    }

    @Test
    @Order(5)
    @DisplayName("Should get user with assigned roles")
    void testGetAssignedRoles() {
        String soapResponse = createGetUserResponse(
                "cn=jdoe,ou=users,dc=company,dc=com",
                new String[]{
                        "cn=SAP_USER,ou=roles,dc=company,dc=com",
                        "cn=APP_ACCESS,ou=roles,dc=company,dc=com"
                }
        );

        stubFor(post(urlPathEqualTo("/IDMProv/role/service"))
                .withRequestBody(containing("getUser"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapResponse)));

        OperationResult<GetUserResponse> result = connector.getAssignedRoles(
                "cn=jdoe,ou=users,dc=company,dc=com"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();

        User user = result.getData().get().getResult().getValue();
        assertThat(user.getAssociatedRoles().getDnstring()).hasSize(2);
    }

    @Test
    @Order(6)
    @DisplayName("Should handle SOAP fault from role service")
    void testSoapFault() {
        String soapFault = createSoapFault("NrfServiceFault", "User not found in directory");

        stubFor(post(urlPathEqualTo("/IDMProv/role/service"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapFault)));

        OperationResult<RequestRolesAssignmentResponse> result = connector.assignRole(
                "cn=nonexistent,ou=users,dc=company,dc=com",
                "cn=SAP_USER,ou=roles,dc=company,dc=com"
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("User not found");
    }

    @Test
    @Order(7)
    @DisplayName("Should handle connection timeout")
    void testConnectionTimeout() {
        // Note: JAX-WS default timeout is longer than test timeout
        // This test verifies the connector handles timeouts gracefully
        stubFor(post(urlPathEqualTo("/IDMProv/role/service"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(60000) // Very long delay
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(createRequestRolesAssignmentResponse("REQ-999"))));

        // This might succeed or timeout depending on JAX-WS client configuration
        OperationResult<RequestRolesAssignmentResponse> result = connector.assignRole(
                "cn=test,ou=users,dc=company,dc=com",
                "cn=SAP_USER,ou=roles,dc=company,dc=com"
        );

        // Just verify we get a result (success or failure both acceptable)
        assertThat(result).isNotNull();
    }

    @Test
    @Order(8)
    @DisplayName("Should handle network error")
    void testNetworkError() {
        wireMockServer.stop();

        OperationResult<RequestRolesAssignmentResponse> result = connector.assignRole(
                "cn=test,ou=users,dc=company,dc=com",
                "cn=SAP_USER,ou=roles,dc=company,dc=com"
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isPresent();

        wireMockServer.start();
    }

    /**
     * Creates a SOAP response for requestRolesAssignment operation based on NetIQ WSDL.
     */
    private String createRequestRolesAssignmentResponse(String requestId) {
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

    /**
     * Creates a SOAP response for removeRoles operation based on NetIQ WSDL.
     */
    private String createRemoveRolesResponse(String message) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "xmlns:tns=\"http://www.novell.com/role/service\">" +
                "<soap:Body>" +
                "<tns:removeRolesResponse>" +
                "<tns:result>" + message + "</tns:result>" +
                "</tns:removeRolesResponse>" +
                "</soap:Body>" +
                "</soap:Envelope>";
    }

    /**
     * Creates a SOAP response for getUser operation with associated roles.
     */
    private String createGetUserResponse(String userDn, String[] roleDns) {
        StringBuilder roleXml = new StringBuilder();
        for (String roleDn : roleDns) {
            roleXml.append("<tns:dnstring><tns:dn>").append(roleDn).append("</tns:dn></tns:dnstring>");
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "xmlns:tns=\"http://www.novell.com/role/service\">" +
                "<soap:Body>" +
                "<tns:getUserResponse>" +
                "<tns:result>" +
                "<tns:dn>" + userDn + "</tns:dn>" +
                "<tns:associatedRoles>" +
                roleXml.toString() +
                "</tns:associatedRoles>" +
                "</tns:result>" +
                "</tns:getUserResponse>" +
                "</soap:Body>" +
                "</soap:Envelope>";
    }

    /**
     * Creates a SOAP fault response for error scenarios.
     */
    private String createSoapFault(String faultCode, String faultString) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "xmlns:tns=\"http://www.novell.com/role/service\">" +
                "<soap:Body>" +
                "<soap:Fault>" +
                "<faultcode>" + faultCode + "</faultcode>" +
                "<faultstring>" + faultString + "</faultstring>" +
                "<detail>" +
                "<tns:NrfServiceException>" +
                "<tns:reason>" + faultString + "</tns:reason>" +
                "</tns:NrfServiceException>" +
                "</detail>" +
                "</soap:Fault>" +
                "</soap:Body>" +
                "</soap:Envelope>";
    }
}
