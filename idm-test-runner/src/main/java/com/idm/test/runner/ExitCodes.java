package com.idm.test.runner;

/** The process exit codes of {@code idmtest}. */
public final class ExitCodes {

    /** Everything selected passed or validated. */
    public static final int OK = 0;
    /** At least one scenario failed or did not validate. */
    public static final int FAILED = 1;
    /** The command could not start (usage error, bad workspace or settings). */
    public static final int CANNOT_START = 2;

    private ExitCodes() {
    }
}
