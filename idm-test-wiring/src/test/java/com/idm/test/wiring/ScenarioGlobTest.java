package com.idm.test.wiring;

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
 * Tests {@link ScenarioGlob#select} directly with the pattern as an argument, so nothing
 * here sets a system property that could leak into other tests.
 */
@DisplayName("Scenario filter (-Dscenario)")
class ScenarioGlobTest {

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
        assertThat(ScenarioGlob.select(FILES, null)).containsExactlyElementsOf(FILES);
    }

    @Test
    @DisplayName("An exact file name selects that one file")
    void testExactNameSelectsOneFile() {
        assertThat(ScenarioGlob.select(FILES, "provisioning-user-to-ldap.yaml"))
                .containsExactly("provisioning-user-to-ldap.yaml");
    }

    @Test
    @DisplayName("A file name without .yaml selects the same file")
    void testNameWithoutExtensionSelectsFile() {
        assertThat(ScenarioGlob.select(FILES, "provisioning-user-to-ldap"))
                .containsExactly("provisioning-user-to-ldap.yaml");
    }

    @Test
    @DisplayName("A group glob selects its group and nothing that merely contains the word")
    void testGroupGlobIsPrecise() {
        assertThat(ScenarioGlob.select(FILES, "provisioning-*"))
                .containsExactly("provisioning-minimal-user.yaml", "provisioning-user-to-ldap.yaml");
    }

    @Test
    @DisplayName("A brace glob selects two groups")
    void testBraceGlobSelectsTwoGroups() {
        assertThat(ScenarioGlob.select(FILES, "{provisioning-*,roles-*}"))
                .containsExactly(
                        "provisioning-minimal-user.yaml",
                        "provisioning-user-to-ldap.yaml",
                        "roles-assign-multiple.yaml");
    }

    @Test
    @DisplayName("An invalid glob fails, naming the pattern")
    void testInvalidGlobFails() {
        assertThatThrownBy(() -> ScenarioGlob.select(FILES, "provisioning-["))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Dscenario=provisioning-[")
                .hasMessageContaining("not a valid glob");
    }

    @Test
    @DisplayName("A pattern that matches nothing returns an empty list, leaving the message to the caller")
    void testNoMatchIsEmpty() {
        assertThat(ScenarioGlob.select(FILES, "provisoning-user-to-ldap.yaml")).isEmpty();
    }

    @Test
    @DisplayName("A .yml file is selected by its name without the extension")
    void testYmlExtensionIsStripped() {
        assertThat(ScenarioGlob.select(List.of("a.yml", "b.yaml"), "a")).containsExactly("a.yml");
    }
}
