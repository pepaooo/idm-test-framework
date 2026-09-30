package com.idm.test.scenarios.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Creates the test schema in the H2 "test-db" database.
 * <p>
 * {@code spring.sql.init} cannot do this: DatabaseConfiguration builds its Hikari
 * DataSources privately inside the jdbcTemplates map bean, so no DataSource bean is
 * exposed for Spring Boot to initialize. When {@code spring.sql.init} was configured it ran
 * schema.sql against Spring Boot's own embedded H2, never against test-db; that
 * DataSource is now excluded (ITF-32), so this class is the only thing that runs schema.sql.
 * <p>
 * Runs once at context startup rather than via H2's INIT=RUNSCRIPT, which would fire
 * on every pooled connection and drop the tables mid-test.
 */
@Component
public class TestSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(TestSchemaInitializer.class);
    private static final String TEST_DATABASE = "test-db";
    private static final String SCHEMA_SCRIPT = "schema.sql";

    private final Map<String, JdbcTemplate> jdbcTemplates;

    public TestSchemaInitializer(Map<String, JdbcTemplate> jdbcTemplates) {
        this.jdbcTemplates = jdbcTemplates;
    }

    @PostConstruct
    public void initializeSchema() {
        JdbcTemplate template = jdbcTemplates.get(TEST_DATABASE);

        if (template == null) {
            throw new IllegalStateException(
                    "Database '" + TEST_DATABASE + "' is not configured; cannot create the test schema");
        }

        DatabasePopulatorUtils.execute(
                new ResourceDatabasePopulator(new ClassPathResource(SCHEMA_SCRIPT)),
                template.getDataSource());

        log.info("Test schema created in '{}' from {}", TEST_DATABASE, SCHEMA_SCRIPT);
    }
}
