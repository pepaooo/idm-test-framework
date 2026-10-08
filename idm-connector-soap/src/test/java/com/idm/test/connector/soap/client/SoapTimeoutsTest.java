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
