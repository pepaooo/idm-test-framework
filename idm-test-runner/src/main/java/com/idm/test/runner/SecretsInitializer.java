package com.idm.test.runner;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

/** Adds the {@code secrets.env} values just below the real environment variables. */
public final class SecretsInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private final Map<String, String> secrets;

    public SecretsInitializer(Map<String, String> secrets) {
        this.secrets = Map.copyOf(secrets);
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addAfter(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, RunnerSettings.secrets(secrets));
    }
}
