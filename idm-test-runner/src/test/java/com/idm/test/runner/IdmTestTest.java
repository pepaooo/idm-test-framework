package com.idm.test.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
    @DisplayName("Should print the three commands for --help and exit 0")
    void testHelp() {
        assertThat(run("--help")).isEqualTo(ExitCodes.OK);
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).contains("init", "validate", "run");
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
}
