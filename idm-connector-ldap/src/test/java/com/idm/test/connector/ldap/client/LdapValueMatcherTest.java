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
                .isEqualTo("is an empty map; use equals, contains, excludes, count, matches or ignoreCase");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contain", "a")))
                .isEqualTo("has unknown matcher keys [contain]; use equals, contains, excludes, count, matches or ignoreCase");
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
                .isEqualTo("Attribute 'groupMembership' is an empty map; use equals, contains, excludes, count, matches or ignoreCase");
    }

    @Test
    @DisplayName("equals is the exact set, like a plain list")
    void equalsKey() {
        assertThat(LdapValueMatcher.match("ou", Map.of("equals", List.of("b", "a")), List.of("a", "b"))).isNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("equals", "a"), "a")).isNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("equals", List.of("a")), List.of("a", "b")))
                .isEqualTo("Attribute 'ou': expected exactly [a] in any order; unexpected [b]");
    }

    @Test
    @DisplayName("count compares the number of values; an absent attribute has none")
    void countKey() {
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("count", 2), List.of("a", "b"))).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("count", 0), null)).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("count", 3), List.of("a", "b")))
                .isEqualTo("Attribute 'groupMembership': expected 3 values, found 2: [a, b]");
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("count", 1), null))
                .isEqualTo("Attribute 'groupMembership': expected 1 value, found 0");
        assertThat(LdapValueMatcher.match("groupMembership", Map.of("count", 2L), List.of("a", "b"))).isNull();
    }

    @Test
    @DisplayName("matches needs each pattern to match the whole of some value")
    void matchesKey() {
        assertThat(LdapValueMatcher.match("mail", Map.of("matches", "^jdoe@.*$"), List.of("x@y.com", "jdoe@a.com")))
                .isNull();
        assertThat(LdapValueMatcher.match("mail", Map.of("matches", "^jdoe@.*$"), "x@y.com"))
                .isEqualTo("Attribute 'mail': no value matches ^jdoe@.*$; actual=x@y.com");
        assertThat(LdapValueMatcher.match("mail", Map.of("matches", "^jdoe@.*$"), null))
                .isEqualTo("Attribute 'mail': no value matches ^jdoe@.*$; actual=null");
        assertThat(LdapValueMatcher.match("mail", Map.of("matches", "jdoe"), "jdoe@a.com"))
                .as("the whole value must match").isNotNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("matches", List.of("^a.*", "^b.*")), List.of("ax", "bx"))).isNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("matches", List.of("^a.*", "^b.*")), List.of("ax")))
                .isEqualTo("Attribute 'ou': no value matches ^b.*; actual=[ax]");
        assertThat(LdapValueMatcher.match("mail", Map.of("matches", "(?i)JDOE.*"), "jdoe@a.com")).isNull();
    }

    @Test
    @DisplayName("ignoreCase applies to equals, contains and excludes")
    void ignoreCaseKey() {
        assertThat(LdapValueMatcher.match("title", Map.of("equals", "Senior Engineer", "ignoreCase", true),
                "senior engineer")).isNull();
        assertThat(LdapValueMatcher.match("title", Map.of("equals", "Senior Engineer"), "senior engineer")).isNotNull();
        assertThat(LdapValueMatcher.match("title", Map.of("equals", "A", "ignoreCase", false), "a")).isNotNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("contains", List.of("SALES"), "ignoreCase", true),
                List.of("sales", "hr"))).isNull();
        assertThat(LdapValueMatcher.match("ou", Map.of("excludes", "SALES", "ignoreCase", true), "sales"))
                .isEqualTo("Attribute 'ou': expected not to contain [SALES]; found [SALES]");
    }

    @Test
    @DisplayName("Keys combine: every one must hold")
    void combined() {
        Map<String, Object> matcher = Map.of("contains", "cn=vpn,o=x", "count", 2, "ignoreCase", true);
        assertThat(LdapValueMatcher.match("groupMembership", matcher, List.of("CN=VPN,O=X", "cn=dev,o=x"))).isNull();
        assertThat(LdapValueMatcher.match("groupMembership", matcher, List.of("CN=VPN,O=X", "cn=dev,o=x", "cn=hr,o=x")))
                .isEqualTo("Attribute 'groupMembership': expected 2 values, found 3: [CN=VPN,O=X, cn=dev,o=x, cn=hr,o=x]");
    }

    @Test
    @DisplayName("shapeProblem names each malformed new key")
    void malformedNewKeys() {
        assertThat(LdapValueMatcher.shapeProblem(Map.of("count", 3.0)))
                .isEqualTo("has a 'count' that is not a non-negative whole number: 3.0");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("count", "3")))
                .isEqualTo("has a 'count' that is not a non-negative whole number: 3");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("count", -1)))
                .isEqualTo("has a 'count' that is not a non-negative whole number: -1");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("matches", List.of()))).isEqualTo("has an empty 'matches'");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("matches", 5)))
                .isEqualTo("has a 'matches' that is not a regular expression or a list of them");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("matches", "[")))
                .startsWith("has an invalid 'matches' pattern '[': ");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("equals", "a", "ignoreCase", "yes")))
                .isEqualTo("has an 'ignoreCase' that is not true or false");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("ignoreCase", true)))
                .isEqualTo("has an 'ignoreCase' with no equals, contains or excludes to apply to");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("count", 1, "ignoreCase", true)))
                .isEqualTo("has an 'ignoreCase' with no equals, contains or excludes to apply to");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("equals", List.of()))).isEqualTo("has an empty 'equals'");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("equals", List.of("a", Map.of()))))
                .isEqualTo("has a null, map or list value in 'equals'; give plain values");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", "Sales", "excludes", "SALES", "ignoreCase", true)))
                .isEqualTo("lists [Sales] in both 'contains' and 'excludes'");
        assertThat(LdapValueMatcher.shapeProblem(Map.of("contains", "Sales", "excludes", "SALES"))).isNull();
        assertThat(LdapValueMatcher.shapeProblem(Map.of("count", java.math.BigInteger.TWO))).isNull();
    }
}
