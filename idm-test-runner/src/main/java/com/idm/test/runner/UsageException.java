package com.idm.test.runner;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.Message;

/** The command line is wrong; {@link #detail()} can be shown in the user's language. */
public class UsageException extends RuntimeException {

    private final transient Message detail;

    public UsageException(Message detail) {
        super(Messages.english(detail));
        this.detail = detail;
    }

    public Message detail() {
        return detail;
    }
}
