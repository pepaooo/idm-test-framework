package com.idm.test.engine.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine messages go through keys (ITF-46), so every one can be shown in Spanish. The String
 * forms of StepOutcome stay for third-party steps; this test keeps the engine itself off them.
 */
@DisplayName("Engine messages use keys")
class NoPlainMessagesTest {

    private static final Pattern PLAIN = Pattern.compile(
            "StepOutcome\\.(success|failure|finalFailure)\\(\\s*\"");

    @Test
    @DisplayName("No engine source builds a StepOutcome from a string literal")
    void testNoPlainOutcomes() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return PLAIN.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(Path::toString).toList();
            assertThat(offenders).as("engine sources building StepOutcome from text; use Message").isEmpty();
        }
    }
}
