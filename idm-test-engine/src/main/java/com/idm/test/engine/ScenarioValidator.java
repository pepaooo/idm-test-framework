package com.idm.test.engine;

import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks a scenario before anything executes.
 * <p>
 * Validating up front matters more here than in most systems: a scenario can spend minutes
 * waiting for provisioning, and discovering a typo after that wait is expensive. Every
 * message names the file and the step, because the reader may not be a Java developer.
 */
public final class ScenarioValidator {

    private final Map<String, StepType> registry;

    public ScenarioValidator(Map<String, StepType> registry) {
        this.registry = registry;
    }

    /**
     * @throws ValidationException on the first problem found
     */
    public void validate(Scenario scenario, String sourceName) {
        if (scenario.steps().isEmpty()) {
            throw new ValidationException(sourceName, "a scenario needs at least one step");
        }

        Set<String> known = scenario.fixtures().keySet();

        for (int i = 0; i < scenario.steps().size(); i++) {
            StepSpec step = scenario.steps().get(i);
            StepType type = registry.get(step.type());

            if (type == null) {
                throw new ValidationException(sourceName, i, step.type(),
                        "unknown step type. Available: " + new TreeSet<>(registry.keySet()));
            }

            if (!step.params().containsKey("dn") && !step.params().containsKey("database")) {
                throw new ValidationException(sourceName, i, step.type(),
                        "missing required parameter 'dn'");
            }

            if (step.params().containsKey("wait") && !type.retryable()) {
                throw new ValidationException(sourceName, i, step.type(),
                        "'wait' is only valid on assertions, and this step is an action");
            }

            for (String reference : Interpolator.references(step.params())) {
                if (!known.contains(reference)) {
                    throw new ValidationException(sourceName, i, step.type(),
                            "references '${" + reference + "...}' but no fixture named '"
                                    + reference + "' is declared");
                }
            }
        }
    }
}
