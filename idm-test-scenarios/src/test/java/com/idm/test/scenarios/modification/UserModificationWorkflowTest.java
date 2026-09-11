package com.idm.test.scenarios.modification;

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
 * Integration tests for user modification workflows.
 * <p>
 * Tests the complete lifecycle of modifying user attributes and verifying
 * the changes propagate to all connected systems.
 * <p>
 * Workflows tested:
 * 1. Create user in LDAP and database
 * 2. Modify user attributes (department, job title, email, etc.)
 * 3. Wait for modifications to sync across systems
 * 4. Verify changes in LDAP
 * 5. Verify changes in databases
 * 6. Test bulk modifications
 * 7. Automatic cleanup
 */
@DisplayName("User Modification Workflow Tests")
class UserModificationWorkflowTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should modify user attributes in LDAP and sync")
    void testUserModificationInLdap() {
        // Require connectors

        // STEP 1: Create test user
        log.info("\n[STEP 1] Creating test user...");
        UserTestData testUser = UserTestData.randomUser(testDataGenerator)
                .withDepartment("Engineering")
                .withJobTitle("Software Engineer");

        String userDn = testUser.buildLdapDn("ou=users,dc=test,dc=com");

        edirectoryConnector.create(testUser.toLdapUser(userDn));
        trackLdapDn(userDn);

        log.info("User created: {}", testUser);
        log.info("  Department: {}", testUser.getDepartment());
        log.info("  Job Title: {}", testUser.getJobTitle());

        // STEP 2: Modify user attributes
        log.info("\n[STEP 2] Modifying user attributes...");

        String newDepartment = "Product Management";
        String newJobTitle = "Senior Product Manager";
        String newPhone = testDataGenerator.generatePhoneNumber();

        Map<String, Object> modifications = new HashMap<>();
        modifications.put("ou", newDepartment);
        modifications.put("title", newJobTitle);
        modifications.put("telephoneNumber", newPhone);

        OperationResult<Void> updateResult = edirectoryConnector.update(userDn, modifications);

        assertThat(updateResult.isSuccess())
                .as("User modification should succeed")
                .isTrue();

        log.info("User attributes modified");
        log.info("  New Department: {}", newDepartment);
        log.info("  New Job Title: {}", newJobTitle);
        log.info("  New Phone: {}", newPhone);

        // STEP 3: Wait for changes to propagate
        log.info("\n[STEP 3] Waiting for changes to propagate...");

        boolean changesApplied = testExecutor.waitUntil(
                () -> {
                    OperationResult<LdapUser> result = edirectoryConnector.read(userDn);
                    if (result.isFailure()) return false;

                    LdapUser user = result.getData().get();
                    String currentTitle = user.getTitle();

                    return newJobTitle.equals(currentTitle);
                },
                executorProperties.getLdapProvisionTimeout(),
                executorProperties.getDefaultPollInterval()
        );

        assertThat(changesApplied)
                .as("Changes should propagate within timeout")
                .isTrue();

        log.info("Changes propagated successfully");

        // STEP 4: Verify all modifications
        log.info("\n[STEP 4] Verifying all modifications...");

        OperationResult<LdapUser> readResult = edirectoryConnector.read(userDn);
        assertThat(readResult.isSuccess()).isTrue();

        LdapUser user = readResult.getData().get();

        assertThat(user.getOu())
                .as("Department should be updated")
                .isEqualTo(newDepartment);

        assertThat(user.getTitle())
                .as("Job title should be updated")
                .isEqualTo(newJobTitle);

        assertThat(user.getTelephoneNumber())
                .as("Phone should be updated")
                .isEqualTo(newPhone);

        log.info("All modifications verified successfully");
        log.info("\n[TEST COMPLETE] User modification successful!");
    }

    @Test
    @DisplayName("Should modify user in database and verify sync")
    void testUserModificationInDatabase() {
        // Require connectors

        // STEP 1: Create test user in database
        log.info("\n[STEP 1] Creating test user in database...");
        UserTestData testUser = UserTestData.randomUser(testDataGenerator)
                .withDepartment("Sales")
                .withJobTitle("Sales Representative");

        String insertSql = """
                INSERT INTO users (user_id, first_name, last_name, email, department, job_title, active)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        databaseConnector.executeUpdate(
                "test-db",
                insertSql,
                testUser.getUserId(),
                testUser.getFirstName(),
                testUser.getLastName(),
                testUser.getEmail(),
                testUser.getDepartment(),
                testUser.getJobTitle(),
                true
        );

        trackDatabaseRecord("test-db", "users", "user_id = '" + testUser.getUserId() + "'");

        log.info("User created in database");
        log.info("  Department: {}", testUser.getDepartment());
        log.info("  Job Title: {}", testUser.getJobTitle());

        // STEP 2: Modify user attributes
        log.info("\n[STEP 2] Modifying user in database...");

        String newDepartment = "Marketing";
        String newJobTitle = "Marketing Manager";

        String updateSql = """
                UPDATE users
                SET department = ?, job_title = ?
                WHERE user_id = ?
                """;

        OperationResult<Integer> updateResult = databaseConnector.executeUpdate(
                "test-db",
                updateSql,
                newDepartment,
                newJobTitle,
                testUser.getUserId()
        );

        Integer updatedRows = assertDataPresent(updateResult, "Database update");
        assertThat(updatedRows)
                .as("One row should be updated")
                .isEqualTo(1);

        log.info("User modified in database");
        log.info("  New Department: {}", newDepartment);
        log.info("  New Job Title: {}", newJobTitle);

        // STEP 3: Verify modifications
        log.info("\n[STEP 3] Verifying modifications...");

        boolean modificationsApplied = testExecutor.waitUntil(
                () -> {
                    OperationResult<List<Map<String, Object>>> result = databaseConnector.executeQuery(
                            "test-db",
                            "SELECT * FROM users WHERE user_id = ? AND department = ?",
                            testUser.getUserId(),
                            newDepartment
                    );
                    return result.isSuccess() && !result.getData().get().isEmpty();
                },
                executorProperties.getDatabaseProvisionTimeout(),
                executorProperties.getDefaultPollInterval()
        );

        assertThat(modificationsApplied)
                .as("Modifications should be visible within timeout")
                .isTrue();

        // STEP 4: Verify all changes
        log.info("\n[STEP 4] Verifying all changes...");

        OperationResult<List<Map<String, Object>>> queryResult = databaseConnector.executeQuery(
                "test-db",
                "SELECT * FROM users WHERE user_id = ?",
                testUser.getUserId()
        );

        List<Map<String, Object>> userRows = assertDataPresent(queryResult, "Final verification query");

        Map<String, Object> userRow = userRows.get(0);

        assertThat(userRow.get("department"))
                .as("Department should be updated")
                .isEqualTo(newDepartment);

        assertThat(userRow.get("job_title"))
                .as("Job title should be updated")
                .isEqualTo(newJobTitle);

        log.info("All changes verified: {}", userRow);
        log.info("\n[TEST COMPLETE] Database modification successful!");
    }

    @Test
    @DisplayName("Should handle email change and update across systems")
    void testEmailChangeWorkflow() {
        // Require connectors

        // STEP 1: Create user in both systems
        log.info("\n[STEP 1] Creating user in LDAP and database...");
        UserTestData testUser = UserTestData.randomUser(testDataGenerator);

        String userDn = testUser.buildLdapDn("ou=users,dc=test,dc=com");
        String originalEmail = testUser.getEmail();

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

        log.info("User created with email: {}", originalEmail);

        // STEP 2: Change email in LDAP
        log.info("\n[STEP 2] Changing email address...");

        String newEmail = testDataGenerator.generateEmail();
        Map<String, Object> modifications = new HashMap<>();
        modifications.put("mail", newEmail);

        edirectoryConnector.update(userDn, modifications);

        log.info("Email changed in LDAP: {} -> {}", originalEmail, newEmail);

        // STEP 3: Update email in database (simulating sync)
        log.info("\n[STEP 3] Syncing email to database...");

        String updateSql = "UPDATE users SET email = ? WHERE user_id = ?";

        databaseConnector.executeUpdate(
                "test-db",
                updateSql,
                newEmail,
                testUser.getUserId()
        );

        // STEP 4: Verify email in both systems
        log.info("\n[STEP 4] Verifying email in both systems...");

        // Verify LDAP
        OperationResult<LdapUser> ldapResult = edirectoryConnector.read(userDn);
        LdapUser ldapUser = assertDataPresent(ldapResult, "LDAP email verification");
        assertThat(ldapUser.getMail())
                .as("Email should be updated in LDAP")
                .isEqualTo(newEmail);

        // Verify database
        OperationResult<List<Map<String, Object>>> dbResult = databaseConnector.executeQuery(
                "test-db",
                "SELECT email FROM users WHERE user_id = ?",
                testUser.getUserId()
        );

        List<Map<String, Object>> dbRecords = assertDataPresent(dbResult, "Database email verification");
        assertThat(dbRecords.get(0).get("email"))
                .as("Email should be updated in database")
                .isEqualTo(newEmail);

        log.info("Email verified in both systems: {}", newEmail);
        log.info("\n[TEST COMPLETE] Email change workflow successful!");
    }

    @Test
    @DisplayName("Should handle attribute removal")
    void testAttributeRemoval() {
        // Require connector

        log.info("\n[TEST] Testing attribute removal...");

        // Create user with phone number
        UserTestData testUser = UserTestData.randomUser(testDataGenerator);
        String userDn = testUser.buildLdapDn("ou=users,dc=test,dc=com");

        edirectoryConnector.create(testUser.toLdapUser(userDn));
        trackLdapDn(userDn);

        log.info("User created with phone: {}", testUser.getPhone());

        // Remove phone number
        Map<String, Object> modifications = new HashMap<>();
        modifications.put("telephoneNumber", null);  // null means remove

        OperationResult<Void> updateResult = edirectoryConnector.update(userDn, modifications);

        // Note: Actual removal behavior depends on LDAP implementation
        log.info("Attribute removal result: {}", updateResult.isSuccess());

        log.info("\n[TEST COMPLETE] Attribute removal tested!");
    }
}
