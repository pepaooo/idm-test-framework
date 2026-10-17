package com.idm.test.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("secrets.env")
class SecretsEnvTest {

    @Test
    @DisplayName("Should read KEY=VALUE lines, skipping comments and blanks, with CRLF, a BOM and '=' in values")
    void testRead(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("secrets.env");
        Files.writeString(file, "﻿# lab secrets\r\nIDM_TEST_LDAP_PASSWORD=s3cr=t\r\n\r\n  # indented comment\r\n  IDM_TEST_DB_PW =x y\r\n",
                StandardCharsets.UTF_8);

        assertThat(SecretsEnv.read(file))
                .containsEntry("IDM_TEST_LDAP_PASSWORD", "s3cr=t")
                .containsEntry("IDM_TEST_DB_PW", "x y")
                .hasSize(2);
    }

    @Test
    @DisplayName("A missing file is empty")
    void testMissing(@TempDir Path dir) {
        assertThat(SecretsEnv.read(dir.resolve("secrets.env"))).isEmpty();
    }

    @Test
    @DisplayName("A malformed line is reported by number, never by content")
    void testMalformed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("secrets.env");
        Files.writeString(file, "A=1\nTOPSECRETVALUE\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SecretsEnv.read(file))
                .isInstanceOf(UsageException.class)
                .hasMessage("secrets.env line 2 is not KEY=VALUE")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("TOPSECRET"));
    }

    @Test
    @DisplayName("A file that is not UTF-8 or not a regular file is a usage error that never shows content")
    void testBadFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("secrets.env");
        Files.write(file, new byte[] {'A', '=', (byte) 0xE9, (byte) 0xFF, '\n'});
        assertThatThrownBy(() -> SecretsEnv.read(file)).isInstanceOf(UsageException.class)
                .hasMessage("secrets.env is not UTF-8 text; save it as UTF-8");

        Path folder = Files.createDirectory(dir.resolve("folder"));
        assertThatThrownBy(() -> SecretsEnv.read(folder)).isInstanceOf(UsageException.class)
                .hasMessage("secrets.env is not a file");
    }
}
