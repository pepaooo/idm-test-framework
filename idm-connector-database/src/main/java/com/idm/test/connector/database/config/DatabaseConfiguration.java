package com.idm.test.connector.database.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configures JdbcTemplate beans for each configured database.
 * Creates a HikariCP connection pool for each database, lazily: each pool is
 * created on first use, not at startup. The pools are not beans of their own, so this
 * configuration closes them when the context closes.
 */
@Configuration
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseConfiguration implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfiguration.class);

    private final List<HikariDataSource> dataSources = new ArrayList<>();

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

    /** Closes every pool {@link #jdbcTemplates} built: their connections, and their housekeeper threads. */
    @Override
    public void destroy() {
        List<HikariDataSource> open;
        synchronized (dataSources) {
            open = new ArrayList<>(dataSources);
            dataSources.clear();
        }
        for (HikariDataSource dataSource : open) {
            try {
                dataSource.close();
            } catch (RuntimeException e) {
                log.warn("Could not close database pool {}: {}", dataSource.getPoolName(), e.toString());
            }
        }
    }

    /**
     * Creates a HikariCP DataSource for the given database configuration.
     *
     * @param name   database name
     * @param config database configuration
     * @return configured DataSource
     */
    private DataSource createDataSource(String name, DatabaseProperties.DatabaseConfig config) {
        String driverClassName = config.getDriverClassName();
        if (driverClassName != null && !driverClassName.isBlank() && !driverLoads(driverClassName.strip())) {
            // HikariConfig loads the class right away, which would stop every run over a database
            // no scenario uses. Only this database fails, on first use; idmtest run checks the
            // databases its scenarios use before starting, and names the drivers folder.
            log.warn("No JDBC driver class '{}' for database '{}'; it fails when used", driverClassName, name);
            return new MissingDriverDataSource(name, driverClassName.strip());
        }
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

        // Use JDBC Connection.isValid(): validation SQL differs between databases (ITF-16).
        hikariConfig.setValidationTimeout(5000);
        hikariConfig.setLeakDetectionThreshold(60000);

        // Lazy on purpose: the no-arg HikariDataSource starts its pool on the first
        // getConnection(), so a database no scenario uses is never contacted, not even by the
        // min-idle housekeeper. A used database that is unreachable fails its first query, as a
        // DATABASE_ERROR, instead of stopping the whole context.
        HikariDataSource dataSource = new HikariDataSource();
        hikariConfig.copyStateTo(dataSource);
        synchronized (dataSources) {
            dataSources.add(dataSource);
        }
        return dataSource;
    }

    /** Whether the class loads as HikariCP loads it: its own class loader, then the context class loader. */
    private static boolean driverLoads(String className) {
        for (ClassLoader loader : new ClassLoader[] {HikariConfig.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader()}) {
            if (loader == null) {
                continue;
            }
            try {
                Class.forName(className, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                // try the next loader
            }
        }
        return false;
    }

    /** A database whose driver class is missing: every connection request says which, and for which database. */
    private static final class MissingDriverDataSource extends AbstractDataSource {

        private final String name;
        private final String driverClassName;

        MissingDriverDataSource(String name, String driverClassName) {
            this.name = name;
            this.driverClassName = driverClassName;
        }

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("no JDBC driver class '" + driverClassName + "' for database '" + name
                    + "'; add the driver jar (for idmtest, to the workspace's drivers/ folder)");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }
}
