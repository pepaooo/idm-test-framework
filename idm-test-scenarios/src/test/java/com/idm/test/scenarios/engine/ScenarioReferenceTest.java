package com.idm.test.scenarios.engine;

import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.spi.StepType;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.StepRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Keeps docs/reference/writing-scenarios.md honest: every registered step has exactly one
 * section, each section's parameter table matches what the step declares, and the worked
 * example passes the real loader and validator. Adding a step or parameter without documenting
 * it fails here, naming what to document. Captures and prose are not checked.
 */
@DisplayName("Scenario authoring reference")
class ScenarioReferenceTest {

    static final Path PAGE = Path.of("..", "docs", "reference", "writing-scenarios.md");
    /** Documented once for every step, in "Values" and "Waiting", not per step. */
    static final Set<String> COMMON_PARAMETERS = Set.of("as", "wait");

    private static final Pattern STEP_HEADING = Pattern.compile("^#### `([a-z]+\\.[a-z]+)`\\s*$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|\\s*`([A-Za-z]+)`\\s*\\|\\s*(yes|no)\\s*\\|.*\\|\\s*$");
    private static final String TABLE_HEADER = "| Parameter | Required | Meaning |";

    private static List<String> lines;
    private static Map<String, StepType> registry;

    @BeforeAll
    static void load() throws IOException {
        lines = Files.readAllLines(PAGE, StandardCharsets.UTF_8);
        registry = StepRegistry.of(null, null, null, null, null,
                new RestProperties(), RestAuthenticator.withoutOAuth());
    }

    /** step name → (parameter → required?), from each step section's first table. */
    static Map<String, Map<String, Boolean>> documentedSteps(List<String> lines) {
        Map<String, Map<String, Boolean>> steps = new TreeMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher heading = STEP_HEADING.matcher(lines.get(i));
            if (!heading.matches()) {
                continue;
            }
            Map<String, Boolean> parameters = new LinkedHashMap<>();
            boolean inTable = false;
            for (int j = i + 1; j < lines.size() && !lines.get(j).startsWith("#"); j++) {
                String line = lines.get(j).trim();
                if (!inTable) {
                    inTable = line.equals(TABLE_HEADER);
                    continue;
                }
                if (line.startsWith("|---")) {
                    continue;
                }
                Matcher row = TABLE_ROW.matcher(line);
                if (!row.matches()) {
                    break;
                }
                parameters.put(row.group(1), row.group(2).equals("yes"));
            }
            steps.put(heading.group(1), parameters);
        }
        return steps;
    }

    @Test
    @DisplayName("Every registered step has a section, and every section is a registered step")
    void testCoverage() {
        Set<String> documented = documentedSteps(lines).keySet();
        Set<String> registered = new TreeSet<>(registry.keySet());

        Set<String> undocumented = new TreeSet<>(registered);
        undocumented.removeAll(documented);
        Set<String> unregistered = new TreeSet<>(documented);
        unregistered.removeAll(registered);

        assertThat(undocumented).as("registered steps with no #### section in " + PAGE).isEmpty();
        assertThat(unregistered).as("documented steps that are not registered").isEmpty();
    }

    @Test
    @DisplayName("Each step's parameter table matches its required and accepted parameters")
    void testParameters() {
        documentedSteps(lines).forEach((name, documented) -> {
            StepType step = registry.get(name);
            if (step == null) {
                return; // reported by testCoverage
            }
            Set<String> required = new TreeSet<>(step.requiredParams());
            Set<String> all = new TreeSet<>(step.acceptedParams());
            all.addAll(required);
            all.removeAll(COMMON_PARAMETERS);

            Set<String> documentedRequired = new TreeSet<>();
            documented.forEach((parameter, isRequired) -> {
                if (isRequired) {
                    documentedRequired.add(parameter);
                }
            });

            assertThat(documented.keySet()).as(name + ": documented parameters vs declared").containsExactlyInAnyOrderElementsOf(all);
            assertThat(documentedRequired).as(name + ": parameters marked 'yes' vs required").isEqualTo(required);
        });
    }

    @Test
    @DisplayName("The worked example is a complete scenario that passes the loader and validator")
    void testWorkedExample() {
        String yaml = workedExample(lines);
        ScenarioValidator validator = EngineFactory.validator(registry);

        assertThatCode(() -> {
            Scenario scenario = ScenarioLoader.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "worked-example.yaml");
            validator.validate(scenario, "worked-example.yaml");
        }).doesNotThrowAnyException();
    }

    /** The first ```yaml block after the "## 10. A worked example" heading. */
    static String workedExample(List<String> lines) {
        int start = lines.indexOf("## 10. A worked example");
        assertThat(start).as("heading '## 10. A worked example' in " + PAGE).isNotNegative();
        StringBuilder yaml = new StringBuilder();
        boolean inBlock = false;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!inBlock) {
                inBlock = line.trim().equals("```yaml");
                continue;
            }
            if (line.trim().equals("```")) {
                return yaml.toString();
            }
            yaml.append(line).append('\n');
        }
        throw new AssertionError("no ```yaml block after '## 10. A worked example' in " + PAGE);
    }
}
