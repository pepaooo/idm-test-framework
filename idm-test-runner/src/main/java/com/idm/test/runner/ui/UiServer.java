package com.idm.test.runner.ui;

/**
 * A running workbench server: the port it listens on (on {@code 127.0.0.1}), its session token,
 * how to stop it, and its session. {@link #toString()} leaves the token out.
 */
public record UiServer(int port, String token, AutoCloseable stop, UiSession session) implements AutoCloseable {

    @Override
    public void close() throws Exception {
        stop.close();
    }

    @Override
    public String toString() {
        return "UiServer[port=" + port + ", token=****]";
    }
}
