package com.idm.test.core.exception;

import com.idm.test.core.model.ErrorType;

/**
 * Exception thrown when configuration is invalid or missing.
 */
public class ConfigurationException extends IdmTestException {

    private final String configKey;

    public ConfigurationException(String message) {
        super(message, ErrorType.CONFIGURATION_ERROR);
        this.configKey = null;
    }

    public ConfigurationException(String message, String configKey) {
        super(message, ErrorType.CONFIGURATION_ERROR);
        this.configKey = configKey;
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause, ErrorType.CONFIGURATION_ERROR);
        this.configKey = null;
    }

    public String getConfigKey() {
        return configKey;
    }

    @Override
    public String toString() {
        String base = super.toString();
        if (configKey != null) {
            return base + ", configKey='" + configKey + "'";
        }
        return base;
    }
}
