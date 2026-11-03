package com.idm.test.engine.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * What the engine knows about the scenario a step runs in, for the few steps that need more than
 * their parameters. Step instances are shared between scenarios (and between threads of a run), so
 * anything scenario-specific has to arrive here rather than live in the step.
 *
 * @param scenarioStartedAt when the scenario began: the same for every step, every attempt of a wait
 *                          and every cleanup entry of one scenario. {@code file.verify} uses it so that a
 *                          file left by an earlier run cannot make it pass.
 */
public record StepContext(Instant scenarioStartedAt) {

    public StepContext {
        Objects.requireNonNull(scenarioStartedAt, "scenarioStartedAt");
    }
}
