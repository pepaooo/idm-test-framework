package com.idm.test.engine.result;

import com.idm.test.engine.spi.SqlEvidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsonResultWriter")
class JsonResultWriterTest {

    @Test
    @DisplayName("Should write the fields a report or UI would need")
    void testWritesResults(@TempDir Path dir) throws Exception {
        StepResult step = new StepResult(
                0, "ldap.verify", Map.of("dn", "cn=ada,ou=users"),
                false, 1500, "did not match", 12, 30000L);
        ScenarioResult scenario = new ScenarioResult(
                "Provision Ada", "user-provisioning.yaml", false, 1600, List.of(step), null);

        Path target = dir.resolve("nested/results.json");
        JsonResultWriter.write(List.of(scenario), target);

        String json = Files.readString(target);
        assertThat(json)
                .contains("\"name\" : \"Provision Ada\"")
                .contains("\"sourceName\" : \"user-provisioning.yaml\"")
                .contains("\"type\" : \"ldap.verify\"")
                .contains("\"attempts\" : 12")
                .contains("\"waitedMillis\" : 30000")
                .contains("\"failureDetail\" : null")
                .contains("cn=ada,ou=users");
    }

    @Test
    @DisplayName("Should write a cleanup entry's status and type")
    void testWritesCleanupResults(@TempDir Path dir) throws Exception {
        CleanupResult cleanup = new CleanupResult(
                0, "ldap.delete", Map.of(), CleanupStatus.SKIPPED, 0,
                "skipped: [createdDn] never bound, so what this would clean up never happened");
        ScenarioResult scenario = new ScenarioResult(
                "Provision Ada", "user-provisioning.yaml", false, 1600,
                List.of(), List.of(cleanup), null);

        Path target = dir.resolve("cleanup-results.json");
        JsonResultWriter.write(List.of(scenario), target);

        String json = Files.readString(target);
        assertThat(json)
                .contains("\"cleanup\"")
                .contains("\"status\" : \"SKIPPED\"")
                .contains("\"type\" : \"ldap.delete\"");
    }

    @Test
    @DisplayName("Should create parent directories that do not exist")
    void testCreatesDirectories(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("a/b/c/results.json");

        JsonResultWriter.write(List.of(), target);

        assertThat(Files.exists(target)).isTrue();
    }

    @Test
    @DisplayName("Should write the scenario's, step's and cleanup entry's descriptions")
    void testWritesDescriptions(@TempDir Path dir) throws Exception {
        StepResult step = new StepResult(0, "ldap.create", Map.of("dn", "x"), true, 5, "ok", null, null,
                "Create the user");
        CleanupResult cleanup = new CleanupResult(0, "ldap.delete", Map.of("dn", "x"),
                CleanupStatus.SUCCEEDED, 1, "deleted", "Remove the user");
        ScenarioResult scenario = new ScenarioResult("Provision Ada", "p.yaml", true, 10,
                List.of(step), List.of(cleanup), null, "Checks provisioning");

        Path target = dir.resolve("descriptions.json");
        JsonResultWriter.write(List.of(scenario), target);

        assertThat(Files.readString(target))
                .contains("\"description\" : \"Checks provisioning\"")
                .contains("\"description\" : \"Create the user\"")
                .contains("\"description\" : \"Remove the user\"");
    }

    @Test
    @DisplayName("Writes a step's evidence with its kind, and leaves the key out when there is none")
    void testWritesEvidence(@TempDir Path dir) throws Exception {
        StepResult withEvidence = new StepResult(0, "db.verify", Map.of(), true, 5, "ok", null, null, null,
                SqlEvidence.query("hr", "SELECT status FROM users WHERE username = ?", new Object[]{"jdoe"},
                        List.of(Map.of("STATUS", "ACTIVE"))));
        StepResult without = new StepResult(1, "ldap.delete", Map.of(), true, 5, "ok", null, null, null);
        ScenarioResult scenario = new ScenarioResult("S", "s.yaml", true, 10, List.of(withEvidence, without), null);

        Path target = dir.resolve("r.json");
        JsonResultWriter.write(List.of(scenario), target);

        String json = Files.readString(target);
        assertThat(json).contains("\"kind\" : \"sql\"").contains("\"database\" : \"hr\"")
                .contains("\"totalRows\" : 1").contains("\"STATUS\" : \"ACTIVE\"");
        assertThat(json.split("\"evidence\"", -1)).hasSize(2);
    }
}
