package com.idm.test.connector.exec.client;

/** Reads one byte of stdin and prints what it got: {@code read -1} when stdin is closed. */
public final class StdinReader {

    private StdinReader() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("read " + System.in.read());
    }
}
