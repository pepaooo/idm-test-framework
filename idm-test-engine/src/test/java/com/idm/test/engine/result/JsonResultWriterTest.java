package com.idm.test.engine.result;

import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.Message;
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
    @DisplayName("Should write a java.sql.Timestamp parameter as epoch milliseconds")
    void testWritesTimestampAsEpochMillis(@TempDir Path dir) throws Exception {
        StepResult step = new StepResult(0, "db.verify",
                Map.of("at", new java.sql.Timestamp(1_700_000_000_000L)), true, 5, "ok", null, null, null);
        ScenarioResult scenario = new ScenarioResult(
                "Dates", "dates.yaml", true, 10, List.of(step), null);

        Path target = dir.resolve("dates.json");
        JsonResultWriter.write(List.of(scenario), target);

        assertThat(Files.readString(target)).contains("\"at\" : 1700000000000");
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

    @Test
    @DisplayName("Should keep the exact layout: declaration order, ' : ' separators, empty arrays and nulls")
    void testExactLayout(@TempDir Path dir) throws Exception {
        ScenarioResult scenario = new ScenarioResult("S", "s.yaml", true, 10, List.of(), null);

        Path target = dir.resolve("layout.json");
        JsonResultWriter.write(List.of(scenario), target);

        assertThat(Files.readString(target).replace("\r\n", "\n")).isEqualTo("""
                [ {
                  "name" : "S",
                  "sourceName" : "s.yaml",
                  "success" : true,
                  "durationMillis" : 10,
                  "steps" : [ ],
                  "cleanup" : [ ],
                  "failureDetail" : null,
                  "description" : null
                } ]""");
    }

    @Test
    @DisplayName("Should write a step's fields in declaration order")
    void testStepFieldOrder(@TempDir Path dir) throws Exception {
        StepResult step = new StepResult(0, "ldap.verify", Map.of("dn", "x"), false, 5, "m", 2, 100L, "d");
        ScenarioResult scenario = new ScenarioResult("S", "s.yaml", false, 10, List.of(step), null);

        Path target = dir.resolve("order.json");
        JsonResultWriter.write(List.of(scenario), target);

        String json = Files.readString(target);
        List<String> keys = List.of("\"index\"", "\"type\"", "\"resolvedParams\"", "\"success\" : false",
                "\"durationMillis\" : 5", "\"message\"", "\"attempts\"", "\"waitedMillis\"", "\"description\" : \"d\"");
        int last = json.indexOf("\"steps\"");
        for (String key : keys) {
            int at = json.indexOf(key, last);
            assertThat(at).as(key + " after the previous field").isGreaterThan(last);
            last = at;
        }
    }

    @Test
    @DisplayName("Writes a keyed message's key and arguments last, after the evidence or the description")
    void testWritesMessageKey(@TempDir Path dir) throws Exception {
        StepResult withEvidence = new StepResult(0, "ldap.verify", Map.of(), false, 5,
                "probe (last observed value after retrying)", 2, 100L, "d", LdapEvidence.exists(null, "cn=x", false),
                "engine.wait.lastObserved", List.of("probe"));
        StepResult withoutEvidence = new StepResult(1, "ldap.verify", Map.of(), false, 5,
                "probe (last observed value after retrying)", null, null, "d2", null,
                "engine.wait.lastObserved", List.of("probe"));
        ScenarioResult scenario = new ScenarioResult("S", "s.yaml", false, 10,
                List.of(withEvidence, withoutEvidence), null);

        Path target = dir.resolve("keyed.json");
        JsonResultWriter.write(List.of(scenario), target);

        String json = Files.readString(target).replace("\r\n", "\n");
        assertThat(json).contains("\"messageKey\" : \"engine.wait.lastObserved\",\n"
                + "    \"messageArgs\" : [ \"probe\" ]\n");
        int first = json.indexOf("\"evidence\"");
        assertThat(json.indexOf("\"messageKey\"")).isGreaterThan(first);
        int second = json.indexOf("\"description\" : \"d2\"");
        assertThat(json).contains("\"description\" : \"d2\",\n    \"messageKey\" : \"engine.wait.lastObserved\"");
        assertThat(json.lastIndexOf("\"messageKey\"")).isGreaterThan(second);
    }

    @Test
    @DisplayName("Writes a nested Message argument as its key and arguments")
    void testWritesNestedMessage(@TempDir Path dir) throws Exception {
        StepResult step = new StepResult(0, "ldap.verify", Map.of(), false, 5,
                "x (last observed value after retrying) (last observed value after retrying)", null, null, null, null,
                "engine.wait.lastObserved", List.of(Message.of("engine.wait.lastObserved", "x")));
        ScenarioResult scenario = new ScenarioResult("S", "s.yaml", false, 10, List.of(step), null);

        Path target = dir.resolve("nested.json");
        JsonResultWriter.write(List.of(scenario), target);

        String json = Files.readString(target).replace("\r\n", "\n");
        assertThat(json).contains("    \"messageArgs\" : [ {\n"
                + "      \"key\" : \"engine.wait.lastObserved\",\n"
                + "      \"args\" : [ \"x\" ]\n"
                + "    } ]");
    }

    @Test
    @DisplayName("Writes a scenario's failure detail key and arguments last")
    void testWritesFailureDetailKey(@TempDir Path dir) throws Exception {
        ScenarioResult scenario = new ScenarioResult("S", "s.yaml", false, 10, List.of(), List.of(),
                "x (last observed value after retrying)", null, "engine.wait.lastObserved", List.of("x"));

        Path target = dir.resolve("detail.json");
        JsonResultWriter.write(List.of(scenario), target);

        assertThat(Files.readString(target).replace("\r\n", "\n")).endsWith("""
                  "description" : null,
                  "failureDetailKey" : "engine.wait.lastObserved",
                  "failureDetailArgs" : [ "x" ]
                } ]""");
    }

    @Test
    @DisplayName("Should write a failure's kind last, and no kind for a passing step or cleanup entry")
    void testWritesFailureKind(@TempDir Path dir) throws Exception {
        StepResult passed = new StepResult(0, "set", Map.of(), true, 1, "ok", null, null, null);
        StepResult failed = new StepResult(1, "ldap.verify", Map.of(), false, 5, "m", null, null, null,
                null, null, null, "FINDING");
        CleanupResult undone = new CleanupResult(0, "ldap.delete", Map.of(), CleanupStatus.SUCCEEDED, 1, "ok");
        CleanupResult left = new CleanupResult(1, "ldap.delete", Map.of(), CleanupStatus.FAILED, 1, "no",
                null, null, null, "FINDING");
        ScenarioResult scenario = new ScenarioResult("S", "s.yaml", false, 10, List.of(passed, failed),
                List.of(undone, left), null);

        Path target = dir.resolve("kind.json");
        JsonResultWriter.write(List.of(scenario), target);

        String json = Files.readString(target).replace("\r\n", "\n");
        assertThat(json.split("\"failureKind\"", -1)).as("one per failure, none when passing").hasSize(3);
        assertThat(json.split("\"failureKind\" : \"FINDING\"\n", -1)).as("the last field").hasSize(3);
    }

    @Test
    @DisplayName("Should write the waiting history last, after the failure kind")
    void testWritesWaitHistory(@TempDir Path dir) throws Exception {
        StepResult step = new StepResult(0, "ldap.verify", Map.of(), false, 5, "m", 3, 100L, null, null, null, null,
                "FINDING", List.of(new com.idm.test.engine.spi.WaitObservation(1, 0, "status=PENDING"),
                new com.idm.test.engine.spi.WaitObservation(3, 40, "status=LOCKED")));
        ScenarioResult scenario = new ScenarioResult("S", "s.yaml", false, 10, List.of(step), null);

        Path target = dir.resolve("history.json");
        JsonResultWriter.write(List.of(scenario), target);

        String json = Files.readString(target).replace("\r\n", "\n");
        assertThat(json).contains("    \"failureKind\" : \"FINDING\",\n"
                + "    \"waitHistory\" : [ {\n"
                + "      \"attempt\" : 1,\n"
                + "      \"elapsedMillis\" : 0,\n"
                + "      \"message\" : \"status=PENDING\"\n"
                + "    }, {");
    }

    @Test
    @DisplayName("Should write a stopped run's flag and skipped cleanup last, and neither for an ordinary run")
    void testWritesStopped(@TempDir Path dir) throws Exception {
        CleanupResult skipped = new CleanupResult(0, "ldap.delete", Map.of("dn", "cn=ada"), CleanupStatus.SKIPPED, 0,
                "Stopped — cleanup did not run", null, "engine.run.stopped", List.of(), null);
        ScenarioResult stopped = new ScenarioResult("S", "s.yaml", false, 10, List.of(), List.of(), null, null,
                null, null, true, List.of(skipped));

        Path target = dir.resolve("stopped.json");
        JsonResultWriter.write(List.of(stopped), target);

        String json = Files.readString(target).replace("\r\n", "\n");
        assertThat(json).contains("  \"description\" : null,\n"
                + "  \"stopped\" : true,\n"
                + "  \"skippedCleanup\" : [ {\n"
                + "    \"index\" : 0,\n"
                + "    \"type\" : \"ldap.delete\",\n"
                + "    \"resolvedParams\" : {\n"
                + "      \"dn\" : \"cn=ada\"\n"
                + "    },\n"
                + "    \"status\" : \"SKIPPED\",");

        ScenarioResult ordinary = new ScenarioResult("S", "s.yaml", true, 10, List.of(), List.of(), null, null,
                null, null, false, List.of());
        JsonResultWriter.write(List.of(ordinary), target);
        assertThat(Files.readString(target)).doesNotContain("stopped").doesNotContain("skippedCleanup");
    }
}
