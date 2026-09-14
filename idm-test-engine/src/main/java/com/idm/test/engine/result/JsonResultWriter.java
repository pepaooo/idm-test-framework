package com.idm.test.engine.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes scenario results as JSON.
 * <p>
 * The only thing in the engine that produces output. An HTML renderer, CLI console output
 * and any future UI all read this same shape, which is why its structure is pinned by test.
 */
public final class JsonResultWriter {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonResultWriter() {
    }

    public static void write(List<ScenarioResult> results, Path target) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JSON.writeValue(target.toFile(), results);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write results to " + target, e);
        }
    }
}
