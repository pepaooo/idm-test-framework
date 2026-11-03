package com.idm.test.connector.file.client;

/** The record separator {@code file.write} uses. Reading accepts either. */
public enum LineEnding {
    CRLF("\r\n"),
    LF("\n");

    private final String separator;

    LineEnding(String separator) {
        this.separator = separator;
    }

    public String separator() {
        return separator;
    }
}
