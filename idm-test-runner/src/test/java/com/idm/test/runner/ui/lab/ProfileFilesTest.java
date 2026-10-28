package com.idm.test.runner.ui.lab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProfileFiles")
class ProfileFilesTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("Without hard links, a profile is still written whole and never over an existing file")
    void testWriteNewWithoutHardLinks() throws IOException {
        Path target = temp.resolve("application-uat.yml");

        ProfileFiles.writeNew(target, "# uat\n".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(target)).isEqualTo("# uat\n");
        assertThatThrownBy(() -> ProfileFiles.writeNew(target, "# other\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(target)).isEqualTo("# uat\n");
    }
}
