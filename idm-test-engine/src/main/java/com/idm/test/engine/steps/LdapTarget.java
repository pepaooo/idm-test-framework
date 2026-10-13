package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;

import java.util.Map;
import java.util.Optional;

/**
 * The directory an ldap.* step works in: its {@code directory:} parameter, else the default.
 * An unknown directory, or none when there is no default, is a final failure: waiting cannot
 * configure a directory.
 */
final class LdapTarget {

    private final EdirectoryConnector connector;
    private final String directoryName;
    private final StepOutcome failure;

    private LdapTarget(EdirectoryConnector connector, String directoryName, StepOutcome failure) {
        this.connector = connector;
        this.directoryName = directoryName;
        this.failure = failure;
    }

    static LdapTarget of(String step, Map<String, Object> params, LdapDirectories directories) {
        Object requested = params.get("directory");
        String name;
        if (requested != null) {
            name = String.valueOf(requested);
        } else {
            Optional<String> fallback = directories.defaultName();
            if (fallback.isEmpty()) {
                return failed(Message.of("step.ldap.target.noDefault", step, directories.names()));
            }
            name = fallback.get();
        }
        Optional<EdirectoryConnector> connector = directories.connector(name);
        if (connector.isEmpty()) {
            return failed(Message.of("step.ldap.target.unknown", step, name, directories.names()));
        }
        return new LdapTarget(connector.get(), directories.names().size() > 1 ? name : null, null);
    }

    /** The {@code directory:} the step named, or null when it relies on the default. */
    static String requested(Map<String, Object> params) {
        Object directory = params.get("directory");
        return directory == null ? null : String.valueOf(directory);
    }

    private static LdapTarget failed(Message message) {
        return new LdapTarget(null, null, StepOutcome.finalFailure(message));
    }

    boolean isFailure() {
        return failure != null;
    }

    StepOutcome failure() {
        return failure;
    }

    EdirectoryConnector connector() {
        return connector;
    }

    /** The DN as messages show it: with the directory's name when more than one is configured. */
    Object at(String dn) {
        return directoryName == null ? dn : Message.of("step.ldap.target.inDirectory", dn, directoryName);
    }
}
