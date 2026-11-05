package com.idm.test.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IdmTest entry point")
class IdmTestTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int run(String... args) {
        return IdmTest.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Should print the version and exit 0")
    void testVersion() {
        assertThat(run("--version")).isEqualTo(ExitCodes.OK);
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).matches("(?s)idmtest \\d+\\.\\d+.*");
    }

    @Test
    @DisplayName("Should print every command and option for --help, its apostrophes intact, and exit 0")
    void testHelp() {
        assertThat(run("--help")).isEqualTo(ExitCodes.OK);
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).contains("init", "validate", "run", "ui", "trust import",
                "trust import-server <host:port>",
                "commands list|approve|revoke", "the profile's registered commands", "--yes");
    }

    @Test
    @DisplayName("Should print a usage error on stderr and exit 2")
    void testUsageError() {
        assertThat(run("deploy")).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("unknown command 'deploy'");
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @DisplayName("Should print the usage error in Spanish when --locale es is given")
    void testSpanishUsageError() {
        assertThat(run("deploy", "--locale", "es")).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("comando desconocido 'deploy'");
    }

    @Test
    @DisplayName("Should report an I/O failure on one line, with the stack trace only for --verbose")
    void testIoErrorOneLine(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        java.nio.file.Files.writeString(temp.resolve("scenarios"), "a file, not a folder");

        assertThat(run("init", temp.toString())).isEqualTo(ExitCodes.CANNOT_START);
        String quiet = errBytes.toString(StandardCharsets.UTF_8);
        assertThat(quiet).startsWith("cannot complete 'init': ").doesNotContain("\tat ");
        assertThat(quiet.strip().lines()).hasSize(1);

        errBytes.reset();
        assertThat(run("init", temp.toString(), "--verbose")).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("cannot complete 'init': ", "\tat ");
    }

    @Test
    @DisplayName("Should select the per-user truststore before run and leave an explicit JVM setting alone")
    void testUserTruststoreSelection(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        String home = System.getProperty("user.home");
        String trust = System.getProperty("javax.net.ssl.trustStore");
        String password = System.getProperty("javax.net.ssl.trustStorePassword");
        String type = System.getProperty("javax.net.ssl.trustStoreType");
        try {
            System.setProperty("user.home", temp.toString());
            Path file = temp.resolve(".idmtest/truststore.p12");
            Files.createDirectories(file.getParent());
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, "changeit".toCharArray());
            try (var output = Files.newOutputStream(file)) { store.store(output, "changeit".toCharArray()); }
            System.clearProperty("javax.net.ssl.trustStore");

            run("run");
            assertThat(System.getProperty("javax.net.ssl.trustStore")).isEqualTo(file.toString());

            System.setProperty("javax.net.ssl.trustStore", "/explicit/store.p12");
            run("run");
            assertThat(System.getProperty("javax.net.ssl.trustStore")).isEqualTo("/explicit/store.p12");
        } finally {
            restore("user.home", home);
            restore("javax.net.ssl.trustStore", trust);
            restore("javax.net.ssl.trustStorePassword", password);
            restore("javax.net.ssl.trustStoreType", type);
        }
    }

    @Test
    @DisplayName("Should stop ui and run before starting when the per-user truststore is corrupt")
    void testCorruptUserTruststore(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        String home = System.getProperty("user.home");
        String trust = System.getProperty("javax.net.ssl.trustStore");
        try {
            System.setProperty("user.home", temp.toString());
            System.clearProperty("javax.net.ssl.trustStore");
            Path file = temp.resolve(".idmtest/truststore.p12");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "corrupt");

            assertThat(run("run")).isEqualTo(ExitCodes.CANNOT_START);
            assertThat(run("ui")).isEqualTo(ExitCodes.CANNOT_START);
            assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("cannot read idmtest truststore");
        } finally {
            restore("user.home", home);
            restore("javax.net.ssl.trustStore", trust);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
