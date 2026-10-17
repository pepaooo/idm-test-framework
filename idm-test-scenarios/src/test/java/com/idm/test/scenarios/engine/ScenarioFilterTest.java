package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code -Dscenario} narrows a run to the files an author is working on. A filter that
 * selects nothing must fail, never pass having run zero scenarios, so most of these tests
 * are about the refusals.
 * <p>
 * Tests {@link ScenarioSupport#select} directly with the pattern as an argument, so nothing
 * here sets a system property that could leak into other tests.
 */
@DisplayName("Scenario filter (-Dscenario)")
class ScenarioFilterTest {

    private static final String DIR = "scenarios";

    // Includes the deprovisioning-* and *-provisioning names that a substring match on
    // "provisioning" would wrongly select, so the group test below proves the glob does not.
    private static final List<String> FILES = List.of(
            "database-provisioning.yaml",
            "deprovisioning-user-delete.yaml",
            "deprovisioning-user-disable.yaml",
            "provisioning-minimal-user.yaml",
            "provisioning-user-to-ldap.yaml",
            "roles-assign-multiple.yaml",
            "user-provisioning.yaml");

    @Test
    @DisplayName("A filter that matches nothing fails, listing what is available")
    void testNoMatchFails() {
        assertThatThrownBy(() -> ScenarioSupport.select(FILES, "provisoning-user-to-ldap.yaml", DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Dscenario=provisoning-user-to-ldap.yaml")
                .hasMessageContaining(DIR)
                .hasMessageContaining("provisioning-user-to-ldap.yaml")
                .hasMessageContaining("roles-assign-multiple.yaml");
    }

    @Test
    @DisplayName("A filter against an empty directory fails and says there is nothing to choose from")
    void testNoMatchInEmptyDirectoryFails() {
        assertThatThrownBy(() -> ScenarioSupport.select(List.of(), "anything.yaml", DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Dscenario=anything.yaml")
                .hasMessageContaining("Available: none");
    }

    @Test
    @DisplayName("A blank filter fails rather than guessing")
    void testBlankFilterFails() {
        assertThatThrownBy(() -> ScenarioSupport.select(FILES, "  ", DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is set but empty");
    }
}
