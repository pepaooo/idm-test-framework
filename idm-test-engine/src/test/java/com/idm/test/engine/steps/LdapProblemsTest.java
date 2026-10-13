package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.LdapProblem;
import com.idm.test.connector.ldap.client.LdapValueChanges;
import com.idm.test.connector.ldap.client.LdapValueMatcher;
import com.idm.test.engine.i18n.Messages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LDAP matcher and change problems by key")
class LdapProblemsTest {

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
        return problems;
    }

    @Test
    @DisplayName("Every problem reads in English exactly as the connector words it, and differently in Spanish")
    void testCatalogMatchesConnectorWording() {
        for (LdapProblem problem : allProblems()) {
            assertThat(Messages.english(LdapProblems.of(problem))).as(problem.kind().name())
                    .isEqualTo(problem.english());
            assertThat(Messages.render(LdapProblems.of(problem), Locale.of("es"))).as(problem.kind().name())
                    .isNotEqualTo(problem.english());
        }
    }

    @Test
    @DisplayName("Every directory operation problem reads in English exactly as the connector words it, and differently in Spanish")
    void testOperationProblems() {
        List<LdapProblem.Kind> operations = List.of(LdapProblem.Kind.CREATE_FAILED, LdapProblem.Kind.ENTRY_NOT_FOUND,
                LdapProblem.Kind.READ_FAILED, LdapProblem.Kind.UPDATE_FAILED, LdapProblem.Kind.UPDATE_REFUSED_REQUEST,
                LdapProblem.Kind.UPDATE_REFUSED_CHANGE, LdapProblem.Kind.DELETE_FAILED, LdapProblem.Kind.EXISTS_FAILED,
                LdapProblem.Kind.REFUSED_ADD_ONE, LdapProblem.Kind.REFUSED_ADD_OTHER, LdapProblem.Kind.REFUSED_REMOVE_ONE,
                LdapProblem.Kind.REFUSED_REMOVE_OTHER, LdapProblem.Kind.REFUSED_ADD_SECRET,
                LdapProblem.Kind.REFUSED_REMOVE_SECRET);
        List<LdapProblem> problems = new java.util.ArrayList<>();
        for (LdapProblem.Kind kind : operations) {
            problems.add(new LdapProblem(kind, List.of("[v0]", "v1")));
        }
        problems.add(new LdapProblem(LdapProblem.Kind.REFUSED_ALL, List.copyOf(problems.subList(8, 11))));
        for (LdapProblem problem : problems) {
            assertThat(Messages.english(LdapProblems.of(problem))).as(problem.kind().name())
                    .isEqualTo(problem.english());
            assertThat(Messages.render(LdapProblems.of(problem), Locale.of("es"))).as(problem.kind().name())
                    .isNotEqualTo(problem.english());
        }
        assertThat(Messages.render(LdapProblems.of(problems.get(problems.size() - 1)), Locale.of("es")))
                .isEqualTo("no se puede agregar [v0] a v1: la entrada ya lo tiene; no se pueden agregar [v0] a v1:"
                        + " la entrada ya los tiene; no se puede quitar [v0] de v1: la entrada no lo tiene");
    }
}
