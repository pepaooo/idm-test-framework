package com.idm.test.scenarios.engine;

import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.result.CleanupStatus;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.FileEvidence;
import com.idm.test.engine.spi.StepType;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.StepRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@code scenarios/files-delimited-round-trip.yaml} on its own and checks what the scenario
 * cannot check about itself: that its cleanup left the folder empty, and that its evidence names the
 * folder by its settings name, never by its path.
 */
@DisplayName("Delimited text file scenario")
class DelimitedFileScenarioTest extends BaseIntegrationTest {

    private static final String SCENARIO = "scenarios/files-delimited-round-trip.yaml";

    @Test
    @DisplayName("Complete workflow: write → wait and verify → delete in cleanup, leaving nothing behind")
    void testRoundTripLeavesFolderEmpty() throws Exception {
        Map<String, StepType> registry = StepRegistry.of(ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator, fileConnector,
                commandConnector, commandApproval);
        ScenarioValidator validator = EngineFactory.validator(registry);
        ScenarioEngine engine = EngineFactory.engine(registry, testDataGenerator, testExecutor, executorProperties);

        Scenario scenario;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCENARIO)) {
            assertThat(in).as(SCENARIO).isNotNull();
            scenario = ScenarioLoader.load(in, SCENARIO);
        }
        validator.validate(scenario, SCENARIO);

        ScenarioResult result = engine.run(scenario, SCENARIO);

        assertThat(result.success()).as(String.valueOf(result.failureSummary())).isTrue();
        assertThat(result.steps()).extracting(StepResult::type)
                .containsExactly("file.write", "file.verify", "file.verify");
        assertThat(result.cleanup()).singleElement()
                .satisfies(entry -> assertThat(entry.status()).isEqualTo(CleanupStatus.SUCCEEDED));
        try (Stream<Path> left = Files.list(FILES_SCRATCH)) {
            assertThat(left.map(p -> p.getFileName().toString()).filter(name -> name.startsWith("hr-")).toList())
                    .as("files the scenario left in its folder")
                    .isEmpty();
        }
        for (StepResult step : result.steps()) {
            assertThat(step.evidence()).isInstanceOfSatisfying(FileEvidence.class, evidence -> {
                assertThat(evidence.folder()).isEqualTo("scratch");
                assertThat(evidence.toString()).doesNotContain(FILES_SCRATCH.toString());
            });
        }
        assertThat(((FileEvidence) result.steps().get(1).evidence()).selectedRows()).isEqualTo(1);
    }
}
