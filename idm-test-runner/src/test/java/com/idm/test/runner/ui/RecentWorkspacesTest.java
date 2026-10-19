package com.idm.test.runner.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Recent workspaces file")
class RecentWorkspacesTest {

    @Test
    @DisplayName("Most recent first, each folder once, at most 20")
    void testOrderAndLimit(@TempDir Path home) {
        RecentWorkspaces recent = new RecentWorkspaces(home.resolve(".idmtest/recent.json"));
        recent.add(home.resolve("a"));
        recent.add(home.resolve("b"));
        recent.add(home.resolve("a"));
        assertThat(recent.paths()).containsExactly(home.resolve("a"), home.resolve("b"));

        IntStream.range(0, 25).forEach(i -> recent.add(home.resolve("w" + i)));
        assertThat(recent.paths()).hasSize(20).first().isEqualTo(home.resolve("w24"));
        assertThat(recent.paths()).last().isEqualTo(home.resolve("w5"));
    }

    @Test
    @DisplayName("Survives a restart, and remove takes one folder out")
    void testSurvivesRestart(@TempDir Path home) {
        Path file = home.resolve(".idmtest/recent.json");
        RecentWorkspaces first = new RecentWorkspaces(file);
        first.add(home.resolve("one"));
        first.add(home.resolve("two with spaces ñ"));

        RecentWorkspaces again = new RecentWorkspaces(file);
        assertThat(again.paths()).containsExactly(home.resolve("two with spaces ñ"), home.resolve("one"));
        again.remove(home.resolve("one"));
        assertThat(new RecentWorkspaces(file).paths()).containsExactly(home.resolve("two with spaces ñ"));
    }

    @Test
    @DisplayName("A corrupt file reads as empty and stays as it is until the next write")
    void testCorruptFile(@TempDir Path home) throws Exception {
        Path file = home.resolve(".idmtest/recent.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json");

        RecentWorkspaces recent = new RecentWorkspaces(file);
        assertThat(recent.paths()).isEmpty();
        assertThat(Files.readString(file)).isEqualTo("{not json");

        recent.add(home.resolve("ws"));
        assertThat(new RecentWorkspaces(file).paths()).containsExactly(home.resolve("ws"));
        try (Stream<Path> left = Files.list(file.getParent())) {
            assertThat(left).as("the temporary file of the atomic write is gone").containsExactly(file);
        }
    }

    @Test
    @DisplayName("A missing file is an empty list, and reading does not create it")
    void testMissingFile(@TempDir Path home) {
        Path file = home.resolve(".idmtest/recent.json");
        assertThat(new RecentWorkspaces(file).paths()).isEmpty();
        assertThat(file.getParent()).doesNotExist();
    }
}
