package com.idm.test.engine.i18n;

import com.idm.test.engine.spi.Message;

/**
 * An {@link IllegalArgumentException} whose message is a {@link Message}, so whoever reports it
 * can show it in the run's language. {@link #getMessage()} is the English text, exactly what the
 * plain exception said before (ITF-46). Reported as an {@code IllegalArgumentException}: see
 * {@link #typeName(Throwable)}.
 */
public class KeyedArgumentException extends IllegalArgumentException {

    private final transient Message detail;

    public KeyedArgumentException(Message detail) {
        super(Messages.english(detail));
        this.detail = detail;
    }

    public KeyedArgumentException(Message detail, Throwable cause) {
        super(Messages.english(detail), cause);
        this.detail = detail;
    }

    public Message detail() {
        return detail;
    }

    /**
     * The exception's type as a message names it: {@code IllegalArgumentException} for this
     * class, as it always was before messages had keys, otherwise the class's simple name.
     */
    public static String typeName(Throwable e) {
        return e instanceof KeyedArgumentException
                ? IllegalArgumentException.class.getSimpleName() : e.getClass().getSimpleName();
    }
}
