package com.idm.test.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Workspace")
class WorkspaceTest {

    @Test
    @DisplayName("Should refuse a folder without scenarios/")
    void testNotWorkspace(@TempDir Path dir) {
        assertThatThrownBy(() -> Workspace.open(dir)).isInstanceOf(UsageException.class)
                .hasMessage("not a workspace: " + dir.toAbsolutePath().normalize()
                        + " has no scenarios/ folder (run 'idmtest init " + dir.toAbsolutePath().normalize()
                        + "' to create one)");
    }

    @Test
    @DisplayName("Should work in a folder whose name has spaces")
    void testWorkspaceWithSpaces(@TempDir Path temp) throws Exception {
        Path dir = Files.createDirectories(temp.resolve("My Tests").resolve("scenarios")).getParent();
        Workspace ws = Workspace.open(dir);
        Clock clock = fixedClock();

        assertThat(ws.scenarios()).isEqualTo(dir.resolve("scenarios"));
        assertThat(ws.secretsFile()).isEqualTo(dir.resolve("secrets.env"));
        assertThat(ws.newRunFolder("lab", clock)).isDirectory().isEqualTo(dir.resolve("reports/20261015-143005-lab"));
    }

    @Test
    @DisplayName("Should add -2 to a run folder created in the same second")
    void testRunFolderCollision(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("scenarios"));
        Workspace ws = Workspace.open(dir);
        Clock clock = fixedClock();

        assertThat(ws.newRunFolder("lab", clock).getFileName().toString()).isEqualTo("20261015-143005-lab");
        assertThat(ws.newRunFolder("lab", clock).getFileName().toString()).isEqualTo("20261015-143005-lab-2");
        assertThat(ws.newRunFolder("lab", clock).getFileName().toString()).isEqualTo("20261015-143005-lab-3");
    }

    @Test
    @DisplayName("Should name the missing profile file")
    void testRequireProfile(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("scenarios"));
        Workspace ws = Workspace.open(dir);

        assertThatThrownBy(() -> Workspace.requireProfile(ws, "lab")).isInstanceOf(UsageException.class)
                .hasMessage("no settings for profile 'lab': config/application-lab.yml not found");

        Files.createDirectories(ws.config());
        Files.writeString(ws.profileFile("lab"), "idm.test.a: 1\n");
        Workspace.requireProfile(ws, "lab");
    }

    private static Clock fixedClock() {
        ZoneId zone = ZoneId.systemDefault();
        return Clock.fixed(LocalDateTime.of(2026, 10, 15, 14, 30, 5).atZone(zone).toInstant(), zone);
    }

    @Test
    @DisplayName("Should reject profile names that could leave config/ or reports/")
    void testInvalidProfile(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("scenarios"));
        Workspace ws = Workspace.open(dir);

        for (String bad : new String[] {"../x", "a/b", "a\\b", "x..y"}) {
            assertThatThrownBy(() -> Workspace.requireProfile(ws, bad)).isInstanceOf(UsageException.class)
                    .hasMessage("profile name '" + bad + "' may not contain '/', '\\' or '..'");
            assertThatThrownBy(() -> ws.newRunFolder(bad, fixedClock())).isInstanceOf(UsageException.class);
        }
    }
}
