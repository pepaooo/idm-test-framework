package com.idm.test.scenarios.base;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.core.config.TestExecutorProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.core.model.OperationResult;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
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
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class})
@Testcontainers
public abstract class BaseIntegrationTest {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    // ===================================================================
    // Testcontainers - Shared across all tests
    // ===================================================================

    /**
     * OpenLDAP container for LDAP integration tests.
     * Shared across all test classes, started once and reused.
     * <p>
     * After container starts, organizational units are created via LDIF import:
     * - ou=users (for user accounts)
     * - ou=roles (for role definitions)
     * - ou=groups (for security groups)
     */
    @Container
    protected static final GenericContainer<?> LDAP_CONTAINER = new GenericContainer<>(
            DockerImageName.parse("osixia/openldap:1.5.0"))
            .withExposedPorts(389, 636)
            .withEnv("LDAP_ORGANISATION", "Test Organization")
            .withEnv("LDAP_DOMAIN", "test.com")
            .withEnv("LDAP_ADMIN_PASSWORD", "test")
            .withEnv("LDAP_CONFIG_PASSWORD", "config")
            .withEnv("LDAP_TLS", "false")
            .withEnv("LDAP_LOG_LEVEL", "256")  // Increase logging for debugging
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("ldap-init.ldif"),
                    "/tmp/ldap-init.ldif")
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("LDAP")))
            // Wait for LDAP to be fully started and accepting connections
            .waitingFor(Wait.forListeningPort()
                    .withStartupTimeout(Duration.ofMinutes(3)))
            .withStartupTimeout(Duration.ofMinutes(3))
            .withReuse(true);

    /**
     * WireMock server for SOAP service mocking.
     * Started once and reused across all test classes.
     */
    protected static final WireMockServer WIREMOCK_SERVER = new WireMockServer(
            WireMockConfiguration.options()
                    .port(8090)
                    .usingFilesUnderClasspath("wiremock")
                    .globalTemplating(true));

    /**
     * Flag to track if LDAP structure has been initialized.
     * Volatile to ensure visibility across threads.
     */
    private static volatile boolean ldapInitialized = false;

    /**
     * Lock object for synchronizing LDAP initialization.
     */
    private static final Object LDAP_INIT_LOCK = new Object();

    static {
        // Start WireMock server
        WIREMOCK_SERVER.start();
        LoggerFactory.getLogger(BaseIntegrationTest.class)
                .info("WireMock server started on port 8090");
    }

    /**
     * Configure Spring properties dynamically based on container ports.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // LDAP properties
        if (LDAP_CONTAINER.isRunning()) {
            String ldapUrl = String.format("ldap://%s:%d",
                    LDAP_CONTAINER.getHost(),
                    LDAP_CONTAINER.getMappedPort(389));

            registry.add("idm.test.ldap.url", () -> ldapUrl);
            registry.add("idm.test.ldap.base-dn", () -> "dc=test,dc=com");
            registry.add("idm.test.ldap.user-dn", () -> "cn=admin,dc=test,dc=com");
            registry.add("idm.test.ldap.password", () -> "test");

            LoggerFactory.getLogger(BaseIntegrationTest.class)
                    .info("Configured LDAP properties: {}", ldapUrl);
        }

        // WireMock/SOAP properties
        registry.add("idm.test.soap.url", () -> "http://localhost:8090/IDMProv/role/service");
    }

    // ===================================================================
    // Connectors - Injected by Spring
    // ===================================================================

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

        // Verify LDAP connectivity before initializing
        verifyLdapConnectivity();

        // Initialize LDAP structure on first test run
        initializeLdapStructure();

        logConnectorAvailability();

        // Subclasses can override setupTest() for custom setup
        setupTest();
    }

    /**
     * Verify LDAP container is connectable before proceeding with tests.
     * This method adds an additional wait after container startup to ensure
     * the LDAP server is fully ready to accept connections.
     */
    private void verifyLdapConnectivity() {
        if (!LDAP_CONTAINER.isRunning()) {
            log.warn("LDAP container is not running, skipping connectivity check");
            return;
        }

        log.info("Verifying LDAP connectivity...");
        int maxAttempts = 10;
        int attempt = 0;
        boolean connected = false;

        while (attempt < maxAttempts && !connected) {
            attempt++;
            try {
                // Try to execute a simple LDAP search to verify connectivity
                org.testcontainers.containers.Container.ExecResult result = LDAP_CONTAINER.execInContainer(
                        "ldapsearch",
                        "-x",
                        "-H", "ldap://localhost:389",
                        "-b", "dc=test,dc=com",
                        "-D", "cn=admin,dc=test,dc=com",
                        "-w", "test",
                        "(objectClass=*)",
                        "-LLL"
                );

                if (result.getExitCode() == 0) {
                    log.info("LDAP connectivity verified successfully on attempt {}", attempt);
                    connected = true;
                } else {
                    log.warn("LDAP connectivity check failed on attempt {} (exit code: {})",
                            attempt, result.getExitCode());
                    if (attempt < maxAttempts) {
                        Thread.sleep(2000); // Wait 2 seconds before retry
                    }
                }
            } catch (Exception e) {
                log.warn("LDAP connectivity check failed on attempt {}: {}", attempt, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(2000); // Wait 2 seconds before retry
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (!connected) {
            log.error("Failed to verify LDAP connectivity after {} attempts", maxAttempts);
            log.error("Tests may fail due to LDAP connection issues");
        } else {
            // Additional wait to ensure connection pool is ready
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Initialize LDAP organizational structure.
     * This method is called before the first test and creates the required OUs.
     * Uses double-checked locking to ensure initialization happens only once.
     */
    private void initializeLdapStructure() {
        if (!ldapInitialized) {
            synchronized (LDAP_INIT_LOCK) {
                if (!ldapInitialized) {
                    try {
                        log.info("Initializing LDAP organizational structure...");

                        // Import LDIF file to create organizational units
                        org.testcontainers.containers.Container.ExecResult result = LDAP_CONTAINER.execInContainer(
                                "ldapadd",
                                "-x",
                                "-D", "cn=admin,dc=test,dc=com",
                                "-w", "test",
                                "-f", "/tmp/ldap-init.ldif"
                        );

                        if (result.getExitCode() == 0) {
                            log.info("LDAP organizational units created successfully");
                            log.info("  - ou=users,dc=test,dc=com");
                            log.info("  - ou=roles,dc=test,dc=com");
                            log.info("  - ou=groups,dc=test,dc=com");
                            ldapInitialized = true;
                        } else {
                            log.warn("LDAP initialization exit code: {}", result.getExitCode());
                            log.warn("LDAP initialization stderr: {}", result.getStderr());
                            log.warn("LDAP initialization stdout: {}", result.getStdout());
                            // Mark as initialized even if it fails (might be already initialized)
                            ldapInitialized = true;
                        }
                    } catch (Exception e) {
                        log.error("Failed to initialize LDAP structure", e);
                        // Mark as initialized to avoid retry on every test
                        ldapInitialized = true;
                    }
                }
            }
        }
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
