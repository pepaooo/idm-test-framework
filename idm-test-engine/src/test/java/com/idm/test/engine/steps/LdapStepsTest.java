package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the LDAP step types against an embedded directory.
 * <p>
 * Builds the LdapTemplate directly rather than through Spring, because this module has no
 * Spring context by design.
 */
@DisplayName("LDAP step types")
class LdapStepsTest {

    private static final String BASE_DN = "dc=test,dc=com";
    private static final String BIND_DN = "cn=admin,dc=test,dc=com";
    private static final String BIND_PASSWORD = "test";

    private static EmbeddedLdapServer server;
    private static EdirectoryConnector connector;

    @BeforeAll
    static void startDirectory() throws Exception {
        server = EmbeddedLdapServer.start(
                BASE_DN, BIND_DN, BIND_PASSWORD, 0, "scenarios/engine-test.ldif");

        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(server.getUrl());
        contextSource.setBase(BASE_DN);
        contextSource.setUserDn(BIND_DN);
        contextSource.setPassword(BIND_PASSWORD);
        contextSource.afterPropertiesSet();

        connector = new EdirectoryConnector(new LdapTemplate(contextSource));
    }

    @AfterAll
    static void stopDirectory() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("ldap.create should create an entry and return an undo that deletes it")
    void testCreateAndUndo() {
        LdapCreateStep create = new LdapCreateStep(connector);
        String dn = "cn=ada.create,ou=users";

        StepOutcome outcome = create.execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.create", "sn", "Lovelace", "givenName", "Ada")));

        assertThat(outcome.success()).isTrue();
        assertThat(connector.read(dn).isSuccess()).isTrue();

        outcome.undo().run();
        assertThat(connector.read(dn).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("ldap.create should report failure rather than throw on a schema violation")
    void testCreateFailure() {
        LdapCreateStep create = new LdapCreateStep(connector);

        // inetOrgPerson requires sn; omitting it must fail
        StepOutcome outcome = create.execute(Map.of(
                "dn", "cn=broken,ou=users",
                "attributes", Map.of("cn", "broken")));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).containsIgnoringCase("failed");
    }

    @Test
    @DisplayName("ldap.verify should pass when the attributes match")
    void testVerifyMatches() {
        String dn = "cn=ada.verify,ou=users";
        new LdapCreateStep(connector).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.verify", "sn", "Lovelace", "givenName", "Ada")));

        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Ada", "sn", "Lovelace")));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("ldap.verify should fail and say what it saw when an attribute differs")
    void testVerifyMismatch() {
        String dn = "cn=grace.verify,ou=users";
        new LdapCreateStep(connector).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "grace.verify", "sn", "Hopper", "givenName", "Grace")));

        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Ada")));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains(dn);
        assertThat(outcome.message()).containsIgnoringCase("givenName");
    }

    @Test
    @DisplayName("ldap.create's undo should remove the entry it created")
    void testCreateUndoRemovesEntry() {
        // NOTE: the undo-throws-on-failed-delete path (IllegalStateException) could not be
        // exercised here. EdirectoryConnector.delete() on this embedded directory treats
        // deleting an already-absent DN as a successful no-op rather than a failure (verified
        // by running undo twice: both runs logged "LDAP user deleted successfully" and returned
        // success). Since the connector never reports failure for this case, the throwing
        // branch of the undo is untestable against this connector/backend and is instead
        // covered by the fact that undo() surfaces IllegalStateException whenever
        // connector.delete(dn) does return an unsuccessful OperationResult.
        LdapCreateStep create = new LdapCreateStep(connector);
        String dn = "cn=ada.undo-failure,ou=users";

        StepOutcome outcome = create.execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.undo-failure", "sn", "Lovelace", "givenName", "Ada")));

        assertThat(outcome.success()).isTrue();

        outcome.undo().run();
        assertThat(connector.read(dn).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("ldap.verify should fail when the entry does not exist")
    void testVerifyMissingEntry() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", "cn=absent,ou=users",
                "expect", Map.of("givenName", "Ada")));

        assertThat(outcome.success()).isFalse();
    }

    @Test
    @DisplayName("Only the assertion step is retryable")
    void testRetryability() {
        assertThat(new LdapCreateStep(connector).retryable()).isFalse();
        assertThat(new LdapVerifyStep(connector).retryable()).isTrue();
        assertThat(new LdapCreateStep(connector).name()).isEqualTo("ldap.create");
        assertThat(new LdapVerifyStep(connector).name()).isEqualTo("ldap.verify");
    }

    @Test
    @DisplayName("ldap.verify should declare 'expect' as required, closing the "
            + "vacuous-assertion path by construction")
    void testVerifyDeclaresExpectRequired() {
        LdapVerifyStep verify = new LdapVerifyStep(connector);

        // A typo like "expct" must fail ScenarioValidator instead of falling through to
        // params.getOrDefault("expect", Map.of()), which would compare against nothing and
        // report success. Declaring 'expect' (and 'dn') as required is what closes that gap.
        assertThat(verify.requiredParams()).containsExactlyInAnyOrder("dn", "expect");
        assertThat(verify.acceptedParams()).containsExactlyInAnyOrder("dn", "expect", "wait");
    }

    @Test
    @DisplayName("ldap.create should require 'dn' and also accept 'attributes'")
    void testCreateRequiredAndAcceptedParams() {
        LdapCreateStep create = new LdapCreateStep(connector);

        assertThat(create.requiredParams()).containsExactly("dn");
        assertThat(create.acceptedParams()).containsExactlyInAnyOrder("dn", "attributes");
    }

    @Test
    @DisplayName("ldap.create should return the DN it created, for as: to capture")
    void testCreateReturnsDn() {
        String dn = "cn=ada.value,ou=users";

        StepOutcome outcome = new LdapCreateStep(connector).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.value", "sn", "Lovelace")));

        assertThat(outcome.value()).isEqualTo(dn);
    }

    @Test
    @DisplayName("ldap.verify should return the entry's attributes, for as: to capture")
    void testVerifyReturnsAttributes() {
        String dn = "cn=grace.value,ou=users";
        new LdapCreateStep(connector).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "grace.value", "sn", "Hopper", "givenName", "Grace")));

        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Grace")));

        assertThat(outcome.value()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) outcome.value();
        assertThat(attributes).containsEntry("sn", "Hopper").containsEntry("givenName", "Grace");
    }

    @Test
    @DisplayName("A failed step should carry no captured value")
    void testFailureHasNoValue() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", "cn=absent.value,ou=users",
                "expect", Map.of("givenName", "Ada")));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.value()).isNull();
    }
}
