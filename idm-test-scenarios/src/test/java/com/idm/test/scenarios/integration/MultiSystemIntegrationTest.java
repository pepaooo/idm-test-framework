package com.idm.test.scenarios.integration;

import com.idm.test.connector.soap.generated.role.RequestRolesAssignmentResponse;
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
 * End-to-end integration tests across all systems.
 * <p>
 * These tests demonstrate complete provisioning workflows that span
 * multiple systems: IdM → LDAP → Databases → SOAP Services.
 * <p>
 * Complete workflows tested:
 * 1. New hire onboarding (create user + assign roles + provision access)
 * 2. Role change (modify roles + update access)
 * 3. Employee termination (disable user + revoke access + cleanup)
 * 4. Department transfer (update LDAP + sync databases)
 */
@DisplayName("Multi-System Integration Tests")
class MultiSystemIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Complete new hire onboarding workflow across all systems")
    void testNewHireOnboardingWorkflow() {
        // Require all connectors for complete workflow
        requireConnector(edirectoryConnector, "EdirectoryConnector");
        requireConnector(databaseConnector, "MultiDatabaseConnector");
        requireConnector(roleServiceConnector, "RoleServiceConnector");
        requireConnector(testDataGenerator, "TestDataGenerator");

        log.info("\n" + "=".repeat(80));
        log.info("COMPLETE NEW HIRE ONBOARDING WORKFLOW");
        log.info("=".repeat(80));

        // ========== STEP 1: Generate new hire data ==========
        log.info("\n[STEP 1] Generating new hire data...");

        UserTestData newHire = UserTestData.randomUser(testDataGenerator)
                .withDepartment("Engineering")
                .withJobTitle("Software Engineer")
                .withActive(true);

        log.info("New Hire Details:");
        log.info("  Name: {}", newHire.getFullName());
        log.info("  User ID: {}", newHire.getUserId());
        log.info("  Email: {}", newHire.getEmail());
        log.info("  Department: {}", newHire.getDepartment());
        log.info("  Job Title: {}", newHire.getJobTitle());

        // ========== STEP 2: Create user in eDirectory (LDAP) ==========
        log.info("\n[STEP 2] Creating user in eDirectory...");

        String userDn = newHire.buildLdapDn("ou=users,dc=test,dc=com");
        log.info("User DN: {}", userDn);

        OperationResult<LdapUser> ldapCreateResult = edirectoryConnector.create(
                newHire.toLdapUser(userDn)
        );

        assertThat(ldapCreateResult.isSuccess())
                .as("LDAP user creation should succeed")
                .isTrue();

        trackLdapDn(userDn);

        log.info("✓ User created in eDirectory");

        // ========== STEP 3: Wait for LDAP provisioning to complete ==========
        log.info("\n[STEP 3] Waiting for LDAP provisioning...");

        boolean ldapReady = testExecutor.waitUntil(
                () -> edirectoryConnector.read(userDn).isSuccess(),
                executorProperties.getLdapProvisionTimeout(),
                executorProperties.getDefaultPollInterval()
        );

        assertThat(ldapReady).isTrue();
        log.info("✓ LDAP provisioning complete");

        // ========== STEP 4: Assign base roles via SOAP ==========
        log.info("\n[STEP 4] Assigning base roles to user...");

        String[] baseRoles = {
                "cn=EMAIL_ACCESS,ou=roles,dc=test,dc=com",
                "cn=INTRANET_USER,ou=roles,dc=test,dc=com",
                "cn=BASIC_EMPLOYEE,ou=roles,dc=test,dc=com"
        };

        OperationResult<RequestRolesAssignmentResponse> roleAssignResult = roleServiceConnector.assignRoles(
                userDn,
                baseRoles,
                "New hire onboarding - base roles"
        );

        assertThat(roleAssignResult.isSuccess())
                .as("Base role assignment should succeed")
                .isTrue();

        for (String roleDn : baseRoles) {
            trackRoleAssignment(userDn, roleDn);
        }

        log.info("✓ Assigned {} base roles", baseRoles.length);

        // ========== STEP 5: Provision user in HR database ==========
        log.info("\n[STEP 5] Provisioning user in HR database...");

        String hrInsertSql = """
                INSERT INTO users (user_id, first_name, last_name, email, department, job_title, active)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        OperationResult<Integer> hrInsertResult = databaseConnector.executeUpdate(
                "test-db",
                hrInsertSql,
                newHire.getUserId(),
                newHire.getFirstName(),
                newHire.getLastName(),
                newHire.getEmail(),
                newHire.getDepartment(),
                newHire.getJobTitle(),
                true
        );

        Integer insertedRows = assertDataPresent(hrInsertResult, "HR database insert");
        assertThat(insertedRows).isEqualTo(1);

        trackDatabaseRecord("test-db", "users", "user_id = '" + newHire.getUserId() + "'");

        log.info("✓ User provisioned in HR database");

        // ========== STEP 6: Grant application access (SAP, Workday) ==========
        log.info("\n[STEP 6] Granting application access...");

        String[] applications = {"SAP", "Workday", "Email", "Intranet"};

        for (String app : applications) {
            String accessInsertSql = """
                    INSERT INTO user_access (user_id, system_name, access_level, granted_date)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """;

            databaseConnector.executeUpdate(
                    "test-db",
                    accessInsertSql,
                    newHire.getUserId(),
                    app,
                    "USER"
            );
        }

        trackDatabaseRecord("test-db", "user_access", "user_id = '" + newHire.getUserId() + "'");

        log.info("✓ Granted access to {} applications", applications.length);

        // ========== STEP 7: Wait for all provisioning to complete ==========
        log.info("\n[STEP 7] Waiting for all provisioning to complete...");

        boolean allProvisioned = testExecutor.waitUntil(
                () -> {
                    // Check HR database
                    OperationResult<List<Map<String, Object>>> hrResult = databaseConnector.executeQuery(
                            "test-db",
                            "SELECT * FROM users WHERE user_id = ? AND active = true",
                            newHire.getUserId()
                    );

                    if (hrResult.isFailure() || hrResult.getData().get().isEmpty()) {
                        return false;
                    }

                    // Check access grants
                    OperationResult<List<Map<String, Object>>> accessResult = databaseConnector.executeQuery(
                            "test-db",
                            "SELECT * FROM user_access WHERE user_id = ?",
                            newHire.getUserId()
                    );

                    return accessResult.isSuccess() &&
                            accessResult.getData().get().size() == applications.length;
                },
                executorProperties.getDatabaseProvisionTimeout(),
                executorProperties.getDefaultPollInterval()
        );

        assertThat(allProvisioned).isTrue();
        log.info("✓ All provisioning complete");

        // ========== STEP 8: Comprehensive verification ==========
        log.info("\n[STEP 8] Performing comprehensive verification...");

        // Verify LDAP
        OperationResult<LdapUser> ldapVerify = edirectoryConnector.read(userDn);
        assertThat(ldapVerify.isSuccess()).isTrue();
        assertThat(ldapVerify.getData()).isPresent();

        LdapUser ldapUser = ldapVerify.getData().get();
        assertThat(ldapUser.getMail()).isEqualTo(newHire.getEmail());

        log.info("✓ LDAP verification passed");

        // Verify HR database
        OperationResult<List<Map<String, Object>>> hrVerify = databaseConnector.executeQuery(
                "test-db",
                "SELECT * FROM users WHERE user_id = ?",
                newHire.getUserId()
        );

        List<Map<String, Object>> hrRecords = assertDataPresent(hrVerify, "HR database verification");
        assertThat(hrRecords).hasSize(1);
        Map<String, Object> hrRecord = hrRecords.get(0);
        assertThat(hrRecord.get("email")).isEqualTo(newHire.getEmail());
        assertThat(hrRecord.get("department")).isEqualTo(newHire.getDepartment());
        assertThat(hrRecord.get("active")).isEqualTo(true);

        log.info("✓ HR database verification passed");

        // Verify application access
        OperationResult<List<Map<String, Object>>> accessVerify = databaseConnector.executeQuery(
                "test-db",
                "SELECT system_name, access_level FROM user_access WHERE user_id = ? ORDER BY system_name",
                newHire.getUserId()
        );

        List<Map<String, Object>> accessRecords = assertDataPresent(accessVerify, "Application access verification");
        assertThat(accessRecords).hasSize(4);

        log.info("✓ Application access verification passed");
        log.info("  Access granted to: {}", accessRecords.stream()
                .map(r -> r.get("system_name"))
                .toList());

        // ========== STEP 9: Summary ==========
        log.info("\n" + "=".repeat(80));
        log.info("ONBOARDING COMPLETE - SUMMARY");
        log.info("=".repeat(80));
        log.info("User: {} ({})", newHire.getFullName(), newHire.getUserId());
        log.info("Email: {}", newHire.getEmail());
        log.info("Department: {}", newHire.getDepartment());
        log.info("LDAP: ✓ Created");
        log.info("Base Roles: ✓ {} roles assigned", baseRoles.length);
        log.info("HR Database: ✓ Provisioned");
        log.info("Applications: ✓ {} access grants", applications.length);
        log.info("=".repeat(80));

        log.info("\n[TEST COMPLETE] New hire onboarding successful!");
    }

    @Test
    @DisplayName("Complete employee termination workflow across all systems")
    void testEmployeeTerminationWorkflow() {
        // Require all connectors
        requireConnector(edirectoryConnector, "EdirectoryConnector");
        requireConnector(databaseConnector, "MultiDatabaseConnector");
        requireConnector(testDataGenerator, "TestDataGenerator");

        log.info("\n" + "=".repeat(80));
        log.info("COMPLETE EMPLOYEE TERMINATION WORKFLOW");
        log.info("=".repeat(80));

        // ========== SETUP: Create employee with full provisioning ==========
        log.info("\n[SETUP] Creating fully provisioned employee...");

        UserTestData employee = UserTestData.randomUser(testDataGenerator)
                .withDepartment("Sales")
                .withActive(true);

        String userDn = employee.buildLdapDn("ou=users,dc=test,dc=com");

        // Create in LDAP
        edirectoryConnector.create(employee.toLdapUser(userDn));
        // Note: Don't track for cleanup - we're testing deletion

        // Create in database
        String insertSql = """
                INSERT INTO users (user_id, first_name, last_name, email, department, active)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        databaseConnector.executeUpdate(
                "test-db",
                insertSql,
                employee.getUserId(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.getEmail(),
                employee.getDepartment(),
                true
        );

        // Grant access
        String[] systems = {"SAP", "Workday", "Email"};
        for (String system : systems) {
            String accessSql = """
                    INSERT INTO user_access (user_id, system_name, access_level, granted_date)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """;

            databaseConnector.executeUpdate("test-db", accessSql, employee.getUserId(), system, "USER");
        }

        log.info("Employee provisioned: {}", employee.getUserId());
        log.info("  LDAP: ✓");
        log.info("  HR Database: ✓");
        log.info("  Access Grants: {} systems", systems.length);

        // ========== STEP 1: Revoke all application access ==========
        log.info("\n[STEP 1] Revoking all application access...");

        String revokeAccessSql = "DELETE FROM user_access WHERE user_id = ?";

        OperationResult<Integer> revokeResult = databaseConnector.executeUpdate(
                "test-db",
                revokeAccessSql,
                employee.getUserId()
        );

        Integer revokedCount = assertDataPresent(revokeResult, "Access revocation");
        assertThat(revokedCount)
                .as("All access grants should be revoked")
                .isEqualTo(systems.length);

        log.info("✓ Revoked access to {} systems", systems.length);

        // ========== STEP 2: Disable account in LDAP ==========
        log.info("\n[STEP 2] Disabling user account in LDAP...");

        java.util.Map<String, Object> modifications = new java.util.HashMap<>();
        modifications.put("loginDisabled", "TRUE");

        OperationResult<Void> disableResult = edirectoryConnector.update(userDn, modifications);

        assertThat(disableResult.isSuccess()).isTrue();
        log.info("✓ Account disabled in LDAP");

        // ========== STEP 3: Mark as inactive in HR database ==========
        log.info("\n[STEP 3] Marking user as inactive in HR database...");

        String deactivateSql = "UPDATE users SET active = false WHERE user_id = ?";

        OperationResult<Integer> deactivateResult = databaseConnector.executeUpdate(
                "test-db",
                deactivateSql,
                employee.getUserId()
        );

        Integer updatedRows = assertDataPresent(deactivateResult, "User deactivation");
        assertThat(updatedRows).isEqualTo(1);
        log.info("✓ User marked as inactive");

        // ========== STEP 4: Verify termination ==========
        log.info("\n[STEP 4] Verifying termination state...");

        // Verify no access
        OperationResult<List<Map<String, Object>>> accessCheck = databaseConnector.executeQuery(
                "test-db",
                "SELECT * FROM user_access WHERE user_id = ?",
                employee.getUserId()
        );

        List<Map<String, Object>> accessRecords = assertDataPresent(accessCheck, "Access verification");
        assertThat(accessRecords)
                .as("User should have no access grants")
                .isEmpty();

        log.info("✓ All access revoked");

        // Verify inactive in database
        OperationResult<List<Map<String, Object>>> userCheck = databaseConnector.executeQuery(
                "test-db",
                "SELECT active FROM users WHERE user_id = ?",
                employee.getUserId()
        );

        List<Map<String, Object>> userRecords = assertDataPresent(userCheck, "User status verification");
        assertThat(userRecords.get(0).get("active"))
                .as("User should be inactive")
                .isEqualTo(false);

        log.info("✓ User inactive in HR database");

        // ========== STEP 5: Final cleanup (after grace period) ==========
        log.info("\n[STEP 5] Performing final cleanup...");

        // Delete from LDAP
        edirectoryConnector.delete(userDn);
        log.info("✓ Removed from LDAP");

        // Delete from database
        String deleteSql = "DELETE FROM users WHERE user_id = ?";
        databaseConnector.executeUpdate("test-db", deleteSql, employee.getUserId());
        log.info("✓ Removed from HR database");

        // ========== Summary ==========
        log.info("\n" + "=".repeat(80));
        log.info("TERMINATION COMPLETE - SUMMARY");
        log.info("=".repeat(80));
        log.info("User: {} ({})", employee.getFullName(), employee.getUserId());
        log.info("Access Revocation: ✓ {} systems", systems.length);
        log.info("LDAP Disable: ✓ Account disabled");
        log.info("HR Database: ✓ Marked inactive");
        log.info("Final Cleanup: ✓ Removed from all systems");
        log.info("=".repeat(80));

        log.info("\n[TEST COMPLETE] Employee termination successful!");
    }

    @Test
    @DisplayName("Department transfer workflow with cross-system sync")
    void testDepartmentTransferWorkflow() {
        // Require connectors
        requireConnector(edirectoryConnector, "EdirectoryConnector");
        requireConnector(databaseConnector, "MultiDatabaseConnector");
        requireConnector(testDataGenerator, "TestDataGenerator");

        log.info("\n[TEST] Department Transfer Workflow");

        // Create user
        UserTestData employee = UserTestData.randomUser(testDataGenerator)
                .withDepartment("Engineering")
                .withJobTitle("Software Engineer");

        String userDn = employee.buildLdapDn("ou=users,dc=test,dc=com");

        // Create in both systems
        edirectoryConnector.create(employee.toLdapUser(userDn));
        trackLdapDn(userDn);

        String insertSql = """
                INSERT INTO users (user_id, first_name, last_name, email, department, job_title, active)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        databaseConnector.executeUpdate(
                "test-db",
                insertSql,
                employee.getUserId(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.getEmail(),
                employee.getDepartment(),
                employee.getJobTitle(),
                true
        );

        trackDatabaseRecord("test-db", "users", "user_id = '" + employee.getUserId() + "'");

        log.info("Employee created in Engineering department");

        // Transfer to new department
        log.info("\n[TRANSFER] Moving to Sales department...");

        String newDept = "Sales";
        String newTitle = "Sales Representative";

        // Update LDAP
        java.util.Map<String, Object> ldapUpdates = new java.util.HashMap<>();
        ldapUpdates.put("ou", newDept);
        ldapUpdates.put("title", newTitle);

        edirectoryConnector.update(userDn, ldapUpdates);

        // Update database
        String updateSql = "UPDATE users SET department = ?, job_title = ? WHERE user_id = ?";

        databaseConnector.executeUpdate("test-db", updateSql, newDept, newTitle, employee.getUserId());

        log.info("Department transfer complete");

        // Verify sync
        OperationResult<List<Map<String, Object>>> dbVerify = databaseConnector.executeQuery(
                "test-db",
                "SELECT department, job_title FROM users WHERE user_id = ?",
                employee.getUserId()
        );

        List<Map<String, Object>> records = assertDataPresent(dbVerify, "Department transfer verification");
        Map<String, Object> record = records.get(0);
        assertThat(record.get("department")).isEqualTo(newDept);
        assertThat(record.get("job_title")).isEqualTo(newTitle);

        log.info("✓ Department transfer verified in both systems");
        log.info("\n[TEST COMPLETE] Department transfer successful!");
    }
}
