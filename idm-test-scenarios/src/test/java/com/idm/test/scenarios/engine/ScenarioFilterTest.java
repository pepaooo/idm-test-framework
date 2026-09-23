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
 * Tests {@link YamlScenarioTest#select} directly with the pattern as an argument, so nothing
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
    @DisplayName("No filter returns every file unchanged")
    void testNoFilterReturnsEverything() {
        assertThat(YamlScenarioTest.select(FILES, null, DIR)).containsExactlyElementsOf(FILES);
    }

    @Test
    @DisplayName("An exact file name selects that one file")
    void testExactNameSelectsOneFile() {
        assertThat(YamlScenarioTest.select(FILES, "provisioning-user-to-ldap.yaml", DIR))
                .containsExactly("provisioning-user-to-ldap.yaml");
    }

    @Test
    @DisplayName("A file name without .yaml selects the same file")
    void testNameWithoutExtensionSelectsFile() {
        assertThat(YamlScenarioTest.select(FILES, "provisioning-user-to-ldap", DIR))
                .containsExactly("provisioning-user-to-ldap.yaml");
    }

    @Test
    @DisplayName("A group glob selects its group and nothing that merely contains the word")
    void testGroupGlobIsPrecise() {
        assertThat(YamlScenarioTest.select(FILES, "provisioning-*", DIR))
                .containsExactly("provisioning-minimal-user.yaml", "provisioning-user-to-ldap.yaml");
    }

    @Test
    @DisplayName("A brace glob selects two groups")
    void testBraceGlobSelectsTwoGroups() {
        assertThat(YamlScenarioTest.select(FILES, "{provisioning-*,roles-*}", DIR))
                .containsExactly(
                        "provisioning-minimal-user.yaml",
                        "provisioning-user-to-ldap.yaml",
                        "roles-assign-multiple.yaml");
    }

    @Test
    @DisplayName("A filter that matches nothing fails, listing what is available")
    void testNoMatchFails() {
        assertThatThrownBy(() -> YamlScenarioTest.select(FILES, "provisoning-user-to-ldap.yaml", DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Dscenario=provisoning-user-to-ldap.yaml")
                .hasMessageContaining(DIR)
                .hasMessageContaining("provisioning-user-to-ldap.yaml")
                .hasMessageContaining("roles-assign-multiple.yaml");
    }

    @Test
    @DisplayName("A filter against an empty directory fails and says there is nothing to choose from")
    void testNoMatchInEmptyDirectoryFails() {
        assertThatThrownBy(() -> YamlScenarioTest.select(List.of(), "anything.yaml", DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Dscenario=anything.yaml")
                .hasMessageContaining("Available: none");
    }

    @Test
    @DisplayName("A blank filter fails rather than guessing")
    void testBlankFilterFails() {
        assertThatThrownBy(() -> YamlScenarioTest.select(FILES, "  ", DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is set but empty");
    }

    @Test
    @DisplayName("An invalid glob fails, naming the pattern")
    void testInvalidGlobFails() {
        assertThatThrownBy(() -> YamlScenarioTest.select(FILES, "provisioning-[", DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Dscenario=provisioning-[")
                .hasMessageContaining("not a valid glob");
    }
}
