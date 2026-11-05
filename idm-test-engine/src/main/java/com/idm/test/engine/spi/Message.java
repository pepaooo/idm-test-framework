package com.idm.test.engine.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A user-facing message by key, with its arguments, so it can be shown in any supported
 * language (see {@code com.idm.test.engine.i18n.Messages}). Arguments are kept as plain values —
 * String, Number, Boolean, null — or a nested Message; anything else is turned into text when the
 * message is created, so results stay serializable.
 */
public record Message(String key, List<Object> args) {

    public Message {
        List<Object> plain = new ArrayList<>(args == null ? List.of() : args);
        plain.replaceAll(Message::plain);
        args = Collections.unmodifiableList(plain);
    }

    public static Message of(String key, Object... args) {
        List<Object> list = new ArrayList<>();
        if (args != null) {
            Collections.addAll(list, args);
        }
        return new Message(key, list);
    }

    /**
     * {@code args} as they were before being written as JSON: a nested message reads back as a map of
     * its {@code key} and {@code args}, and is turned into a {@link Message} again. Null stays null.
     */
    public static List<Object> revived(List<Object> args) {
        if (args == null) {
            return null;
        }
        List<Object> list = new ArrayList<>(args.size());
        for (Object arg : args) {
            list.add(revive(arg));
        }
        return list;
    }

    private static Object revive(Object value) {
        if (value instanceof java.util.Map<?, ?> map && map.size() == 2 && map.get("key") instanceof String key
                && map.get("args") instanceof List<?> nested) {
            @SuppressWarnings("unchecked")
            List<Object> inner = (List<Object>) nested;
            return new Message(key, revived(inner));
        }
        return value;
    }

    private static Object plain(Object value) {
        return value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Message ? value : String.valueOf(value);
    }
}
