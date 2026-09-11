package com.idm.test.scenarios.deprovisioning;

import com.idm.test.core.model.OperationResult;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import com.idm.test.scenarios.fixtures.UserTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.idm.test.connector.ldap.model.LdapUser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for user deprovisioning workflows.
 * <p>
 * Tests the complete lifecycle of deprovisioning (disabling/deleting) users
 * and verifying the changes propagate to all connected systems.
 * <p>
 * Workflows tested:
 * 1. Soft delete (disable user account)
 * 2. Hard delete (remove user from all systems)
 * 3. Access revocation (remove database access, role assignments)
 * 4. Cleanup verification
 * 5. Grace period handling
 */
@DisplayName("User Deprovisioning Workflow Tests")
class UserDeprovisioningWorkflowTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should disable user (soft delete) across all systems")
    void testUserDisableWorkflow() {
        // Require connectors

        // STEP 1: Create active user in both systems
        log.info("\n[STEP 1] Creating active user...");
        UserTestData testUser = UserTestData.randomUser(testDataGenerator)
                .withActive(true);

        String userDn = testUser.buildLdapDn("ou=users,dc=test,dc=com");

        // Create in LDAP
        edirectoryConnector.create(testUser.toLdapUser(userDn));
        trackLdapDn(userDn);

        // Create in database
        String insertSql = """
                INSERT INTO users (user_id, first_name, last_name, email, active)
                VALUES (?, ?, ?, ?, ?)
                """;

        databaseConnector.executeUpdate(
                "test-db",
                insertSql,
                testUser.getUserId(),
                testUser.getFirstName(),
                testUser.getLastName(),
                testUser.getEmail(),
                true
        );

        trackDatabaseRecord("test-db", "users", "user_id = '" + testUser.getUserId() + "'");

        log.info("User created and active: {}", testUser.getUserId());

        // STEP 2: Disable user in LDAP
        log.info("\n[STEP 2] Disabling user account in LDAP...");

        Map<String, Object> modifications = new HashMap<>();
        modifications.put("loginDisabled", "TRUE");

        OperationResult<Void> disableResult = edirectoryConnector.update(userDn, modifications);

        assertThat(disableResult.isSuccess())
                .as("User disable should succeed")
                .isTrue();

        log.info("User disabled in LDAP");

        // STEP 3: Disable user in database
        log.info("\n[STEP 3] Disabling user in database...");

        String updateSql = "UPDATE users SET active = ? WHERE user_id = ?";

        OperationResult<Integer> updateResult = databaseConnector.executeUpdate(
                "test-db",
                updateSql,
                false,
                testUser.getUserId()
        );

        Integer rowsUpdated = assertDataPresent(updateResult, "User disable update");
        assertThat(rowsUpdated).isEqualTo(1);

        log.info("User disabled in database");

        // STEP 4: Verify user is disabled but still exists
        log.info("\n[STEP 4] Verifying user is disabled...");

        // Verify in database
        OperationResult<List<Map<String, Object>>> queryResult = databaseConnector.executeQuery(
                "test-db",
                "SELECT * FROM users WHERE user_id = ?",
                testUser.getUserId()
        );

        List<Map<String, Object>> userRows = assertDataPresent(queryResult, "User query after disable");
        assertThat(userRows).hasSize(1);

        Map<String, Object> userRow = userRows.get(0);

        assertThat(userRow.get("active"))
                .as("User should be inactive")
                .isEqualTo(false);

        log.info("User verified as disabled: {}", userRow);
        log.info("\n[TEST COMPLETE] User disable workflow successful!");
    }

    @Test
    @DisplayName("Should delete user (hard delete) from all systems")
    void testUserDeleteWorkflow() {
        // Require connectors

        // STEP 1: Create user
        log.info("\n[STEP 1] Creating user for deletion...");
        UserTestData testUser = UserTestData.randomUser(testDataGenerator);

        String userDn = testUser.buildLdapDn("ou=users,dc=test,dc=com");

        // Create in LDAP
        edirectoryConnector.create(testUser.toLdapUser(userDn));
        // Note: Don't track for cleanup since we're testing deletion

        // Create in database
        String insertSql = """
                INSERT INTO users (user_id, first_name, last_name, email, active)
                VALUES (?, ?, ?, ?, ?)
                """;

        databaseConnector.executeUpdate(
                "test-db",
                insertSql,
                testUser.getUserId(),
                testUser.getFirstName(),
                testUser.getLastName(),
                testUser.getEmail(),
                true
        );

        log.info("User created: {}", testUser.getUserId());

        // STEP 2: Delete user from LDAP
        log.info("\n[STEP 2] Deleting user from LDAP...");

        OperationResult<Void> deleteResult = edirectoryConnector.delete(userDn);

        assertThat(deleteResult.isSuccess())
                .as("User delete from LDAP should succeed")
                .isTrue();

        log.info("User deleted from LDAP");

        // STEP 3: Delete user from database
        log.info("\n[STEP 3] Deleting user from database...");

        String deleteSql = "DELETE FROM users WHERE user_id = ?";

        OperationResult<Integer> dbDeleteResult = databaseConnector.executeUpdate(
                "test-db",
                deleteSql,
                testUser.getUserId()
        );

        Integer deletedRows = assertDataPresent(dbDeleteResult, "User database deletion");
        assertThat(deletedRows)
                .as("One row should be deleted")
                .isEqualTo(1);

        log.info("User deleted from database");

        // STEP 4: Verify user no longer exists
        log.info("\n[STEP 4] Verifying user deletion...");

        // Verify not in LDAP
        OperationResult<?> ldapReadResult = edirectoryConnector.read(userDn);
        assertThat(ldapReadResult.isFailure())
                .as("User should not exist in LDAP")
                .isTrue();

        // Verify not in database
        OperationResult<List<Map<String, Object>>> dbQueryResult = databaseConnector.executeQuery(
                "test-db",
                "SELECT * FROM users WHERE user_id = ?",
                testUser.getUserId()
        );

        List<Map<String, Object>> remainingUsers = assertDataPresent(dbQueryResult, "User verification query");
        assertThat(remainingUsers)
                .as("User should not exist in database")
                .isEmpty();

        log.info("User deletion verified in all systems");
        log.info("\n[TEST COMPLETE] User delete workflow successful!");
    }

    @Test
    @DisplayName("Should revoke all access during deprovisioning")
    void testAccessRevocationWorkflow() {
        // Require connectors

        // STEP 1: Create user with multiple access grants
        log.info("\n[STEP 1] Creating user with multiple access grants...");
        UserTestData testUser = UserTestData.randomUser(testDataGenerator);

        // Create user
        String insertUserSql = """
                INSERT INTO users (user_id, first_name, last_name, email, active)
                VALUES (?, ?, ?, ?, ?)
                """;

        databaseConnector.executeUpdate(
                "test-db",
                insertUserSql,
                testUser.getUserId(),
                testUser.getFirstName(),
                testUser.getLastName(),
                testUser.getEmail(),
                true
        );

        trackDatabaseRecord("test-db", "users", "user_id = '" + testUser.getUserId() + "'");

        // Grant multiple access rights
        String[] systems = {"SAP", "Workday", "ServiceNow"};
        for (String system : systems) {
            String insertAccessSql = """
                    INSERT INTO user_access (user_id, system_name, access_level, granted_date)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """;

            databaseConnector.executeUpdate(
                    "test-db",
                    insertAccessSql,
                    testUser.getUserId(),
                    system,
                    "USER"
            );
        }

        trackDatabaseRecord("test-db", "user_access", "user_id = '" + testUser.getUserId() + "'");

        log.info("User created with {} access grants", systems.length);

        // STEP 2: Verify access exists
        log.info("\n[STEP 2] Verifying initial access grants...");

        OperationResult<List<Map<String, Object>>> accessQuery = databaseConnector.executeQuery(
                "test-db",
                "SELECT * FROM user_access WHERE user_id = ?",
                testUser.getUserId()
        );

        List<Map<String, Object>> accessGrants = assertDataPresent(accessQuery, "Initial access query");
        assertThat(accessGrants)
                .as("User should have 3 access grants")
                .hasSize(3);

        log.info("Access grants verified: {}", accessGrants.size());

        // STEP 3: Revoke all access
        log.info("\n[STEP 3] Revoking all access...");

        String revokeAccessSql = "DELETE FROM user_access WHERE user_id = ?";

        OperationResult<Integer> revokeResult = databaseConnector.executeUpdate(
                "test-db",
                revokeAccessSql,
                testUser.getUserId()
        );

        Integer revokedCount = assertDataPresent(revokeResult, "Access revocation");
        assertThat(revokedCount)
                .as("All 3 access grants should be revoked")
                .isEqualTo(3);

        log.info("All access revoked");

        // STEP 4: Verify no access remains
        log.info("\n[STEP 4] Verifying access revocation...");

        OperationResult<List<Map<String, Object>>> verifyQuery = databaseConnector.executeQuery(
                "test-db",
                "SELECT * FROM user_access WHERE user_id = ?",
                testUser.getUserId()
        );

        List<Map<String, Object>> remainingAccess = assertDataPresent(verifyQuery, "Access verification query");
        assertThat(remainingAccess)
                .as("User should have no access grants")
                .isEmpty();

        log.info("Access revocation verified");
        log.info("\n[TEST COMPLETE] Access revocation workflow successful!");
    }

    @Test
    @DisplayName("Should handle deprovisioning with grace period")
    void testGracePeriodDeprovisioning() {
        // Require connector

        log.info("\n[TEST] Testing deprovisioning with grace period...");

        // STEP 1: Create user
        UserTestData testUser = UserTestData.randomUser(testDataGenerator);

        String insertSql = """
                INSERT INTO users (user_id, first_name, last_name, email, active)
                VALUES (?, ?, ?, ?, ?)
                """;

        databaseConnector.executeUpdate(
                "test-db",
                insertSql,
                testUser.getUserId(),
                testUser.getFirstName(),
                testUser.getLastName(),
                testUser.getEmail(),
                true
        );

        trackDatabaseRecord("test-db", "users", "user_id = '" + testUser.getUserId() + "'");

        log.info("User created: {}", testUser.getUserId());

        // STEP 2: Mark user for deprovisioning (set termination date)
        log.info("\n[STEP 2] Marking user for future deprovisioning...");

        // In a real system, this would set a termination_date in the future
        String markForDeletionSql = """
                UPDATE users
                SET active = false
                WHERE user_id = ?
                """;

        databaseConnector.executeUpdate(
                "test-db",
                markForDeletionSql,
                testUser.getUserId()
        );

        log.info("User marked for deprovisioning (disabled)");

        // STEP 3: Verify user still exists but is inactive
        log.info("\n[STEP 3] Verifying grace period state...");

        OperationResult<List<Map<String, Object>>> queryResult = databaseConnector.executeQuery(
                "test-db",
                "SELECT * FROM users WHERE user_id = ?",
                testUser.getUserId()
        );

        List<Map<String, Object>> users = assertDataPresent(queryResult, "Grace period query");
        assertThat(users).hasSize(1);

        Map<String, Object> userRow = users.get(0);
        assertThat(userRow.get("active")).isEqualTo(false);

        log.info("User in grace period (exists but inactive)");

        // STEP 4: Simulate grace period expiration and final deletion
        log.info("\n[STEP 4] Simulating grace period expiration...");

        String finalDeleteSql = "DELETE FROM users WHERE user_id = ? AND active = false";

        OperationResult<Integer> deleteResult = databaseConnector.executeUpdate(
                "test-db",
                finalDeleteSql,
                testUser.getUserId()
        );

        Integer deletedCount = assertDataPresent(deleteResult, "Grace period final deletion");
        assertThat(deletedCount).isEqualTo(1);

        log.info("User permanently deleted after grace period");
        log.info("\n[TEST COMPLETE] Grace period deprovisioning successful!");
    }

    @Test
    @DisplayName("Should cleanup related data during deprovisioning")
    void testRelatedDataCleanup() {
        // Require connector

        log.info("\n[TEST] Testing related data cleanup...");

        // Create user with related data
        UserTestData testUser = UserTestData.randomUser(testDataGenerator);

        // Create user
        String insertUserSql = """
                INSERT INTO users (user_id, first_name, last_name, email, active)
                VALUES (?, ?, ?, ?, ?)
                """;

        databaseConnector.executeUpdate(
                "test-db",
                insertUserSql,
                testUser.getUserId(),
                testUser.getFirstName(),
                testUser.getLastName(),
                testUser.getEmail(),
                true
        );

        // Create related access records
        String insertAccessSql = """
                INSERT INTO user_access (user_id, system_name, access_level, granted_date)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """;

        databaseConnector.executeUpdate("test-db", insertAccessSql, testUser.getUserId(), "SAP", "USER");
        databaseConnector.executeUpdate("test-db", insertAccessSql, testUser.getUserId(), "Workday", "USER");

        log.info("User and related data created");

        // Delete related data first (standard cleanup order)
        log.info("\n[CLEANUP] Deleting related access data...");

        String deleteAccessSql = "DELETE FROM user_access WHERE user_id = ?";
        OperationResult<Integer> deleteAccess = databaseConnector.executeUpdate(
                "test-db",
                deleteAccessSql,
                testUser.getUserId()
        );

        Integer deletedAccess = assertDataPresent(deleteAccess, "Related access data deletion");
        assertThat(deletedAccess).isEqualTo(2);

        // Delete user
        log.info("\n[CLEANUP] Deleting user...");

        String deleteUserSql = "DELETE FROM users WHERE user_id = ?";
        OperationResult<Integer> deleteUser = databaseConnector.executeUpdate(
                "test-db",
                deleteUserSql,
                testUser.getUserId()
        );

        Integer deletedUsers = assertDataPresent(deleteUser, "User deletion");
        assertThat(deletedUsers).isEqualTo(1);

        log.info("User and related data deleted successfully");
        log.info("\n[TEST COMPLETE] Related data cleanup successful!");
    }
}
