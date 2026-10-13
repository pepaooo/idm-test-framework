package com.idm.test.connector.ldap.client;

import org.junit.jupiter.api.DisplayName;
import org.springframework.ldap.core.LdapTemplate;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LdapProblem")
class LdapProblemTest {

    /** One input per problem the matcher and the change rules can report. */
    static List<LdapProblem> allProblems() {
        List<LdapProblem> problems = new java.util.ArrayList<>();
        Object[][] matches = {
                {"a", Map.of(), "x"},
                {"userPassword", Map.of("contains", "a"), "b"},
                {"a", Map.of("contains", List.of("a", "b")), "b"},
                {"userPassword", Map.of("excludes", "a"), "a"},
                {"a", Map.of("excludes", "a"), List.of("a", "c")},
                {"a", Map.of("count", 1), List.of("x", "y")},
                {"a", Map.of("count", 2), List.of("x")},
                {"userPassword", Map.of("matches", "z.*"), "abc"},
                {"a", Map.of("matches", "z.*"), "abc"},
                {"userPassword", "a", "b"},
                {"a", "a", "b"},
                {"a", List.of("a", "b"), List.of("a")},
                {"a", List.of("a"), List.of("a", "b")},
                {"a", List.of("a", "b"), List.of("b", "c")},
        };
        for (Object[] m : matches) {
            problems.add(LdapValueMatcher.problem((String) m[0], m[1], m[2]));
        }
        Object[][] shapes = {
                {"a", Map.of()},
                {"a", Map.of("bogus", 1)},
                {"a", Map.of("equals", List.of())},
                {"a", Map.of("equals", java.util.Arrays.asList("a", null))},
                {"userPassword", Map.of("count", "x")},
                {"a", Map.of("count", "x")},
                {"a", Map.of("matches", List.of())},
                {"a", Map.of("matches", 5)},
                {"userPassword", Map.of("matches", "(")},
                {"a", Map.of("matches", "(")},
                {"a", Map.of("equals", "a", "ignoreCase", "yes")},
                {"a", Map.of("ignoreCase", true)},
                {"userPassword", Map.of("contains", "a", "excludes", "a")},
                {"a", Map.of("contains", "a", "excludes", "a")},
                {"a", List.of()},
                {"a", java.util.Arrays.asList("a", null)},
        };
        for (Object[] s : shapes) {
            problems.add(LdapValueMatcher.problemOfShape((String) s[0], s[1]));
        }
        Object[][] changes = {
                {"a", Map.of()},
                {"a", Map.of("bogus", 1)},
                {"a", Map.of("add", List.of())},
                {"a", Map.of("add", java.util.Arrays.asList("a", null))},
                {"userPassword", Map.of("add", "a", "remove", "a")},
                {"a", Map.of("add", "a", "remove", "a")},
        };
        for (Object[] c : changes) {
            problems.add(LdapValueChanges.problem((String) c[0], c[1]));
        }
        problems.addAll(operationProblems());
        return problems;
    }

    /** One input per problem the connector's operations can report (ITF-51). */
    static List<LdapProblem> operationProblems() {
        RuntimeException boom = new RuntimeException("boom");
        // A refused add/remove goes through update() itself, which works out the problem with its
        // one read; a template that finds nothing leaves only the secret reasons.
        LdapTemplate template = org.mockito.Mockito.mock(LdapTemplate.class);
        org.mockito.Mockito.doThrow(new org.springframework.ldap.AttributeInUseException(
                        new javax.naming.directory.AttributeInUseException("in use")))
                .when(template).modifyAttributes(org.mockito.ArgumentMatchers.any(javax.naming.Name.class),
                        org.mockito.ArgumentMatchers.any(javax.naming.directory.ModificationItem[].class));
        EdirectoryConnector connector = new EdirectoryConnector(template);
        java.util.function.Function<Map<String, Object>, LdapProblem> refused = attributes ->
                EdirectoryConnector.updateProblem(attributes,
                        connector.update("cn=x", attributes).getThrowable().orElseThrow());
        List<LdapProblem> problems = new java.util.ArrayList<>(List.of(
                EdirectoryConnector.createProblem(boom),
                EdirectoryConnector.readAttributesProblem("cn=x", new org.springframework.ldap.NameNotFoundException(
                        new javax.naming.NameNotFoundException("gone"))),
                EdirectoryConnector.readAttributesProblem("cn=x", boom),
                EdirectoryConnector.updateProblem(Map.of("mail", "a"), boom),
                EdirectoryConnector.updateProblem(Map.of("userPassword", "a"), boom),
                refused.apply(Map.of("userPassword", "a", "mail", Map.of("add", "b"))),
                refused.apply(Map.of("userPassword", Map.of("add", "a"), "pwdHistory", Map.of("add", "b"))),
                EdirectoryConnector.deleteProblem(boom),
                EdirectoryConnector.existsProblem(boom),
                LdapValueChanges.reasonProblem(LdapValueChanges.ADD, "mail", List.of("a")),
                LdapValueChanges.reasonProblem(LdapValueChanges.ADD, "mail", List.of("a", "b")),
                LdapValueChanges.reasonProblem(LdapValueChanges.REMOVE, "mail", List.of("a")),
                LdapValueChanges.reasonProblem(LdapValueChanges.REMOVE, "mail", List.of("a", "b")),
                LdapValueChanges.secretReasonProblem(LdapValueChanges.ADD, "userPassword"),
                LdapValueChanges.secretReasonProblem(LdapValueChanges.REMOVE, "userPassword")));
        return problems;
    }

    @Test
    @DisplayName("Each operation problem's English is the connector's message, character for character")
    void testOperationProblemsWording() {
        List<LdapProblem> problems = operationProblems();

        assertThat(problems.get(0).english()).isEqualTo("Failed to create user: boom");
        assertThat(problems.get(1).english()).isEqualTo("Entry not found: cn=x");
        assertThat(problems.get(3).english()).isEqualTo("Failed to update user: boom");
        assertThat(problems.get(4).english()).isEqualTo("Failed to update user: the directory refused the request");
        assertThat(problems.get(5).english()).isEqualTo("Failed to update user: the directory refused the add/remove (LDAP 20)");
        assertThat(problems.get(6).kind()).isEqualTo(LdapProblem.Kind.REFUSED_ALL);
        assertThat(problems.get(6).english()).contains("; ");
        assertThat(problems.get(10).english()).isEqualTo(LdapValueChanges.reason(LdapValueChanges.ADD, "mail", List.of("a", "b")));
    }

    @Test
    @DisplayName("Every matcher and change problem is reported, with its kind")
    void testEveryKindIsReported() {
        List<LdapProblem> problems = allProblems();

        assertThat(problems).doesNotContainNull();
        Set<LdapProblem.Kind> seen = problems.stream().map(LdapProblem::kind).collect(Collectors.toSet());
        assertThat(seen).isEqualTo(EnumSet.allOf(LdapProblem.Kind.class));
    }

    @Test
    @DisplayName("A mismatch carries the attribute and the values it quotes")
    void testStructuredValues() {
        LdapProblem problem = LdapValueMatcher.problem("title", "Manager", "Lead");

        assertThat(problem.kind()).isEqualTo(LdapProblem.Kind.MATCH_VALUE);
        assertThat(problem.values()).containsExactly("title", "Manager", "Lead");
        assertThat(problem.english()).isEqualTo(LdapValueMatcher.match("title", "Manager", "Lead"));
    }

    @Test
    @DisplayName("A malformed matcher nests its shape problem in the match failure")
    void testShapeProblemIsNested() {
        LdapProblem problem = LdapValueMatcher.problem("title", Map.of(), "x");

        assertThat(problem.kind()).isEqualTo(LdapProblem.Kind.MATCH_SHAPE);
        assertThat(problem.values().get(1)).isInstanceOf(LdapProblem.class);
        assertThat(problem.english()).startsWith("Attribute 'title' is an empty map; ");
    }

    @Test
    @DisplayName("A refused update keeps its problem in the result, so rebuilding it reads nothing more")
    void testRefusedUpdateReadsOnce() {
        LdapTemplate template = org.mockito.Mockito.mock(LdapTemplate.class);
        org.mockito.Mockito.doThrow(new org.springframework.ldap.NoSuchAttributeException(
                        new javax.naming.directory.NoSuchAttributeException("missing")))
                .when(template).modifyAttributes(org.mockito.ArgumentMatchers.any(javax.naming.Name.class),
                        org.mockito.ArgumentMatchers.any(javax.naming.directory.ModificationItem[].class));
        EdirectoryConnector connector = new EdirectoryConnector(template);
        Map<String, Object> attributes = Map.of("userPassword", Map.of("remove", "a"));

        var result = connector.update("cn=x", attributes);
        LdapProblem problem = EdirectoryConnector.updateProblem(attributes, result.getThrowable().orElseThrow());

        assertThat(problem.english()).isEqualTo(result.getMessage())
                .isEqualTo("cannot remove from userPassword: the directory refused it because a value is missing");
        assertThat(result.getThrowable().orElseThrow().getCause())
                .isInstanceOf(org.springframework.ldap.NoSuchAttributeException.class);
        org.mockito.Mockito.verify(template, org.mockito.Mockito.times(1))
                .lookup(org.mockito.ArgumentMatchers.any(javax.naming.Name.class),
                        org.mockito.ArgumentMatchers.<org.springframework.ldap.core.AttributesMapper<Object>>any());
    }
}
