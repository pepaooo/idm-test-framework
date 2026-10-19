package com.idm.test.runner.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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
}
