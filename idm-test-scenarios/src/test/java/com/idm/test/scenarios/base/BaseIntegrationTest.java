package com.idm.test.scenarios.base;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.core.config.TestExecutorProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.core.model.OperationResult;
import com.idm.test.utils.data.TestDataGenerator;
import com.idm.test.scenarios.config.IdmTestSchema;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for all integration tests.
 * Provides common setup, teardown, and utility methods.
 * <p>
 * Features:
 * - Automatic cleanup of test data after each test
 * - Access to all connectors and utilities
 * - Common logging and assertion helpers
 * - Test data tracking for cleanup
 * - Testcontainers for LDAP and SOAP services
 * <p>
 * Testcontainers automatically start:
 * - OpenLDAP server (ldap://localhost:random-port)
 * - WireMock SOAP server (http://localhost:8090)
 * <p>
 * Excludes Spring Boot's LdapAutoConfiguration since we provide custom LDAP configuration.
 * <p>
 * {@code RestProperties} no longer needs enabling here: {@code RestClientConfiguration} now
 * carries {@code @EnableConfigurationProperties(RestProperties.class)} itself (the same fix
 * this class used to patch in test-side only), and that configuration is already reached by
 * this context's component scan, the same way {@code LdapConfiguration} and
 * {@code SoapConfiguration} enable their own properties classes.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class})
public abstract class BaseIntegrationTest {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    // ===================================================================
    // Test infrastructure - started once for the whole JVM
    // ===================================================================

    private static final String LDAP_BASE_DN = "dc=test,dc=com";
    private static final String LDAP_BIND_DN = "cn=admin,dc=test,dc=com";
    private static final String LDAP_BIND_PASSWORD = "test";

    /**
     * In-memory LDAP directory. Binds an ephemeral port so parallel module builds
     * cannot collide, and imports ldap-init.ldif to create ou=users, ou=roles, ou=groups.
     */
    private static final EmbeddedLdapServer LDAP_SERVER = startLdapServer();

    /**
     * WireMock server for SOAP mocking, on a dynamically allocated port.
     */
    private static final WireMockServer WIREMOCK_SERVER = new WireMockServer(
            WireMockConfiguration.options()
                    .dynamicPort()
                    .usingFilesUnderClasspath("wiremock")
                    .globalTemplating(true));

    static {
        WIREMOCK_SERVER.start();
        LoggerFactory.getLogger(BaseIntegrationTest.class)
                .info("WireMock server started on port {}", WIREMOCK_SERVER.port());
    }

    private static EmbeddedLdapServer startLdapServer() {
        try {
            return EmbeddedLdapServer.start(
                    LDAP_BASE_DN, LDAP_BIND_DN, LDAP_BIND_PASSWORD, 0, "ldap-init.ldif",
                    IdmTestSchema.edirectoryLikeSchema());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start embedded LDAP server", e);
        }
    }

    /**
     * Point the connectors at the embedded infrastructure.
     * Everything else comes from src/test/resources/application.yml, so switching to a
     * real directory in phase (b) is a profile change rather than a code change.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("idm.test.ldap.url", LDAP_SERVER::getUrl);
        registry.add("idm.test.ldap.base-dn", () -> LDAP_BASE_DN);
        registry.add("idm.test.ldap.user-dn", () -> LDAP_BIND_DN);
        registry.add("idm.test.ldap.password", () -> LDAP_BIND_PASSWORD);
        registry.add("idm.test.soap.url",
                () -> "http://localhost:" + WIREMOCK_SERVER.port() + "/IDMProv/role/service");
        // ResourceServiceConnector is only registered as a bean when this property is set
        // (@ConditionalOnProperty), so it must be non-null here too, even though no scenario
        // executed by this module drives a resource grant/revoke against it.
        registry.add("idm.test.soap.resource-url",
                () -> "http://localhost:" + WIREMOCK_SERVER.port() + "/IDMProv/resource/service");
    }

    // ===================================================================
    // Connectors - injected by Spring, required
    // ===================================================================

    // These are deliberately NOT optional. A missing bean must fail context
    // startup loudly rather than silently skipping the tests that need it.

    @Autowired
    protected EdirectoryConnector edirectoryConnector;

    @Autowired
    protected MultiDatabaseConnector databaseConnector;

    @Autowired
    protected RoleServiceConnector roleServiceConnector;

    @Autowired
    protected ResourceServiceConnector resourceServiceConnector;

    @Autowired
    protected IdmRestConnector idmRestConnector;

    @Autowired
    protected RestProperties restProperties;

    @Autowired
    protected RestAuthenticator restAuthenticator;

    @Autowired
    protected TestExecutor testExecutor;

    @Autowired
    protected TestExecutorProperties executorProperties;

    @Autowired
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
     * Helper to safely extract data from OperationResult with proper assertions.
     * This prevents NoSuchElementException by checking if data is present first.
     *
     * @param result The operation result to extract data from
     * @param description Description of the operation for error messages
     * @param <T> Type of data
     * @return The data from the result
     * @throws AssertionError if operation failed or data is not present
     */
    protected <T> T assertDataPresent(OperationResult<T> result, String description) {
        assertThat(result.isSuccess())
                .as(description + " should succeed. Error: " + result.getMessage())
                .isTrue();
        assertThat(result.getData())
                .as(description + " should have data present")
                .isPresent();
        return result.getData().get();
    }

    /**
     * Helper to safely extract data from OperationResult with custom failure message.
     * Use this when you want to provide a specific error message for operation failure.
     *
     * @param result The operation result to extract data from
     * @param description Description of the operation
     * @param failureMessage Custom message to show if operation fails
     * @param <T> Type of data
     * @return The data from the result
     * @throws AssertionError if operation failed or data is not present
     */
    protected <T> T assertDataPresent(OperationResult<T> result, String description, String failureMessage) {
        if (!result.isSuccess()) {
            throw new AssertionError(failureMessage + ". Error: " + result.getMessage());
        }
        assertThat(result.getData())
                .as(description + " should have data present")
                .isPresent();
        return result.getData().get();
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
