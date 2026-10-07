package com.idm.test.connector.ldap.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LdapValueChanges")
class LdapValueChangesTest {

    @Test
    @DisplayName("Well-formed changes, and anything that is not a map, have no problem")
    void wellFormed() {
        assertThat(LdapValueChanges.shapeProblem(Map.of("add", "a"))).isNull();
        assertThat(LdapValueChanges.shapeProblem(Map.of("add", List.of("a"), "remove", "b"))).isNull();
        assertThat(LdapValueChanges.shapeProblem("plain")).isNull();
        assertThat(LdapValueChanges.shapeProblem(List.of("a"))).isNull();
    }

    @Test
    @DisplayName("Each malformed change is named")
    void malformed() {
        assertThat(LdapValueChanges.shapeProblem(Map.of())).isEqualTo("is an empty map; use add and/or remove");
        assertThat(LdapValueChanges.shapeProblem(Map.of("ad", "a"))).isEqualTo("has unknown keys [ad]; use add and/or remove");
        assertThat(LdapValueChanges.shapeProblem(Map.of("add", List.of()))).isEqualTo("has an empty 'add'");
        Map<String, Object> nullRemove = new HashMap<>();
        nullRemove.put("remove", null);
        assertThat(LdapValueChanges.shapeProblem(nullRemove)).isEqualTo("has an empty 'remove'");
        assertThat(LdapValueChanges.shapeProblem(Map.of("add", List.of("a", List.of("b")))))
                .isEqualTo("has a null, map or list value in 'add'; give plain values");
        assertThat(LdapValueChanges.shapeProblem(Map.of("add", "cn=a,o=x", "remove", "CN=A,O=X")))
                .isEqualTo("lists [cn=a,o=x] in both 'add' and 'remove'");
    }

    @Test
    @DisplayName("Offending values: those already present for add, those missing for remove")
    void offending() {
        assertThat(LdapValueChanges.offending("add", List.of("cn=a,o=x", "cn=b,o=x"), List.of("CN=A,O=X")))
                .containsExactly("cn=a,o=x");
        assertThat(LdapValueChanges.offending("remove", List.of("x", "y"), "x")).containsExactly("y");
        assertThat(LdapValueChanges.offending("remove", "x", null)).containsExactly("x");
    }

    @Test
    @DisplayName("Reasons say which values and why")
    void reasons() {
        assertThat(LdapValueChanges.reason("add", "seeAlso", List.of("cn=a")))
                .isEqualTo("cannot add [cn=a] to seeAlso: the entry already has it");
        assertThat(LdapValueChanges.reason("remove", "seeAlso", List.of("cn=a", "cn=b")))
                .isEqualTo("cannot remove [cn=a, cn=b] from seeAlso: the entry does not have them");
    }

    @Test
    @DisplayName("Secret reasons carry no values; offending ignores case")
    void secretReasonsAndCase() {
        assertThat(LdapValueChanges.secretReason("add", "userPassword"))
                .isEqualTo("cannot add to userPassword: the directory refused it because a value already exists");
        assertThat(LdapValueChanges.secretReason("remove", "userPassword"))
                .isEqualTo("cannot remove from userPassword: the directory refused it because a value is missing");
        assertThat(LdapValueChanges.offending("add", "ENGINEER", "Engineer")).containsExactly("ENGINEER");
        assertThat(SecretNames.isSecret("unicodePwd")).isTrue();
        assertThat(SecretNames.isSecret("seeAlso")).isFalse();
    }
}
