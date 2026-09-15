package com.idm.test.engine.expr;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between Java values and native JavaScript values.
 * <p>
 * This class is what makes a total sandbox possible. The obvious approach — handing Rhino a
 * Java {@code Map} via {@code Context.javaToJS} — fails once every Java class is denied:
 * wrapping the map itself requires access to {@code java.util.ImmutableCollections$Map1}.
 * Converting to native {@link Scriptable} objects instead means no Java class is ever exposed,
 * so the shutter can refuse all of them.
 */
final class JsValues {

    private JsValues() {
    }

    /**
     * Converts a Java value into a native JS value. Maps become JS objects, lists become JS
     * arrays, and everything else is passed through.
     */
    static Object toJs(Context cx, Scriptable scope, Object value) {
        if (value instanceof Map<?, ?> map) {
            Scriptable object = cx.newObject(scope);
            map.forEach((key, entry) ->
                    ScriptableObject.putProperty(object, String.valueOf(key), toJs(cx, scope, entry)));
            return object;
        }
        if (value instanceof List<?> list) {
            Object[] items = new Object[list.size()];
            for (int i = 0; i < list.size(); i++) {
                items[i] = toJs(cx, scope, list.get(i));
            }
            return cx.newArray(scope, items);
        }
        return value;
    }

    /**
     * Converts a JS result back into plain Java values, so nothing Rhino-specific escapes into
     * the rest of the engine. JS numbers arrive as {@link Double}; that is JavaScript's only
     * numeric type and is not hidden here.
     */
    static Object fromJs(Object value) {
        if (value == null || value instanceof Undefined || value == Scriptable.NOT_FOUND) {
            return null;
        }
        // Rhino represents whole-number literals as Integer/Long internally for performance,
        // even though JavaScript itself has only one numeric type. Normalise here so callers
        // never have to guess which boxed type a given number came back as.
        if (value instanceof Number number && !(value instanceof Double)) {
            return number.doubleValue();
        }
        if (value instanceof NativeArray array) {
            List<Object> list = new ArrayList<>((int) array.getLength());
            for (int i = 0; i < array.getLength(); i++) {
                list.add(fromJs(array.get(i, array)));
            }
            return list;
        }
        if (value instanceof Scriptable object) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Object id : object.getIds()) {
                String key = String.valueOf(id);
                map.put(key, fromJs(object.get(key, object)));
            }
            return map;
        }
        return value;
    }
}
