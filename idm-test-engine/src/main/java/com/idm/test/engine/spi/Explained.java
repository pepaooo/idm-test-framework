package com.idm.test.engine.spi;

import com.idm.test.engine.i18n.Messages;

import java.util.List;

/**
 * An expression's value, plus the notes its list helpers recorded when they returned false
 * ({@code containsAll: missing [VPN]}), in call order, by key so they read in any language.
 */
public record Explained(Object value, List<Message> details) {

    public Explained {
        details = List.copyOf(details);
    }

    /** The notes in English. */
    public List<String> notes() {
        return details.stream().map(Messages::english).toList();
    }
}
