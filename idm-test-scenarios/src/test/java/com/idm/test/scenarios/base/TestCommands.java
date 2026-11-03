package com.idm.test.scenarios.base;

import com.idm.test.connector.exec.client.CommandConnector;
import com.idm.test.connector.exec.client.RegisteredCommand;
import com.idm.test.engine.spi.CommandApproval;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The registered command the test suites run, and the approval they give it (ADR 0008). A test JVM
 * keeps no approvals file: it approves the named commands, but only with the definition the context
 * bound, so a test cannot run a definition it did not register.
 */
public final class TestCommands {

    /** Prints the test JVM's version on stderr and exits 0 (application-test.yml). */
    public static final String JAVA_VERSION = "java-version";

    /** Where the runners set {@code java-version}'s executable: it depends on the JVM running the tests. */
    public static final String EXECUTABLE_PROPERTY = "idm.test.commands." + JAVA_VERSION + ".executable";

    private TestCommands() {
    }

    /** The {@code java} of the JVM running the tests, by absolute path, so no PATH lookup is involved. */
    public static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    /**
     * Approves each of {@code names} with the definition {@code connector} holds for it when asked, and
     * nothing else. The connector is read lazily because Spring injects it after the field initialisers.
     */
    public static CommandApproval approving(Supplier<CommandConnector> connector, String... names) {
        Set<String> approved = Set.of(names);
        return (command, hash) -> approved.contains(command)
                && connector.get().command(command).map(RegisteredCommand::hash).filter(hash::equals).isPresent();
    }
}
