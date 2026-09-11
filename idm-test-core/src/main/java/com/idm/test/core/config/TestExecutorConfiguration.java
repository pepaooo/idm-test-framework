package com.idm.test.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link TestExecutorProperties} so it can be injected.
 * <p>
 * Mirrors the pattern used by LdapConfiguration, DatabaseConfiguration and
 * SoapConfiguration, each of which enables its own properties class. Without
 * this, TestExecutorProperties is annotated @ConfigurationProperties but never
 * becomes a bean, so every consumer outside a test that declares
 * {@code @EnableConfigurationProperties} itself receives nothing.
 */
@Configuration
@EnableConfigurationProperties(TestExecutorProperties.class)
public class TestExecutorConfiguration {
}
