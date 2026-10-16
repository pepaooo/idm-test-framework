package com.idm.test.scenarios.engine;

import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.spi.StepType;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.StepRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A real run of two corpus scenarios writes their database and LDAP evidence to both outputs. */
@DisplayName("Evidence in a runner's results and report")
class EvidenceReportTest extends BaseIntegrationTest {

    @Test
    @DisplayName("The JSON results and the HTML report hold SQL and LDAP evidence")
    void testEvidenceWritten(@TempDir Path dir) throws Throwable {
        Map<String, StepType> registry = StepRegistry.of(
                ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioEngine engine = EngineFactory.engine(registry, testDataGenerator, testExecutor, executorProperties);
        Path results = dir.resolve("scenario-results.json");

        List<DynamicTest> tests = ScenarioSupport.buildTests("scenarios",
                List.of("provisioning-user-to-database.yaml", "provisioning-user-to-ldap.yaml"),
                EngineFactory.validator(registry), engine, results,
                scenarios -> RunInfo.minimal("Embedded"));
        for (DynamicTest test : tests) {
            test.getExecutable().execute();
        }

        String json = Files.readString(results);
        assertThat(json).contains("\"kind\" : \"sql\"").contains("\"kind\" : \"ldap\"");
        String html = Files.readString(dir.resolve("scenario-report.html"));
        assertThat(html).contains("<details class=\"evidence\"").contains("SQL · ");
        // None of the embedded run's configured credentials may reach either output. The LDAP password
        // default ("secret") and the other bare "test" values are skipped as too generic to search for.
        for (String credential : List.of("sappass", "hrpass", "wdaypass", "idmpass",
                "sap-test-secret", "workday-secret", "snow-secret")) {
            assertThat(json).doesNotContain(credential);
            assertThat(html).doesNotContain(credential);
        }
    }
}
