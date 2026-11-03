package com.idm.test.connector.exec.client;

/**
 * A command as it will run, with the hash of its definition as written: what an approval on this
 * machine must match before it runs (ADR 0008).
 */
public record RegisteredCommand(CommandDefinition definition, String hash) {

    /**
     * @param resolved  the definition with its secret references resolved, as it will run
     * @param asWritten the same definition as written in the settings, {@code ${NAME}} kept
     */
    public static RegisteredCommand of(CommandDefinition resolved, CommandDefinition asWritten) {
        return new RegisteredCommand(resolved, DefinitionHash.of(asWritten));
    }
}
