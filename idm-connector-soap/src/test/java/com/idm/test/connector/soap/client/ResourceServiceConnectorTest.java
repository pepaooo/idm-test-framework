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
