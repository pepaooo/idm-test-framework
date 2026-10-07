package com.idm.test.engine.expr;

import com.idm.test.engine.expr.ExpressionEvaluator.ExpressionException;
import com.idm.test.engine.spi.Explained;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;

import java.util.ArrayList;
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
        // The trailing reference gives the statement a defined completion value; a bare `var`
        // declaration's completion value is itself `undefined`, which ITF-20 now refuses.
        Object first = evaluator.evaluate("var leaked = 'first'; leaked", Map.of());
        assertThat(first).isEqualTo("first");

        assertThatThrownBy(() -> evaluator.evaluate("leaked", Map.of()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("leaked")
                .hasMessageContaining("not defined");
    }

    @Test
    @DisplayName("Should read integer-keyed object properties instead of calling them undefined")
    void testIntegerKeyedPropertiesConvert() {
        Object result = evaluator.evaluate("({0: 'a', 1001: 'x', b: 1})", Map.of());

        assertThat(result).isEqualTo(Map.of("0", "a", "1001", "x", "b", 1.0));
    }

    @Test
    @DisplayName("Should report a self-referencing object as an ExpressionException, not a StackOverflowError")
    void testSelfReferencingObjectDoesNotOverflowStack() {
        assertThatThrownBy(() -> evaluator.evaluate("var o = {}; o.self = o; o", Map.of()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("o.self = o");
    }

    @Test
    @DisplayName("Should report a self-referencing array as an ExpressionException, not a StackOverflowError")
    void testSelfReferencingArrayDoesNotOverflowStack() {
        assertThatThrownBy(() -> evaluator.evaluate("var x = []; x.push(x); x", Map.of()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("x.push(x)");
    }

    @Test
    @DisplayName("Should report a huge array as an ExpressionException, not an OutOfMemoryError")
    void testHugeArrayDoesNotExhaustMemory() {
        assertThatThrownBy(() -> evaluator.evaluate("new Array(2000000000)", Map.of()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("new Array(2000000000)");
    }

    @Test
    @DisplayName("Should reject a BigInt result rather than silently rounding it")
    void testBigIntegerResultIsRejected() {
        assertThatThrownBy(() -> evaluator.evaluate("(10n ** 30n)", Map.of()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("(10n ** 30n)")
                .hasMessageContaining("BigInt");
    }

    @Test
    @DisplayName("Should reject a Date result rather than returning an empty map")
    void testDateResultIsRejected() {
        assertThatThrownBy(() -> evaluator.evaluate("new Date(0)", Map.of()))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("new Date(0)")
                .hasMessageContaining("Date");
    }

    @Test
    @Timeout(5)
    @DisplayName("Should fail fast, not hang, when a foreign Context is already open on this thread")
    void testBudgetAppliesWithPreEnteredContext() {
        // Simulates an embedded/nested use (Task 2: wiring this evaluator into the scenario
        // engine) where some other code has already entered an unrelated Rhino Context on this
        // thread. evaluate() cannot let that expression run at all, because the instruction
        // budget is permanently tied to whichever ContextFactory created the Context that is
        // already bound (Context#getFactory is set once, at construction), so it refuses up
        // front instead. The @Timeout is a backstop only: this test must never be able to hang
        // the suite.
        Context unrelated = new ContextFactory().enterContext();
        try {
            assertThat(Context.getCurrentContext()).isSameAs(unrelated);

            ExpressionEvaluator budgeted = new ExpressionEvaluator(10_000);

            assertThatThrownBy(() -> budgeted.evaluate("1 + 1", Map.of()))
                    .isInstanceOf(ExpressionException.class)
                    .hasMessageContaining("another Rhino Context is already open on this thread");
        } finally {
            Context.exit();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("Should report a wide, exponentially-duplicated structure as an ExpressionException, not exhaust memory")
    void testWideDuplicatedStructureDoesNotExhaustMemory() {
        // Acyclic and only 40 levels deep - it defeats the cycle check and the depth cap - but
        // fromJs walks it as a tree, so it visits 2^40 nodes unless a total-node cap catches it.
        String expression = "var a = {}; for (var i = 0; i < 40; i++) { a = {x: a, y: a}; } a";

        assertThatThrownBy(() -> evaluator.evaluate(expression, Map.of()))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    @DisplayName("Should refuse a result that is undefined, naming the expression")
    void testUndefinedResultRefused() {
        assertThatThrownBy(() -> evaluator.evaluate("user.firstname", scope()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessageContaining("js:{ user.firstname }")
                .hasMessageContaining("result is undefined");
    }

    @Test
    @DisplayName("Should refuse undefined nested in an object, naming the property")
    void testUndefinedPropertyRefused() {
        assertThatThrownBy(() -> evaluator.evaluate("({ dn: user.firstname })", scope()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessageContaining("result.dn is undefined");
    }

    @Test
    @DisplayName("Should refuse undefined nested in an array, naming the index")
    void testUndefinedElementRefused() {
        assertThatThrownBy(() -> evaluator.evaluate("[user.firstName, user.firstname]", scope()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessageContaining("result[1] is undefined");
    }

    @Test
    @DisplayName("Should refuse an array hole, which JavaScript reads as undefined")
    void testArrayHoleRefused() {
        assertThatThrownBy(() -> evaluator.evaluate("[1, , 3]", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessageContaining("result[1] is undefined");
    }

    @Test
    @DisplayName("Should still allow an explicit null, at the top level and nested")
    void testExplicitNullAllowed() {
        assertThat(evaluator.evaluate("null", Map.of())).isNull();

        Object result = evaluator.evaluate("({ a: null, b: [null] })", Map.of());
        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) result;
        assertThat(map.containsKey("a")).isTrue();
        assertThat(map.get("a")).isNull();
        @SuppressWarnings("unchecked")
        List<Object> b = (List<Object>) map.get("b");
        assertThat(b).containsExactly((Object) null);
    }

    @Test
    @DisplayName("Should convert a result with no undefined exactly as before")
    void testOrdinaryResultUnchanged() {
        Object result = evaluator.evaluate("({ name: user.firstName, tags: ['a', 'b'] })", scope());

        assertThat(result).isEqualTo(Map.of("name", "ada", "tags", List.of("a", "b")));
    }

    private Explained explain(String expression, Map<String, Object> variables) {
        return evaluator.evaluateExplained(expression, variables);
    }

    @Test
    @DisplayName("containsAll: every value present, single values or lists, compared as text")
    void testContainsAll() {
        Map<String, Object> vars = Map.of("groups", List.of("HR", "VPN", 42));
        assertThat(evaluator.evaluate("containsAll(groups, ['VPN', 'HR'])", vars)).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll(groups, 'HR')", vars)).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll(groups, '42')", vars)).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll('HR', 'HR')", Map.of())).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll(groups, 'hr')", vars)).isEqualTo(false);

        Explained missing = explain("containsAll(groups, ['HR', 'Finance'])", vars);
        assertThat(missing.value()).isEqualTo(false);
        assertThat(missing.notes()).containsExactly("containsAll: missing [Finance]");
    }

    @Test
    @DisplayName("excludesAll: none present; an absent list excludes everything")
    void testExcludesAll() {
        Map<String, Object> vars = Map.of("groups", List.of("HR", "VPN"));
        assertThat(evaluator.evaluate("excludesAll(groups, ['Finance'])", vars)).isEqualTo(true);
        assertThat(evaluator.evaluate("excludesAll(null, 'X')", Map.of())).isEqualTo(true);
        assertThat(evaluator.evaluate("excludesAll(undefined, 'X')", Map.of())).isEqualTo(true);

        Explained found = explain("excludesAll(groups, ['Finance', 'VPN'])", vars);
        assertThat(found.value()).isEqualTo(false);
        assertThat(found.notes()).containsExactly("excludesAll: found [VPN]");
    }

    @Test
    @DisplayName("sameMembers: same values in any order, duplicates ignored")
    void testSameMembers() {
        Map<String, Object> vars = Map.of("groups", List.of("HR", "VPN", "HR"));
        assertThat(evaluator.evaluate("sameMembers(groups, ['VPN', 'HR'])", vars)).isEqualTo(true);

        Explained differs = explain("sameMembers(groups, ['HR', 'Finance'])", vars);
        assertThat(differs.value()).isEqualTo(false);
        assertThat(differs.notes()).containsExactly("sameMembers: missing [Finance]; unexpected [VPN]");

        assertThat(explain("sameMembers(groups, ['HR', 'VPN', 'X'])", vars).notes())
                .containsExactly("sameMembers: missing [X]");
    }

    @Test
    @DisplayName("Objects compare by content, whatever their key order; arrays element by element")
    void testObjectsByContent() {
        Map<String, Object> vars = Map.of("roles", List.of(
                Map.of("id", 1, "role", "HR"), Map.of("id", 2, "role", "VPN")));
        assertThat(evaluator.evaluate("containsAll(roles, [{role: 'HR', id: '1'}])", vars)).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll(roles, [{role: 'HR', id: 2}])", vars)).isEqualTo(false);
        assertThat(evaluator.evaluate("containsAll([[1, 2]], [['1', '2']])", Map.of())).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll([[1, 2]], [[2, 1]])", Map.of())).isEqualTo(false);
    }

    @Test
    @DisplayName("Notes show at most ten values, then how many more")
    void testNoteCap() {
        List<Object> twelve = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            twelve.add("v" + i);
        }
        Explained capped = explain("containsAll([], wanted)", Map.of("wanted", twelve));
        assertThat(capped.notes()).containsExactly(
                "containsAll: missing [v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, … 2 more]");
    }

    @Test
    @DisplayName("Notes: in call order, none when true, none carried into the next evaluation")
    void testNotesLifecycle() {
        Explained two = explain("containsAll(['a'], 'b') || excludesAll(['a'], 'a')", Map.of());
        assertThat(two.value()).isEqualTo(false);
        assertThat(two.notes()).containsExactly("containsAll: missing [b]", "excludesAll: found [a]");

        Explained ok = explain("containsAll(['a'], 'a')", Map.of());
        assertThat(ok.value()).isEqualTo(true);
        assertThat(ok.notes()).isEmpty();

        assertThat(explain("1 + 1", Map.of()).notes()).isEmpty();
        assertThat(evaluator.evaluate("1 + 1", Map.of())).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Helpers run inside the sandbox and the instruction budget")
    void testHelpersSandboxed() {
        ExpressionEvaluator tight = new ExpressionEvaluator(5_000);
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            many.add("v" + i);
        }
        assertThatThrownBy(() -> tight.evaluate("sameMembers(many, many)", Map.of("many", many)))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class)
                .hasMessageContaining("ran too long");
        assertThatThrownBy(() -> evaluator.evaluate("java.lang.System.exit(0)", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionException.class);
    }

    @Test
    @DisplayName("Prototype-named values are ordinary values")
    void testPrototypeNames() {
        Explained e = explain("containsAll(['a'], 'constructor')", Map.of());
        assertThat(e.value()).isEqualTo(false);
        assertThat(e.notes()).containsExactly("containsAll: missing [constructor]");
        assertThat(evaluator.evaluate("containsAll(['__proto__', 'toString'], ['toString', '__proto__'])", Map.of()))
                .isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll(['a'], ['__proto__'])", Map.of())).isEqualTo(false);
        assertThat(evaluator.evaluate("excludesAll(['a'], ['valueOf'])", Map.of())).isEqualTo(true);
    }

    @Test
    @DisplayName("Nested text is unambiguous; the text rule still applies at every depth")
    void testNestedCanonical() {
        assertThat(evaluator.evaluate("sameMembers([['a,b']], [['a','b']])", Map.of())).isEqualTo(false);
        assertThat(evaluator.evaluate("containsAll([[1]], '[1]')", Map.of())).isEqualTo(false);
        assertThat(evaluator.evaluate("containsAll([[1, 2]], [['1', '2']])", Map.of())).isEqualTo(true);
        assertThat(evaluator.evaluate("containsAll([{id: 1}], [{id: '1'}])", Map.of())).isEqualTo(true);
        Explained e = explain("containsAll([], [{id: 1, n: 'x'}])", Map.of());
        assertThat(e.notes()).containsExactly("containsAll: missing [{id: 1, n: \"x\"}]");
    }

    @Test
    @DisplayName("Dates compare by their text")
    void testDates() {
        assertThat(evaluator.evaluate(
                "containsAll([new Date(0)], [new Date(0)])", Map.of())).isEqualTo(true);
        assertThat(evaluator.evaluate(
                "containsAll([new Date(0)], [new Date(1000)])", Map.of())).isEqualTo(false);
    }

    @Test
    @DisplayName("An expression overwriting the notes does not break explain")
    void testNotesOverwritten() {
        Explained e = explain("(function () { __checkNotes = undefined; return 1; })()", Map.of());
        assertThat(e.notes()).isEmpty();
    }
}
