package com.idm.test.engine.expr;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts between Java values and native JavaScript values.
 * <p>
 * This class is what makes a total sandbox possible. The obvious approach — handing Rhino a
 * Java {@code Map} via {@code Context.javaToJS} — fails once every Java class is denied:
 * wrapping the map itself requires access to {@code java.util.ImmutableCollections$Map1}.
 * Converting to native {@link Scriptable} objects instead means no Java class is ever exposed,
 * so the shutter can refuse all of them.
 * <p>
 * Both directions walk the value graph recursively, so both guard against the same two hazards:
 * a structure that cycles back on itself (which would otherwise overflow the stack) and a
 * structure that is simply too large to be a legitimate scenario value. Those guards run as part
 * of the walk itself, which matters because the walk happens <em>after</em> {@code evaluateString}
 * returns — the instruction budget that bounds a runaway expression has no reach here at all.
 */
final class JsValues {

    /**
     * Real scenario values (variables handed in, or JSON-ish results computed by an expression)
     * nest a handful of levels deep at most. A cap of 100 leaves generous headroom for any
     * legitimate structure while still failing fast — via a clear exception — long before a
     * self-referencing or pathologically deep structure could overflow the JVM stack.
     */
    private static final int MAX_DEPTH = 100;

    /**
     * Scenario arrays are configuration-sized (role lists, entitlement sets, and the like).
     * A million elements is already far beyond anything a real scenario would need, so treating
     * it as a hard cap catches a mistake like {@code new Array(2_000_000_000)} before a single
     * byte is allocated for it, rather than after the JVM has tried and failed to find the memory.
     */
    private static final int MAX_ARRAY_LENGTH = 1_000_000;

    private JsValues() {
    }

    /**
     * Converts a Java value into a native JS value. Maps become JS objects, lists become JS
     * arrays, and everything else is passed through.
     *
     * @throws JsConversionException if the value nests too deeply or contains a cycle
     */
    static Object toJs(Context cx, Scriptable scope, Object value) {
        return toJs(cx, scope, value, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static Object toJs(Context cx, Scriptable scope, Object value, Set<Object> ancestors, int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsConversionException(
                    "value is nested more than " + MAX_DEPTH + " levels deep; is it self-referencing?");
        }
        if (value instanceof Map<?, ?> map) {
            if (!ancestors.add(map)) {
                throw new JsConversionException("value contains a self-referencing map");
            }
            try {
                Scriptable object = cx.newObject(scope);
                map.forEach((key, entry) -> ScriptableObject.putProperty(
                        object, String.valueOf(key), toJs(cx, scope, entry, ancestors, depth + 1)));
                return object;
            } finally {
                ancestors.remove(map);
            }
        }
        if (value instanceof List<?> list) {
            if (!ancestors.add(list)) {
                throw new JsConversionException("value contains a self-referencing list");
            }
            try {
                Object[] items = new Object[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    items[i] = toJs(cx, scope, list.get(i), ancestors, depth + 1);
                }
                return cx.newArray(scope, items);
            } finally {
                ancestors.remove(list);
            }
        }
        return value;
    }

    /**
     * Converts a JS result back into plain Java values, so nothing Rhino-specific escapes into
     * the rest of the engine. JS numbers arrive as {@link Double} — that is JavaScript's only
     * numeric type — except for a {@link BigInteger} or {@link BigDecimal}, which Rhino also
     * reports as a {@link Number} but which cannot be narrowed to a double without silently
     * losing precision; those are rejected instead of being hidden here.
     *
     * @throws JsConversionException if the result nests too deeply, contains a cycle, is an
     *                                array beyond the size cap, is a BigInt, or is a JS value
     *                                (such as a Date or RegExp) with no plain Java equivalent
     */
    static Object fromJs(Object value) {
        return fromJs(value, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static Object fromJs(Object value, Set<Object> ancestors, int depth) {
        if (value == null || value instanceof Undefined || value == Scriptable.NOT_FOUND) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            throw new JsConversionException(
                    "result is nested more than " + MAX_DEPTH + " levels deep; is it self-referencing?");
        }
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            throw new JsConversionException(
                    "result is a BigInt (" + value + ") and cannot be represented as a JavaScript number "
                            + "without losing precision; convert it to a string in the expression instead, "
                            + "e.g. with .toString()");
        }
        // Rhino represents whole-number literals as Integer/Long internally for performance,
        // even though JavaScript itself has only one numeric type. Normalise here so callers
        // never have to guess which boxed type a given number came back as.
        if (value instanceof Number number && !(value instanceof Double)) {
            return number.doubleValue();
        }
        if (value instanceof NativeArray array) {
            if (!ancestors.add(array)) {
                throw new JsConversionException("result contains a self-referencing array");
            }
            try {
                long length = array.getLength();
                if (length > MAX_ARRAY_LENGTH) {
                    throw new JsConversionException(
                            "array has " + length + " elements, over the limit of " + MAX_ARRAY_LENGTH);
                }
                List<Object> list = new ArrayList<>((int) length);
                for (int i = 0; i < length; i++) {
                    list.add(fromJs(array.get(i, array), ancestors, depth + 1));
                }
                return list;
            } finally {
                ancestors.remove(array);
            }
        }
        if (value instanceof Scriptable object) {
            if (!isPlainObject(object)) {
                throw new JsConversionException(
                        "result is a JS " + object.getClassName() + ", which has no plain Java equivalent; "
                                + "convert it in the expression instead, e.g. call .toISOString() on a Date");
            }
            if (!ancestors.add(object)) {
                throw new JsConversionException("result contains a self-referencing object");
            }
            try {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Object id : object.getIds()) {
                    String key = String.valueOf(id);
                    map.put(key, fromJs(object.get(key, object), ancestors, depth + 1));
                }
                return map;
            } finally {
                ancestors.remove(object);
            }
        }
        return value;
    }

    /**
     * True for a plain {@code {}}-style object, false for a JS value that carries behaviour or
     * semantics a {@code Map} can't express — a Date, a RegExp, a Function, and so on.
     */
    private static boolean isPlainObject(Scriptable object) {
        return "Object".equals(object.getClassName());
    }

    /**
     * A Java value could not be converted to JavaScript, or a JS value could not be converted
     * back to Java. Always a sign the value itself is the problem (too deep, cyclic, too large,
     * or of a kind with no plain Java equivalent) rather than a bug in the conversion. Caught and
     * re-thrown by {@link ExpressionEvaluator} as an {@link ExpressionEvaluator.ExpressionException}
     * carrying the expression text, since this class never sees the expression itself.
     */
    static final class JsConversionException extends RuntimeException {
        JsConversionException(String message) {
            super(message);
        }
    }
}
