package com.idm.test.runner.ui;

import com.idm.test.runner.ui.LastRuns.HistoryEntry;
import com.idm.test.runner.ui.LastRuns.LastRun;
import com.idm.test.runner.ui.LastRuns.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Last runs: a stopped scenario counts as stopped")
class LastRunsTest {

    @TempDir
    Path temp;
    private Path ws;

    @BeforeEach
    void workspace() throws IOException {
        ws = Files.createDirectories(temp.resolve("ws"));
        Files.createDirectories(ws.resolve("scenarios"));
        Files.createDirectories(ws.resolve("config"));
        Files.writeString(ws.resolve("scenarios/a.yaml"), "name: a\n");
    }

    @Test
    @DisplayName("Should give a scenario's last finished outcome, passing over a newer stopped run; its history lists the stop")
    void testLastOutcomeSkipsStopped() throws Exception {
        writeResults("20261019-090000-lab", Instant.parse("2026-10-19T07:00:00Z"), "[" + finding("a.yaml") + "]");
        writeResults("20261020-090000-lab", Instant.parse("2026-10-20T07:00:00Z"), "[" + passed("a.yaml") + "]");
        writeResults("20261021-090000-lab", Instant.parse("2026-10-21T07:00:00Z"), "[" + stopped("a.yaml") + "]");

        assertThat(LastRuns.lastOutcomes(ws, Set.of("a.yaml"))).containsExactly(Map.entry("a.yaml", Outcome.PASSED));
        assertThat(LastRuns.history(ws, "a.yaml", 10)).extracting(HistoryEntry::result)
                .containsExactly("stopped", "passed", "finding");
    }

    @Test
    @DisplayName("Should give no last result for a scenario whose only run was stopped, in the outcomes and the files tree")
    void testStoppedFirstRunHasNoLastResult() throws Exception {
        writeResults("20261021-090000-lab", Instant.parse("2026-10-21T07:00:00Z"), "[" + stopped("a.yaml") + "]");

        assertThat(LastRuns.lastOutcomes(ws, Set.of("a.yaml"))).isEmpty();
        WorkspaceFiles.Node scenarios = new WorkspaceFiles(ws).tree().children().stream()
                .filter(n -> n.path().equals("scenarios")).findFirst().orElseThrow();
        WorkspaceFiles.Node a = scenarios.children().stream()
                .filter(n -> n.path().equals("scenarios/a.yaml")).findFirst().orElseThrow();
        assertThat(a.lastResult()).isNull();
    }

    @Test
    @DisplayName("Should read a results file without any stopped key exactly as before")
    void testOldResultsWithoutStoppedField() throws Exception {
        writeResults("20261018-091000-lab", Instant.parse("2026-10-18T07:10:00Z"),
                "[" + passed("p.yaml") + "," + finding("f.yaml") + "," + setup("s.yaml") + "]");

        assertThat(LastRuns.lastOutcomes(ws, Set.of("p.yaml", "f.yaml", "s.yaml"))).containsOnly(
                Map.entry("p.yaml", Outcome.PASSED), Map.entry("f.yaml", Outcome.FINDING),
                Map.entry("s.yaml", Outcome.SETUP));
        LastRun run = LastRuns.newest(ws).orElseThrow();
        assertThat(run.passed()).isEqualTo(1);
        assertThat(run.findings()).isEqualTo(1);
        assertThat(run.setupProblems()).isEqualTo(1);
        assertThat(run.stopped()).isZero();
        assertThat(LastRuns.history(ws, null, 10)).extracting(HistoryEntry::result).containsExactly("setup");
    }

    @Test
    @DisplayName("Should count a run's stopped scenario on its own")
    void testLastRunCountsStopped() throws Exception {
        writeResults("20261021-090000-lab", Instant.parse("2026-10-21T07:00:00Z"),
                "[" + passed("p.yaml") + "," + passed("q.yaml") + "," + stopped("a.yaml") + "]");

        LastRun run = LastRuns.newest(ws).orElseThrow();
        assertThat(run.passed()).isEqualTo(2);
        assertThat(run.findings()).isZero();
        assertThat(run.setupProblems()).isZero();
        assertThat(run.stopped()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should rank a run's worst result setup, then finding, then stopped, then passed")
    void testWorkspaceHistoryRanksStopped() throws Exception {
        writeResults("20261018-090000-lab", Instant.parse("2026-10-18T07:00:00Z"),
                "[" + stopped("a.yaml") + "," + setup("s.yaml") + "]");
        writeResults("20261019-090000-lab", Instant.parse("2026-10-19T07:00:00Z"),
                "[" + stopped("a.yaml") + "," + finding("f.yaml") + "]");
        writeResults("20261020-090000-lab", Instant.parse("2026-10-20T07:00:00Z"),
                "[" + passed("p.yaml") + "," + stopped("a.yaml") + "]");
        writeResults("20261021-090000-lab", Instant.parse("2026-10-21T07:00:00Z"), "[" + passed("p.yaml") + "]");

        assertThat(LastRuns.history(ws, null, 10)).extracting(HistoryEntry::result)
                .containsExactly("passed", "stopped", "finding", "setup");
    }

    // --- helpers --------------------------------------------------------------------------------

    private void writeResults(String runId, Instant at, String json) throws IOException {
        Path file = Files.createDirectories(ws.resolve("reports").resolve(runId)).resolve("results.json");
        Files.writeString(file, json);
        Files.setLastModifiedTime(file, FileTime.from(at));
    }

    private static String passed(String name) {
        return record(name, true, "{\"index\":0,\"type\":\"ldap.verify\",\"success\":true}", "");
    }

    private static String finding(String name) {
        return record(name, false,
                "{\"index\":0,\"type\":\"ldap.verify\",\"success\":false,\"failureKind\":\"FINDING\"}", "");
    }

    private static String setup(String name) {
        return record(name, false,
                "{\"index\":0,\"type\":\"ldap.verify\",\"success\":false,\"failureKind\":\"SETUP\"}", "");
    }

    /** As the engine writes a scenario stopped during a wait: the step is SETUP, the record says stopped. */
    private static String stopped(String name) {
        return record(name, false, "{\"index\":0,\"type\":\"ldap.verify\",\"success\":false,"
                        + "\"message\":\"Stopped — cleanup did not run\",\"messageKey\":\"engine.run.stopped\","
                        + "\"failureKind\":\"SETUP\"}",
                ",\"stopped\":true,\"skippedCleanup\":[{\"index\":0,\"type\":\"ldap.delete\",\"status\":\"SKIPPED\"}]");
    }

    private static String record(String name, boolean success, String step, String extra) {
        return "{\"name\":\"" + name + "\",\"sourceName\":\"" + name + "\",\"success\":" + success
                + ",\"durationMillis\":1,\"steps\":[" + step + "],\"cleanup\":[]" + extra + "}";
    }
}
