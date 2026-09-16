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
