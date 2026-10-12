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
}
