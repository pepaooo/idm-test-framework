package com.idm.test.engine.expr;

import com.idm.test.engine.expr.ExpressionEvaluator.ExpressionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExpressionEvaluator")
class ExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    private static Map<String, Object> scope() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("firstName", "ada");
        user.put("lastName", "lovelace");
        user.put("startDate", "2026-01-15");
        return Map.of("user", user);
    }

    @Test
    @DisplayName("Should evaluate a literal expression")
    void testLiteral() {
        assertThat(evaluator.evaluate("'ada'.toUpperCase()", Map.of())).isEqualTo("ADA");
    }

    @Test
    @DisplayName("Should read scenario variables")
    void testReadsVariables() {
        assertThat(evaluator.evaluate("user.lastName.toUpperCase()", scope()))
                .isEqualTo("LOVELACE");
    }

    @Test
    @DisplayName("Should do the date arithmetic that replaced a helper API")
    void testDateArithmetic() {
        String expression =
                "var d = new Date(user.startDate); d.setDate(d.getDate() + 30); "
                        + "d.toISOString().slice(0, 10)";

        assertThat(evaluator.evaluate(expression, scope())).isEqualTo("2026-02-14");
    }

    @Test
    @DisplayName("Should return native types, not only strings")
    void testReturnsNativeTypes() {
        assertThat(evaluator.evaluate("1 + 1", Map.of())).isEqualTo(2.0);
        assertThat(evaluator.evaluate("true", Map.of())).isEqualTo(true);
        assertThat(evaluator.evaluate("['a','b']", Map.of())).isEqualTo(List.of("a", "b"));
        assertThat(evaluator.evaluate("null", Map.of())).isNull();
    }

    @Test
    @DisplayName("Should deny access to java.lang.System")
    void testBlocksSystem() {
        assertThatThrownBy(() -> evaluator.evaluate("java.lang.System.getenv('HOME')", Map.of()))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    @DisplayName("Should deny access to the filesystem")
    void testBlocksFileAccess() {
        assertThatThrownBy(() ->
                evaluator.evaluate("new java.io.File('/etc/passwd').exists()", Map.of()))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    @DisplayName("Should deny the Packages back door")
    void testBlocksPackages() {
        assertThatThrownBy(() ->
                evaluator.evaluate("Packages.java.lang.Runtime.getRuntime()", Map.of()))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    @DisplayName("Should abort an expression that never terminates")
    void testAbortsRunawayExpression() {
        ExpressionEvaluator budgeted = new ExpressionEvaluator(10_000);

        assertThatThrownBy(() -> budgeted.evaluate("var i = 0; while (true) { i++; } i", Map.of()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("Should report a syntax error with the expression text")
    void testSyntaxError() {
        assertThatThrownBy(() -> evaluator.evaluate("user.lastName.(", scope()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("user.lastName.(");
    }

    @Test
    @DisplayName("Should report an unknown variable with the expression text")
    void testUnknownVariable() {
        assertThatThrownBy(() -> evaluator.evaluate("absent.field", scope()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("Should not leak variables between evaluations")
    void testNoStateLeak() {
        evaluator.evaluate("var leaked = 'first'", Map.of());

        assertThatThrownBy(() -> evaluator.evaluate("leaked", Map.of()))
                .isInstanceOf(ExpressionException.class);
    }
}
