package com.idm.test.engine.model;

import java.util.List;
import java.util.Map;

/**
 * A parsed scenario file. Produced by ScenarioLoader, checked by ScenarioValidator,
 * executed by ScenarioEngine.
 */
public record Scenario(
        String name,
        String description,
        Map<String, FixtureSpec> fixtures,
        List<StepSpec> steps) {
}
