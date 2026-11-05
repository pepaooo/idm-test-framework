package com.idm.test.engine.result;

import com.idm.test.engine.spi.CommandEvidence;
import com.idm.test.engine.spi.FileEvidence;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.SqlEvidence;
import com.idm.test.engine.spi.WaitObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RunFolder")
class RunFolderTest {

    private static StepResult step(int index, String type, com.idm.test.engine.spi.Evidence evidence) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "ada");
        params.put("password", "hunter2-plain");
        return new StepResult(index, type, params, true, 12, "ok", 3, 100L, "described", evidence,
                null, null, null, List.of(new WaitObservation(1, 10, "not yet")), null);
    }

    private static List<ScenarioResult> everyKind() {
        List<StepResult> steps = List.of(
                step(0, "rest.request", new HttpEvidence("POST", "https://idm.example/api?x=1",
                        Map.of("Accept", List.of("application/json")), Map.of("a", 1), 201,
                        Map.of("Location", List.of("/u/1")), Map.of("id", "u1"), 40, null)),
                step(1, "db.query", SqlEvidence.query("hr", "select 1", new Object[]{"p"},
                        List.of(Map.of("id", 1)))),
                step(2, "ldap.read", LdapEvidence.read("edir", "cn=ada,o=x", Map.of("cn", "ada"))),
                step(3, "file.read", FileEvidence.read("in", "a.csv", 10, List.of("id", "name"),
                        List.of(List.of("1", "ada"), List.of("2", "bob")), Set.of(0))),
                new StepResult(4, "command.run", Map.of("command", "echo"), false, 5, "failed", null, null, null,
                        CommandEvidence.ran("echo", List.of("echo", "hi"), Set.of(), 1, 5, false, "out", "err", 3, 3),
                        "engine.step.failed", List.of("a", 2), "FINDING", null, null));
        CleanupResult cleanup = new CleanupResult(0, "ldap.delete", Map.of("dn", "cn=ada"),
                CleanupStatus.FAILED, 7, "could not", "tidy", "engine.cleanup.failed", List.of("x"), "FINDING");
        CleanupResult skipped = new CleanupResult(1, "ldap.delete", Map.of("apiKey", "skipped-key"),
                CleanupStatus.SKIPPED, 0, "Stopped", null, "engine.run.stopped", null, null);
        ScenarioResult stopped = new ScenarioResult("Stopped one", "stopped.yaml", false, 90, List.of(), List.of(),
                "Stopped", null, ScenarioResult.STOPPED_KEY, List.of(Message.of("x", 1)), true, List.of(skipped));
        return List.of(new ScenarioResult("All kinds", "kinds.yaml", false, 500, steps, List.of(cleanup),
                        null, "a description", "engine.detail", List.of("a", 1), false, List.of()),
                stopped);
    }

    @Test
    @DisplayName("Should read back every evidence kind, cleanup entry, message key and stopped scenario")
    void roundTripsEveryEvidenceKind(@TempDir Path dir) {
        List<ScenarioResult> results = everyKind();
        JsonResultWriter.write(results, dir.resolve("results.json"));

        RunFolder folder = RunFolder.read(dir, null, Locale.ENGLISH);

        assertThat(folder.results()).isEqualTo(results.stream().map(Secrets::paramsMasked).toList());
    }

    @Test
    @DisplayName("Should read run.json back as written")
    void readsRunJson(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("x"));
        Path folderPath = dir.resolve("x");
        Instant started = Instant.parse("2026-11-04T10:00:00Z");
        Instant finished = Instant.parse("2026-11-04T10:05:30.250Z");
        RunInfo run = new RunInfo("Real environment", started, List.of("local", "ci"), "provisioning-*",
                Map.of("idm", "https://idm.example/api", "ldap", "ldap://edir:389"), Locale.forLanguageTag("es"));
        JsonResultWriter.write(List.of(), folderPath.resolve("results.json"));
        new RunRecord(run, finished).write(folderPath.resolve("run.json"));

        RunFolder folder = RunFolder.read(folderPath, null, Locale.ENGLISH);

        assertThat(folder.runId()).isEqualTo("x");
        assertThat(folder.run()).isEqualTo(run);
        assertThat(folder.run().locale()).isEqualTo(Locale.forLanguageTag("es"));
        assertThat(folder.finishedAt()).isEqualTo(finished);
    }

    @Test
    @DisplayName("Should let an override beat the recorded locale, and fall back when none was recorded")
    void localePrecedence(@TempDir Path dir) {
        RunInfo run = new RunInfo("Embedded", Instant.parse("2026-11-04T10:00:00Z"), List.of(), null, Map.of(),
                Locale.forLanguageTag("es"));
        JsonResultWriter.write(List.of(), dir.resolve("results.json"));
        new RunRecord(run, Instant.parse("2026-11-04T10:01:00Z")).write(dir.resolve("run.json"));

        assertThat(RunFolder.read(dir, Locale.GERMAN, Locale.ENGLISH).run().locale()).isEqualTo(Locale.GERMAN);
        assertThat(RunFolder.read(dir, null, Locale.FRENCH).run().locale())
                .isEqualTo(Locale.forLanguageTag("es"));
    }

    @Test
    @DisplayName("Should work out start, profile and end of an old folder that has no run.json")
    void readsOldFolderWithoutRunJson(@TempDir Path dir) {
        Path folderPath = dir.resolve("20261104-153012-local");
        List<ScenarioResult> results = List.of(
                new ScenarioResult("a", "a.yaml", true, 1000, List.of(), null),
                new ScenarioResult("b", "b.yaml", true, 2500, List.of(), null));
        JsonResultWriter.write(results, folderPath.resolve("results.json"));

        RunFolder folder = RunFolder.read(folderPath, null, Locale.FRENCH);

        Instant started = LocalDateTime.of(2026, 11, 4, 15, 30, 12).atZone(ZoneId.systemDefault()).toInstant();
        assertThat(folder.runId()).isEqualTo("20261104-153012-local");
        assertThat(folder.run().profiles()).containsExactly("local");
        assertThat(folder.run().startedAt()).isEqualTo(started);
        assertThat(folder.run().runner()).isNull();
        assertThat(folder.run().scenarioFilter()).isNull();
        assertThat(folder.run().endpoints()).isEmpty();
        assertThat(folder.run().locale()).isEqualTo(Locale.FRENCH);
        assertThat(folder.finishedAt()).isEqualTo(started.plusMillis(3500));
    }

    @Test
    @DisplayName("Should fall back when run.json does not parse")
    void unreadableRunJsonFallsBack(@TempDir Path dir) throws Exception {
        Path folderPath = dir.resolve("20261104-153012-local");
        JsonResultWriter.write(List.of(), folderPath.resolve("results.json"));
        Files.writeString(folderPath.resolve("run.json"), "{ not json");

        RunFolder folder = RunFolder.read(folderPath, null, null);

        assertThat(folder.run().profiles()).containsExactly("local");
        assertThat(folder.run().locale()).isEqualTo(Locale.ENGLISH);
    }

    @Test
    @DisplayName("Should be unreadable when results.json is missing or does not parse")
    void missingResultsIsUnreadable(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> RunFolder.read(dir, null, Locale.ENGLISH))
                .isInstanceOf(RunFolder.Unreadable.class);

        Files.writeString(dir.resolve("results.json"), "not json");
        assertThatThrownBy(() -> RunFolder.read(dir, null, Locale.ENGLISH))
                .isInstanceOf(RunFolder.Unreadable.class);
    }
}
