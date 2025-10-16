package com.idm.test.connector.soap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for SOAP web services.
 * Maps from application.yml: idm.test.soap.*
 */
@ConfigurationProperties(prefix = "idm.test.soap")
public class SoapProperties {

    /**
     * SOAP service endpoint URL (e.g., http://idm.company.com:8080/IDMProv/role/service)
     */
    private String url;

    /**
     * Username for SOAP authentication
     */
    private String username;

    /**
     * Password for SOAP authentication
     */
    private String password;

    /**
     * Connection timeout in milliseconds
     */
    private int connectionTimeout = 10000; // 10 seconds

    /**
     * Read timeout in milliseconds
     */
    private int readTimeout = 30000; // 30 seconds

    /**
     * Enable message logging for debugging
     */
    private boolean messageLogging = false;

    public SoapProperties() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
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

    public boolean isMessageLogging() {
        return messageLogging;
    }

    public void setMessageLogging(boolean messageLogging) {
        this.messageLogging = messageLogging;
    }
}
