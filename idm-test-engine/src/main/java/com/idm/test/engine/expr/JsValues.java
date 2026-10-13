package com.idm.test.engine.expr;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.Message;
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

    /**
     * Total nodes visited across one whole {@link #toJs} or {@link #fromJs} walk, independent of
     * depth or array length. Depth alone cannot bound a value built by repeatedly duplicating
     * both branches of a tree — {@code var a = {}; for (i = 0; i < 40; i++) a = {x: a, y: a};} is
     * acyclic and only 40 levels deep, and only ~40 distinct JS objects, yet walking it as a tree
     * (which is what the cycle-detecting recursion below does) visits 2^40 nodes and exhausts
     * memory well within the instruction budget. A million nodes is already far more than any
     * legitimate scenario value would contain, so it fails fast long before that happens.
     */
    private static final int MAX_NODES = 1_000_000;

    private JsValues() {
    }

    /**
     * Converts a Java value into a native JS value. Maps become JS objects, lists become JS
     * arrays, and everything else is passed through.
     *
     * @throws JsConversionException if the value nests too deeply, contains a cycle, or has too
     *                                many total nodes
     */
    static Object toJs(Context cx, Scriptable scope, Object value) {
        return toJs(cx, scope, value, Collections.newSetFromMap(new IdentityHashMap<>()), 0, new NodeBudget());
    }

    private static Object toJs(
            Context cx, Scriptable scope, Object value, Set<Object> ancestors, int depth, NodeBudget budget) {
        budget.visit();
        if (depth > MAX_DEPTH) {
            throw new JsConversionException(Message.of("engine.js.tooDeep", MAX_DEPTH));
        }
        if (value instanceof Map<?, ?> map) {
            if (!ancestors.add(map)) {
                throw new JsConversionException(Message.of("engine.js.selfMap"));
            }
            try {
                Scriptable object = cx.newObject(scope);
                map.forEach((key, entry) -> ScriptableObject.putProperty(
                        object, String.valueOf(key), toJs(cx, scope, entry, ancestors, depth + 1, budget)));
                return object;
            } finally {
                ancestors.remove(map);
            }
        }
        if (value instanceof List<?> list) {
            if (!ancestors.add(list)) {
                throw new JsConversionException(Message.of("engine.js.selfList"));
            }
            try {
                Object[] items = new Object[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    items[i] = toJs(cx, scope, list.get(i), ancestors, depth + 1, budget);
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
     * @throws JsConversionException if the result nests too deeply, contains a cycle, has too
     *                                many total nodes, is an array beyond the size cap, is a
     *                                BigInt, is a JS value (such as a Date or RegExp) with no
     *                                plain Java equivalent, or contains {@code undefined} anywhere
     *                                (including an array hole)
     */
    static Object fromJs(Object value) {
        return fromJs(value, "result", Collections.newSetFromMap(new IdentityHashMap<>()), 0, new NodeBudget());
    }

    /**
     * @param path where {@code value} sits in the result, e.g. {@code result.items[2]}, so an
     *             error can point at the exact spot
     */
    private static Object fromJs(
            Object value, String path, Set<Object> ancestors, int depth, NodeBudget budget) {
        // JavaScript reads a missing property as undefined rather than failing, so a typo like
        // user.userid would otherwise become a silent null — and a null parameter can make a
        // step, or a cleanup DELETE, quietly do nothing. An array hole reads as NOT_FOUND, which
        // JavaScript also treats as undefined. Explicit null is a deliberate value and passes.
        if (value instanceof Undefined || value == Scriptable.NOT_FOUND) {
            throw new JsConversionException(Message.of("engine.js.undefined", path));
        }
        if (value == null) {
            return null;
        }
        budget.visit();
        if (depth > MAX_DEPTH) {
            throw new JsConversionException(Message.of("engine.js.resultTooDeep", MAX_DEPTH));
        }
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            throw new JsConversionException(Message.of("engine.js.bigInt", value));
        }
        // Rhino represents whole-number literals as Integer/Long internally for performance,
        // even though JavaScript itself has only one numeric type. Normalise here so callers
        // never have to guess which boxed type a given number came back as.
        if (value instanceof Number number && !(value instanceof Double)) {
            return number.doubleValue();
        }
        if (value instanceof NativeArray array) {
            if (!ancestors.add(array)) {
                throw new JsConversionException(Message.of("engine.js.selfArray"));
            }
            try {
                long length = array.getLength();
                if (length > MAX_ARRAY_LENGTH) {
                    throw new JsConversionException(Message.of("engine.js.arrayTooLong", length, MAX_ARRAY_LENGTH));
                }
                List<Object> list = new ArrayList<>((int) length);
                for (int i = 0; i < length; i++) {
                    list.add(fromJs(array.get(i, array), path + "[" + i + "]", ancestors, depth + 1, budget));
                }
                return list;
            } finally {
                ancestors.remove(array);
            }
        }
        if (value instanceof Scriptable object) {
            if (!isPlainObject(object)) {
                throw new JsConversionException(Message.of("engine.js.noJavaEquivalent", object.getClassName()));
            }
            if (!ancestors.add(object)) {
                throw new JsConversionException(Message.of("engine.js.selfObject"));
            }
            try {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Object id : object.getIds()) {
                    String key = String.valueOf(id);
                    Object property = id instanceof Integer index ? object.get(index, object) : object.get(key, object);
                    map.put(key, fromJs(property, path + "." + key, ancestors, depth + 1, budget));
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

        private final transient Message detail;

        JsConversionException(Message detail) {
            super(Messages.english(detail));
            this.detail = detail;
        }

        /** The problem by key, so it can be shown in the run's language. */
        Message detail() {
            return detail;
        }
    }

    /**
     * Counts nodes visited across one entire {@link #toJs} or {@link #fromJs} walk. Must be
     * created once per top-level call and threaded through every recursive call by reference -
     * a counter allocated fresh per branch (e.g. one per map entry or array element) would reset
     * to zero on every branch and never notice that the branches together add up to an explosion.
     */
    private static final class NodeBudget {
        private int visited = 0;

        void visit() {
            if (++visited > MAX_NODES) {
                throw new JsConversionException(Message.of("engine.js.tooManyNodes", MAX_NODES));
            }
        }
    }
}
