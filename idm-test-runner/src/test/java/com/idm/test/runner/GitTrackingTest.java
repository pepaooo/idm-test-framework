package com.idm.test.runner;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GitTracking")
class GitTrackingTest {

    @BeforeAll
    static void requireGit() {
        Assumptions.assumeTrue(gitAvailable(), "git is not on the PATH");
    }

    @Test
    @DisplayName("Should tell tracked files from untracked ones and non-repositories")
    void testIsTracked(@TempDir Path temp) throws Exception {
        Path repo = Files.createDirectories(temp.resolve("my repo"));
        Files.writeString(repo.resolve("secrets.env"), "A=1\n");
        Files.writeString(repo.resolve("other.txt"), "x\n");
        git(repo, "init", "-q");
        git(repo, "add", "secrets.env");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false",
                "-c", "core.hooksPath=" + Files.createDirectories(temp.resolve("nohooks")), "commit", "-q", "-m", "x");

        assertThat(GitTracking.isTracked(repo, repo.resolve("secrets.env"))).isTrue();
        assertThat(GitTracking.isTracked(repo, repo.resolve("other.txt"))).isFalse();

        Path plain = Files.createDirectories(temp.resolve("plain"));
        Files.writeString(plain.resolve("secrets.env"), "A=1\n");
        assertThat(GitTracking.isTracked(plain, plain.resolve("secrets.env"))).isFalse();
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = dir.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process p = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
