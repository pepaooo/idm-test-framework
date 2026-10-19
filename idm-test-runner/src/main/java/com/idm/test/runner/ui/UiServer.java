package com.idm.test.runner.ui;

/**
 * A running workbench server: the port it listens on (on {@code 127.0.0.1}), its session token,
 * and how to stop it. {@link #toString()} leaves the token out.
 */
public record UiServer(int port, String token, AutoCloseable stop) implements AutoCloseable {

    @Override
    public void close() throws Exception {
        stop.close();
    }

    @Override
    public String toString() {
        return "UiServer[port=" + port + ", token=****]";
    }
}
