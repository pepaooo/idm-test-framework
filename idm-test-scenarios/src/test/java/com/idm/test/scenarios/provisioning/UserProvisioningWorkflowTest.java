package com.idm.test.scenarios.provisioning;

import com.idm.test.core.model.OperationResult;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import com.idm.test.scenarios.fixtures.UserTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.idm.test.connector.ldap.model.LdapUser;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for user provisioning workflows.
 * <p>
 * Tests the complete lifecycle of creating a user in IdM and verifying
 * the provisioning propagates to all connected systems (LDAP, databases).
 * <p>
 * Workflow tested:
 * 1. Generate test user data
 * 2. Create user in IdM (simulated)
 * 3. Wait for user to appear in eDirectory (LDAP)
 * 4. Verify user attributes in LDAP
 * 5. Wait for provisioning to target databases
 * 6. Verify user data in each database
 * 7. Automatic cleanup after test
 */
@DisplayName("User Provisioning Workflow Tests")
class UserProvisioningWorkflowTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should create user and verify LDAP provisioning")
    void testUserProvisioningToLdap() {
        // Require LDAP connector for this test

        // STEP 1: Generate test user data
        log.info("\n[STEP 1] Generating test user data...");
        UserTestData testUser = UserTestData.randomUser(testDataGenerator)
                .withDepartment("Engineering")
                .withJobTitle("Software Engineer");

        log.info("Generated user: {}", testUser);
        log.info("  User ID: {}", testUser.getUserId());
        log.info("  Name: {}", testUser.getFullName());
        log.info("  Email: {}", testUser.getEmail());
        log.info("  Department: {}", testUser.getDepartment());

        // STEP 2: Create user in LDAP (simulating IdM provisioning)
        log.info("\n[STEP 2] Creating user in LDAP...");
        String userDn = testUser.buildLdapDn("ou=users,dc=test,dc=com");
        log.info("User DN: {}", userDn);

        OperationResult<LdapUser> createResult = edirectoryConnector.create(
                testUser.toLdapUser(userDn)
        );

        assertThat(createResult.isSuccess())
                .as("User should be created successfully in LDAP")
                .isTrue();

        // Track for cleanup
        trackLdapDn(userDn);

        log.info("User created successfully in LDAP");

        // STEP 3: Verify user exists in LDAP
        log.info("\n[STEP 3] Verifying user in LDAP...");
        boolean userExists = testExecutor.waitUntil(
                () -> edirectoryConnector.read(userDn).isSuccess(),
                executorProperties.getLdapProvisionTimeout(),
                executorProperties.getDefaultPollInterval()
        );

        assertThat(userExists)
                .as("User should exist in LDAP within timeout")
                .isTrue();

        log.info("User verified in LDAP");

        // STEP 4: Verify user attributes
        log.info("\n[STEP 4] Verifying user attributes...");
        OperationResult<LdapUser> readResult = edirectoryConnector.read(userDn);

        assertThat(readResult.isSuccess()).isTrue();
        assertThat(readResult.getData()).isPresent();

        LdapUser user = readResult.getData().get();
        log.info("Retrieved user from LDAP: {}", user.getDn());

        // Verify key attributes
        assertThat(user.getGivenName())
                .as("First name should match")
                .isEqualTo(testUser.getFirstName());

        assertThat(user.getSn())
                .as("Last name should match")
                .isEqualTo(testUser.getLastName());

        assertThat(user.getMail())
                .as("Email should match")
                .isEqualTo(testUser.getEmail());

        log.info("All user attributes verified successfully");
        log.info("\n[TEST COMPLETE] User provisioning to LDAP successful!");
    }

    @Test
    @DisplayName("Should create user and verify database provisioning")
    void testUserProvisioningToDatabase() {
        // Require database connector for this test

        // STEP 1: Generate test user data
        log.info("\n[STEP 1] Generating test user data...");
        UserTestData testUser = UserTestData.randomUser(testDataGenerator)
                .withDepartment("Sales")
                .withJobTitle("Sales Representative");

        log.info("Generated user: {}", testUser);

        // STEP 2: Create user in database (simulating IdM provisioning)
        log.info("\n[STEP 2] Creating user in test database...");

        String insertSql = """
                INSERT INTO users (user_id, first_name, last_name, email, department, job_title, active)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        OperationResult<Integer> insertResult = databaseConnector.executeUpdate(
                "test-db",
                insertSql,
                testUser.getUserId(),
                testUser.getFirstName(),
                testUser.getLastName(),
                testUser.getEmail(),
                testUser.getDepartment(),
                testUser.getJobTitle(),
                testUser.isActive()
        );

        assertThat(insertResult.isSuccess())
                .as("User should be inserted into database")
                .isTrue();

        assertThat(insertResult.getData()).isPresent();
        assertThat(insertResult.getData().get())
                .as("One row should be inserted")
                .isEqualTo(1);

        // Track for cleanup
        trackDatabaseRecord("test-db", "users", "user_id = '" + testUser.getUserId() + "'");

        log.info("User created in database");

        // STEP 3: Wait and verify user in database
        log.info("\n[STEP 3] Verifying user in database...");

        boolean userProvisioned = testExecutor.waitUntil(
                () -> {
                    OperationResult<List<Map<String, Object>>> result = databaseConnector.executeQuery(
                            "test-db",
                            "SELECT * FROM users WHERE user_id = ?",
                            testUser.getUserId()
                    );
                    return result.isSuccess() && !result.getData().get().isEmpty();
                },
                executorProperties.getDatabaseProvisionTimeout(),
                executorProperties.getDefaultPollInterval()
        );

        assertThat(userProvisioned)
                .as("User should be provisioned to database within timeout")
                .isTrue();

        log.info("User verified in database");

        // STEP 4: Verify user data
        log.info("\n[STEP 4] Verifying user data in database...");

        OperationResult<List<Map<String, Object>>> queryResult = databaseConnector.executeQuery(
                "test-db",
                "SELECT * FROM users WHERE user_id = ?",
                testUser.getUserId()
        );

        assertThat(queryResult.isSuccess()).isTrue();
        assertThat(queryResult.getData()).isPresent();

        List<Map<String, Object>> rows = queryResult.getData().get();
        assertThat(rows).hasSize(1);

        Map<String, Object> userRow = rows.get(0);
        log.info("Retrieved user data: {}", userRow);

        // Verify data
        assertThat(userRow.get("first_name"))
                .as("First name should match")
                .isEqualTo(testUser.getFirstName());

        assertThat(userRow.get("last_name"))
                .as("Last name should match")
                .isEqualTo(testUser.getLastName());

        assertThat(userRow.get("email"))
                .as("Email should match")
                .isEqualTo(testUser.getEmail());

        assertThat(userRow.get("department"))
                .as("Department should match")
                .isEqualTo(testUser.getDepartment());

        log.info("All user data verified successfully");
        log.info("\n[TEST COMPLETE] User provisioning to database successful!");
    }

    @Test
    @DisplayName("Should handle provisioning timeout gracefully")
    void testProvisioningTimeout() {
        // Require LDAP connector for this test

        log.info("\n[TEST] Testing provisioning timeout handling...");

        // Try to read a non-existent user with very short timeout
        String nonExistentDn = "cn=nonexistent.user,ou=users,dc=test,dc=com";

        boolean foundUser = testExecutor.waitUntil(
                () -> edirectoryConnector.read(nonExistentDn).isSuccess(),
                java.time.Duration.ofSeconds(2),  // Very short timeout
                java.time.Duration.ofMillis(500)
        );

        assertThat(foundUser)
                .as("Non-existent user should not be found")
                .isFalse();

        log.info("Timeout handled correctly - user not found");
        log.info("\n[TEST COMPLETE] Timeout handling verified!");
    }

    @Test
    @DisplayName("Should provision user with minimal required attributes")
    void testMinimalUserProvisioning() {
        // Require LDAP connector for this test

        log.info("\n[TEST] Creating user with minimal attributes...");

        // Create minimal user
        UserTestData minimalUser = UserTestData.minimalUser(testDataGenerator);

        log.info("Minimal user: {}", minimalUser);

        String userDn = minimalUser.buildLdapDn("ou=users,dc=test,dc=com");

        OperationResult<LdapUser> createResult = edirectoryConnector.create(
                minimalUser.toLdapUser(userDn)
        );

        assertThat(createResult.isSuccess())
                .as("Minimal user should be created successfully")
                .isTrue();

        // Track for cleanup
        trackLdapDn(userDn);

        // Verify
        OperationResult<LdapUser> readResult = edirectoryConnector.read(userDn);
        assertThat(readResult.isSuccess()).isTrue();

        log.info("Minimal user created and verified successfully");
        log.info("\n[TEST COMPLETE] Minimal user provisioning successful!");
    }
}
