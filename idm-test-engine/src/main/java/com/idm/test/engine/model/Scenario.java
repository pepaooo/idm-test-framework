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
        List<StepSpec> steps,
        List<StepSpec> cleanup) {

    public Scenario {
        cleanup = cleanup == null ? List.of() : cleanup;
    }

    /** A scenario with no cleanup block. */
    public Scenario(String name, String description, Map<String, FixtureSpec> fixtures, List<StepSpec> steps) {
        this(name, description, fixtures, steps, List.of());
    }
}
