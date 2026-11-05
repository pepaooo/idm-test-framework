package com.idm.test.engine.result;

import com.idm.test.engine.spi.JsonMappers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * What {@code run.json} holds beside {@code results.json}: the run's {@link RunInfo} (endpoint URLs
 * already sanitised, so no secret) and when it finished. A report reads it back with {@link RunFolder}.
 */
public record RunRecord(RunInfo run, Instant finishedAt) {

    private static final JsonMapper JSON = JsonMappers.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /** Writes this record to {@code target}, creating its folder. */
    public void write(Path target) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JSON.writeValue(target.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the run record to " + target, e);
        } catch (JacksonException e) {
            throw new UncheckedIOException("Could not write the run record to " + target,
                    e.getCause() instanceof IOException io ? io : new IOException(e));
        }
    }
}
