package com.idm.test.connector.rest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for REST API endpoints.
 * Supports multiple REST APIs with individual settings.
 * <p>
 * Example configuration in application.yml:
 * <pre>
 * idm:
 *   test:
 *     rest:
 *       idm:
 *         base-url: https://idm.company.com:8543/IDMProv
 *         username: admin
 *         password: secret
 *         connect-timeout: 10000
 *         read-timeout: 30000
 *       sap:
 *         base-url: https://sap.company.com/api/v1
 *         connect-timeout: 15000
 *         read-timeout: 45000
 * </pre>
 */
@ConfigurationProperties(prefix = "idm.test")
public class RestProperties {

    private Map<String, RestApiConfig> rest = new HashMap<>();

    public Map<String, RestApiConfig> getRest() {
        return rest;
    }

    public void setRest(Map<String, RestApiConfig> rest) {
        this.rest = rest;
    }

    /**
     * Gets configuration for a specific REST API by name.
     *
     * @param apiName the name of the API (e.g., "idm", "sap", "workday")
     * @return the API configuration, or null if not found
     */
    public RestApiConfig getApiConfig(String apiName) {
        return rest.get(apiName);
    }

    /**
     * Configuration for a single REST API endpoint.
     */
    public static class RestApiConfig {
        private String label;

        public String getLabel() { return label; }

        public void setLabel(String label) { this.label = label; }

        private String baseUrl;
        private String username;
        private String password;
        private Integer connectTimeout = 10000; // 10 seconds
        private Integer readTimeout = 30000;    // 30 seconds
        private boolean useOAuth = false;
        private String oauthClientName;  // Reference to spring.security.oauth2.client.registration

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Integer getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Integer connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Integer getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Integer readTimeout) {
            this.readTimeout = readTimeout;
        }

        public boolean isUseOAuth() {
            return useOAuth;
        }

        public void setUseOAuth(boolean useOAuth) {
            this.useOAuth = useOAuth;
        }

        public String getOauthClientName() {
            return oauthClientName;
        }

        public void setOauthClientName(String oauthClientName) {
            this.oauthClientName = oauthClientName;
        }
    }
}
