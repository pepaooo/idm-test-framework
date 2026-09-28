package com.idm.test.connector.database.config;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("DatabaseConfiguration")
class DatabaseConfigurationTest {

    private static DatabaseProperties.DatabaseConfig h2(String url) {
        DatabaseProperties.DatabaseConfig config = new DatabaseProperties.DatabaseConfig();
        config.setJdbcUrl(url);
        config.setUsername("sa");
        config.setPassword("");
        config.setDriverClassName("org.h2.Driver");
        config.setMaxPoolSize(2);
        config.setMinIdle(1);
        config.setConnectionTimeout(2000L);
        config.setIdleTimeout(600000L);
        config.setMaxLifetime(1800000L);
        return config;
    }

    private static DatabaseProperties properties() {
        DatabaseProperties properties = new DatabaseProperties();
        properties.setDatabases(Map.of(
                "up", h2("jdbc:h2:mem:lazy-up;DB_CLOSE_DELAY=-1"),
                // Nothing listens on port 1: an eager pool fails right here, a lazy one does not.
                "down", h2("jdbc:h2:tcp://localhost:1/none")));
        return properties;
    }

    @Test
    @DisplayName("Pools are built without connecting, so an unreachable database does not stop startup")
    void testUnreachableDatabaseDoesNotFailStartup() {
        assertThatCode(() -> new DatabaseConfiguration().jdbcTemplates(properties()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A reachable database answers on first use; an unreachable one fails only when used")
    void testConnectsOnFirstUse() {
        Map<String, JdbcTemplate> templates = new DatabaseConfiguration().jdbcTemplates(properties());
        MultiDatabaseConnector connector = new MultiDatabaseConnector(templates);

        OperationResult<List<Map<String, Object>>> up = connector.executeQuery("up", "SELECT 1 AS one");
        assertThat(up.isSuccess()).as(up.getMessage()).isTrue();

        OperationResult<List<Map<String, Object>>> down = connector.executeQuery("down", "SELECT 1 AS one");
        assertThat(down.isFailure()).isTrue();
        assertThat(down.getErrorType()).contains(ErrorType.DATABASE_ERROR);
    }
}
