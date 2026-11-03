package com.idm.test.runner.commands;

import com.idm.test.runner.CommandLine;
import com.idm.test.runner.ExitCodes;
import com.idm.test.runner.InitCommand;
import com.idm.test.runner.UsageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("idmtest commands")
class CommandsCommandTest {

    private static final String SECRET = "fake-token-6Kq";

    @TempDir
    Path temp;

    private Path ws;
    private ApprovalStore store;
    private ByteArrayOutputStream outBytes;

    @BeforeEach
    void setUp() throws Exception {
        ws = temp.resolve("ws");
        InitCommand.run(ws, new PrintStream(OutputStream.nullOutputStream()));
        Files.writeString(ws.resolve("config/application-lab.yml"), """
                idm:
                  test:
                    commands:
                      driver-state:
                        executable: /opt/novell/eDirectory/bin/dxcmd
                        args: ["-token", "${IDMTEST_DXCMD_TOKEN}", "-getstate", "{driver}"]
                        params:
                          driver: "[A-Za-z0-9 ,=.-]+"
                        env:
                          DXCMD_HOME: /opt/dxcmd
                        timeout: 30s
                      restart:
                        executable: /usr/bin/true
                """);
        Files.writeString(ws.resolve("secrets.env"), "IDMTEST_DXCMD_TOKEN=" + SECRET + "\n");
        store = new ApprovalStore(temp.resolve("home/.idmtest/command-approvals.json"), Clock.systemUTC());
        outBytes = new ByteArrayOutputStream();
    }

    private int run(String input, String... args) {
        List<String> all = new ArrayList<>(List.of("commands"));
        all.addAll(List.of(args));
        all.addAll(List.of("--workspace", ws.toString()));
        return CommandsCommand.run(CommandLine.parse(all.toArray(String[]::new)),
                new BufferedReader(new StringReader(input)), new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream()), store);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Should list each definition as written, secrets by name only, and its state")
    void testList() {
        int exit = run("", "list", "--profile", "lab");

        assertThat(exit).isEqualTo(ExitCodes.OK);
        assertThat(out()).contains("Commands of profile 'lab'",
                "driver-state  (not approved)", "restart  (not approved)",
                "executable: /opt/novell/eDirectory/bin/dxcmd",
                "arguments:  -token ${IDMTEST_DXCMD_TOKEN} -getstate {driver}",
                "parameter driver: must match [A-Za-z0-9 ,=.-]+",
                "environment: [DXCMD_HOME]",
                "timeout 30 s")
                .doesNotContain(SECRET).doesNotContain("/opt/dxcmd");
        assertThat(store.file()).doesNotExist();
    }

    @Test
    @DisplayName("Should ask y/N for each command, approve the yeses, and show them approved")
    void testApproveInteractively() {
        int exit = run("y\nn\n", "approve", "--profile", "lab");

        assertThat(exit).isEqualTo(ExitCodes.OK);
        assertThat(out()).contains("Approve 'driver-state' to run on this machine, exactly as shown? [y/N]",
                "approved 'driver-state'", "'restart' not approved");
        assertThat(store.approvals(ws, "lab")).containsOnlyKeys("driver-state");

        outBytes.reset();
        run("", "list", "--profile", "lab");
        assertThat(out()).contains("driver-state  (approved on this machine)", "restart  (not approved)");
    }

    @Test
    @DisplayName("Should approve without asking with --yes, and only the commands named")
    void testApproveYesAndNames() {
        run("", "approve", "--profile", "lab", "--yes", "restart");

        assertThat(store.approvals(ws, "lab")).containsOnlyKeys("restart");
        assertThat(out()).doesNotContain("[y/N]");
    }

    @Test
    @DisplayName("Should take an end of input as no")
    void testEndOfInputIsNo() {
        run("", "approve", "--profile", "lab");

        assertThat(store.approvals(ws, "lab")).isEmpty();
    }

    @Test
    @DisplayName("Should show a changed definition as changed and ask again; a new secret value changes nothing")
    void testChangedDefinition() throws Exception {
        run("", "approve", "--profile", "lab", "--yes");
        Files.writeString(ws.resolve("secrets.env"), "IDMTEST_DXCMD_TOKEN=another-token-2Zr\n");
        outBytes.reset();
        run("", "list", "--profile", "lab");
        assertThat(out()).contains("driver-state  (approved on this machine)");

        Path settings = ws.resolve("config/application-lab.yml");
        Files.writeString(settings, Files.readString(settings).replace("\"-getstate\"", "\"-stop\""));
        outBytes.reset();
        run("n\n", "approve", "--profile", "lab");
        assertThat(out()).contains("driver-state  (changed since it was approved)", "[y/N]", "'driver-state' not approved");
    }

    @Test
    @DisplayName("Should revoke approvals")
    void testRevoke() {
        run("", "approve", "--profile", "lab", "--yes");
        outBytes.reset();

        run("", "revoke", "--profile", "lab", "restart");

        assertThat(out()).contains("revoked the approval of 'restart'");
        assertThat(store.approvals(ws, "lab")).containsOnlyKeys("driver-state");
    }

    @Test
    @DisplayName("Should refuse a missing profile, an unknown action or command, and --yes elsewhere")
    void testUsage() {
        assertThatThrownBy(() -> run("", "list")).isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> run("", "trust", "--profile", "lab")).isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> run("", "approve", "--profile", "lab", "nope")).isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> run("", "list", "--profile", "lab", "--yes")).isInstanceOf(UsageException.class);
        assertThat(store.file()).doesNotExist();
    }

    @Test
    @DisplayName("Should speak Spanish with --locale es")
    void testSpanish() {
        run("s\n", "approve", "--profile", "lab", "--locale", "es", "driver-state");

        assertThat(out()).contains("no aprobado", "[s/N]", "'driver-state' aprobado");
        assertThat(store.approvals(ws, "lab")).containsOnlyKeys("driver-state");
    }
}
