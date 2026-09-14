package com.idm.test.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine must not reference Spring. Spec section 6.2 requires it to stay free of
 * application contexts and configuration loading so a CLI can wrap it later without rework.
 * <p>
 * Stated as a test rather than a convention, because conventions erode.
 */
@DisplayName("Engine independence")
class NoSpringDependencyTest {

    @Test
    @DisplayName("No engine source file should import org.springframework")
    void testNoSpringImports() throws IOException {
        Path sources = Path.of("src/main/java");

        try (Stream<Path> files = Files.walk(sources)) {
            List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(NoSpringDependencyTest::importsSpring)
                    .map(Path::toString)
                    .toList();

            assertThat(offenders)
                    .as("engine sources importing Spring; see spec section 6.2")
                    .isEmpty();
        }
    }

    private static boolean importsSpring(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .anyMatch(line -> line.startsWith("import org.springframework"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
