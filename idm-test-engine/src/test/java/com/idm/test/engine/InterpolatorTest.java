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
        Object result = Interpolator.resolve("cn=${user.userId},ou=users", SCOPE, evaluator);

        assertThat(result).isEqualTo("cn=test.123,ou=users");
    }

    @Test
    @DisplayName("Should replace several references in one string")
    void testReplacesMultipleReferences() {
        Object result = Interpolator.resolve(
                "${user.firstName} <${user.userId}>", SCOPE, evaluator);

        assertThat(result).isEqualTo("Ada <test.123>");
    }

    @Test
    @DisplayName("Should walk nested maps and lists")
    void testWalksNestedStructures() {
        Object result = Interpolator.resolve(
                Map.of("expect", Map.of("givenName", "${user.firstName}"),
                       "roles", List.of("${user.userId}")),
                SCOPE, evaluator);

        assertThat(result).isEqualTo(
                Map.of("expect", Map.of("givenName", "Ada"),
                       "roles", List.of("test.123")));
    }

    @Test
    @DisplayName("Should leave values without references untouched")
    void testLeavesPlainValues() {
        assertThat(Interpolator.resolve("ou=users", SCOPE, evaluator)).isEqualTo("ou=users");
        assertThat(Interpolator.resolve(42, SCOPE, evaluator)).isEqualTo(42);
        assertThat(Interpolator.resolve(null, SCOPE, evaluator)).isNull();
    }

    @Test
    @DisplayName("Should name the offending reference when it cannot be resolved")
    void testFailsOnUnknownReference() {
        assertThatThrownBy(() -> Interpolator.resolve("cn=${missing.id}", SCOPE, evaluator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${missing.id}");
    }

    @Test
    @DisplayName("Should name the offending reference when an intermediate path is missing")
    void testFailsOnUnknownNestedPath() {
        assertThatThrownBy(() -> Interpolator.resolve("${user.absent}", SCOPE, evaluator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${user.absent}");
    }

    @Test
    @DisplayName("A whole-value reference to a map should preserve the map")
    void testWholeValueReferencePreservesMap() {
        Map<String, Object> user = Map.of("userId", "jdoe", "firstName", "Ada");
        Object resolved = Interpolator.resolve("${user}", Map.of("user", user), evaluator);

        // Previously this produced the String "{userId=jdoe, firstName=Ada}" — Java's
        // Map.toString, which is not JSON and cannot be sent as a request body.
        assertThat(resolved).isInstanceOf(Map.class);
        // Cast to a concrete Map<String, Object> rather than Map<?, ?>: AssertJ's
        // containsEntry(K, V) takes the map assert's own type parameters, and a wildcard
        // capture there cannot accept a String literal argument — a real javac limitation,
        // not a stale-build artifact.
        assertThat((Map<String, Object>) resolved).containsEntry("userId", "jdoe");
    }

    @Test
    @DisplayName("A whole-value reference to a number should preserve the number")
    void testWholeValueReferencePreservesNumber() {
        Object resolved = Interpolator.resolve("${count}", Map.of("count", 5), evaluator);

        assertThat(resolved).isInstanceOf(Integer.class).isEqualTo(5);
    }

    @Test
    @DisplayName("A whole-value reference to a list should preserve the list")
    void testWholeValueReferencePreservesList() {
        Object resolved = Interpolator.resolve(
                "${roles}", Map.of("roles", List.of("cn=SAP,ou=roles")), evaluator);

        assertThat(resolved).isInstanceOf(List.class);
        // Same reasoning as the map test above: a concrete element type, not a wildcard.
        assertThat((List<String>) resolved).containsExactly("cn=SAP,ou=roles");
    }

    @Test
    @DisplayName("A reference embedded in a larger string should still produce a string")
    void testEmbeddedReferenceStillStringifies() {
        Object resolved = Interpolator.resolve(
                "cn=${user.userId},ou=users",
                Map.of("user", Map.of("userId", "jdoe")), evaluator);

        assertThat(resolved).isInstanceOf(String.class).isEqualTo("cn=jdoe,ou=users");
    }

    @Test
    @DisplayName("Two references in one value should still produce a string")
    void testTwoReferencesStillStringify() {
        Object resolved = Interpolator.resolve(
                "${a}-${b}", Map.of("a", 1, "b", 2), evaluator);

        assertThat(resolved).isInstanceOf(String.class).isEqualTo("1-2");
    }

    @Test
    @DisplayName("A whole-value reference with surrounding whitespace is not a whole value")
    void testReferenceWithWhitespaceStillStringifies() {
        // Only an exact match is a whole value. Trimming here would silently change what a
        // scenario asked for.
        Object resolved = Interpolator.resolve(" ${count} ", Map.of("count", 5), evaluator);

        assertThat(resolved).isInstanceOf(String.class).isEqualTo(" 5 ");
    }

    @Test
    @DisplayName("Should collect the root name of every reference for validation")
    void testCollectsReferenceRoots() {
        assertThat(Interpolator.references(
                Map.of("dn", "cn=${user.userId}", "note", "${created.body.id}")))
                .containsExactlyInAnyOrder("user", "created");
    }

    @Test
    @DisplayName("Should still collect a reference embedded alongside plain text")
    void testCollectsReferenceRootFromEmbeddedValue() {
        assertThat(Interpolator.references("cn=${user.userId},ou=users"))
                .containsExactly("user");
    }

    @Test
    @DisplayName("Should report free JS names in an expression, but never read a template literal's ${...} as a scenario reference")
    void testCollectsFreeNamesInsideExpression() {
        assertThat(Interpolator.references("js:{ `${ 1 + 1 }` }")).isEmpty();
        assertThat(Interpolator.references("js:{ `hello ${user.firstName}` }")).containsExactly("user");
        assertThat(Interpolator.references("js:{ user.lastName.toUpperCase() }")).containsExactly("user");
    }

    @Test
    @DisplayName("Should not throw when collecting references from a malformed expression")
    void testCollectingReferencesDoesNotThrowOnMalformedExpression() {
        assertThat(Interpolator.references("js:{ unclosed")).isEmpty();
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
    @DisplayName("Should let a plain ${...} reference and a js:{ } expression coexist in the "
            + "same params, without interpolating inside the expression")
    void testExpressionsAreNotInterpolatedFirst() {
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
    @DisplayName("Should name the unterminated string, not a missing brace")
    void testUnterminatedStringNamesTheQuote() {
        assertThatThrownBy(() ->
                Interpolator.resolve("js:{ \"abc }", jsScope(), evaluator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not closed")
                .hasMessageContaining("\"");
    }

    @Test
    @DisplayName("Should name the unterminated block comment, not a missing brace")
    void testUnterminatedBlockCommentNamesTheComment() {
        assertThatThrownBy(() ->
                Interpolator.resolve("js:{ /* abc }", jsScope(), evaluator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not closed")
                .hasMessageContaining("*/");
    }

    @Test
    @DisplayName("Should reject an expression with an empty body")
    void testRejectsEmptyExpression() {
        assertThatThrownBy(() -> Interpolator.resolve("js:{}", jsScope(), evaluator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("js:{}");

        assertThatThrownBy(() -> Interpolator.resolve("js:{   }", jsScope(), evaluator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("js:{   }");
    }

    @Test
    @DisplayName("Should leave a value alone when js: is not the marker")
    void testPlainValueWithJsPrefixIsUntouched() {
        assertThat(Interpolator.resolve("javascript is fine here", jsScope(), evaluator))
                .isEqualTo("javascript is fine here");
    }

    @Test
    @DisplayName("Should not be fooled by a brace inside a double-quoted string")
    void testBraceInsideDoubleQuotedString() {
        assertThat(Interpolator.resolve("js:{ \"}\" }", jsScope(), evaluator))
                .isEqualTo("}");
    }

    @Test
    @DisplayName("Should not be fooled by a brace inside a single-quoted string")
    void testBraceInsideSingleQuotedString() {
        assertThat(Interpolator.resolve("js:{ '}' }", jsScope(), evaluator))
                .isEqualTo("}");
    }

    @Test
    @DisplayName("Should not be fooled by a brace inside a template literal")
    void testBraceInsideTemplateLiteral() {
        assertThat(Interpolator.resolve("js:{ `a}b` }", jsScope(), evaluator))
                .isEqualTo("a}b");
    }

    @Test
    @DisplayName("Should not be fooled by a brace inside a line comment")
    void testBraceInsideLineComment() {
        assertThat(Interpolator.resolve("js:{ 1 // }\n }", jsScope(), evaluator))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should not be fooled by a brace inside a block comment")
    void testBraceInsideBlockComment() {
        assertThat(Interpolator.resolve("js:{ /* } */ 1 }", jsScope(), evaluator))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should honour a backslash-escaped quote inside a string")
    void testEscapedQuoteInsideString() {
        assertThat(Interpolator.resolve("js:{ \"\\\"}\\\"\" }", jsScope(), evaluator))
                .isEqualTo("\"}\"");
    }

    @Test
    @DisplayName("Should track nested braces inside a template literal substitution")
    void testTemplateLiteralSubstitutionWithBraces() {
        assertThat(Interpolator.resolve("js:{ `a${ {b: 1}.b }c` }", jsScope(), evaluator))
                .isEqualTo("a1c");
    }
}
