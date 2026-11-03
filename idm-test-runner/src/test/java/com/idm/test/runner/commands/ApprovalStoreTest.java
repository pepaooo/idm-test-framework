package com.idm.test.runner.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ApprovalStoreTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @TempDir
    Path temp;

    private ApprovalStore store() {
        return new ApprovalStore(temp.resolve("home/.idmtest/command-approvals.json"),
                Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Should approve one definition of one command, for one workspace and profile only")
    void testApprovalIsExact() throws IOException {
        Path ws = Files.createDirectories(temp.resolve("ws"));
        Path other = Files.createDirectories(temp.resolve("other"));
        ApprovalStore store = store();

        store.approve(ws, "lab", "driver-state", HASH_A);

        assertThat(store.approved(ws, "lab", "driver-state", HASH_A)).isTrue();
        assertThat(store.approved(ws, "lab", "driver-state", HASH_B)).as("changed definition").isFalse();
        assertThat(store.approved(ws, "uat", "driver-state", HASH_A)).as("other profile").isFalse();
        assertThat(store.approved(other, "lab", "driver-state", HASH_A)).as("other workspace").isFalse();
        assertThat(store.approved(ws, "lab", "driver-start", HASH_A)).as("other command").isFalse();
        assertThat(store.forWorkspace(ws, "lab").approved("driver-state", HASH_A)).isTrue();
        assertThat(store.approvals(ws, "lab")).containsOnlyKeys("driver-state");
        assertThat(store.approvals(ws, "lab").get("driver-state").approvedAt()).isEqualTo("2026-11-04T10:00:00Z");
    }

    @Test
    @DisplayName("Should replace an approval with a new one, and revoke it")
    void testReplaceAndRevoke() throws IOException {
        Path ws = Files.createDirectories(temp.resolve("ws"));
        ApprovalStore store = store();

        store.approve(ws, "lab", "c", HASH_A);
        store.approve(ws, "lab", "c", HASH_B);

        assertThat(store.approved(ws, "lab", "c", HASH_A)).isFalse();
        assertThat(store.approved(ws, "lab", "c", HASH_B)).isTrue();
        assertThat(store.revoke(ws, "lab", "c")).isTrue();
        assertThat(store.revoke(ws, "lab", "c")).isFalse();
        assertThat(store.approved(ws, "lab", "c", HASH_B)).isFalse();
    }

    @Test
    @DisplayName("Should treat a link to an approved workspace as another workspace")
    void testSymlinkIsAnotherWorkspace() throws IOException {
        Path ws = Files.createDirectories(temp.resolve("ws"));
        Path link = temp.resolve("link");
        try {
            Files.createSymbolicLink(link, ws);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links not supported here");
        }
        ApprovalStore store = store();

        store.approve(link, "lab", "c", HASH_A);

        // The key is the real path, so approving through the link approves the real folder, not a second one.
        assertThat(store.approved(ws, "lab", "c", HASH_A)).isTrue();
        assertThat(store.approvals(ws, "lab")).hasSize(1);
    }

    @Test
    @DisplayName("Should read a missing or corrupt file as nothing approved, never as everything")
    void testCorruptFile() throws IOException {
        Path ws = Files.createDirectories(temp.resolve("ws"));
        ApprovalStore store = store();
        assertThat(store.approved(ws, "lab", "c", HASH_A)).isFalse();
        assertThat(store.file()).doesNotExist();

        Files.createDirectories(store.file().getParent());
        for (String corrupt : new String[]{"not json", "[]", "{\"approvals\": 3}",
                "{\"approvals\": [{\"workspace\": \"" + ws.toRealPath().toString().replace("\\", "\\\\")
                        + "\", \"profile\": \"lab\", \"command\": \"c\", \"hash\": \"*\"}]}"}) {
            Files.writeString(store.file(), corrupt);
            assertThat(store.approved(ws, "lab", "c", HASH_A)).as(corrupt).isFalse();
            assertThat(store.forWorkspace(ws, "lab").approved("c", "*")).as(corrupt).isFalse();
        }
        store.approve(ws, "lab", "c", HASH_A);
        assertThat(store.approved(ws, "lab", "c", HASH_A)).isTrue();
    }
}
