package com.idm.test.runner.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The installed git")
class GitToolTest {

    @Test
    @DisplayName("git runs without the workbench's own GIT_* variables")
    void testWithoutGitVariables() {
        Map<String, String> environment = new HashMap<>(Map.of("PATH", "/usr/bin", "HOME", "/home/me",
                "GIT_DIR", "/elsewhere/.git", "GIT_WORK_TREE", "/elsewhere", "Git_Index_File", "/x",
                "MY_GIT_THING", "kept"));

        GitTool.withoutGitVariables(environment);

        assertThat(environment).containsOnlyKeys("PATH", "HOME", "MY_GIT_THING");
    }

    @Test
    @DisplayName("A git that is not installed is not available and its init fails without throwing")
    void testMissingProgram(@TempDir Path folder) {
        GitTool missing = new GitTool("idmtest-no-such-git-program");
        assertThat(missing.available()).isFalse();
        assertThat(missing.init(folder)).isFalse();
    }

    @Test
    @DisplayName("Git state: clean, changed, untracked and none (no .git, no git), and the call changes nothing in the repository")
    void testGitState(@TempDir Path temp) throws Exception {
        Path plain = Files.createDirectories(temp.resolve("plain"));
        Files.writeString(plain.resolve("a.yaml"), "name: a\n");
        assertThat(new GitTool().state(plain, "a.yaml")).as("no .git").isEqualTo(GitTool.FileState.NONE);
        assertThat(new GitTool("idmtest-no-such-git-program").state(plain, "a.yaml"))
                .as("git missing").isEqualTo(GitTool.FileState.NONE);

        GitTool git = new GitTool();
        Assumptions.assumeTrue(git.available(), "git is not installed");
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectories(repo.resolve("scenarios/sub"));
        Files.writeString(repo.resolve("scenarios/clean.yaml"), "name: clean\n");
        Files.writeString(repo.resolve("scenarios/sub/changed [1].yaml"), "name: changed\n");
        Files.writeString(repo.resolve("scenarios/[ab].yaml"), "name: brackets\n");
        Files.writeString(repo.resolve("scenarios/a.yaml"), "name: a\n");
        Files.writeString(repo.resolve(".gitignore"), "ignored.yaml\n");
        assertThat(git.init(repo)).isTrue();
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false",
                "commit", "--quiet", "--no-verify", "-m", "first");
        Files.writeString(repo.resolve("scenarios/sub/changed [1].yaml"), "name: changed on disk\n");
        Files.writeString(repo.resolve("scenarios/a.yaml"), "name: a changed\n");
        Files.writeString(repo.resolve("scenarios/untracked.yaml"), "name: new\n");
        Files.writeString(repo.resolve("scenarios/ignored.yaml"), "name: ignored\n");
        Files.writeString(repo.resolve("scenarios/staged.yaml"), "name: staged\n");
        git(repo, "add", "scenarios/staged.yaml");
        // the modified file's mtime moves on, so a git that refreshes its index would write it now
        Files.setLastModifiedTime(repo.resolve("scenarios/clean.yaml"), FileTime.from(Instant.now().plusSeconds(5)));
        Map<String, String> before = snapshot(repo.resolve(".git"));

        assertThat(git.state(repo, "scenarios/clean.yaml")).isEqualTo(GitTool.FileState.CLEAN);
        assertThat(git.state(repo, "scenarios/[ab].yaml")).as("glob characters are a literal name, not a.yaml")
                .isEqualTo(GitTool.FileState.CLEAN);
        assertThat(git.state(repo, "scenarios/sub/changed [1].yaml")).isEqualTo(GitTool.FileState.CHANGED);
        assertThat(git.state(repo, "scenarios/staged.yaml")).as("added, never committed")
                .isEqualTo(GitTool.FileState.CHANGED);
        assertThat(git.state(repo, "scenarios/untracked.yaml")).isEqualTo(GitTool.FileState.UNTRACKED);
        assertThat(git.state(repo, "scenarios/ignored.yaml")).isEqualTo(GitTool.FileState.UNTRACKED);

        assertThat(snapshot(repo.resolve(".git"))).as("nothing under .git changed").isEqualTo(before);
    }

    @Test
    @DisplayName("Git state never starts a configured fsmonitor hook or daemon")
    void testGitStateStartsNoFsmonitor(@TempDir Path temp) throws Exception {
        GitTool git = new GitTool();
        Assumptions.assumeTrue(git.available(), "git is not installed");
        Assumptions.assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "needs a shell hook");
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectories(repo.resolve("scenarios"));
        Files.writeString(repo.resolve("scenarios/a.yaml"), "name: a\n");
        assertThat(git.init(repo)).isTrue();
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false",
                "commit", "--quiet", "--no-verify", "-m", "first");
        Path marker = temp.resolve("fsmonitor-ran");
        Path hook = temp.resolve("fsmonitor-hook.sh");
        Files.writeString(hook, "#!/bin/sh\ntouch '" + marker + "'\nexit 1\n");
        Files.setPosixFilePermissions(hook, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        git(repo, "config", "core.fsmonitor", hook.toString());
        Files.writeString(repo.resolve("scenarios/a.yaml"), "name: a changed\n");

        assertThat(git.state(repo, "scenarios/a.yaml")).isEqualTo(GitTool.FileState.CHANGED);
        assertThat(git.state(repo, "scenarios/new.yaml")).isEqualTo(GitTool.FileState.UNTRACKED);

        assertThat(marker).as("the fsmonitor hook was started").doesNotExist();
    }

    /** Runs git in {@code repo} without the GIT_* variables; fails the test when it fails. */
    private static void git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
        GitTool.withoutGitVariables(builder.environment());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("git " + String.join(" ", args) + ": " + output).isZero();
    }

    /** Every file under {@code folder}: its relative path → size, modified time and SHA-256. */
    private static Map<String, String> snapshot(Path folder) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(folder.relativize(file).toString(), Files.size(file) + "@"
                        + Files.getLastModifiedTime(file).toMillis() + "#"
                        + WorkspaceFiles.version(Files.readAllBytes(file)));
            }
        }
        return files;
    }
}
