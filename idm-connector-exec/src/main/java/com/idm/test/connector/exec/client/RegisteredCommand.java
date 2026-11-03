package com.idm.test.connector.exec.client;

/**
 * A command as it will run, with its definition as written and the hash of that: what an approval on
 * this machine must match before it runs (ADR 0008). Show {@link #asWritten()}, never {@link #definition()}:
 * the definition as it runs holds resolved secrets.
 */
public record RegisteredCommand(CommandDefinition definition, CommandDefinition asWritten, String hash) {

    /**
     * @param resolved  the definition with its secret references resolved, as it will run
     * @param asWritten the same definition as written in the settings, {@code ${NAME}} kept
     */
    public static RegisteredCommand of(CommandDefinition resolved, CommandDefinition asWritten) {
        return new RegisteredCommand(resolved, asWritten, DefinitionHash.of(asWritten));
    }
}
