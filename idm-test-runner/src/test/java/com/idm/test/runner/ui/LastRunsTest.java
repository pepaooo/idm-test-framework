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

    @Test
    @DisplayName("Should count a scenario stopped after its step found a problem as a finding, not pass over it")
    void testStoppedAfterFindingIsAFinding() throws Exception {
        writeResults("20261020-090000-lab", Instant.parse("2026-10-20T07:00:00Z"), "[" + passed("a.yaml") + "]");
        writeResults("20261021-090000-lab", Instant.parse("2026-10-21T07:00:00Z"),
                "[" + stoppedAfterFinding("a.yaml") + "]");

        assertThat(LastRuns.lastOutcomes(ws, Set.of("a.yaml"))).containsExactly(Map.entry("a.yaml", Outcome.FINDING));
        assertThat(LastRuns.history(ws, "a.yaml", 10)).extracting(HistoryEntry::result)
                .containsExactly("finding", "passed");
        assertThat(LastRuns.history(ws, null, 10)).extracting(HistoryEntry::result)
                .containsExactly("finding", "passed");
        LastRun run = LastRuns.newest(ws).orElseThrow();
        assertThat(run.findings()).isEqualTo(1);
        assertThat(run.stopped()).isZero();
    }

    @Test
    @DisplayName("Should count a stopped record with an earlier failed finding and a later stopped step as a finding")
    void testEarlierFindingBeforeStoppedStep() throws Exception {
        writeResults("20261020-090000-lab", Instant.parse("2026-10-20T07:00:00Z"), "[" + passed("a.yaml") + "]");
        String steps = "{\"index\":0,\"type\":\"ldap.verify\",\"success\":false,\"failureKind\":\"FINDING\"},"
                + "{\"index\":1,\"type\":\"ldap.verify\",\"success\":false,"
                + "\"messageKey\":\"engine.run.stopped\",\"failureKind\":\"SETUP\"}";
        writeResults("20261021-090000-lab", Instant.parse("2026-10-21T07:00:00Z"),
                "[" + record("a.yaml", false, steps, ",\"stopped\":true") + "]");

        assertThat(LastRuns.lastOutcomes(ws, Set.of("a.yaml"))).containsExactly(Map.entry("a.yaml", Outcome.FINDING));
    }

    @Test
    @DisplayName("Should count a stopped record whose fixtures failed as a setup problem")
    void testStoppedWithFixtureFailureIsSetup() throws Exception {
        writeResults("20261021-090000-lab", Instant.parse("2026-10-21T07:00:00Z"),
                "[{\"name\":\"a.yaml\",\"sourceName\":\"a.yaml\",\"success\":false,\"steps\":[],\"cleanup\":[],"
                        + "\"failureDetail\":\"fixture failed\",\"failureDetailKey\":\"engine.fixture.failed\","
                        + "\"stopped\":true}]");

        assertThat(LastRuns.lastOutcomes(ws, Set.of("a.yaml"))).containsExactly(Map.entry("a.yaml", Outcome.SETUP));
    }

    @Test
    @DisplayName("Should count a scenario stopped after its steps passed, before its cleanup, as stopped")
    void testStoppedBeforeCleanupIsStopped() throws Exception {
        writeResults("20261021-090000-lab", Instant.parse("2026-10-21T07:00:00Z"),
                "[" + record("a.yaml", false, "{\"index\":0,\"type\":\"ldap.verify\",\"success\":true}",
                        ",\"failureDetail\":\"Stopped — cleanup did not run\","
                                + "\"failureDetailKey\":\"engine.run.stopped\",\"stopped\":true") + "]");

        assertThat(LastRuns.newest(ws).orElseThrow().stopped()).isEqualTo(1);
        assertThat(LastRuns.lastOutcomes(ws, Set.of("a.yaml"))).isEmpty();
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

    /** A FINDING step failed, then the stop came before the cleanup. */
    private static String stoppedAfterFinding(String name) {
        return record(name, false,
                "{\"index\":0,\"type\":\"ldap.verify\",\"success\":false,\"failureKind\":\"FINDING\"}",
                ",\"stopped\":true,\"skippedCleanup\":[{\"index\":0,\"type\":\"ldap.delete\",\"status\":\"SKIPPED\"}]");
    }

    private static String record(String name, boolean success, String step, String extra) {
        return "{\"name\":\"" + name + "\",\"sourceName\":\"" + name + "\",\"success\":" + success
                + ",\"durationMillis\":1,\"steps\":[" + step + "],\"cleanup\":[]" + extra + "}";
    }
}
