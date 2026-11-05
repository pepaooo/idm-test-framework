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
                .hasMessage("unknown command 'deploy' (expected init, validate, run, ui, commands or report)");
        assertThatThrownBy(() -> CommandLine.parse("run", "--colour")).isInstanceOf(UsageException.class)
                .hasMessage("unknown option '--colour'");
        assertThatThrownBy(() -> CommandLine.parse("run", "--profile")).isInstanceOf(UsageException.class)
                .hasMessage("option '--profile' needs a value");
    }

    @Test
    @DisplayName("Should parse ui with a port and a workspace")
    void testUi() {
        CommandLine line = CommandLine.parse("ui", "--port", "0", "--workspace", "/w");
        assertThat(line.command()).isEqualTo("ui");
        assertThat(line.port()).isZero();
        assertThat(line.workspace()).isEqualTo(Path.of("/w").toAbsolutePath());
        assertThat(CommandLine.parse("ui", "--port", "8123", "--locale", "es").port()).isEqualTo(8123);
        assertThat(CommandLine.parse("ui").port()).isNull();
    }

    @Test
    @DisplayName("Should record whether --workspace was given, keeping the current folder as the default")
    void testWorkspaceGiven() {
        assertThat(CommandLine.parse("ui", "--workspace", "/w").workspaceGiven()).isTrue();
        CommandLine none = CommandLine.parse("ui");
        assertThat(none.workspaceGiven()).isFalse();
        assertThat(none.workspace()).isEqualTo(Path.of("").toAbsolutePath());
        assertThat(CommandLine.parse("run").workspaceGiven()).isFalse();
        assertThat(CommandLine.parse("run").workspace()).isEqualTo(Path.of("").toAbsolutePath());
        assertThat(CommandLine.parse("validate", "--workspace", "x").workspaceGiven()).isTrue();
    }

    @Test
    @DisplayName("Should reject a --port that is not a port number, and --port outside ui")
    void testBadPort() {
        assertThatThrownBy(() -> CommandLine.parse("ui", "--port", "abc")).isInstanceOf(UsageException.class)
                .hasMessage("option '--port' needs a port number from 0 to 65535, not 'abc'");
        assertThatThrownBy(() -> CommandLine.parse("ui", "--port", "65536")).isInstanceOf(UsageException.class)
                .hasMessage("option '--port' needs a port number from 0 to 65535, not '65536'");
        assertThatThrownBy(() -> CommandLine.parse("ui", "--port", "-1")).isInstanceOf(UsageException.class)
                .hasMessage("option '--port' needs a port number from 0 to 65535, not '-1'");
        assertThatThrownBy(() -> CommandLine.parse("ui", "--port", "80")).isInstanceOf(UsageException.class)
                .hasMessage("option '--port' cannot be 80: browsers leave port 80 out of the address, "
                        + "and the workbench needs it there; choose another port");
        assertThatThrownBy(() -> CommandLine.parse("run", "--port", "8123")).isInstanceOf(UsageException.class)
                .hasMessage("unknown option '--port'");
    }

    @Test
    @DisplayName("An option value starting with -- is not a value")
    void testOptionValueLooksLikeOption() {
        assertThatThrownBy(() -> CommandLine.parse("run", "--profile", "--verbose"))
                .isInstanceOf(UsageException.class).hasMessage("option '--profile' needs a value");
        assertThatThrownBy(() -> CommandLine.parse("run", "--profile", "-h"))
                .isInstanceOf(UsageException.class).hasMessage("option '--profile' needs a value");
    }

    @Test
    @DisplayName("--help or -h after a command means help")
    void testHelpAnywhere() {
        assertThat(CommandLine.parse("run", "--help").command()).isEqualTo("help");
        assertThat(CommandLine.parse("validate", "--profile", "lab", "-h").command()).isEqualTo("help");
    }

    @Test
    @DisplayName("A relative --workspace is made absolute")
    void testRelativeWorkspace() {
        assertThat(CommandLine.parse("validate", "--workspace", "tests/../lab").workspace())
                .isEqualTo(Path.of("lab").toAbsolutePath());
    }

    @Test
    @DisplayName("Should parse idmtest commands: the action, the names after it, --yes and --profile")
    void testCommands() {
        CommandLine line = CommandLine.parse("commands", "approve", "driver-state", "restart", "--yes",
                "--profile", "lab");

        org.assertj.core.api.Assertions.assertThat(line.command()).isEqualTo("commands");
        org.assertj.core.api.Assertions.assertThat(line.action()).isEqualTo("approve");
        org.assertj.core.api.Assertions.assertThat(line.names()).containsExactly("driver-state", "restart");
        org.assertj.core.api.Assertions.assertThat(line.yes()).isTrue();
        org.assertj.core.api.Assertions.assertThat(line.profile()).isEqualTo("lab");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CommandLine.parse("run", "--yes"))
                .isInstanceOf(UsageException.class);
        org.assertj.core.api.Assertions.assertThat(CommandLine.parse("run", "--profile", "lab").names()).isEmpty();
    }

    @Test
    @DisplayName("Should parse report with its folder, --pdf, --out and --locale")
    void parsesReport() {
        CommandLine line = CommandLine.parse("report", "reports/x", "--pdf", "detailed", "--out", "a.pdf",
                "--locale", "es");
        assertThat(line.command()).isEqualTo("report");
        assertThat(line.runFolder()).isEqualTo(Path.of("reports/x").toAbsolutePath().normalize());
        assertThat(line.pdf()).isEqualTo("detailed");
        assertThat(line.out()).isEqualTo(Path.of("a.pdf").toAbsolutePath());
        assertThat(line.locale()).isEqualTo("es");
        assertThat(CommandLine.parse("report", "x", "--pdf", "summary").out()).isNull();
    }

    @Test
    @DisplayName("Should reject a bad --pdf, a missing folder or --pdf, and options report does not take")
    void reportRejectsBadPdf() {
        assertThatThrownBy(() -> CommandLine.parse("report", "x", "--pdf", "full"))
                .isInstanceOf(UsageException.class).hasMessageContaining("summary or detailed");
        assertThatThrownBy(() -> CommandLine.parse("report", "--pdf", "summary"))
                .isInstanceOf(UsageException.class).hasMessageContaining("run folder");
        assertThatThrownBy(() -> CommandLine.parse("report", "x"))
                .isInstanceOf(UsageException.class).hasMessageContaining("--pdf");
        assertThatThrownBy(() -> CommandLine.parse("report", "x", "--pdf", "summary", "--workspace", "w"))
                .isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> CommandLine.parse("run", "--pdf", "summary"))
                .isInstanceOf(UsageException.class).hasMessage("unknown option '--pdf'");
    }
}
