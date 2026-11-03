package com.idm.test.runner.commands;

import com.idm.test.connector.exec.client.RegisteredCommand;
import com.idm.test.runner.Workspace;

import java.nio.file.Path;
import java.util.Map;

/** Lets tests outside this package read a profile's commands as {@code idmtest commands} does. */
public final class CommandsCommandAccess {

    private CommandsCommandAccess() {
    }

    public static Map<String, RegisteredCommand> load(Path workspace, String profile) {
        return CommandsCommand.load(Workspace.open(workspace), profile);
    }
}
