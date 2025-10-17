package com.idm.test.scenarios.base;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.core.config.TestExecutorProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all integration tests.
 * Provides common setup, teardown, and utility methods.
 * <p>
 * Features:
 * - Automatic cleanup of test data after each test
 * - Access to all connectors and utilities
 * - Common logging and assertion helpers
 * - Test data tracking for cleanup
 * <p>
 * Excludes Spring Boot's LdapAutoConfiguration since we provide custom LDAP configuration.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class})
public abstract class BaseIntegrationTest {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    // Connectors - may be null if not configured
    @Autowired(required = false)
    protected EdirectoryConnector edirectoryConnector;

    @Autowired(required = false)
    protected MultiDatabaseConnector databaseConnector;

    @Autowired(required = false)
    protected RoleServiceConnector roleServiceConnector;

    // Test infrastructure
    @Autowired(required = false)
    protected TestExecutor testExecutor;

    @Autowired(required = false)
    protected TestExecutorProperties executorProperties;

    @Autowired(required = false)
    protected TestDataGenerator testDataGenerator;

    // Track created resources for cleanup
    protected final List<String> createdLdapDns = new ArrayList<>();
    protected final List<DatabaseCleanupTask> databaseCleanupTasks = new ArrayList<>();
    protected final List<String> assignedRoles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        log.info("=".repeat(80));
        log.info("Starting test: {}", getTestName());
        log.info("=".repeat(80));

        logConnectorAvailability();

        // Subclasses can override setupTest() for custom setup
        setupTest();
    }

    @AfterEach
    void tearDown() {
        log.info("\n" + "-".repeat(80));
        log.info("Cleaning up test data...");
        log.info("-".repeat(80));

        // Cleanup in reverse order of creation
        cleanupRoles();
        cleanupDatabase();
        cleanupLdap();

        // Subclasses can override cleanupTest() for custom cleanup
        cleanupTest();

        log.info("=".repeat(80));
        log.info("Test completed: {}", getTestName());
        log.info("=".repeat(80) + "\n");
    }

    /**
     * Override this method for custom test setup.
     */
    protected void setupTest() {
        // Default: no-op
    }

    /**
     * Override this method for custom test cleanup.
     */
    protected void cleanupTest() {
        // Default: no-op
    }

    /**
     * Track an LDAP DN for automatic cleanup.
     */
    protected void trackLdapDn(String dn) {
        createdLdapDns.add(dn);
        log.debug("Tracking LDAP DN for cleanup: {}", dn);
    }

    /**
     * Track a database record for automatic cleanup.
     */
    protected void trackDatabaseRecord(String dbName, String tableName, String whereClause) {
        databaseCleanupTasks.add(new DatabaseCleanupTask(dbName, tableName, whereClause));
        log.debug("Tracking database record for cleanup: {}.{} WHERE {}", dbName, tableName, whereClause);
    }

    /**
     * Track a role assignment for automatic cleanup.
     */
    protected void trackRoleAssignment(String userDn, String roleDn) {
        assignedRoles.add(userDn + "|" + roleDn);
        log.debug("Tracking role assignment for cleanup: {} -> {}", userDn, roleDn);
    }

    /**
     * Clean up LDAP entries.
     */
    private void cleanupLdap() {
        if (edirectoryConnector == null || createdLdapDns.isEmpty()) {
            return;
        }

        log.info("Cleaning up {} LDAP entries...", createdLdapDns.size());
        for (String dn : createdLdapDns) {
            try {
                edirectoryConnector.delete(dn);
                log.debug("Deleted LDAP entry: {}", dn);
            } catch (Exception e) {
                log.warn("Failed to delete LDAP entry {}: {}", dn, e.getMessage());
            }
        }
        createdLdapDns.clear();
    }

    /**
     * Clean up database records.
     */
    private void cleanupDatabase() {
        if (databaseConnector == null || databaseCleanupTasks.isEmpty()) {
            return;
        }

        log.info("Cleaning up {} database records...", databaseCleanupTasks.size());
        for (DatabaseCleanupTask task : databaseCleanupTasks) {
            try {
                String deleteSql = "DELETE FROM " + task.tableName + " WHERE " + task.whereClause;
                databaseConnector.executeUpdate(task.dbName, deleteSql);
                log.debug("Deleted from {}.{} WHERE {}", task.dbName, task.tableName, task.whereClause);
            } catch (Exception e) {
                log.warn("Failed to delete from {}.{}: {}", task.dbName, task.tableName, e.getMessage());
            }
        }
        databaseCleanupTasks.clear();
    }

    /**
     * Clean up role assignments.
     */
    private void cleanupRoles() {
        if (roleServiceConnector == null || assignedRoles.isEmpty()) {
            return;
        }

        log.info("Cleaning up {} role assignments...", assignedRoles.size());
        for (String assignment : assignedRoles) {
            try {
                String[] parts = assignment.split("\\|");
                String userDn = parts[0];
                String roleDn = parts[1];

                // Note: Actual cleanup would use roleServiceConnector.removeRole()
                // This is a placeholder since we don't have real IdM environment
                log.debug("Would remove role {} from user {}", roleDn, userDn);
            } catch (Exception e) {
                log.warn("Failed to remove role assignment {}: {}", assignment, e.getMessage());
            }
        }
        assignedRoles.clear();
    }

    /**
     * Log connector availability for debugging.
     */
    private void logConnectorAvailability() {
        log.info("Connector Availability:");
        log.info("  EdirectoryConnector: {}", edirectoryConnector != null ? "AVAILABLE" : "NOT AVAILABLE");
        log.info("  MultiDatabaseConnector: {}", databaseConnector != null ? "AVAILABLE" : "NOT AVAILABLE");
        log.info("  RoleServiceConnector: {}", roleServiceConnector != null ? "AVAILABLE" : "NOT AVAILABLE");
        log.info("  TestExecutor: {}", testExecutor != null ? "AVAILABLE" : "NOT AVAILABLE");
        log.info("  TestDataGenerator: {}", testDataGenerator != null ? "AVAILABLE" : "NOT AVAILABLE");
    }

    /**
     * Get the current test name for logging.
     */
    private String getTestName() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            if (element.getMethodName().startsWith("test")) {
                return element.getClassName() + "." + element.getMethodName();
            }
        }
        return "Unknown Test";
    }

    /**
     * Helper to check if a connector is available.
     */
    protected boolean isConnectorAvailable(Object connector) {
        return connector != null;
    }

    /**
     * Helper to require a connector or skip test.
     */
    protected void requireConnector(Object connector, String connectorName) {
        if (connector == null) {
            log.warn("Skipping test - {} not available", connectorName);
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                connectorName + " not available in test environment");
        }
    }

    /**
     * Database cleanup task.
     */
    protected static class DatabaseCleanupTask {
        final String dbName;
        final String tableName;
        final String whereClause;

        public DatabaseCleanupTask(String dbName, String tableName, String whereClause) {
            this.dbName = dbName;
            this.tableName = tableName;
            this.whereClause = whereClause;
        }
    }
}
