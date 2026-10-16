package com.idm.test.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommandLine")
class CommandLineTest {

    @Test
    @DisplayName("Should parse run with every option")
    void testRun() {
        CommandLine line = CommandLine.parse("run", "--profile", "lab", "--scenario", "users/*",
                "--locale", "es", "--workspace", "/w s", "--verbose");
        assertThat(line.command()).isEqualTo("run");
        assertThat(line.profile()).isEqualTo("lab");
        assertThat(line.scenario()).isEqualTo("users/*");
        assertThat(line.locale()).isEqualTo("es");
        assertThat(line.workspace()).isEqualTo(Path.of("/w s"));
        assertThat(line.verbose()).isTrue();
    }

    @Test
    @DisplayName("Should default the workspace to the current folder and accept init's target")
    void testDefaults() {
        assertThat(CommandLine.parse("validate").workspace()).isEqualTo(Path.of("").toAbsolutePath());
        assertThat(CommandLine.parse("init", "my-tests").initTarget()).isEqualTo(Path.of("my-tests"));
        assertThat(CommandLine.parse("init").initTarget()).isNull();
        assertThat(CommandLine.parse("--help").command()).isEqualTo("help");
        assertThat(CommandLine.parse().command()).isEqualTo("help");
        assertThat(CommandLine.parse("--version").command()).isEqualTo("version");
    }

    @Test
    @DisplayName("Should reject unknown commands, unknown options and missing values")
    void testErrors() {
        assertThatThrownBy(() -> CommandLine.parse("deploy")).isInstanceOf(UsageException.class)
                .hasMessage("unknown command 'deploy' (expected init, validate or run)");
        assertThatThrownBy(() -> CommandLine.parse("run", "--colour")).isInstanceOf(UsageException.class)
                .hasMessage("unknown option '--colour'");
        assertThatThrownBy(() -> CommandLine.parse("run", "--profile")).isInstanceOf(UsageException.class)
                .hasMessage("option '--profile' needs a value");
    }
}
