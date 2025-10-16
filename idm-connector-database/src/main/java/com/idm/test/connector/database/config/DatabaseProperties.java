package com.idm.test.connector.database.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for multiple database connections.
 * Each database is identified by a name and has its own connection settings.
 * <p>
 * Example configuration in application.yml:
 * <pre>
 * idm:
 *   test:
 *     databases:
 *       sap:
 *         jdbc-url: jdbc:postgresql://localhost:5432/sap
 *         username: sapuser
 *         password: sappass
 *         driver-class-name: org.postgresql.Driver
 *       oracle-hr:
 *         jdbc-url: jdbc:oracle:thin:@localhost:1521:HRDB
 *         username: hruser
 *         password: hrpass
 *         driver-class-name: oracle.jdbc.OracleDriver
 * </pre>
 */
@ConfigurationProperties(prefix = "idm.test")
public class DatabaseProperties {

    private Map<String, DatabaseConfig> databases = new HashMap<>();

    public Map<String, DatabaseConfig> getDatabases() {
        return databases;
    }

    public void setDatabases(Map<String, DatabaseConfig> databases) {
        this.databases = databases;
    }

    /**
     * Configuration for a single database connection.
     */
    public static class DatabaseConfig {
        private String jdbcUrl;
        private String username;
        private String password;
        private String driverClassName;
        private Integer maxPoolSize = 10;
        private Integer minIdle = 2;
        private Long connectionTimeout = 30000L; // 30 seconds
        private Long idleTimeout = 600000L; // 10 minutes
        private Long maxLifetime = 1800000L; // 30 minutes

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
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

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public Integer getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(Integer maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public Integer getMinIdle() {
            return minIdle;
        }

        public void setMinIdle(Integer minIdle) {
            this.minIdle = minIdle;
        }

        public Long getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(Long connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }

        public Long getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Long idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        public Long getMaxLifetime() {
            return maxLifetime;
        }

        public void setMaxLifetime(Long maxLifetime) {
            this.maxLifetime = maxLifetime;
        }
    }
}
