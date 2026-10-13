package com.idm.test.engine.expr;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.result.Secrets;
import com.idm.test.engine.spi.Message;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The list helpers every expression can call: {@code containsAll(list, values)},
 * {@code excludesAll(list, values)} and {@code sameMembers(list, values)}.
 * <p>
 * They are written in Java and defined into each evaluation's scope as Rhino function objects,
 * so they work on the JavaScript values directly and never expose a Java object or class to the
 * expression (the sandbox's ClassShutter still denies every class). Being Java, their work is not
 * counted against the instruction budget, which is what lets a list of thousands of values be
 * checked; a per-evaluation node cap and depth cap stand in for the budget instead.
 * <p>
 * <strong>Arguments.</strong> Each is a single value or an array. A {@code null} or
 * {@code undefined} list is an empty list. A {@code null} or {@code undefined} values argument is
 * an error ({@code containsAll: the values to look for are undefined}), because it is almost always
 * a misspelled name, and treating it as empty would make {@code containsAll} and
 * {@code excludesAll} pass without comparing anything.
 * <p>
 * <strong>Equality</strong> is by canonical text:
 * <ul>
 *   <li>a top-level primitive (and a Date, RegExp or function) is its JavaScript {@code String(v)},
 *       so {@code 42} equals {@code '42'} and {@code true} equals {@code 'true'}</li>
 *   <li>inside an array or object, a primitive is that text JSON-quoted, so {@code 1} still equals
 *       {@code '1'} at every depth but {@code ['a,b']} differs from {@code ['a', 'b']}</li>
 *   <li>an array is a U+0001 marker, {@code [}, its elements' text in order and {@code ]}; an
 *       object is the marker, <code>{</code>, its keys sorted, each JSON-quoted with its value's
 *       text, and <code>}</code>; the marker keeps {@code [1]} apart from the string
 *       {@code '[1]'}</li>
 * </ul>
 * The texts live in a Java set, so prototype names such as {@code constructor} or
 * {@code __proto__} are ordinary values. Duplicates are ignored.
 * <p>
 * <strong>Notes.</strong> A helper that returns false adds one note to this evaluation's notes, read
 * back by {@link #notes()}: {@code containsAll: missing [..]}, {@code excludesAll: found [..]},
 * {@code sameMembers: missing [..]; unexpected [..]}; at most ten values, then {@code … N more}.
 * Top-level strings show as themselves, nested strings JSON-quoted, objects as
 * {@code {key: value}} with values under secret-looking keys shown as {@code ***}.
 */
final class ListHelpers {

    /** Values in one evaluation's helper calls, together; far beyond any real list. */
    static final int MAX_NODES = 1_000_000;

    /** How deeply a compared value may nest; also what stops a self-referencing value. */
    static final int MAX_DEPTH = 100;

    private static final int SHOWN = 10;

    private final List<Message> notes = new ArrayList<>();
    private int nodes;
    private Message failure;

    /** Defines the three helpers in {@code scope}; the returned instance collects their notes. */
    static ListHelpers defineIn(Context cx, Scriptable scope) {
        ListHelpers helpers = new ListHelpers();
        helpers.define(cx, scope, "containsAll", helpers::containsAll);
        helpers.define(cx, scope, "excludesAll", helpers::excludesAll);
        helpers.define(cx, scope, "sameMembers", helpers::sameMembers);
        return helpers;
    }

    /** The notes recorded so far, in call order. */
    List<Message> notes() {
        return new ArrayList<>(notes);
    }

    /** The last error a helper raised, by key, or null; its text is inside the error JavaScript reports. */
    Message failure() {
        return failure;
    }

    private interface Helper {
        boolean apply(String name, Object list, Object values);
    }

    private void define(Context cx, Scriptable scope, String name, Helper helper) {
        BaseFunction function = new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                Object list = args.length > 0 ? args[0] : Undefined.instance;
                Object values = args.length > 1 ? args[1] : Undefined.instance;
                if (isAbsent(values)) {
                    throw error(Message.of("engine.list.undefinedValues", name));
                }
                return helper.apply(name, list, values);
            }

            @Override
            public String getFunctionName() {
                return name;
            }

            @Override
            public int getArity() {
                return 2;
            }

            @Override
            public int getLength() {
                return 2;
            }
        };
        ScriptRuntime.setFunctionProtoAndParent(function, cx, scope);
        ScriptableObject.defineProperty(scope, name, function, ScriptableObject.DONTENUM);
    }

    private boolean containsAll(String name, Object list, Object values) {
        List<Object> missing = select(name, asList(name, values), asList(name, list), false);
        if (missing.isEmpty()) {
            return true;
        }
        notes.add(Message.of("engine.list.missing", name, show(missing)));
        return false;
    }

    private boolean excludesAll(String name, Object list, Object values) {
        List<Object> found = select(name, asList(name, values), asList(name, list), true);
        if (found.isEmpty()) {
            return true;
        }
        notes.add(Message.of("engine.list.found", name, show(found)));
        return false;
    }

    private boolean sameMembers(String name, Object list, Object values) {
        List<Object> a = asList(name, list);
        List<Object> b = asList(name, values);
        List<Object> missing = select(name, b, a, false);
        List<Object> unexpected = select(name, a, b, false);
        if (missing.isEmpty() && unexpected.isEmpty()) {
            return true;
        }
        if (unexpected.isEmpty()) {
            notes.add(Message.of("engine.list.missing", name, show(missing)));
        } else if (missing.isEmpty()) {
            notes.add(Message.of("engine.list.unexpected", name, show(unexpected)));
        } else {
            notes.add(Message.of("engine.list.missingUnexpected", name, show(missing), show(unexpected)));
        }
        return false;
    }

    /** The distinct values of {@code from} that are ({@code wantPresent}) or are not in {@code against}. */
    private List<Object> select(String name, List<Object> from, List<Object> against, boolean wantPresent) {
        Set<String> known = new HashSet<>();
        for (Object value : against) {
            known.add(key(name, value, false, 0));
        }
        Set<String> seen = new HashSet<>();
        List<Object> out = new ArrayList<>();
        for (Object value : from) {
            String key = key(name, value, false, 0);
            if (seen.add(key) && known.contains(key) == wantPresent) {
                out.add(value);
            }
        }
        return out;
    }

    private List<Object> asList(String name, Object value) {
        if (isAbsent(value)) {
            return List.of();
        }
        if (value instanceof NativeArray array) {
            long length = array.getLength();
            if (length > MAX_NODES) {
                throw tooLarge(name);
            }
            List<Object> elements = new ArrayList<>((int) length);
            for (int i = 0; i < length; i++) {
                elements.add(element(array, i));
            }
            return elements;
        }
        return List.of(value);
    }

    private String key(String name, Object value, boolean nested, int depth) {
        visit(name, depth);
        if (value instanceof NativeArray array) {
            StringBuilder text = new StringBuilder("\u0001[");
            long length = array.getLength();
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    text.append(',');
                }
                text.append(key(name, element(array, i), true, depth + 1));
            }
            return text.append(']').toString();
        }
        if (value instanceof Scriptable object && isObject(object)) {
            StringBuilder text = new StringBuilder("\u0001{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : entries(object).entrySet()) {
                if (!first) {
                    text.append(',');
                }
                first = false;
                text.append(jsonQuote(entry.getKey())).append(':')
                        .append(key(name, entry.getValue(), true, depth + 1));
            }
            return text.append('}').toString();
        }
        String text = ScriptRuntime.toString(value);
        return nested ? jsonQuote(text) : text;
    }

    private String display(Object value, boolean nested, int depth) {
        if (value instanceof NativeArray array) {
            List<String> parts = new ArrayList<>();
            long length = array.getLength();
            for (int i = 0; i < length; i++) {
                parts.add(display(element(array, i), true, depth + 1));
            }
            return "[" + String.join(", ", parts) + "]";
        }
        if (value instanceof Scriptable object && isObject(object)) {
            List<String> parts = new ArrayList<>();
            entries(object).forEach((k, v) -> parts.add(
                    k + ": " + (Secrets.isSecret(k) ? Secrets.MASK : display(v, true, depth + 1))));
            return "{" + String.join(", ", parts) + "}";
        }
        return nested && value instanceof CharSequence text
                ? jsonQuote(text.toString())
                : ScriptRuntime.toString(value);
    }

    /** The values as {@code [a, b]}, or past ten a message ending in how many more. */
    private Object show(List<Object> values) {
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(SHOWN, values.size()); i++) {
            shown.add(display(values.get(i), false, 0));
        }
        if (values.size() > SHOWN) {
            return Message.of("engine.list.more", String.join(", ", shown), values.size() - SHOWN);
        }
        return "[" + String.join(", ", shown) + "]";
    }

    /** An object's own enumerable string keys, sorted as JavaScript's sort() would, with their values. */
    private static Map<String, Object> entries(Scriptable object) {
        Object[] ids = object.getIds();
        String[] keys = new String[ids.length];
        int count = 0;
        for (Object id : ids) {
            if (id instanceof Integer || id instanceof CharSequence) {
                keys[count++] = String.valueOf(id);
            }
        }
        keys = Arrays.copyOf(keys, count);
        Arrays.sort(keys);
        Map<String, Object> entries = new LinkedHashMap<>();
        for (String k : keys) {
            Object v = ScriptableObject.getProperty(object, k);
            entries.put(k, v == Scriptable.NOT_FOUND ? Undefined.instance : v);
        }
        return entries;
    }

    private static Object element(NativeArray array, int index) {
        Object value = ScriptableObject.getProperty(array, index);
        return value == Scriptable.NOT_FOUND ? Undefined.instance : value;
    }

    /** A plain object, compared by content; a Date, RegExp or function compares by its text. */
    private static boolean isObject(Scriptable object) {
        if (object instanceof Callable) {
            return false;
        }
        String className = object.getClassName();
        return !"Date".equals(className) && !"RegExp".equals(className);
    }

    private static boolean isAbsent(Object value) {
        return value == null || value instanceof Undefined || value == Scriptable.NOT_FOUND;
    }

    private void visit(String name, int depth) {
        if (depth > MAX_DEPTH) {
            throw error(Message.of("engine.list.tooDeep", name, MAX_DEPTH));
        }
        if (++nodes > MAX_NODES) {
            throw tooLarge(name);
        }
    }

    private RuntimeException tooLarge(String name) {
        return error(Message.of("engine.list.tooLarge", name, MAX_NODES));
    }

    /** A JavaScript Error with {@code problem}'s English text; {@link #failure()} keeps it by key. */
    private RuntimeException error(Message problem) {
        failure = problem;
        return ScriptRuntime.constructError("Error", Messages.english(problem));
    }

    /** The string as JSON.stringify would quote it. */
    private static String jsonQuote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
