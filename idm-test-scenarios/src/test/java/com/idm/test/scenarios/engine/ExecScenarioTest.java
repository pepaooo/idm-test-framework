package com.idm.test.scenarios.engine;

import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.CommandApproval;
import com.idm.test.engine.spi.CommandEvidence;
import com.idm.test.engine.spi.StepType;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import com.idm.test.scenarios.base.TestCommands;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.StepRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@code scenarios/exec-harmless-command.yaml} with and without the test approval, and checks what
 * the scenario cannot check about itself: that an unapproved definition starts nothing.
 */
@DisplayName("Registered command scenario")
class ExecScenarioTest extends BaseIntegrationTest {

    private static final String SCENARIO = "scenarios/exec-harmless-command.yaml";

    @Test
    @DisplayName("Complete workflow: run the approved command → verify its exit code and output")
    void testApprovedCommandRuns() throws Exception {
        ScenarioResult result = run(commandApproval);

        assertThat(result.success()).as(String.valueOf(result.failureSummary())).isTrue();
        assertThat(result.steps()).extracting(StepResult::type).containsExactly("exec.run", "exec.verify");
        assertThat(result.steps().getFirst().evidence()).isInstanceOfSatisfying(CommandEvidence.class, evidence -> {
            assertThat(evidence.command()).isEqualTo(TestCommands.JAVA_VERSION);
            assertThat(evidence.exitCode()).isZero();
        });
    }

    @Test
    @DisplayName("Should refuse the scenario's command, starting nothing, when this machine has not approved it")
    void testUnapprovedCommandRefused() throws Exception {
        ScenarioResult result = run(CommandApproval.NONE);

        assertThat(result.success()).isFalse();
        StepResult first = result.steps().getFirst();
        assertThat(first.success()).isFalse();
        assertThat(first.message()).contains(TestCommands.JAVA_VERSION);
        assertThat(first.evidence()).isInstanceOfSatisfying(CommandEvidence.class,
                evidence -> assertThat(evidence.exitCode()).isNull());
    }

    @Test
    @DisplayName("Should refuse a definition other than the one the context registered, even under an approved name")
    void testOtherDefinitionRefused() {
        String hash = commandConnector.command(TestCommands.JAVA_VERSION).orElseThrow().hash();

        assertThat(commandApproval.approved(TestCommands.JAVA_VERSION, hash)).isTrue();
        assertThat(commandApproval.approved(TestCommands.JAVA_VERSION, "0".repeat(64))).isFalse();
        assertThat(commandApproval.approved("other", hash)).isFalse();
    }

    private ScenarioResult run(CommandApproval approval) throws Exception {
        Map<String, StepType> registry = StepRegistry.of(ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator, fileConnector,
                commandConnector, approval);
        ScenarioValidator validator = EngineFactory.validator(registry);
        ScenarioEngine engine = EngineFactory.engine(registry, testDataGenerator, testExecutor, executorProperties);

        Scenario scenario;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCENARIO)) {
            assertThat(in).as(SCENARIO).isNotNull();
            scenario = ScenarioLoader.load(in, SCENARIO);
        }
        validator.validate(scenario, SCENARIO);
        return engine.run(scenario, SCENARIO);
    }
}
