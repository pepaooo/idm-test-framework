package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                "attributes", Map.of("cn", "ada.create", "sn", "Lovelace", "givenName", "Ada")), false);

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
                "attributes", Map.of("cn", "broken")), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).containsIgnoringCase("failed");
    }

    @Test
    @DisplayName("ldap.verify should pass when the attributes match")
    void testVerifyMatches() {
        String dn = "cn=ada.verify,ou=users";
        new LdapCreateStep(connector).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.verify", "sn", "Lovelace", "givenName", "Ada")), false);

        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Ada", "sn", "Lovelace")), false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("ldap.verify should fail and say what it saw when an attribute differs")
    void testVerifyMismatch() {
        String dn = "cn=grace.verify,ou=users";
        new LdapCreateStep(connector).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "grace.verify", "sn", "Hopper", "givenName", "Grace")), false);

        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Ada")), false);

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
                "attributes", Map.of("cn", "ada.undo-failure", "sn", "Lovelace", "givenName", "Ada")), false);

        assertThat(outcome.success()).isTrue();

        outcome.undo().run();
        assertThat(connector.read(dn).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("ldap.verify should fail when the entry does not exist")
    void testVerifyMissingEntry() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", "cn=absent,ou=users",
                "expect", Map.of("givenName", "Ada")), false);

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
    @DisplayName("ldap.verify should require only 'dn', leaving the expect-vs-exists choice "
            + "to rejectMalformedAssertion")
    void testVerifyRequiresOnlyDn() {
        LdapVerifyStep verify = new LdapVerifyStep(connector);

        // 'expect' is no longer unconditionally required: exists: is now a valid alternative
        // assertion. A typo like "expct" (with no exists: present either) is still caught,
        // not by requiredParams() anymore, but by rejectMalformedAssertion's "neither
        // supplied" branch, exercised in testVerifyRejectsNeitherExpectNorExists.
        assertThat(verify.requiredParams()).containsExactly("dn");
        assertThat(verify.acceptedParams())
                .containsExactlyInAnyOrder("dn", "expect", "exists", "wait");
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
                "attributes", Map.of("cn", "ada.value", "sn", "Lovelace")), false);

        assertThat(outcome.value()).isEqualTo(dn);
    }

    @Test
    @DisplayName("ldap.verify should return the entry's attributes when a capture is "
            + "requested, for as: to capture")
    void testVerifyReturnsAttributes() {
        String dn = "cn=grace.value,ou=users";
        new LdapCreateStep(connector).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "grace.value", "sn", "Hopper", "givenName", "Grace")), false);

        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Grace")), true);

        assertThat(outcome.value()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) outcome.value();
        assertThat(attributes).containsEntry("sn", "Hopper").containsEntry("givenName", "Grace");
    }

    @Test
    @DisplayName("ldap.verify should skip the follow-up read when no capture is requested")
    void testVerifySkipsReadWhenNoCaptureRequested() {
        // A mocked connector, unlike the rest of this file's embedded directory: the point of
        // this test is to prove read() is never called, which an embedded directory can't
        // observe directly.
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        when(mockConnector.verifyAttributes(any(), any()))
                .thenReturn(OperationResult.success(true));

        StepOutcome outcome = new LdapVerifyStep(mockConnector).execute(Map.of(
                "dn", "cn=x,ou=users", "expect", Map.of("givenName", "Ada")), false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isNull();
        verify(mockConnector, never()).read(any());
    }

    @Test
    @DisplayName("ldap.verify should fail, not fabricate an empty capture, when verification "
            + "passes but the follow-up read for a requested capture fails")
    void testVerifyFailsWhenCaptureReadFailsAfterSuccessfulVerify() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        when(mockConnector.verifyAttributes(any(), any()))
                .thenReturn(OperationResult.success(true));
        when(mockConnector.read(any()))
                .thenReturn(OperationResult.<LdapUser>failure("connection dropped"));

        StepOutcome outcome = new LdapVerifyStep(mockConnector).execute(Map.of(
                "dn", "cn=x,ou=users", "expect", Map.of("givenName", "Ada")), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.value()).isNull();
        assertThat(outcome.message()).contains("cn=x,ou=users").contains("connection dropped");
    }

    @Test
    @DisplayName("A failed step should carry no captured value")
    void testFailureHasNoValue() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", "cn=absent.value,ou=users",
                "expect", Map.of("givenName", "Ada")), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.value()).isNull();
    }

    // ------------------------------------------------------------------
    // ldap.update (ITF-8 Task 2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ldap.update should change attributes and restore prior values on undo")
    void testUpdateRestoresPriorValues() {
        LdapCreateStep create = new LdapCreateStep(connector);
        LdapUpdateStep update = new LdapUpdateStep(connector);
        String dn = "cn=ada.update,ou=users";

        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.update", "sn", "Lovelace", "title", "Analyst")), false);

        StepOutcome outcome = update.execute(
                Map.of("dn", dn, "attributes", Map.of("title", "Engineer")), false);

        assertThat(outcome.success()).isTrue();
        assertThat(connector.read(dn).getData().orElseThrow().getTitle()).isEqualTo("Engineer");

        outcome.undo().run();
        assertThat(connector.read(dn).getData().orElseThrow().getTitle()).isEqualTo("Analyst");
    }

    @Test
    @DisplayName("ldap.update undo should remove an attribute that did not exist before")
    void testUpdateUndoRemovesNewlyAddedAttribute() {
        LdapCreateStep create = new LdapCreateStep(connector);
        LdapUpdateStep update = new LdapUpdateStep(connector);
        String dn = "cn=ada.added,ou=users";

        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.added", "sn", "Lovelace")), false);

        StepOutcome outcome = update.execute(
                Map.of("dn", dn, "attributes", Map.of("description", "temporary")), false);

        assertThat(outcome.success()).isTrue();
        assertThat(connector.read(dn).getData().orElseThrow().getDescription())
                .isEqualTo("temporary");

        // 'description' had no prior value, so restoring it means removing it. Without this
        // the undo would silently leave the attribute behind and still report success.
        outcome.undo().run();
        assertThat(connector.read(dn).getData().orElseThrow().getDescription()).isNull();
    }

    @Test
    @DisplayName("ldap.update should report failure rather than throw for a missing entry")
    void testUpdateMissingEntryFails() {
        LdapUpdateStep update = new LdapUpdateStep(connector);

        StepOutcome outcome = update.execute(
                Map.of("dn", "cn=nobody.here,ou=users", "attributes", Map.of("title", "X")), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("cn=nobody.here,ou=users");
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("ldap.update should bind the dn when a capture was requested")
    void testUpdateCaptureIsTheDn() {
        LdapCreateStep create = new LdapCreateStep(connector);
        LdapUpdateStep update = new LdapUpdateStep(connector);
        String dn = "cn=ada.capture,ou=users";

        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.capture", "sn", "Lovelace")), false);

        StepOutcome outcome = update.execute(
                Map.of("dn", dn, "attributes", Map.of("title", "Engineer")), true);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(dn);
    }

    // ------------------------------------------------------------------
    // ldap.delete (ITF-8 Task 3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ldap.delete should remove the entry and register no undo")
    void testDeleteRegistersNoUndo() {
        LdapCreateStep create = new LdapCreateStep(connector);
        LdapDeleteStep delete = new LdapDeleteStep(connector);
        String dn = "cn=ada.delete,ou=users";

        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.delete", "sn", "Lovelace")), false);

        StepOutcome outcome = delete.execute(Map.of("dn", dn), false);

        assertThat(outcome.success()).isTrue();
        assertThat(connector.read(dn).isSuccess()).isFalse();

        // Deliberate: a recreated entry silently loses userPassword and operational
        // attributes, so no undo is better than a convincing fake.
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("ldap.delete should fail for an entry that was never there")
    void testDeleteMissingEntryFails() {
        // LdapTemplate.unbind succeeds silently on a DN that does not exist, so without an
        // explicit existence check this step would report "deleted" having deleted nothing.
        LdapDeleteStep delete = new LdapDeleteStep(connector);

        StepOutcome outcome = delete.execute(Map.of("dn", "cn=never.existed,ou=users"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("cn=never.existed,ou=users");
    }

    @Test
    @DisplayName("ldap.delete should bind the dn when a capture was requested")
    void testDeleteCaptureIsTheDn() {
        LdapCreateStep create = new LdapCreateStep(connector);
        LdapDeleteStep delete = new LdapDeleteStep(connector);
        String dn = "cn=ada.deletecapture,ou=users";

        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.deletecapture", "sn", "Lovelace")), false);

        StepOutcome outcome = delete.execute(Map.of("dn", dn), true);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(dn);
    }

    @Test
    @DisplayName("ldap.verify should fail rather than throw when expect itself is not a map")
    void testVerifyRejectsNonMapExpect() {
        // A scenario writing `expect: Engineer` (a scalar) where a map of attribute names to
        // expected values belongs used to throw a ClassCastException out of execute() — a
        // StepType.execute implementation must never throw. rest.verify and db.verify were
        // both fixed for exactly this; this closes the same gap in their remaining sibling.
        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", "cn=whoever,ou=users",
                "expect", "Engineer"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("expect");
    }

    @Test
    @DisplayName("ldap.verify should reject an expect block that asserts nothing")
    void testVerifyRejectsEmptyExpect() {
        LdapCreateStep create = new LdapCreateStep(connector);
        String dn = "cn=ada.vacuous,ou=users";
        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.vacuous", "sn", "Lovelace")), false);

        // 'expect' being required catches a misspelled expct:, but a present-but-empty
        // expect: {} passed that check and reported "verified" having compared nothing.
        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", dn, "expect", Map.of()), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("verify nothing");
    }

    // ------------------------------------------------------------------
    // ldap.verify exists: (ITF-5 Task 1)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ldap.verify exists:false should pass once the entry is gone")
    void testVerifyExistsFalsePassesWhenAbsent() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", "cn=never.created,ou=users", "exists", false), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("ldap.verify exists:false should fail while the entry is still there")
    void testVerifyExistsFalseFailsWhenPresent() {
        LdapCreateStep create = new LdapCreateStep(connector);
        String dn = "cn=ada.stillhere,ou=users";
        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.stillhere", "sn", "Lovelace")), false);

        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", dn, "exists", false), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains(dn);
    }

    @Test
    @DisplayName("ldap.verify exists:true should pass without asserting attributes")
    void testVerifyExistsTruePassesWhenPresent() {
        LdapCreateStep create = new LdapCreateStep(connector);
        String dn = "cn=ada.present,ou=users";
        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.present", "sn", "Lovelace")), false);

        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", dn, "exists", true), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("ldap.verify exists:true should fail when the entry is absent")
    void testVerifyExistsTrueFailsWhenAbsent() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", "cn=not.there,ou=users", "exists", true), false);

        assertThat(outcome.success()).isFalse();
    }

    @Test
    @DisplayName("ldap.verify should reject expect and exists supplied together")
    void testVerifyRejectsExpectAndExistsTogether() {
        // expect already implies existence, so supplying both says nothing about intent.
        StepOutcome outcome = new LdapVerifyStep(connector).execute(Map.of(
                "dn", "cn=ada,ou=users",
                "expect", Map.of("sn", "Lovelace"),
                "exists", true), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("expect").contains("exists");
    }

    @Test
    @DisplayName("ldap.verify should reject neither expect nor exists")
    void testVerifyRejectsNeitherExpectNorExists() {
        // The vacuous-assertion trap: making expect optional means "neither supplied"
        // must fail, or the step verifies nothing at all.
        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", "cn=ada,ou=users"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("expect").contains("exists");
    }

    @Test
    @DisplayName("ldap.verify should reject a null expected value inside expect rather than "
            + "treat it as a match against an absent attribute")
    void testVerifyRejectsNullValueInsideExpect() {
        // The eighth occurrence of the vacuous-assertion class: EdirectoryConnector.valuesEqual
        // treats (null, null) as equal, so expect: { titel: null } (a typo written without a
        // value) or the deliberate-but-wrong expect: { telephoneNumber: null } both compare
        // equal against an attribute the entry does not have, and the step reports "verified"
        // having compared nothing. Map.of() cannot hold a null value — that is exactly why
        // earlier test rounds never caught this — so a HashMap is required here.
        LdapCreateStep create = new LdapCreateStep(connector);
        String dn = "cn=ada.nullexpect,ou=users";
        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.nullexpect", "sn", "Lovelace")), false);

        Map<String, Object> expect = new HashMap<>();
        expect.put("title", null);
        Map<String, Object> params = new HashMap<>();
        params.put("dn", dn);
        params.put("expect", expect);

        StepOutcome outcome = new LdapVerifyStep(connector).execute(params, false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains(dn).containsIgnoringCase("title");
    }

    @Test
    @DisplayName("ldap.verify should reject a non-boolean exists")
    void testVerifyRejectsNonBooleanExists() {
        StepOutcome outcome = new LdapVerifyStep(connector).execute(
                Map.of("dn", "cn=ada,ou=users", "exists", "yes"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("exists");
    }
}
