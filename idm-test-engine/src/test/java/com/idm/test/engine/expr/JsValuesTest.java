package com.idm.test.engine.expr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("Should reject a self-referencing Java map instead of overflowing the stack")
    void testToJsRejectsSelfReferencingMap() {
        inContext((cx, scope) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("self", map);

            assertThatThrownBy(() -> JsValues.toJs(cx, scope, map))
                    .isInstanceOf(JsValues.JsConversionException.class)
                    .hasMessageContaining("self-referencing");
            return null;
        });
    }

    @Test
    @DisplayName("Should reject a self-referencing Java list instead of overflowing the stack")
    void testToJsRejectsSelfReferencingList() {
        inContext((cx, scope) -> {
            List<Object> list = new ArrayList<>();
            list.add(list);

            assertThatThrownBy(() -> JsValues.toJs(cx, scope, list))
                    .isInstanceOf(JsValues.JsConversionException.class)
                    .hasMessageContaining("self-referencing");
            return null;
        });
    }

    @Test
    @DisplayName("Should reject a self-referencing JS object instead of overflowing the stack")
    void testFromJsRejectsSelfReferencingObject() {
        inContext((cx, scope) -> {
            Object cyclic = cx.evaluateString(scope, "var o = {}; o.self = o; o", "t", 1, null);

            assertThatThrownBy(() -> JsValues.fromJs(cyclic))
                    .isInstanceOf(JsValues.JsConversionException.class)
                    .hasMessageContaining("self-referencing");
            return null;
        });
    }

    @Test
    @DisplayName("Should reject a self-referencing JS array instead of overflowing the stack")
    void testFromJsRejectsSelfReferencingArray() {
        inContext((cx, scope) -> {
            Object cyclic = cx.evaluateString(scope, "var x = []; x.push(x); x", "t", 1, null);

            assertThatThrownBy(() -> JsValues.fromJs(cyclic))
                    .isInstanceOf(JsValues.JsConversionException.class)
                    .hasMessageContaining("self-referencing");
            return null;
        });
    }

    @Test
    @DisplayName("Should reject a huge JS array before allocating anything for it")
    void testFromJsRejectsHugeArray() {
        inContext((cx, scope) -> {
            Object huge = cx.evaluateString(scope, "new Array(2000000000)", "t", 1, null);

            assertThatThrownBy(() -> JsValues.fromJs(huge))
                    .isInstanceOf(JsValues.JsConversionException.class)
                    .hasMessageContaining("2000000000");
            return null;
        });
    }

    @Test
    @DisplayName("Should reject a BigInt result instead of silently rounding it to a double")
    void testFromJsRejectsBigInteger() {
        inContext((cx, scope) -> {
            Object bigInt = cx.evaluateString(scope, "(10n ** 30n)", "t", 1, null);

            assertThatThrownBy(() -> JsValues.fromJs(bigInt))
                    .isInstanceOf(JsValues.JsConversionException.class)
                    .hasMessageContaining("BigInt");
            return null;
        });
    }

    @Test
    @DisplayName("Should reject a JS Date result instead of returning an empty map")
    void testFromJsRejectsDate() {
        inContext((cx, scope) -> {
            Object date = cx.evaluateString(scope, "new Date(0)", "t", 1, null);

            assertThatThrownBy(() -> JsValues.fromJs(date))
                    .isInstanceOf(JsValues.JsConversionException.class)
                    .hasMessageContaining("Date");
            return null;
        });
    }

    @Test
    @DisplayName("Should reject a JS RegExp result instead of returning an empty map")
    void testFromJsRejectsRegExp() {
        inContext((cx, scope) -> {
            Object regExp = cx.evaluateString(scope, "/a/", "t", 1, null);

            assertThatThrownBy(() -> JsValues.fromJs(regExp))
                    .isInstanceOf(JsValues.JsConversionException.class)
                    .hasMessageContaining("RegExp");
            return null;
        });
    }

    @Test
    @DisplayName("Should populate a scope with no NativeJavaObject, which would unseal the sandbox")
    void testPopulatedScopeContainsNoNativeJavaObject() {
        inContext((cx, scope) -> {
            Map<String, Object> user = new HashMap<>();
            user.put("firstName", "ada");
            user.put("lastName", "lovelace");
            user.put("roles", List.of("admin", "user"));
            Map<String, Object> variables = Map.of("user", user, "count", 3, "active", true);

            variables.forEach((name, value) -> scope.put(name, scope, JsValues.toJs(cx, scope, value)));

            ScopeWalk walk = walkForNativeJavaObject(scope);
            // Anchors the negative assertion below to a walk known to have actually reached the
            // injected values - without this, a traversal that silently stopped early would
            // trivially pass the same way a correct one does.
            assertThat(walk.visitedLeaves()).contains("lovelace", "admin");
            assertThat(walk.foundNativeJavaObject()).isFalse();
            return null;
        });
    }

    private record ScopeWalk(boolean foundNativeJavaObject, Set<Object> visitedLeaves) {
    }

    /**
     * Walks a converted value graph looking for a {@code NativeJavaObject} - the wrapper Rhino
     * produces for a raw Java object, which the sandbox's {@code ClassShutter} cannot see through.
     * If {@link JsValues#toJs} ever started wrapping instead of converting, this would catch it.
     * Also records every leaf value the walk actually reached, so a caller can confirm the walk
     * covered what it was meant to cover rather than stopping early.
     */
    private static ScopeWalk walkForNativeJavaObject(Object value) {
        Deque<Object> queue = new ArrayDeque<>();
        queue.add(value);
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> leaves = new HashSet<>();
        boolean foundNativeJavaObject = false;
        while (!queue.isEmpty()) {
            Object current = queue.poll();
            if (current == null || !seen.add(current)) {
                continue;
            }
            if (current.getClass().getName().contains("NativeJavaObject")) {
                foundNativeJavaObject = true;
            }
            if (current instanceof NativeArray array) {
                for (int i = 0; i < array.getLength(); i++) {
                    queue.add(array.get(i, array));
                }
            } else if (current instanceof Scriptable scriptable) {
                for (Object id : scriptable.getIds()) {
                    queue.add(scriptable.get(String.valueOf(id), scriptable));
                }
            } else {
                leaves.add(current);
            }
        }
        return new ScopeWalk(foundNativeJavaObject, leaves);
    }
}
