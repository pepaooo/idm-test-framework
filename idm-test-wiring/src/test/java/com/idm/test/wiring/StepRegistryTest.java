package com.idm.test.wiring;

import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.model.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("StepRegistry")
class StepRegistryTest {

    @Test
    @DisplayName("Should register the 14 step types, and validate without any connector")
    void testForValidation() {
        var registry = StepRegistry.forValidation();
        assertThat(registry).hasSize(14).containsKeys("ldap.create", "db.verify", "rest.request", "http.verify",
                "roles.grant", "resources.revoke");

        Scenario scenario = ScenarioLoader.load(new ByteArrayInputStream("""
                name: Probe
                steps:
                  - ldap.verify:
                      dn: cn=x,ou=users
                      expect: { sn: X }
                """.getBytes(StandardCharsets.UTF_8)), "probe.yaml");
        assertThatCode(() -> EngineFactory.validator(registry).validate(scenario, "probe.yaml"))
                .doesNotThrowAnyException();
    }
}
