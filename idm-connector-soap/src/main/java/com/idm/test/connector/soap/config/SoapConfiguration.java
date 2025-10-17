package com.idm.test.connector.soap.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for SOAP web service connectors.
 * <p>
 * This configuration enables the SoapProperties and makes SOAP-related beans
 * conditional on the presence of the soap.url property. This allows tests to
 * run without SOAP infrastructure if it's not configured.
 */
@Configuration
@EnableConfigurationProperties(SoapProperties.class)
@ConditionalOnProperty(prefix = "idm.test.soap", name = "url")
public class SoapConfiguration {
    // This class enables SoapProperties as a bean and makes all SOAP-related
    // components conditional on the presence of idm.test.soap.url property
}
