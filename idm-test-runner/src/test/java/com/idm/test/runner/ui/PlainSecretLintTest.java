package com.idm.test.runner.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Plain-text password lint")
class PlainSecretLintTest {

    private static final String SCENARIO = """
            name: create a user
            steps:
              - ldap.create:
                  dn: cn=jdoe,ou=users,o=lab
                  attributes:
                    cn: jdoe
                    userPassword: %s
            """;

    @Test
    @DisplayName("A literal under a secret-looking key is a warning on its line, at the value")
    void testLiteralIsFlagged() {
        List<Problem> problems = PlainSecretLint.check(SCENARIO.formatted("Fake-Value-1"));

        assertThat(problems).hasSize(1);
        Problem problem = problems.getFirst();
        assertThat(problem.severity()).isEqualTo(Problem.Severity.WARNING);
        assertThat(problem.line()).isEqualTo(7);
        assertThat(problem.column()).isEqualTo(23);
        assertThat(problem.key()).isEqualTo("ui.lint.plainSecret");
        assertThat(problem.message().key()).isEqualTo("runner.ui.lint.plainSecret");
        assertThat(problem.message().args()).isEmpty();
    }

    @Test
    @DisplayName("A ${…} reference, a js:{ } expression, an empty value or a non-secret key is not flagged")
    void testReferencesAreFine() {
        assertThat(PlainSecretLint.check(SCENARIO.formatted("${IDM_TEST_PW}"))).isEmpty();
        assertThat(PlainSecretLint.check(SCENARIO.formatted("\"${IDM_TEST_PW}\""))).isEmpty();
        assertThat(PlainSecretLint.check(SCENARIO.formatted("js:{ fixtures.pw }"))).isEmpty();
        assertThat(PlainSecretLint.check(SCENARIO.formatted("\"\""))).isEmpty();
        assertThat(PlainSecretLint.check(SCENARIO.formatted(""))).isEmpty();
        assertThat(PlainSecretLint.check("name: x\ndescription: not a secret\n")).isEmpty();
    }

    @Test
    @DisplayName("Settings files: nested and dotted keys, numbers and list items are flagged")
    void testConfigFile() {
        String config = """
                spring:
                  datasource:
                    password: hunter-fake
                idm.test.rest.targets.hr.client-secret: 12345678
                api-keys:
                  token:
                    - first-fake
                    - ${IDM_TEST_SECOND}
                url: ldap://localhost:389
                """;

        List<Problem> problems = PlainSecretLint.check(config);

        assertThat(problems).extracting(Problem::line).containsExactly(3, 4, 7);
        assertThat(problems).allMatch(p -> p.key().equals("ui.lint.plainSecret"));
    }

    @Test
    @DisplayName("Broken YAML: what was read before the error is still checked, and nothing is thrown")
    void testBrokenYaml() {
        String broken = "password: fake-one\nlist: [unclosed\n  token: fake-two\n";

        assertThat(PlainSecretLint.check(broken)).extracting(Problem::line).containsExactly(1);
        assertThat(PlainSecretLint.check("")).isEmpty();
        assertThat(PlainSecretLint.check("\t: :")).isEmpty();
    }

    @Test
    @DisplayName("literals() returns the values the problems are about (for masking), and expressions too")
    void testLiterals() {
        String yaml = "password: fake-one\nsecret: js:{ 'x' + 1 }\ntoken: ${REF}\nname: open\n";

        assertThat(PlainSecretLint.literals(yaml)).containsExactlyInAnyOrder("fake-one", "js:{ 'x' + 1 }", "'x' + 1");
    }

    @Test
    @DisplayName("literals() still finds a secret on a line the YAML parser cannot read")
    void testLiteralsOnBrokenLine() {
        assertThat(PlainSecretLint.literals("name: x\nuserPassword: \"fake-unclosed\n"))
                .contains("fake-unclosed");
    }
}
