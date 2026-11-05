package com.idm.test.wiring;

import com.idm.test.engine.result.RunFolder;
import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.result.ScenarioResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RunRecorder")
class RunRecorderTest {

    @Test
    @DisplayName("Should write run.json beside results.json so the folder reads back")
    void writesRunJsonBesideResults(@TempDir Path dir) {
        Path folderPath = dir.resolve("20261104-153012-local");
        Path results = folderPath.resolve("results.json");
        RunInfo run = new RunInfo("Embedded", Instant.parse("2026-11-04T10:00:00Z"), List.of("local"), null,
                Map.of("idm", "https://idm.example"), Locale.forLanguageTag("es"));
        ScenarioResult scenario = new ScenarioResult("a", "a.yaml", true, 10, List.of(), null);
        Instant before = Instant.now();

        RunRecorder.write(List.of(scenario), results, RunRecorder.reportPath(results), run);

        assertThat(Files.exists(folderPath.resolve("run.json"))).isTrue();
        RunFolder folder = RunFolder.read(folderPath, null, Locale.ENGLISH);
        assertThat(folder.run()).isEqualTo(run);
        assertThat(folder.finishedAt()).isBetween(before, Instant.now());
        assertThat(folder.results()).containsExactly(scenario);
    }

    @Test
    @DisplayName("Should name run.json after results.json the way the report is named")
    void runInfoPathMirrorsReportPath() {
        assertThat(RunRecorder.runInfoPath(Path.of("d/results.json"))).isEqualTo(Path.of("d/run.json"));
        assertThat(RunRecorder.runInfoPath(Path.of("d/real-environment-results.json")))
                .isEqualTo(Path.of("d/real-environment-run.json"));
        assertThat(RunRecorder.runInfoPath(Path.of("d/out.json"))).isEqualTo(Path.of("d/out.json.run.json"));
    }
}
