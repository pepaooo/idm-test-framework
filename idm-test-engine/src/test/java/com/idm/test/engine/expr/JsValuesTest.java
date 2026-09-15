package com.idm.test.engine.expr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsValues")
class JsValuesTest {

    /**
     * Runs a function inside a Rhino context, the way ExpressionEvaluator does.
     */
    private static <T> T inContext(BiFunction<Context, Scriptable, T> body) {
        Context cx = new ContextFactory().enterContext();
        try {
            return body.apply(cx, cx.initStandardObjects());
        } finally {
            Context.exit();
        }
    }

    @Test
    @DisplayName("Should pass primitives through unchanged")
    void testPrimitives() {
        inContext((cx, scope) -> {
            assertThat(JsValues.toJs(cx, scope, "ada")).isEqualTo("ada");
            assertThat(JsValues.toJs(cx, scope, 42)).isEqualTo(42);
            assertThat(JsValues.toJs(cx, scope, true)).isEqualTo(true);
            assertThat(JsValues.toJs(cx, scope, null)).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("Should convert a map into a native JS object, not a wrapped Java object")
    void testMapBecomesNativeObject() {
        inContext((cx, scope) -> {
            Object converted = JsValues.toJs(cx, scope, Map.of("lastName", "lovelace"));

            assertThat(converted).isInstanceOf(Scriptable.class);
            // A wrapped Java object would be NativeJavaObject, which the sandbox forbids.
            assertThat(converted.getClass().getName()).doesNotContain("NativeJavaObject");
            assertThat(((Scriptable) converted).get("lastName", (Scriptable) converted))
                    .isEqualTo("lovelace");
            return null;
        });
    }

    @Test
    @DisplayName("Should convert nested maps and lists recursively")
    void testNestedStructures() {
        inContext((cx, scope) -> {
            Object converted = JsValues.toJs(cx, scope,
                    Map.of("roles", List.of("admin", "user")));
            Scriptable outer = (Scriptable) converted;
            Scriptable roles = (Scriptable) outer.get("roles", outer);

            assertThat(roles.get(0, roles)).isEqualTo("admin");
            assertThat(roles.get(1, roles)).isEqualTo("user");
            return null;
        });
    }

    @Test
    @DisplayName("Should convert a JS result back into plain Java values")
    void testResultsComeBackAsJava() {
        inContext((cx, scope) -> {
            Object jsObject = cx.evaluateString(scope, "({a: 1, b: 'two'})", "t", 1, null);

            Object java = JsValues.fromJs(jsObject);

            assertThat(java).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) java;
            assertThat(map).containsEntry("b", "two");
            assertThat(map.get("a")).isEqualTo(1.0);   // JS numbers are doubles
            return null;
        });
    }

    @Test
    @DisplayName("Should convert a JS array back into a List")
    void testArrayComesBackAsList() {
        inContext((cx, scope) -> {
            Object jsArray = cx.evaluateString(scope, "['a','b']", "t", 1, null);

            assertThat(JsValues.fromJs(jsArray)).isEqualTo(List.of("a", "b"));
            return null;
        });
    }

    @Test
    @DisplayName("Should turn JS undefined and null into Java null")
    void testUndefinedBecomesNull() {
        inContext((cx, scope) -> {
            Object undefined = cx.evaluateString(scope, "undefined", "t", 1, null);

            assertThat(JsValues.fromJs(undefined)).isNull();
            assertThat(JsValues.fromJs(null)).isNull();
            return null;
        });
    }
}
