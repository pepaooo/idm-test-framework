package com.idm.test.wiring;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** The {@code .yaml} scenarios directly under a classpath directory (top level only). */
public final class ClasspathScenarioSource implements ScenarioSource {

    private final String directory;

    public ClasspathScenarioSource(String directory) {
        this.directory = directory;
    }

    /** Empty when the directory is not on the classpath. */
    @Override
    public List<String> names() {
        var url = ClasspathScenarioSource.class.getClassLoader().getResource(directory);
        if (url == null) {
            return List.of();
        }
        Path dir;
        try {
            dir = Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException("Could not resolve " + url, e));
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(path -> path.toString().endsWith(".yaml"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list scenarios in " + dir, e);
        }
    }

    /** The stream, or {@code null} when the scenario is not on the classpath (as the class loader answers). */
    @Override
    public InputStream open(String name) {
        return ClasspathScenarioSource.class.getClassLoader().getResourceAsStream(directory + "/" + name);
    }

    @Override
    public String describe() {
        return directory;
    }
}
