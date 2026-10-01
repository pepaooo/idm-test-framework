package com.idm.test.connector.ldap.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LdapValueMatcher")
class LdapValueMatcherTest {

    private static final String DEV = "cn=developers,ou=groups,o=data";
    private static final String VPN = "cn=vpn,ou=groups,o=data";
    private static final String FIN = "cn=finance,ou=groups,o=data";

    @Test
    @DisplayName("A scalar matches exactly one equal value")
    void scalar() {
        assertThat(LdapValueMatcher.match("title", "Manager", "Manager")).isNull();
        assertThat(LdapValueMatcher.match("title", "Manager", List.of("Manager", "Lead")))
                .isEqualTo("Attribute 'title': expected=Manager, actual=[Manager, Lead]");
        assertThat(LdapValueMatcher.match("title", "Manager", null))
                .isEqualTo("Attribute 'title': expected=Manager, actual=null");
        assertThat(LdapValueMatcher.match("title", "Manager", "Lead"))
                .isEqualTo("Attribute 'title': expected=Manager, actual=Lead");
    }

    @Test
    @DisplayName("Numbers and their text compare equal, as before")
    void numberAndText() {
        assertThat(LdapValueMatcher.match("employeeNumber", 200, "200")).isNull();
    }

    @Test
    @DisplayName("A plain list is the exact set, in any order")
    void exactSet() {
        assertThat(LdapValueMatcher.match("groupMembership", List.of(VPN, DEV), List.of(DEV, VPN))).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", List.of(DEV, VPN), List.of(DEV, FIN)))
                .isEqualTo("Attribute 'groupMembership': expected exactly [" + DEV + ", " + VPN
                        + "] in any order; missing [" + VPN + "]; unexpected [" + FIN + "]");
        assertThat(LdapValueMatcher.match("groupMembership", List.of(DEV, VPN), DEV))
                .isEqualTo("Attribute 'groupMembership': expected exactly [" + DEV + ", " + VPN
                        + "] in any order; missing [" + VPN + "]");
        assertThat(LdapValueMatcher.match("groupMembership", List.of(DEV), List.of(DEV, VPN)))
                .isEqualTo("Attribute 'groupMembership': expected exactly [" + DEV
                        + "] in any order; unexpected [" + VPN + "]");
    }

    @Test
    @DisplayName("A one-item list and a single value are the same")
    void oneItemList() {
        assertThat(LdapValueMatcher.match("groupMembership", List.of(DEV), DEV)).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", DEV, List.of(DEV))).isNull();
    }

    @Test
    @DisplayName("Duplicates are ignored")
    void duplicates() {
        assertThat(LdapValueMatcher.match("ou", List.of("Sales", "Sales"), "Sales")).isNull();
    }

    @Test
    @DisplayName("contains passes when every listed value is present, ignoring others")
    void contains() {
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("contains", List.of(DEV)), List.of(DEV, VPN)))
                .isNull();
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("contains", DEV), List.of(DEV, VPN)))
                .isNull();
        assertThat(LdapValueMatcher.match("groupMembership",
                Map.of("contains", List.of(DEV, VPN)), List.of(DEV, FIN)))
                .isEqualTo("Attribute 'groupMembership': expected to contain [" + DEV + ", " + VPN
                        + "]; missing [" + VPN + "]; actual=[" + DEV + ", " + FIN + "]");
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("contains", List.of(DEV)), null))
                .isEqualTo("Attribute 'groupMembership': expected to contain [" + DEV
                        + "]; missing [" + DEV + "]; actual=null");
    }

    @Test
    @DisplayName("excludes passes when no listed value is present, including when the attribute is absent")
    void excludes() {
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("excludes", List.of(FIN)), List.of(DEV)))
                .isNull();
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("excludes", FIN), null)).isNull();
        assertThat(LdapValueMatcher.match("groupMembership",
                Map.of("excludes", List.of(FIN, VPN)), List.of(DEV, FIN)))
                .isEqualTo("Attribute 'groupMembership': expected not to contain [" + FIN + ", " + VPN
                        + "]; found [" + FIN + "]");
    }

    @Test
    @DisplayName("contains and excludes together: both must hold, contains reported first")
    void both() {
        Map<String, Object> matcher = Map.of("contains", List.of(DEV), "excludes", List.of(FIN));
        assertThat(LdapValueMatcher.match("groupMembership", matcher, List.of(DEV, VPN))).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", matcher, List.of(VPN, FIN)))
                .startsWith("Attribute 'groupMembership': expected to contain");
        assertThat(LdapValueMatcher.match("groupMembership", matcher, List.of(DEV, FIN)))
                .startsWith("Attribute 'groupMembership': expected not to contain");
    }

    @Test
    @DisplayName("DNs compare ignoring case and spacing; other text stays exact")
    void dnAware() {
        assertThat(LdapValueMatcher.sameValue("CN=Developers, OU=Groups, O=Data", DEV)).isTrue();
        assertThat(LdapValueMatcher.sameValue("cn=developers,ou=groups,o=other", DEV)).isFalse();
        assertThat(LdapValueMatcher.sameValue("Manager", "manager")).isFalse();
        assertThat(LdapValueMatcher.sameValue("", "")).isTrue();
        assertThat(LdapValueMatcher.match("groupMembership",
                Map.of("contains", "CN=Developers,OU=Groups,O=Data"), List.of(DEV, VPN))).isNull();
    }

    @Test
    @DisplayName("A null expected value passes only when the attribute is absent")
    void nullExpected() {
        assertThat(LdapValueMatcher.match("title", null, null)).isNull();
        assertThat(LdapValueMatcher.match("title", null, "Manager"))
                .isEqualTo("Attribute 'title': expected=null, actual=Manager");
    }

    @Test
    @DisplayName("shapeProblem accepts scalars and well-formed lists and matchers")
    void wellFormed() {
        assertThat(LdapValueMatcher.shapeProblem("x")).isNull();
        assertThat(LdapValueMatcher.shapeProblem(List.of("a", "b"))).isNull();
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", "a"))).isNull();
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", List.of("a"), "excludes", List.of("b")))).isNull();
    }

    @Test
    @DisplayName("shapeProblem names each malformed shape")
    void malformed() {
        assertThat(LdapValueMatcher.shapeProblem(List.of())).isEqualTo(
                "is an empty list, which a misspelled attribute name would also satisfy; "
                        + "to assert values are absent use { excludes: [...] }");
        assertThat(LdapValueMatcher.shapeProblem(Arrays.asList("a", null)))
                .isEqualTo("has a null, map or list value inside its list; give plain values");
        assertThat(LdapValueMatcher.shapeProblem(List.of("a", List.of("b"))))
                .isEqualTo("has a null, map or list value inside its list; give plain values");
        assertThat(LdapValueMatcher.shapeProblem(Map.of()))
                .isEqualTo("is an empty map; use contains and/or excludes");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contain", "a")))
                .isEqualTo("has unknown matcher keys [contain]; use contains and/or excludes");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", List.of())))
                .isEqualTo("has an empty 'contains'");
        Map<String, Object> nullExcludes = new HashMap<>();
        nullExcludes.put("excludes", null);
        assertThat(LdapValueMatcher.shapeProblem(nullExcludes)).isEqualTo("has an empty 'excludes'");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", Map.of("a", "b"))))
                .isEqualTo("has a null, map or list value in 'contains'; give plain values");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("excludes", List.of("a", Map.of()))))
                .isEqualTo("has a null, map or list value in 'excludes'; give plain values");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", List.of(DEV, VPN),
                "excludes", List.of("CN=Developers,OU=Groups,O=Data"))))
                .isEqualTo("lists [" + DEV + "] in both 'contains' and 'excludes'");
    }

    @Test
    @DisplayName("match never passes a malformed matcher")
    void matchRejectsMalformed() {
        assertThat(LdapValueMatcher.match("groupMembership", List.of(), null))
                .startsWith("Attribute 'groupMembership' is an empty list");
        assertThat(LdapValueMatcher.match("groupMembership", Map.of(), List.of(DEV)))
                .isEqualTo("Attribute 'groupMembership' is an empty map; use contains and/or excludes");
    }
}
