package com.idm.test.runner.ui.lab;

import com.idm.test.runner.SecretsEnv;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Writing secrets.env values: comments, order and layout kept, written atomically. All values are fake. */
@DisplayName("secrets.env editor")
class SecretsEnvEditorTest {

    @TempDir
    Path temp;

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static Map<String, String> values(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("Comments, blank lines and order stay; a key is updated in place and a new one appended")
    void testKeepsCommentsAndOrder() throws IOException {
        Path file = temp.resolve("secrets.env");
        Files.writeString(file, "# header\nIDM_TEST_A=old-a\n\n# b explained\n  IDM_TEST_B=\nIDM_TEST_D=x=y\n");

        SecretsEnvEditor.set(file, values("IDM_TEST_B", "fake-b-value", "IDM_TEST_C", "fake-c=value"));

        assertThat(Files.readString(file)).isEqualTo(
                "# header\nIDM_TEST_A=old-a\n\n# b explained\n  IDM_TEST_B=fake-b-value\nIDM_TEST_D=x=y\n"
                        + "IDM_TEST_C=fake-c=value\n");
        assertThat(SecretsEnv.read(file)).containsEntry("IDM_TEST_B", "fake-b-value")
                .containsEntry("IDM_TEST_C", "fake-c=value").containsEntry("IDM_TEST_D", "x=y");
    }

    @Test
    @DisplayName("CRLF and a byte order mark stay; a file without a final newline gets one before an appended key")
    void testLineEndings() throws IOException {
        Path file = temp.resolve("secrets.env");
        Files.writeString(file, "﻿# h\r\nIDM_TEST_A=old-a");

        SecretsEnvEditor.set(file, values("IDM_TEST_A", "fake-a-value", "IDM_TEST_B", "fake-b-value"));

        assertThat(Files.readString(file)).isEqualTo("﻿# h\r\nIDM_TEST_A=fake-a-value\r\nIDM_TEST_B=fake-b-value\r\n");
    }

    @Test
    @DisplayName("When a key appears twice, the line that wins (the last) is updated")
    void testDuplicateKey() throws IOException {
        Path file = temp.resolve("secrets.env");
        Files.writeString(file, "IDM_TEST_A=first\nIDM_TEST_A=second\n");

        SecretsEnvEditor.set(file, values("IDM_TEST_A", "fake-a-value"));

        assertThat(Files.readString(file)).isEqualTo("IDM_TEST_A=first\nIDM_TEST_A=fake-a-value\n");
        assertThat(SecretsEnv.read(file)).containsEntry("IDM_TEST_A", "fake-a-value");
    }

    @Test
    @DisplayName("A new file is created readable by its owner only; an existing file keeps its permissions")
    void testPermissions() throws IOException {
        assumeTrue(posix());
        Path file = temp.resolve("secrets.env");

        SecretsEnvEditor.set(file, values("IDM_TEST_A", "fake-a-value"));

        assertThat(Files.readString(file)).isEqualTo("IDM_TEST_A=fake-a-value\n");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        SecretsEnvEditor.set(file, values("IDM_TEST_A", "fake-a-other"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-r-----");
        try (var leftovers = Files.list(temp)) {
            assertThat(leftovers.map(p -> p.getFileName().toString())).containsExactly("secrets.env");
        }
    }

    @Test
    @DisplayName("A value with a line break or a malformed key is refused and nothing is written")
    void testRefusals() throws IOException {
        Path file = temp.resolve("secrets.env");
        Files.writeString(file, "IDM_TEST_A=old-a\n");

        assertThatThrownBy(() -> SecretsEnvEditor.set(file, values("IDM_TEST_A", "fake\nIDM_TEST_X=y")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("fake");
        assertThatThrownBy(() -> SecretsEnvEditor.set(file, values("IDM_TEST_A", "fake\rvalue")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretsEnvEditor.set(file, values("BAD NAME", "fake-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretsEnvEditor.set(file, values("A=B", "fake-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(file)).isEqualTo("IDM_TEST_A=old-a\n");
    }
}
