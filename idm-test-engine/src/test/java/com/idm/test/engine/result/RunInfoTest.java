package com.idm.test.engine.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RunInfo")
class RunInfoTest {

    @Test
    @DisplayName("Endpoints are sanitised, sorted by label, and null URLs dropped")
    void testEndpoints() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("REST idm", "https://svc:pw@idm.lab/api");
        endpoints.put("Database sap", "jdbc:postgresql://db.lab/sap?password=x");
        endpoints.put("Role service", null);
        endpoints.put(null, "https://idm.lab/ignored");

        RunInfo run = new RunInfo("Real environment", Instant.EPOCH, List.of("dev"), null, endpoints);

        assertThat(run.endpoints()).containsExactly(
                Map.entry("Database sap", "jdbc:postgresql://db.lab/sap?password=***"),
                Map.entry("REST idm", "https://idm.lab/api"));
    }

    @Test
    @DisplayName("minimal has the runner name, a start time and nothing else")
    void testMinimal() {
        RunInfo run = RunInfo.minimal("Scenarios");

        assertThat(run.runner()).isEqualTo("Scenarios");
        assertThat(run.startedAt()).isNotNull();
        assertThat(run.profiles()).isEmpty();
        assertThat(run.scenarioFilter()).isNull();
        assertThat(run.endpoints()).isEmpty();
    }
}
