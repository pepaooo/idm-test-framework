package com.idm.test.engine.result;

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
    @DisplayName("Should create parent directories that do not exist")
    void testCreatesDirectories(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("a/b/c/results.json");

        JsonResultWriter.write(List.of(), target);

        assertThat(Files.exists(target)).isTrue();
    }
}
