package com.idm.test.engine;

import com.idm.test.engine.expr.ExpressionEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Interpolator")
class InterpolatorTest {

    private static final Map<String, Object> SCOPE = Map.of(
            "user", Map.of("userId", "test.123", "firstName", "Ada"));

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    private static Map<String, Object> jsScope() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("firstName", "ada");
        user.put("lastName", "lovelace");
        return Map.of("user", user);
    }

    @Test
    @DisplayName("Should replace a reference embedded in a larger string")
    void testReplacesEmbeddedReference() {
        Object result = Interpolator.resolve("cn=${user.userId},ou=users", SCOPE);

        assertThat(result).isEqualTo("cn=test.123,ou=users");
    }

    @Test
    @DisplayName("Should replace several references in one string")
    void testReplacesMultipleReferences() {
        Object result = Interpolator.resolve("${user.firstName} <${user.userId}>", SCOPE);

        assertThat(result).isEqualTo("Ada <test.123>");
    }

    @Test
    @DisplayName("Should walk nested maps and lists")
    void testWalksNestedStructures() {
        Object result = Interpolator.resolve(
                Map.of("expect", Map.of("givenName", "${user.firstName}"),
                       "roles", List.of("${user.userId}")),
                SCOPE);

        assertThat(result).isEqualTo(
                Map.of("expect", Map.of("givenName", "Ada"),
                       "roles", List.of("test.123")));
    }

    @Test
    @DisplayName("Should leave values without references untouched")
    void testLeavesPlainValues() {
        assertThat(Interpolator.resolve("ou=users", SCOPE)).isEqualTo("ou=users");
        assertThat(Interpolator.resolve(42, SCOPE)).isEqualTo(42);
        assertThat(Interpolator.resolve(null, SCOPE)).isNull();
    }

    @Test
    @DisplayName("Should name the offending reference when it cannot be resolved")
    void testFailsOnUnknownReference() {
        assertThatThrownBy(() -> Interpolator.resolve("cn=${missing.id}", SCOPE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${missing.id}");
    }

    @Test
    @DisplayName("Should name the offending reference when an intermediate path is missing")
    void testFailsOnUnknownNestedPath() {
        assertThatThrownBy(() -> Interpolator.resolve("${user.absent}", SCOPE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${user.absent}");
    }

    @Test
    @DisplayName("Should collect the root name of every reference for validation")
    void testCollectsReferenceRoots() {
        assertThat(Interpolator.references(
                Map.of("dn", "cn=${user.userId}", "note", "${created.body.id}")))
                .containsExactlyInAnyOrder("user", "created");
    }

    @Test
    @DisplayName("Should evaluate a value that is entirely an expression")
    void testEvaluatesWholeValueExpression() {
        Object result = Interpolator.resolve(
                "js:{ user.lastName.toUpperCase() }", jsScope(), evaluator);

        assertThat(result).isEqualTo("LOVELACE");
    }

    @Test
    @DisplayName("Should return the expression's native type rather than a string")
    void testExpressionKeepsItsType() {
        assertThat(Interpolator.resolve("js:{ 1 + 1 }", jsScope(), evaluator)).isEqualTo(2.0);
        assertThat(Interpolator.resolve("js:{ true }", jsScope(), evaluator)).isEqualTo(true);
    }

    @Test
    @DisplayName("Should handle braces inside an expression")
    void testNestedBraces() {
        Object result = Interpolator.resolve(
                "js:{ JSON.stringify({a: 1}) }", jsScope(), evaluator);

        assertThat(result).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("Should evaluate expressions nested in maps and lists")
    void testExpressionsInsideStructures() {
        Object result = Interpolator.resolve(
                Map.of("attributes", Map.of("sn", "js:{ user.lastName.toUpperCase() }")),
                jsScope(), evaluator);

        assertThat(result).isEqualTo(Map.of("attributes", Map.of("sn", "LOVELACE")));
    }

    @Test
    @DisplayName("Should resolve ${...} before the expression runs, so both can be combined")
    void testDollarRefsStillWorkAlongsideExpressions() {
        Map<String, Object> params = Map.of(
                "dn", "cn=${user.firstName},ou=users",
                "upper", "js:{ user.firstName.toUpperCase() }");

        assertThat(Interpolator.resolve(params, jsScope(), evaluator)).isEqualTo(
                Map.of("dn", "cn=ada,ou=users", "upper", "ADA"));
    }

    @Test
    @DisplayName("Should reject an expression embedded in a larger string")
    void testRejectsEmbeddedExpression() {
        assertThatThrownBy(() ->
                Interpolator.resolve("cn=js:{ user.lastName }", jsScope(), evaluator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole value");
    }

    @Test
    @DisplayName("Should reject an expression whose braces never close")
    void testRejectsUnterminatedExpression() {
        assertThatThrownBy(() ->
                Interpolator.resolve("js:{ user.lastName", jsScope(), evaluator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not closed");
    }

    @Test
    @DisplayName("Should leave a value alone when js: is not the marker")
    void testPlainValueWithJsPrefixIsUntouched() {
        assertThat(Interpolator.resolve("javascript is fine here", jsScope(), evaluator))
                .isEqualTo("javascript is fine here");
    }
}
