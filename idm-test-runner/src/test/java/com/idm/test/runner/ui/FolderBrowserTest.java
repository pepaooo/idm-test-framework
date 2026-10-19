package com.idm.test.runner.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Folder browser")
class FolderBrowserTest {

    @Test
    @DisplayName("Lists sub-folders by name, marks workspaces, leaves out files and hidden folders")
    void testListsFolders(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("b-ws/scenarios"));
        Files.createDirectories(dir.resolve("A plain"));
        Files.createDirectories(dir.resolve(".hidden"));
        Files.writeString(dir.resolve("file.txt"), "x");

        FolderBrowser.Listing listing = FolderBrowser.list(dir);

        assertThat(listing.path()).isEqualTo(dir.toAbsolutePath().normalize().toString());
        assertThat(listing.parent()).isEqualTo(dir.toAbsolutePath().normalize().getParent().toString());
        assertThat(listing.entries()).extracting(FolderBrowser.Entry::name).containsExactly("A plain", "b-ws");
        assertThat(listing.entries()).extracting(FolderBrowser.Entry::isWorkspace).containsExactly(false, true);
        assertThat(listing.entries().get(1).path()).isEqualTo(dir.resolve("b-ws").toString());
    }

    @Test
    @DisplayName("Spaces, non-ASCII names, a symlink loop and an unreadable folder: what is readable, no failure")
    void testBrowseOddFolders(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Files.createDirectories(dir.resolve("with spaces"));
        Files.createDirectories(dir.resolve("año ñandú 日本"));
        Files.createSymbolicLink(dir.resolve("loop"), dir.resolve("loop"));
        Files.createSymbolicLink(dir.resolve("up"), dir);
        Path locked = Files.createDirectories(dir.resolve("locked"));
        Files.createDirectories(locked.resolve("inside"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            FolderBrowser.Listing listing = FolderBrowser.list(dir);
            assertThat(listing.entries()).extracting(FolderBrowser.Entry::name)
                    .contains("with spaces", "año ñandú 日本")
                    .doesNotContain("loop", "up");

            FolderBrowser.Listing inside = FolderBrowser.list(locked);
            assertThat(inside.path()).isEqualTo(locked.toString());
            if (!Files.isReadable(locked)) {
                assertThat(inside.entries()).isEmpty();
            }
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @DisplayName("A path that is not a folder is refused")
    void testNotAFolder(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("file.txt"), "x");
        assertThatThrownBy(() -> FolderBrowser.list(file)).isInstanceOf(FolderBrowser.NotAFolderException.class);
        assertThatThrownBy(() -> FolderBrowser.list(dir.resolve("missing")))
                .isInstanceOf(FolderBrowser.NotAFolderException.class);
        assertThatThrownBy(() -> FolderBrowser.list(Path.of("relative")))
                .isInstanceOf(FolderBrowser.NotAFolderException.class);
    }

    @Test
    @DisplayName("A root has no parent")
    void testRoot() {
        Path root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().getRoot();
        assertThat(FolderBrowser.list(root).parent()).isNull();
    }
}
