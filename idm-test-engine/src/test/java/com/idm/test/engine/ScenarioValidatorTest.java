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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScenarioValidator")
class ScenarioValidatorTest {

    private static final StepType ACTION = new StepType() {
        public String name() { return "ldap.create"; }
        public StepOutcome execute(Map<String, Object> params) { return StepOutcome.success("ok"); }
    };

    private static final StepType ASSERTION = new StepType() {
        public String name() { return "ldap.verify"; }
        public boolean retryable() { return true; }
        public StepOutcome execute(Map<String, Object> params) { return StepOutcome.success("ok"); }
    };

    private final ScenarioValidator validator = new ScenarioValidator(
            Map.of("ldap.create", ACTION, "ldap.verify", ASSERTION));

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
}
