package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private static LdapDirectories directories;

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
        directories = only(connector);
    }

    private static LdapDirectories only(EdirectoryConnector connector) {
        return LdapDirectories.ofConnectors(Map.of(LdapDirectories.DEFAULT, connector), null);
    }

    @AfterAll
    static void stopDirectory() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("ldap.create should report failure rather than throw on a schema violation")
    void testCreateFailure() {
        LdapCreateStep create = new LdapCreateStep(directories);

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
        new LdapCreateStep(directories).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.verify", "sn", "Lovelace", "givenName", "Ada")), false);

        StepOutcome outcome = new LdapVerifyStep(directories).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Ada", "sn", "Lovelace")), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("ldap.verify should fail and say what it saw when an attribute differs")
    void testVerifyMismatch() {
        String dn = "cn=grace.verify,ou=users";
        new LdapCreateStep(directories).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "grace.verify", "sn", "Hopper", "givenName", "Grace")), false);

        StepOutcome outcome = new LdapVerifyStep(directories).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Ada")), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains(dn);
        assertThat(outcome.message()).containsIgnoringCase("givenName");
    }

    @Test
    @DisplayName("ldap.verify should fail when the entry does not exist")
    void testVerifyMissingEntry() {
        StepOutcome outcome = new LdapVerifyStep(directories).execute(Map.of(
                "dn", "cn=absent,ou=users",
                "expect", Map.of("givenName", "Ada")), false);

        assertThat(outcome.success()).isFalse();
    }

    @Test
    @DisplayName("ldap.verify should refuse to verify userPassword, without quoting a value or reading the directory")
    void testVerifyRejectsUserPassword() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);

        for (Object expectation : List.of("S3cretValue!", Map.of("count", 0), Map.of("equals", "S3cretValue!"))) {
            StepOutcome outcome = new LdapVerifyStep(only(mockConnector)).execute(Map.of(
                    "dn", "cn=a,ou=users",
                    "expect", Map.of("USERPASSWORD", expectation)), false);

            assertThat(outcome.isFinal()).isTrue();
            assertThat(outcome.success()).isFalse();
            assertThat(outcome.message()).contains("'userPassword' cannot be verified")
                    .doesNotContain("S3cretValue!");
        }
        verify(mockConnector, never()).readAttributes(any());
    }

    @Test
    @DisplayName("Only the assertion step is retryable")
    void testRetryability() {
        assertThat(new LdapCreateStep(directories).retryable()).isFalse();
        assertThat(new LdapVerifyStep(directories).retryable()).isTrue();
        assertThat(new LdapCreateStep(directories).name()).isEqualTo("ldap.create");
        assertThat(new LdapVerifyStep(directories).name()).isEqualTo("ldap.verify");
    }

    @Test
    @DisplayName("ldap.verify should require only 'dn', leaving the expect-vs-exists choice "
            + "to rejectMalformedAssertion")
    void testVerifyRequiresOnlyDn() {
        LdapVerifyStep verify = new LdapVerifyStep(directories);

        // 'expect' is no longer unconditionally required: exists: is now a valid alternative
        // assertion. A typo like "expct" (with no exists: present either) is still caught,
        // not by requiredParams() anymore, but by rejectMalformedAssertion's "neither
        // supplied" branch, exercised in testVerifyRejectsNeitherExpectNorExists.
        assertThat(verify.requiredParams()).containsExactly("dn");
        assertThat(verify.acceptedParams())
                .containsExactlyInAnyOrder("dn", "expect", "exists", "wait", "directory");
    }

    @Test
    @DisplayName("ldap.create should require 'dn' and also accept 'attributes'")
    void testCreateRequiredAndAcceptedParams() {
        LdapCreateStep create = new LdapCreateStep(directories);

        assertThat(create.requiredParams()).containsExactly("dn");
        assertThat(create.acceptedParams()).containsExactlyInAnyOrder("dn", "attributes", "directory");
    }

    @Test
    @DisplayName("ldap.create should return the DN it created, for as: to capture")
    void testCreateReturnsDn() {
        String dn = "cn=ada.value,ou=users";

        StepOutcome outcome = new LdapCreateStep(directories).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.value", "sn", "Lovelace")), false);

        assertThat(outcome.value()).isEqualTo(dn);
    }

    @Test
    @DisplayName("ldap.verify should return the entry's attributes when a capture is "
            + "requested, for as: to capture")
    void testVerifyReturnsAttributes() {
        String dn = "cn=grace.value,ou=users";
        new LdapCreateStep(directories).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "grace.value", "sn", "Hopper", "givenName", "Grace")), false);

        StepOutcome outcome = new LdapVerifyStep(directories).execute(Map.of(
                "dn", dn,
                "expect", Map.of("givenName", "Grace")), true);

        assertThat(outcome.value()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) outcome.value();
        assertThat(attributes).containsEntry("sn", "Hopper").containsEntry("givenName", "Grace");
    }

    @Test
    @DisplayName("ldap.verify reads the entry once per attempt and compares that read")
    void testVerifyReadsOnce() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        when(mockConnector.readAttributes(any()))
                .thenReturn(OperationResult.success(Map.of("givenName", "Ada")));

        StepOutcome outcome = new LdapVerifyStep(only(mockConnector)).execute(Map.of(
                "dn", "cn=x,ou=users", "expect", Map.of("givenName", "Ada")), false);

        assertThat(outcome.success()).as(outcome.message()).isTrue();
        assertThat(outcome.value()).isNull();
        verify(mockConnector, times(1)).readAttributes(any());
        verify(mockConnector, never()).verifyAttributes(any(), any());
    }

    @Test
    @DisplayName("ldap.verify fails with the read's message, and no capture or evidence, when the read fails")
    void testVerifyFailsWhenReadFails() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        when(mockConnector.readAttributes(any()))
                .thenReturn(OperationResult.<Map<String, Object>>failure("connection dropped"));

        StepOutcome outcome = new LdapVerifyStep(only(mockConnector)).execute(Map.of(
                "dn", "cn=x,ou=users", "expect", Map.of("givenName", "Ada")), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.value()).isNull();
        assertThat(outcome.evidence()).isNull();
        assertThat(outcome.message()).contains("cn=x,ou=users").contains("connection dropped");
    }

    @Test
    @DisplayName("LDAP steps record what they wrote, read or deleted, with userPassword masked")
    void testLdapEvidence() {
        String dn = "cn=ada.evidence,ou=users";
        StepOutcome created = new LdapCreateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("cn", "ada.evidence", "sn", "Lovelace", "userPassword", "S3cret!")), false);
        LdapEvidence written = (LdapEvidence) created.evidence();
        assertThat(written.operation()).isEqualTo("create");
        assertThat(written.attributes()).containsEntry("userPassword", "***").containsEntry("sn", "Lovelace");

        StepOutcome updated = new LdapUpdateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("title", "Engineer")), false);
        assertThat(((LdapEvidence) updated.evidence()).operation()).isEqualTo("update");

        StepOutcome mismatch = new LdapVerifyStep(directories).execute(Map.of("dn", dn,
                "expect", Map.of("title", "Manager")), false);
        assertThat(mismatch.success()).isFalse();
        LdapEvidence read = (LdapEvidence) mismatch.evidence();
        assertThat(read.operation()).isEqualTo("read");
        assertThat(read.attributes()).containsEntry("title", "Engineer");

        StepOutcome exists = new LdapVerifyStep(directories).execute(Map.of("dn", dn, "exists", true), false);
        assertThat(((LdapEvidence) exists.evidence()).present()).isTrue();

        StepOutcome deleted = new LdapDeleteStep(directories).execute(Map.of("dn", dn), false);
        assertThat(((LdapEvidence) deleted.evidence()).operation()).isEqualTo("delete");

        StepOutcome missing = new LdapVerifyStep(directories).execute(Map.of("dn", dn,
                "expect", Map.of("title", "Engineer")), false);
        assertThat(((LdapEvidence) missing.evidence()).present()).isFalse();

        StepOutcome deleteMissing = new LdapDeleteStep(directories).execute(Map.of("dn", dn), false);
        assertThat(((LdapEvidence) deleteMissing.evidence()).present()).isFalse();
    }

    @Test
    @DisplayName("Final failures before any I/O record no evidence")
    void testNoEvidenceBeforeIo() {
        assertThat(new LdapVerifyStep(directories).execute(Map.of("dn", "cn=x,ou=users"), false).evidence()).isNull();
        assertThat(new LdapCreateStep(directories).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("cn", "x", "sn", "X", "ou", List.of())), false).evidence()).isNull();
    }

    @Test
    @DisplayName("A failed step should carry no captured value")
    void testFailureHasNoValue() {
        StepOutcome outcome = new LdapVerifyStep(directories).execute(Map.of(
                "dn", "cn=absent.value,ou=users",
                "expect", Map.of("givenName", "Ada")), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.value()).isNull();
    }

    // ------------------------------------------------------------------
    // ldap.update (ITF-8 Task 2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ldap.update should report failure rather than throw for a missing entry")
    void testUpdateMissingEntryFails() {
        LdapUpdateStep update = new LdapUpdateStep(directories);

        StepOutcome outcome = update.execute(
                Map.of("dn", "cn=nobody.here,ou=users", "attributes", Map.of("title", "X")), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("ldap.update failed for")
                .contains("cn=nobody.here,ou=users");
    }

    @Test
    @DisplayName("ldap.update should bind the dn when a capture was requested")
    void testUpdateCaptureIsTheDn() {
        LdapCreateStep create = new LdapCreateStep(directories);
        LdapUpdateStep update = new LdapUpdateStep(directories);
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
    @DisplayName("ldap.delete should remove the entry")
    void testDeleteReturnsTheDn() {
        LdapCreateStep create = new LdapCreateStep(directories);
        LdapDeleteStep delete = new LdapDeleteStep(directories);
        String dn = "cn=ada.delete,ou=users";

        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.delete", "sn", "Lovelace")), false);

        StepOutcome outcome = delete.execute(Map.of("dn", dn), false);

        assertThat(outcome.success()).isTrue();
        assertThat(connector.read(dn).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("ldap.delete should fail for an entry that was never there")
    void testDeleteMissingEntryFails() {
        // LdapTemplate.unbind succeeds silently on a DN that does not exist, so without an
        // explicit existence check this step would report "deleted" having deleted nothing.
        LdapDeleteStep delete = new LdapDeleteStep(directories);

        StepOutcome outcome = delete.execute(Map.of("dn", "cn=never.existed,ou=users"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("cn=never.existed,ou=users");
    }

    @Test
    @DisplayName("ldap.delete should bind the dn when a capture was requested")
    void testDeleteCaptureIsTheDn() {
        LdapCreateStep create = new LdapCreateStep(directories);
        LdapDeleteStep delete = new LdapDeleteStep(directories);
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
        StepOutcome outcome = new LdapVerifyStep(directories).execute(Map.of(
                "dn", "cn=whoever,ou=users",
                "expect", "Engineer"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("expect");
    }

    @Test
    @DisplayName("ldap.verify should reject an expect block that asserts nothing")
    void testVerifyRejectsEmptyExpect() {
        LdapCreateStep create = new LdapCreateStep(directories);
        String dn = "cn=ada.vacuous,ou=users";
        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.vacuous", "sn", "Lovelace")), false);

        // 'expect' being required catches a misspelled expct:, but a present-but-empty
        // expect: {} passed that check and reported "verified" having compared nothing.
        StepOutcome outcome = new LdapVerifyStep(directories).execute(
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
        StepOutcome outcome = new LdapVerifyStep(directories).execute(
                Map.of("dn", "cn=never.created,ou=users", "exists", false), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("ldap.verify exists:false should fail while the entry is still there")
    void testVerifyExistsFalseFailsWhenPresent() {
        LdapCreateStep create = new LdapCreateStep(directories);
        String dn = "cn=ada.stillhere,ou=users";
        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.stillhere", "sn", "Lovelace")), false);

        StepOutcome outcome = new LdapVerifyStep(directories).execute(
                Map.of("dn", dn, "exists", false), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains(dn);
    }

    @Test
    @DisplayName("ldap.verify exists:true should pass without asserting attributes")
    void testVerifyExistsTruePassesWhenPresent() {
        LdapCreateStep create = new LdapCreateStep(directories);
        String dn = "cn=ada.present,ou=users";
        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.present", "sn", "Lovelace")), false);

        StepOutcome outcome = new LdapVerifyStep(directories).execute(
                Map.of("dn", dn, "exists", true), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("ldap.verify exists:true should fail when the entry is absent")
    void testVerifyExistsTrueFailsWhenAbsent() {
        StepOutcome outcome = new LdapVerifyStep(directories).execute(
                Map.of("dn", "cn=not.there,ou=users", "exists", true), false);

        assertThat(outcome.success()).isFalse();
    }

    @Test
    @DisplayName("ldap.verify should reject expect and exists supplied together")
    void testVerifyRejectsExpectAndExistsTogether() {
        // expect already implies existence, so supplying both says nothing about intent.
        StepOutcome outcome = new LdapVerifyStep(directories).execute(Map.of(
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
        StepOutcome outcome = new LdapVerifyStep(directories).execute(
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
        LdapCreateStep create = new LdapCreateStep(directories);
        String dn = "cn=ada.nullexpect,ou=users";
        create.execute(Map.of("dn", dn, "attributes",
                Map.of("cn", "ada.nullexpect", "sn", "Lovelace")), false);

        Map<String, Object> expect = new HashMap<>();
        expect.put("title", null);
        Map<String, Object> params = new HashMap<>();
        params.put("dn", dn);
        params.put("expect", expect);

        StepOutcome outcome = new LdapVerifyStep(directories).execute(params, false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains(dn).containsIgnoringCase("title");
    }

    @Test
    @DisplayName("ldap.verify should reject a non-boolean exists")
    void testVerifyRejectsNonBooleanExists() {
        StepOutcome outcome = new LdapVerifyStep(directories).execute(
                Map.of("dn", "cn=ada,ou=users", "exists", "yes"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("exists");
    }

    @Test
    @DisplayName("ldap.create registers nothing: the entry is still there after the step")
    void testCreateLeavesTheEntry() {
        LdapCreateStep create = new LdapCreateStep(directories);
        String dn = "cn=ada.stays,ou=users";

        StepOutcome outcome = create.execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.stays", "sn", "Lovelace", "givenName", "Ada")), false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(dn);
        assertThat(connector.read(dn).isSuccess()).isTrue();

        connector.delete(dn);   // tidy the shared directory; nothing else will
    }

    @Test
    @DisplayName("A malformed ldap.verify is a final failure; a missing entry is not")
    void testLdapVerifyFinalFailures() {
        LdapVerifyStep verify = new LdapVerifyStep(directories);

        assertThat(verify.execute(Map.of("dn", "cn=x,ou=users", "expect", Map.of("cn", "x"), "exists", true), false)
                .isFinal()).isTrue();
        assertThat(verify.execute(Map.of("dn", "cn=x,ou=users"), false).isFinal()).isTrue();
        assertThat(verify.execute(Map.of("dn", "cn=x,ou=users", "exists", "yes"), false).isFinal()).isTrue();
        assertThat(verify.execute(Map.of("dn", "cn=x,ou=users", "expect", Map.of()), false).isFinal()).isTrue();

        StepOutcome missing = verify.execute(Map.of("dn", "cn=nobody-itf26,ou=users", "exists", true), false);
        assertThat(missing.success()).isFalse();
        assertThat(missing.isFinal()).isFalse();
    }

    @Test
    @DisplayName("directory: selects that directory's connector, and messages name it when several are configured")
    void testDirectorySelectsConnector() {
        EdirectoryConnector a = mock(EdirectoryConnector.class);
        EdirectoryConnector b = mock(EdirectoryConnector.class);
        when(b.create(any())).thenReturn(OperationResult.success(new LdapUser()));
        LdapDirectories two = LdapDirectories.ofConnectors(Map.of("a", a, "b", b), "a");

        StepOutcome outcome = new LdapCreateStep(two).execute(Map.of(
                "directory", "b", "dn", "cn=x,ou=users", "attributes", Map.of("cn", "x", "sn", "X")), false);

        assertThat(outcome.success()).as(outcome.message()).isTrue();
        assertThat(outcome.message()).isEqualTo("created cn=x,ou=users in directory 'b'");
        verify(b).create(any());
        verify(a, never()).create(any());
    }

    @Test
    @DisplayName("Without directory:, the default directory is used")
    void testDefaultDirectoryUsed() {
        EdirectoryConnector a = mock(EdirectoryConnector.class);
        EdirectoryConnector b = mock(EdirectoryConnector.class);
        when(b.exists(any())).thenReturn(OperationResult.success(true));
        when(b.delete(any())).thenReturn(OperationResult.success(null));
        LdapDirectories two = LdapDirectories.ofConnectors(Map.of("a", a, "b", b), "b");

        StepOutcome outcome = new LdapDeleteStep(two).execute(Map.of("dn", "cn=x,ou=users"), false);

        assertThat(outcome.success()).as(outcome.message()).isTrue();
        assertThat(outcome.message()).isEqualTo("deleted cn=x,ou=users in directory 'b'");
        verify(a, never()).exists(any());
    }

    @Test
    @DisplayName("An unknown directory is a final failure, listing the configured ones")
    void testUnknownDirectoryIsFinal() {
        LdapDirectories two = LdapDirectories.ofConnectors(
                Map.of("ad", mock(EdirectoryConnector.class), "default", mock(EdirectoryConnector.class)), null);

        StepOutcome outcome = new LdapVerifyStep(two).execute(Map.of(
                "directory", "adx", "dn", "cn=x,ou=users", "exists", true), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.isFinal()).isTrue();
        assertThat(outcome.message()).isEqualTo("ldap.verify: unknown directory 'adx'. Configured directories: [ad, default]");
    }

    @Test
    @DisplayName("No directory: and no default is a final failure")
    void testNoDefaultIsFinal() {
        LdapDirectories two = LdapDirectories.ofConnectors(
                Map.of("ad", mock(EdirectoryConnector.class), "edir", mock(EdirectoryConnector.class)), null);

        StepOutcome outcome = new LdapUpdateStep(two).execute(Map.of(
                "dn", "cn=x,ou=users", "attributes", Map.of("title", "T")), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.isFinal()).isTrue();
        assertThat(outcome.message()).isEqualTo("ldap.update: name a directory; there is no default."
                + " Configured directories: [ad, edir]");
    }

    @Test
    @DisplayName("Every ldap.* step accepts directory:")
    void testDirectoryAccepted() {
        for (StepType step : List.of(new LdapCreateStep(directories), new LdapVerifyStep(directories),
                new LdapUpdateStep(directories), new LdapDeleteStep(directories))) {
            assertThat(step.acceptedParams()).as(step.name()).contains("directory");
        }
    }

    // ------------------------------------------------------------------
    // ITF-38: multi-valued attributes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ldap.create writes lists as several values, built-in attributes included, and ldap.verify matches them")
    void testCreateAndVerifyLists() {
        String dn = "cn=ada.multi,ou=users";
        StepOutcome created = new LdapCreateStep(directories).execute(Map.of(
                "dn", dn,
                "attributes", Map.of("cn", "ada.multi", "sn", "Lovelace",
                        "ou", List.of("Engineering", "Security"),
                        "seeAlso", List.of("cn=a,ou=groups,dc=test,dc=com", "cn=b,ou=groups,dc=test,dc=com"))),
                false);
        assertThat(created.success()).as(created.message()).isTrue();

        StepOutcome verified = new LdapVerifyStep(directories).execute(Map.of(
                "dn", dn,
                "expect", Map.of(
                        "ou", List.of("Security", "Engineering"),
                        "seeAlso", Map.of("contains", "CN=A,OU=Groups,DC=test,DC=com",
                                "excludes", "cn=c,ou=groups,dc=test,dc=com"))), true);

        assertThat(verified.success()).as(verified.message()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> captured = (Map<String, Object>) verified.value();
        assertThat(captured.get("dn")).isEqualTo(dn);
        assertThat((List<?>) captured.get("ou")).map(String::valueOf)
                .containsExactlyInAnyOrder("Engineering", "Security");
        assertThat(captured.get("sn")).isEqualTo("Lovelace");

        connector.delete(dn);
    }

    @Test
    @DisplayName("ldap.update writes a list as several values and an empty list removes the attribute")
    void testUpdateLists() {
        String dn = "cn=ada.multiupdate,ou=users";
        new LdapCreateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("cn", "ada.multiupdate", "sn", "Lovelace")), false);

        assertThat(new LdapUpdateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("ou", List.of("A", "B"))), false).success()).isTrue();
        assertThat(new LdapVerifyStep(directories).execute(Map.of("dn", dn,
                "expect", Map.of("ou", List.of("B", "A"))), false).success()).isTrue();

        assertThat(new LdapUpdateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("ou", List.of())), false).success()).isTrue();
        assertThat(new LdapVerifyStep(directories).execute(Map.of("dn", dn,
                "expect", Map.of("ou", Map.of("excludes", List.of("A", "B")))), false).success()).isTrue();
        assertThat(connector.readAttributes(dn).getData().orElseThrow()).doesNotContainKey("ou");

        connector.delete(dn);
    }

    @Test
    @DisplayName("ldap.create rejects an empty list, and both write steps reject null, map or list items, without calling the directory")
    void testWriteStepsRejectMalformedLists() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        LdapDirectories mocked = only(mockConnector);

        StepOutcome empty = new LdapCreateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("cn", "x", "sn", "X", "ou", List.of())), false);
        assertThat(empty.success()).isFalse();
        assertThat(empty.message()).isEqualTo(
                "ldap.create for cn=x,ou=users: attribute 'ou' has an empty list; there is nothing to write");

        StepOutcome nested = new LdapCreateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("cn", "x", "sn", "X", "ou", List.of("A", List.of("B")))), false);
        assertThat(nested.success()).isFalse();
        assertThat(nested.message()).isEqualTo("ldap.create for cn=x,ou=users: attribute 'ou' "
                + "has a null, map or list value inside its list; give plain values");

        StepOutcome updateNested = new LdapUpdateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("ou", List.of(Map.of("a", "b")))), false);
        assertThat(updateNested.success()).isFalse();
        assertThat(updateNested.message()).isEqualTo("ldap.update for cn=x,ou=users: attribute 'ou' "
                + "has a null, map or list value inside its list; give plain values");

        verify(mockConnector, never()).create(any());
        verify(mockConnector, never()).update(any(), any());
    }

    @Test
    @DisplayName("A malformed matcher in ldap.verify is a final failure before the directory is called")
    void testVerifyRejectsMalformedMatchers() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        LdapVerifyStep verify = new LdapVerifyStep(only(mockConnector));

        Map<Object, String> cases = new java.util.LinkedHashMap<>();
        cases.put(List.of(), "is an empty list, which a misspelled attribute name would also satisfy; "
                + "to assert values are absent use { excludes: [...] }");
        cases.put(Map.of("contain", "a"), "has unknown matcher keys [contain]; use equals, contains, excludes, count, matches or ignoreCase");
        cases.put(Map.of(), "is an empty map; use equals, contains, excludes, count, matches or ignoreCase");
        cases.put(Map.of("excludes", List.of()), "has an empty 'excludes'");
        cases.put(Map.of("contains", List.of("a", List.of("b"))),
                "has a null, map or list value in 'contains'; give plain values");
        cases.put(List.of("a", Map.of()), "has a null, map or list value inside its list; give plain values");
        cases.put(Map.of("contains", "cn=a,o=x", "excludes", "CN=A,O=X"),
                "lists [cn=a,o=x] in both 'contains' and 'excludes'");

        cases.forEach((expected, problem) -> {
            StepOutcome outcome = verify.execute(Map.of("dn", "cn=x,ou=users",
                    "expect", Map.of("groupMembership", expected)), false);
            assertThat(outcome.success()).as(problem).isFalse();
            assertThat(outcome.isFinal()).as(problem).isTrue();
            assertThat(outcome.message()).isEqualTo(
                    "ldap.verify for cn=x,ou=users: attribute 'groupMembership' " + problem);
        });

        verify(mockConnector, never()).readAttributes(any());
    }

    @Test
    @DisplayName("ldap.update adds and removes single values, records them as written, and reports strict failures")
    void testUpdateAddRemove() {
        String dn = "cn=ada.addremove,ou=users";
        String a = "cn=a,ou=groups,dc=test,dc=com";
        String b = "cn=b,ou=groups,dc=test,dc=com";
        new LdapCreateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("cn", "ada.addremove", "sn", "Lovelace", "seeAlso", List.of(a))), false);

        StepOutcome updated = new LdapUpdateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("seeAlso", Map.of("add", b, "remove", a))), false);
        assertThat(updated.success()).as(updated.message()).isTrue();
        assertThat(String.valueOf(((LdapEvidence) updated.evidence()).attributes().get("seeAlso"))).contains("add");
        assertThat(new LdapVerifyStep(directories).execute(Map.of("dn", dn,
                "expect", Map.of("seeAlso", Map.of("equals", b, "count", 1))), false).success()).isTrue();

        StepOutcome refused = new LdapUpdateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("seeAlso", Map.of("add", b))), false);
        assertThat(refused.success()).isFalse();
        assertThat(refused.message())
                .isEqualTo("ldap.update failed for " + dn + ": cannot add [" + b + "] to seeAlso: the entry already has it");

        connector.delete(dn);
    }

    @Test
    @DisplayName("Malformed add/remove fails before the directory is called; ldap.create refuses add/remove")
    void testAddRemoveRejectedBeforeIo() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        LdapDirectories mocked = only(mockConnector);

        StepOutcome empty = new LdapUpdateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("seeAlso", Map.of("add", List.of()))), false);
        assertThat(empty.message()).isEqualTo("ldap.update for cn=x,ou=users: attribute 'seeAlso' has an empty 'add'");

        StepOutcome unknown = new LdapUpdateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("seeAlso", Map.of("append", "a"))), false);
        assertThat(unknown.message())
                .isEqualTo("ldap.update for cn=x,ou=users: attribute 'seeAlso' has unknown keys [append]; use add and/or remove");

        StepOutcome create = new LdapCreateStep(mocked).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("cn", "x", "sn", "X", "seeAlso", Map.of("add", "a"))), false);
        assertThat(create.message())
                .isEqualTo("ldap.create for cn=x,ou=users: attribute 'seeAlso' uses add/remove, which only apply to ldap.update");

        verify(mockConnector, never()).update(any(), any());
        verify(mockConnector, never()).create(any());
    }

    @Test
    @DisplayName("ldap.update's overlap message never quotes a secret value")
    void testSecretOverlapNotQuoted() {
        StepOutcome outcome = new LdapUpdateStep(only(mock(EdirectoryConnector.class))).execute(Map.of(
                "dn", "cn=x,ou=users",
                "attributes", Map.of("userPassword", Map.of("add", "S3cret!", "remove", "S3cret!"))), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).doesNotContain("S3cret!")
                .isEqualTo("ldap.update for cn=x,ou=users: attribute 'userPassword' lists the same value in both 'add' and 'remove'");
    }

    @Test
    @DisplayName("ldap.verify renders its failure in Spanish with the external text unchanged")
    void testVerifyFailureRendersInSpanish() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        when(mockConnector.readAttributes(any()))
                .thenReturn(OperationResult.<Map<String, Object>>failure("connection dropped"));

        StepOutcome outcome = new LdapVerifyStep(only(mockConnector)).execute(Map.of(
                "dn", "cn=x,ou=users", "expect", Map.of("givenName", "Ada")), false);

        assertThat(Messages.render(outcome.detail(), Locale.of("es")))
                .isEqualTo("ldap.verify falló para cn=x,ou=users: connection dropped");
        assertThat(Messages.render(outcome.detail(), Locale.ENGLISH)).isEqualTo(outcome.message());
    }

    @Test
    @DisplayName("ldap steps name the directory in Spanish when more than one is configured")
    void testDirectorySuffixRendersInSpanish() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        when(mockConnector.exists(any())).thenReturn(OperationResult.success(false));
        LdapDirectories two = LdapDirectories.ofConnectors(
                Map.of("a", mockConnector, "b", mock(EdirectoryConnector.class)), "a");

        StepOutcome outcome = new LdapDeleteStep(two).execute(Map.of("dn", "cn=x"), false);

        assertThat(Messages.render(outcome.detail(), Locale.of("es")))
                .isEqualTo("ldap.delete no encontró ninguna entrada en cn=x en el directorio 'a' para eliminar");
        assertThat(outcome.message())
                .isEqualTo("ldap.delete found no entry at cn=x in directory 'a' to delete");
    }

    private static String spanish(StepOutcome outcome) {
        return Messages.render(outcome.detail(), Locale.of("es"));
    }

    @Test
    @DisplayName("ldap.verify renders a value mismatch in Spanish, from the matcher's structured problem")
    void testVerifyMismatchRendersInSpanish() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        when(mockConnector.readAttributes(any()))
                .thenReturn(OperationResult.success(Map.of("givenName", "Ada")));

        StepOutcome outcome = new LdapVerifyStep(only(mockConnector)).execute(Map.of(
                "dn", "cn=x,ou=users", "expect", Map.of("givenName", "Grace")), false);

        assertThat(spanish(outcome))
                .isEqualTo("ldap.verify falló para cn=x,ou=users: Atributo 'givenName': esperado=Grace, real=Ada");
        assertThat(outcome.message())
                .isEqualTo("ldap.verify failed for cn=x,ou=users: Attribute 'givenName': expected=Grace, actual=Ada");
    }

    @Test
    @DisplayName("ldap.verify renders a malformed matcher in Spanish")
    void testVerifyShapeRendersInSpanish() {
        StepOutcome outcome = new LdapVerifyStep(only(mock(EdirectoryConnector.class))).execute(Map.of(
                "dn", "cn=x,ou=users", "expect", Map.of("title", Map.of())), false);

        assertThat(spanish(outcome)).isEqualTo("ldap.verify para cn=x,ou=users: el atributo 'title' es un mapa vacío; "
                + "use equals, contains, excludes, count, matches o ignoreCase");
    }

    @Test
    @DisplayName("ldap.create renders a directory failure in Spanish")
    void testCreateFailureRendersInSpanish() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        when(mockConnector.create(any())).thenReturn(OperationResult.<LdapUser>failure("boom"));

        StepOutcome outcome = new LdapCreateStep(only(mockConnector)).execute(Map.of(
                "dn", "cn=x,ou=users", "attributes", Map.of("sn", "X")), false);

        assertThat(spanish(outcome)).isEqualTo("ldap.create falló para cn=x,ou=users: boom");
    }

    @Test
    @DisplayName("ldap.update renders its failures in Spanish")
    void testUpdateRendersInSpanish() {
        LdapUpdateStep update = new LdapUpdateStep(only(mock(EdirectoryConnector.class)));

        assertThat(spanish(update.execute(Map.of("dn", "cn=x,ou=users", "attributes", Map.of()), false)))
                .isEqualTo("ldap.update para cn=x,ou=users no tiene atributos que cambiar");
    }

    @Test
    @DisplayName("LDAP list checks render in Spanish, including a malformed add/remove change")
    void testListProblemsRenderInSpanish() {
        StepOutcome empty = new LdapCreateStep(only(mock(EdirectoryConnector.class))).execute(Map.of(
                "dn", "cn=x,ou=users", "attributes", Map.of("ou", List.of())), false);
        StepOutcome change = new LdapUpdateStep(only(mock(EdirectoryConnector.class))).execute(Map.of(
                "dn", "cn=x,ou=users", "attributes", Map.of("seeAlso", Map.of())), false);

        assertThat(spanish(empty)).isEqualTo(
                "ldap.create para cn=x,ou=users: el atributo 'ou' tiene una lista vacía; no hay nada que escribir");
        assertThat(spanish(change)).isEqualTo(
                "ldap.update para cn=x,ou=users: el atributo 'seeAlso' es un mapa vacío; use add y/o remove");
    }

    @Test
    @DisplayName("The directory connector's own frames render in Spanish, the server text unchanged")
    void testConnectorFramesRenderInSpanish() {
        String dn = "cn=ada.frames,ou=users";
        String b = "cn=b,ou=groups,dc=test,dc=com";
        Map<String, Object> attributes = Map.of("cn", "ada.frames", "sn", "Lovelace", "seeAlso", List.of(b));
        new LdapCreateStep(directories).execute(Map.of("dn", dn, "attributes", attributes), false);

        StepOutcome again = new LdapCreateStep(directories).execute(Map.of("dn", dn, "attributes", attributes), false);
        assertThat(again.message()).startsWith("ldap.create failed for " + dn + ": Failed to create user: ");
        String server = again.message().substring(("ldap.create failed for " + dn + ": Failed to create user: ").length());
        assertThat(spanish(again)).isEqualTo("ldap.create falló para " + dn + ": No se pudo crear el usuario: " + server);

        StepOutcome refused = new LdapUpdateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("seeAlso", Map.of("add", b))), false);
        assertThat(refused.message())
                .isEqualTo("ldap.update failed for " + dn + ": cannot add [" + b + "] to seeAlso: the entry already has it");
        assertThat(spanish(refused))
                .isEqualTo("ldap.update falló para " + dn + ": no se puede agregar [" + b + "] a seeAlso: la entrada ya lo tiene");

        connector.delete(dn);
        StepOutcome absent = new LdapVerifyStep(directories).execute(Map.of("dn", dn,
                "expect", Map.of("sn", "Lovelace")), false);
        assertThat(absent.message()).isEqualTo("ldap.verify failed for " + dn + ": Entry not found: " + dn);
        assertThat(spanish(absent)).isEqualTo("ldap.verify falló para " + dn + ": No se encontró la entrada: " + dn);
    }
}
