package com.idm.test.connector.soap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for SOAP web services.
 * Maps from application.yml: idm.test.soap.*
 */
@ConfigurationProperties(prefix = "idm.test.soap")
public class SoapProperties {

    private String label;

    public String getLabel() { return label; }

    public void setLabel(String label) { this.label = label; }

    /**
     * SOAP service endpoint URL (e.g., http://idm.company.com:8080/IDMProv/role/service)
     */
    private String url;

    /**
     * Endpoint for the Identity Applications resource service, e.g.
     * https://idm.company.com:8543/IDMProv/resource/service
     * Separate from {@code url}, which addresses the role service.
     */
    private String resourceUrl;

    /**
     * Username for SOAP authentication
     */
    private String username;

    /**
     * Password for SOAP authentication
     */
    private String password;

    /**
     * Connection timeout in milliseconds, applied to the Role and Resource service ports;
     * must be positive.
     */
    private int connectionTimeout = 10000; // 10 seconds

    /**
     * Read timeout in milliseconds (how long to wait for a response), applied to the Role and
     * Resource service ports; must be positive.
     */
    private int readTimeout = 30000; // 30 seconds

    public SoapProperties() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getResourceUrl() {
        return resourceUrl;
    }

    public void setResourceUrl(String resourceUrl) {
        this.resourceUrl = resourceUrl;
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

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }
}
