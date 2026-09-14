package com.idm.test.engine;

import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScenarioValidator")
class ScenarioValidatorTest {

    // Mirrors LdapCreateStep's real contract: requires 'dn', also accepts 'attributes'.
    private static final StepType ACTION = new StepType() {
        public String name() { return "ldap.create"; }
        public Set<String> requiredParams() { return Set.of("dn"); }
        public Set<String> acceptedParams() { return Set.of("dn", "attributes"); }
        public StepOutcome execute(Map<String, Object> params) { return StepOutcome.success("ok"); }
    };

    // Mirrors LdapVerifyStep's real contract: requires 'dn' and 'expect'; acceptedParams()
    // is left to the interface default, which adds 'wait' because retryable() is true.
    private static final StepType ASSERTION = new StepType() {
        public String name() { return "ldap.verify"; }
        public boolean retryable() { return true; }
        public Set<String> requiredParams() { return Set.of("dn", "expect"); }
        public StepOutcome execute(Map<String, Object> params) { return StepOutcome.success("ok"); }
    };

    private final ScenarioValidator validator = new ScenarioValidator(
            Map.of("ldap.create", ACTION, "ldap.verify", ASSERTION),
            Set.of("random-user"));

    private static Scenario scenario(StepSpec... steps) {
        return new Scenario("Test", null,
                Map.of("user", new FixtureSpec("random-user", Map.of())),
                List.of(steps));
    }

    @Test
    @DisplayName("Should accept a scenario whose steps and references are all known")
    void testAcceptsValidScenario() {
        Scenario valid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=${user.userId},ou=users")),
                new StepSpec("ldap.verify", Map.of("dn", "cn=${user.userId},ou=users",
                        "expect", Map.of("givenName", "${user.firstName}"),
                        "wait", Boolean.TRUE)));

        assertThatCode(() -> validator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should accept a step supplying exactly its accepted parameters")
    void testAcceptsStepWithExactlyAcceptedParameters() {
        Scenario valid = scenario(
                new StepSpec("ldap.create", Map.of(
                        "dn", "cn=${user.userId},ou=users",
                        "attributes", Map.of("sn", "Lovelace"))),
                new StepSpec("ldap.verify", Map.of(
                        "dn", "cn=${user.userId},ou=users",
                        "expect", Map.of("sn", "Lovelace"),
                        "wait", Boolean.TRUE)));

        assertThatCode(() -> validator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should name the unknown step type and list the available ones")
    void testRejectsUnknownStepType() {
        Scenario invalid = scenario(new StepSpec("ldap.summon", Map.of("dn", "cn=x")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.summon")
                .hasMessageContaining("ldap.create");
    }

    @Test
    @DisplayName("Should reject a step with no dn")
    void testRejectsMissingDn() {
        Scenario invalid = scenario(new StepSpec("ldap.create", Map.of()));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("dn");
    }

    @Test
    @DisplayName("Should reject a step missing a required parameter other than 'dn', naming it")
    void testRejectsMissingRequiredParameter() {
        // This is the direct fix for the vacuous-assertion bug: a consultant who omits
        // (or mistypes) 'expect' must be rejected before the step ever runs, rather than
        // silently comparing against an empty map and reporting success.
        Scenario invalid = scenario(
                new StepSpec("ldap.verify", Map.of("dn", "cn=${user.userId},ou=users")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.verify")
                .hasMessageContaining("expect");
    }

    @Test
    @DisplayName("Should reject an unknown parameter, naming it and listing what's accepted")
    void testRejectsUnknownParameter() {
        Scenario invalid = scenario(
                new StepSpec("ldap.verify", Map.of(
                        "dn", "cn=${user.userId},ou=users",
                        "expect", Map.of("givenName", "Ada"),
                        // the typo from the bug report: 'expct' instead of 'expect'
                        "expct", Map.of("givenName", "Ada"))));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.verify")
                .hasMessageContaining("expct")
                .hasMessageContaining("dn")
                .hasMessageContaining("expect")
                .hasMessageContaining("wait");
    }

    @Test
    @DisplayName("Should reject a reference to a fixture that does not exist")
    void testRejectsUnknownReference() {
        Scenario invalid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=${absent.userId},ou=users")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("Should reject wait on an action, since only assertions retry")
    void testRejectsWaitOnAction() {
        Scenario invalid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=x,ou=users", "wait", Boolean.TRUE)));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("wait")
                .hasMessageContaining("ldap.create");
    }

    @Test
    @DisplayName("Should reject a scenario with no steps")
    void testRejectsEmptyScenario() {
        assertThatThrownBy(() -> validator.validate(scenario(), "empty.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one step");
    }

    @Test
    @DisplayName("Should reject a wait with a malformed duration string, naming the file and value")
    void testRejectsWaitWithBadDurationString() {
        // The exact late failure ScenarioValidator exists to prevent: without this check,
        // '30x' would only be discovered once WaitSpec.parse runs inside the engine, after
        // every earlier step (and this scenario's own earlier steps) has already executed.
        Scenario invalid = scenario(
                new StepSpec("ldap.verify", Map.of(
                        "dn", "cn=${user.userId},ou=users",
                        "expect", Map.of("givenName", "Ada"),
                        "wait", Map.of("timeout", "30x"))));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.verify")
                .hasMessageContaining("30x");
    }

    @Test
    @DisplayName("Should reject a wait of the wrong shape, naming the file and value")
    void testRejectsWaitOfWrongType() {
        // 'wait' must be true or a map with timeout/poll; a bare string is the wrong shape
        // entirely, distinct from the bad-duration-string case above.
        Scenario invalid = scenario(
                new StepSpec("ldap.verify", Map.of(
                        "dn", "cn=${user.userId},ou=users",
                        "expect", Map.of("givenName", "Ada"),
                        "wait", "30s")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.verify")
                .hasMessageContaining("30s");
    }

    @Test
    @DisplayName("Should reject a fixture with an unknown generator, naming it and listing what's available")
    void testRejectsUnknownGenerator() {
        Scenario invalid = new Scenario("Test", null,
                Map.of("user", new FixtureSpec("randmo-user", Map.of())),
                List.of(new StepSpec("ldap.create", Map.of("dn", "cn=${user.userId},ou=users"))));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("randmo-user")
                .hasMessageContaining("random-user");
    }
}
