package com.idm.test.wiring;

import com.idm.test.core.config.TestExecutorProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.data.TestDataGenerator;

import java.util.Map;

/** Builds the validator and the engine the same way for every runner. */
public final class EngineFactory {

    private EngineFactory() {
    }

    public static ScenarioValidator validator(Map<String, StepType> registry) {
        return new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS);
    }

    public static ScenarioEngine engine(Map<String, StepType> registry, TestDataGenerator generator,
                                        TestExecutor executor, TestExecutorProperties properties) {
        return new ScenarioEngine(
                registry,
                new FixtureFactory(generator),
                executor,
                properties.getDefaultTimeout(),
                properties.getDefaultPollInterval(),
                new ExpressionEvaluator());
    }
}
