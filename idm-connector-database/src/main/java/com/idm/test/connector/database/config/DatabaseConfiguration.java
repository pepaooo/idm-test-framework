package com.idm.test.connector.database.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Configures JdbcTemplate beans for each configured database.
 * Creates a HikariCP connection pool for each database.
 */
@Configuration
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfiguration.class);

    /**
     * Creates a map of JdbcTemplate instances, one for each configured database.
     * The map key is the database name as configured in application.yml.
     *
     * @param properties database configuration properties
     * @return map of database name to JdbcTemplate
     */
    @Bean
    public Map<String, JdbcTemplate> jdbcTemplates(DatabaseProperties properties) {
        Map<String, JdbcTemplate> templates = new HashMap<>();

        properties.getDatabases().forEach((name, config) -> {
            log.info("Configuring database connection: {}", name);

            try {
                DataSource dataSource = createDataSource(name, config);
                JdbcTemplate template = new JdbcTemplate(dataSource);
                templates.put(name, template);

                log.info("Database connection '{}' configured successfully", name);
            } catch (Exception e) {
                log.error("Failed to configure database connection '{}': {}", name, e.getMessage());
                throw new RuntimeException("Failed to configure database: " + name, e);
            }
        });

        if (templates.isEmpty()) {
            log.warn("No database connections configured");
        } else {
            log.info("Configured {} database connection(s): {}", templates.size(), templates.keySet());
        }

        return templates;
    }

    /**
     * Creates a HikariCP DataSource for the given database configuration.
     *
     * @param name   database name
     * @param config database configuration
     * @return configured DataSource
     */
    private DataSource createDataSource(String name, DatabaseProperties.DatabaseConfig config) {
        HikariConfig hikariConfig = new HikariConfig();

        hikariConfig.setPoolName("HikariPool-" + name);
        hikariConfig.setJdbcUrl(config.getJdbcUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setDriverClassName(config.getDriverClassName());

        // Connection pool settings
        hikariConfig.setMaximumPoolSize(config.getMaxPoolSize());
        hikariConfig.setMinimumIdle(config.getMinIdle());
        hikariConfig.setConnectionTimeout(config.getConnectionTimeout());
        hikariConfig.setIdleTimeout(config.getIdleTimeout());
        hikariConfig.setMaxLifetime(config.getMaxLifetime());

        // Additional HikariCP settings for test stability
        hikariConfig.setConnectionTestQuery("SELECT 1");
        hikariConfig.setValidationTimeout(5000);
        hikariConfig.setLeakDetectionThreshold(60000);

        return new HikariDataSource(hikariConfig);
    }
}
