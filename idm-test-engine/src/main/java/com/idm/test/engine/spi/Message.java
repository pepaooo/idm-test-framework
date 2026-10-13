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

    private static Object plain(Object value) {
        return value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Message ? value : String.valueOf(value);
    }
}
