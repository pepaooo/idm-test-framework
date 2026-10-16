package com.idm.test.wiring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DirectoryScenarioSource")
class DirectoryScenarioSourceTest {

    private static void write(Path root, String name) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "name: " + name + "\nsteps: []\n", StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Should list .yaml and .yml files in sub-folders as sorted relative paths, skipping others")
    void testNames(@TempDir Path root) throws Exception {
        write(root, "z.yaml");
        write(root, "users/create.yaml");
        write(root, "users/roles.grant-v2.yml");
        write(root, "notes.txt");
        write(root, ".hidden/x.yaml");

        assertThat(new DirectoryScenarioSource(root).names())
                .containsExactly("users/create.yaml", "users/roles.grant-v2.yml", "z.yaml");
    }

    @Test
    @DisplayName("Should open a scenario by its relative name")
    void testOpen(@TempDir Path root) throws Exception {
        write(root, "users/create.yaml");
        try (var in = new DirectoryScenarioSource(root).open("users/create.yaml")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).startsWith("name: users/create.yaml");
        }
    }

    @Test
    @DisplayName("Globs match relative paths, with or without the extension")
    void testGlobs(@TempDir Path root) throws Exception {
        write(root, "users/create.yaml");
        write(root, "users/delete.yaml");
        write(root, "roles.grant-v2.yaml");
        var names = new DirectoryScenarioSource(root).names();

        assertThat(ScenarioGlob.select(names, "users/*")).containsExactly("users/create.yaml", "users/delete.yaml");
        assertThat(ScenarioGlob.select(names, "roles.grant-v2")).containsExactly("roles.grant-v2.yaml");
        assertThat(ScenarioGlob.select(names, "users/create.yaml")).containsExactly("users/create.yaml");
        assertThat(ScenarioGlob.select(names, "nothing*")).isEmpty();
        assertThat(ScenarioGlob.select(names, null)).isEqualTo(names);
    }
}
