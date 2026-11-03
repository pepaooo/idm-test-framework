package com.idm.test.engine.spi;

/**
 * Whether a registered command's definition is approved on this machine (ADR 0008). The exec steps ask
 * before they start anything, so a run that missed the real-environment guard still cannot run an
 * unapproved definition.
 */
@FunctionalInterface
public interface CommandApproval {

    /** Approves nothing: a runner without approvals refuses every command. */
    CommandApproval NONE = (command, hash) -> false;

    /**
     * @param command the command's registered name
     * @param hash    the SHA-256 of its definition as written
     * @return whether that exact definition is approved here
     */
    boolean approved(String command, String hash);
}
