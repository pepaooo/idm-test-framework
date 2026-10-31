package com.idm.test.connector.database.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly invoked; the default suite never starts Oracle or contacts an external database. */
class OracleConnectionValidationIT {

    @Test
    @Timeout(420)
    void validatesOracleConnectionOnFirstUse() throws Exception {
        String externalUrl = System.getenv("ORACLE_IT_JDBC_URL");
        String password = externalUrl == null ? UUID.randomUUID().toString()
                : Files.readString(Path.of(Objects.requireNonNull(System.getenv("ORACLE_IT_PASSWORD_FILE"),
                        "ORACLE_IT_PASSWORD_FILE is required with ORACLE_IT_JDBC_URL")))
                        .replaceFirst("\\r?\\n$", "");
        String username = externalUrl == null ? "system"
                : Objects.requireNonNull(System.getenv("ORACLE_IT_USERNAME"),
                        "ORACLE_IT_USERNAME is required with ORACLE_IT_JDBC_URL");

        try (GenericContainer<?> oracle = externalUrl == null
                ? new GenericContainer<>("gvenzl/oracle-xe:21.3.0-slim")
                        .withEnv("ORACLE_PASSWORD", password)
                        .withExposedPorts(1521)
                        .waitingFor(Wait.forLogMessage(".*DATABASE IS READY TO USE!.*\\n", 1)
                                .withStartupTimeout(Duration.ofMinutes(5)))
                : null) {
            String jdbcUrl = externalUrl;
            if (oracle != null) {
                oracle.start();
                jdbcUrl = "jdbc:oracle:thin:@//" + oracle.getHost() + ":"
                        + oracle.getMappedPort(1521) + "/XEPDB1";
            }

            // Establish that the database and application SQL work before testing pool validation.
            Properties credentials = new Properties();
            credentials.setProperty("user", username);
            credentials.setProperty("password", password);
            credentials.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
            credentials.setProperty("oracle.jdbc.ReadTimeout", "15000");
            try (var connection = DriverManager.getConnection(jdbcUrl, credentials);
                 var statement = connection.createStatement()) {
                assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(21);
                try (var result = statement.executeQuery("SELECT 1 FROM DUAL")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
            }
            System.out.println("Oracle 21 direct JDBC probe passed; checking production pool validation.");

            DatabaseProperties.DatabaseConfig config = new DatabaseProperties.DatabaseConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);
            config.setDriverClassName("oracle.jdbc.OracleDriver");
            config.setMaxPoolSize(1);
            config.setMinIdle(0);
            config.setConnectionTimeout(10000L);
            DatabaseProperties properties = new DatabaseProperties();
            properties.setDatabases(Map.of("oracle-validation", config));

            DatabaseConfiguration configuration = new DatabaseConfiguration();
            try {
                var template = configuration.jdbcTemplates(properties).get("oracle-validation");
                assertThat(template.queryForObject("SELECT 1 FROM DUAL", Integer.class)).isEqualTo(1);
            } finally {
                configuration.destroy();
            }
        }
    }
}
