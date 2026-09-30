package com.idm.test.scenarios.engine;

import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.result.ScenarioResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("A report that cannot be written never breaks a run")
class ScenarioSupportReportFailureTest {

    @Test
    @DisplayName("The JSON is still written and nothing escapes when the HTML report cannot be")
    void testReportFailureIsSwallowed(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("x-results.json");
        // A directory where the report file belongs makes the HTML write fail.
        Files.createDirectory(dir.resolve("x-report.html"));
        List<ScenarioResult> collected = List.of(new ScenarioResult("a", "a.yaml", true, 1L, List.of(), null));

        assertThatCode(() -> ScenarioSupport.writeResults(collected, results, RunInfo.minimal("Scenarios")))
                .doesNotThrowAnyException();

        assertThat(Files.readString(results)).contains("a.yaml");
    }
}
