package com.idm.test.wiring;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Scenario files under a folder, sub-folders included; hidden files and folders are skipped. */
public final class DirectoryScenarioSource implements ScenarioSource {

    private final Path root;

    public DirectoryScenarioSource(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public List<String> names() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(rel -> !hidden(rel))
                    .map(rel -> rel.toString().replace('\\', '/'))
                    .filter(name -> name.endsWith(".yaml") || name.endsWith(".yml"))
                    .sorted()
                    .toList();
        }
    }

    private static boolean hidden(Path relative) {
        for (Path part : relative) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public InputStream open(String name) throws IOException {
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root)) {
            throw new NoSuchFileException(name);
        }
        return Files.newInputStream(file);
    }

    @Override
    public String describe() {
        return root.toString();
    }
}
