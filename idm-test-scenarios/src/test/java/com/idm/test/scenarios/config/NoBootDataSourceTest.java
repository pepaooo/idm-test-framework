package com.idm.test.scenarios.config;

import com.idm.test.scenarios.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot must not build its own embedded DataSource in the scenario contexts (ITF-32). The
 * framework uses only its named pools (the {@code jdbcTemplates} map), and
 * {@link TestSchemaInitializer} creates the {@code test-db} schema; Boot's DataSource only ever
 * received a second, unused copy of {@code schema.sql}.
 */
@DisplayName("Spring Boot's own DataSource is excluded")
class NoBootDataSourceTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("No DataSource, JdbcTemplate, SQL-init initializer or transaction manager bean exists")
    void testNoBootDataSourceBeans() {
        assertThat(context.getBeanNamesForType(DataSource.class)).as("DataSource beans").isEmpty();
        assertThat(context.getBeanNamesForType(JdbcTemplate.class)).as("JdbcTemplate beans").isEmpty();
        assertThat(context.getBeanNamesForType(DataSourceScriptDatabaseInitializer.class))
                .as("spring.sql.init initializers").isEmpty();
        assertThat(context.getBeanNamesForType(PlatformTransactionManager.class))
                .as("transaction managers").isEmpty();
    }

    @Test
    @DisplayName("test-db still has the schema TestSchemaInitializer creates")
    @SuppressWarnings("unchecked")
    void testTestDbSchemaExists() {
        Map<String, JdbcTemplate> templates = (Map<String, JdbcTemplate>) context.getBean("jdbcTemplates");

        Integer users = templates.get("test-db").queryForObject("SELECT COUNT(*) FROM users", Integer.class);

        assertThat(users).isNotNull();
    }
}
