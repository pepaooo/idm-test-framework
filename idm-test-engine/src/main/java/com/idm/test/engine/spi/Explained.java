package com.idm.test.engine.spi;

import java.util.List;

/**
 * An expression's value, plus the notes its list helpers recorded when they returned false
 * ({@code containsAll: missing [VPN]}), in call order.
 */
public record Explained(Object value, List<String> notes) {

    public Explained {
        notes = List.copyOf(notes);
    }
}
