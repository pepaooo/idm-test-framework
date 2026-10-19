package com.idm.test.runner.ui;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What one {@code idmtest ui} session knows: its token, the language it was started with, and the
 * current workspace — the one of {@code --workspace} at first (null when not given: the start
 * page), then whichever the start page opens. A bean of the workbench's Spring context, shared by
 * every request thread; {@link #toString()} leaves the token out.
 */
public final class UiSession {

    private final String token;
    private final Locale locale;
    private final AtomicReference<Path> workspace;

    public UiSession(String token, Locale locale, Path workspace) {
        this.token = token;
        this.locale = locale;
        this.workspace = new AtomicReference<>(workspace);
    }

    public String token() {
        return token;
    }

    public Locale locale() {
        return locale;
    }

    /** The current workspace's folder, or null while none is open. */
    public Path workspace() {
        return workspace.get();
    }

    /** Makes {@code folder} the current workspace (files, lab and runs act on it from now on). */
    public void open(Path folder) {
        workspace.set(folder);
    }

    @Override
    public String toString() {
        return "UiSession[token=****, locale=" + locale + ", workspace=" + workspace.get() + "]";
    }
}
