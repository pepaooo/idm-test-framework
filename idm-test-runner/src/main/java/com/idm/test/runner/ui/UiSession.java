package com.idm.test.runner.ui;

import java.util.Locale;

/**
 * What one {@code idmtest ui} session knows: its token and the language it was started with.
 * A bean of the workbench's Spring context; {@link #toString()} leaves the token out.
 */
public record UiSession(String token, Locale locale) {

    @Override
    public String toString() {
        return "UiSession[token=****, locale=" + locale + "]";
    }
}
