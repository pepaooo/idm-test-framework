package com.idm.test.runner.ui;

import java.nio.file.Path;
import java.util.Locale;

/**
 * What one {@code idmtest ui} session knows: its token, the language it was started with, and the
 * workspace of {@code --workspace} (null when not given: the start page). A bean of the
 * workbench's Spring context; {@link #toString()} leaves the token out.
 */
public record UiSession(String token, Locale locale, Path workspace) {

    @Override
    public String toString() {
        return "UiSession[token=****, locale=" + locale + ", workspace=" + workspace + "]";
    }
}
