package com.idm.test.connector.ldap.client;

import com.idm.test.connector.ldap.config.LdapConfiguration;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for EdirectoryConnector.
 * Tests against embedded UnboundID LDAP server.
 */
@SpringBootTest(classes = {
    com.idm.test.connector.ldap.config.EmbeddedLdapServerConfig.class,
    LdapConfiguration.class,
    EdirectoryConnector.class
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EdirectoryConnectorTest {

    @Autowired
    private EdirectoryConnector connector;

    @Autowired
    private LdapTemplate ldapTemplate;

    private static final String TEST_BASE_DN = "ou=people";
    private static final String TEST_USER_CN = "john.doe";
    private static final String TEST_USER_DN = "cn=" + TEST_USER_CN + "," + TEST_BASE_DN;

    @BeforeEach
    void setUp() {
        // Clean up any existing test user
        try {
            connector.delete(TEST_USER_DN);
        } catch (Exception e) {
            // Ignore if user doesn't exist
        }
    }

    @AfterEach
    void tearDown() {
        // Clean up test user
        try {
            connector.delete(TEST_USER_DN);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    @Test
    @Order(1)
    @DisplayName("Should create user in LDAP")
    void testCreateUser() {
        LdapUser user = new LdapUser();
        user.setDn(TEST_USER_DN);
        user.setCn(TEST_USER_CN);
        user.setSn("Doe");
        user.setGivenName("John");
        user.setMail("john.doe@test.com");
        user.setUid("jdoe");
        user.setTelephoneNumber("555-0001");
        user.setTitle("Software Engineer");
        user.setDescription("Test user");

        OperationResult<LdapUser> result = connector.create(user);

        if (!result.isSuccess()) {
            System.out.println("Create failed: " + result.getMessage());
            result.getThrowable().ifPresent(Throwable::printStackTrace);
        }

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get().getDn()).isEqualTo(TEST_USER_DN);
        assertThat(result.getMessage()).contains("created successfully");
    }

    @Test
    @Order(2)
    @DisplayName("Should read user from LDAP")
    void testReadUser() {
        // Create test user first
        LdapUser user = createTestUser();
        connector.create(user);

        // Read user
        OperationResult<LdapUser> result = connector.read(TEST_USER_DN);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();

        LdapUser readUser = result.getData().get();
        assertThat(readUser.getCn()).isEqualTo(TEST_USER_CN);
        assertThat(readUser.getSn()).isEqualTo("Doe");
        assertThat(readUser.getGivenName()).isEqualTo("John");
        assertThat(readUser.getMail()).isEqualTo("john.doe@test.com");
        assertThat(readUser.getUid()).isEqualTo("jdoe");
    }

    @Test
    @Order(3)
    @DisplayName("Should return NOT_FOUND when reading non-existent user")
    void testReadNonExistentUser() {
        String nonExistentDn = "cn=nonexistent," + TEST_BASE_DN;

        OperationResult<LdapUser> result = connector.read(nonExistentDn);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).isEmpty();
        assertThat(result.getErrorType()).isPresent();
        assertThat(result.getErrorType().get()).isEqualTo(ErrorType.NOT_FOUND);
        assertThat(result.getMessage()).contains("not found");
    }

    @Test
    @Order(4)
    @DisplayName("Should update user attributes")
    void testUpdateUser() {
        // Create test user first
        LdapUser user = createTestUser();
        connector.create(user);

        // Update attributes
        Map<String, Object> updates = Map.of(
                "mail", "john.doe.updated@test.com",
                "title", "Senior Software Engineer",
                "telephoneNumber", "555-9999"
        );

        OperationResult<Void> updateResult = connector.update(TEST_USER_DN, updates);

        assertThat(updateResult.isSuccess()).isTrue();
        assertThat(updateResult.getMessage()).contains("updated successfully");

        // Verify updates
        OperationResult<LdapUser> readResult = connector.read(TEST_USER_DN);
        LdapUser updatedUser = readResult.getData().get();

        assertThat(updatedUser.getMail()).isEqualTo("john.doe.updated@test.com");
        assertThat(updatedUser.getTitle()).isEqualTo("Senior Software Engineer");
        assertThat(updatedUser.getTelephoneNumber()).isEqualTo("555-9999");
    }

    @Test
    @Order(5)
    @DisplayName("Should delete user from LDAP")
    void testDeleteUser() {
        // Create test user first
        LdapUser user = createTestUser();
        connector.create(user);

        // Delete user
        OperationResult<Void> deleteResult = connector.delete(TEST_USER_DN);

        assertThat(deleteResult.isSuccess()).isTrue();
        assertThat(deleteResult.getMessage()).contains("deleted successfully");

        // Verify deletion
        OperationResult<LdapUser> readResult = connector.read(TEST_USER_DN);
        assertThat(readResult.isSuccess()).isFalse();
        assertThat(readResult.getErrorType().get()).isEqualTo(ErrorType.NOT_FOUND);
    }

    @Test
    @Order(6)
    @DisplayName("Should check if user exists")
    void testUserExists() {
        // Check non-existent user
        OperationResult<Boolean> result1 = connector.exists(TEST_USER_DN);
        assertThat(result1.isSuccess()).isTrue();
        assertThat(result1.getData().get()).isFalse();

        // Create user
        LdapUser user = createTestUser();
        connector.create(user);

        // Check existing user
        OperationResult<Boolean> result2 = connector.exists(TEST_USER_DN);
        assertThat(result2.isSuccess()).isTrue();
        assertThat(result2.getData().get()).isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("Should verify attributes match expected values")
    void testVerifyAttributesSuccess() {
        // Create test user
        LdapUser user = createTestUser();
        connector.create(user);

        // Verify attributes
        Map<String, Object> expectedAttributes = Map.of(
                "cn", TEST_USER_CN,
                "sn", "Doe",
                "givenName", "John",
                "mail", "john.doe@test.com"
        );

        OperationResult<Boolean> result = connector.verifyAttributes(TEST_USER_DN, expectedAttributes);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().get()).isTrue();
        assertThat(result.getMessage()).contains("verification successful");
    }

    @Test
    @Order(8)
    @DisplayName("Should fail verification when attribute does not match")
    void testVerifyAttributesMismatch() {
        // Create test user
        LdapUser user = createTestUser();
        connector.create(user);

        // Verify with wrong value
        Map<String, Object> expectedAttributes = Map.of(
                "cn", TEST_USER_CN,
                "sn", "Wrong"  // Incorrect value
        );

        OperationResult<Boolean> result = connector.verifyAttributes(TEST_USER_DN, expectedAttributes);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData().get()).isFalse();
        assertThat(result.getMessage()).contains("mismatch");
        assertThat(result.getErrorType().get()).isEqualTo(ErrorType.VALIDATION_ERROR);
    }

    @Test
    @Order(9)
    @DisplayName("Should fail verification when user not found")
    void testVerifyAttributesUserNotFound() {
        String nonExistentDn = "cn=nonexistent," + TEST_BASE_DN;

        Map<String, Object> expectedAttributes = Map.of("cn", "nonexistent");

        OperationResult<Boolean> result = connector.verifyAttributes(nonExistentDn, expectedAttributes);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData().get()).isFalse();
        assertThat(result.getMessage()).contains("not found");
    }

    @Test
    @Order(10)
    @DisplayName("Should search LDAP directory")
    void testSearchUsers() {
        // Create multiple test users
        for (int i = 1; i <= 3; i++) {
            LdapUser user = new LdapUser();
            user.setDn("cn=testuser" + i + "," + TEST_BASE_DN);
            user.setCn("testuser" + i);
            user.setSn("User" + i);
            user.setGivenName("Test");
            user.setMail("testuser" + i + "@test.com");
            user.setUid("tuser" + i);
            connector.create(user);
        }

        // Search for all users with givenName=Test
        OperationResult<List<LdapUser>> result = connector.search(
                TEST_BASE_DN,
                "(givenName=Test)"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();

        List<LdapUser> users = result.getData().get();
        assertThat(users).hasSizeGreaterThanOrEqualTo(3);
        assertThat(users).allMatch(u -> u.getGivenName().equals("Test"));

        // Cleanup
        for (int i = 1; i <= 3; i++) {
            connector.delete("cn=testuser" + i + "," + TEST_BASE_DN);
        }
    }

    @Test
    @Order(11)
    @DisplayName("Should return empty list when search has no results")
    void testSearchNoResults() {
        OperationResult<List<LdapUser>> result = connector.search(
                TEST_BASE_DN,
                "(cn=nonexistent)"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isEmpty();
    }

    @Test
    @Order(12)
    @DisplayName("Should handle custom attributes")
    void testCustomAttributes() {
        LdapUser user = createTestUser();
        user.addCustomAttribute("description", "Custom description");

        OperationResult<LdapUser> createResult = connector.create(user);
        assertThat(createResult.isSuccess()).isTrue();

        // Read and verify custom attribute
        OperationResult<LdapUser> readResult = connector.read(TEST_USER_DN);
        LdapUser readUser = readResult.getData().get();

        assertThat(readUser.getDescription()).isEqualTo("Custom description");
    }

    @Test
    @Order(13)
    @DisplayName("Should create user with minimal required attributes")
    void testCreateMinimalUser() {
        LdapUser user = new LdapUser();
        user.setDn(TEST_USER_DN);
        user.setCn(TEST_USER_CN);
        user.setSn("Doe");  // Required attribute

        OperationResult<LdapUser> result = connector.create(user);

        assertThat(result.isSuccess()).isTrue();

        // Verify user exists
        OperationResult<Boolean> existsResult = connector.exists(TEST_USER_DN);
        assertThat(existsResult.getData().get()).isTrue();
    }

    @Test
    @Order(14)
    @DisplayName("Should search with wildcard filter")
    void testSearchWithWildcard() {
        // Create test user
        LdapUser user = createTestUser();
        connector.create(user);

        // Search with wildcard
        OperationResult<List<LdapUser>> result = connector.search(
                TEST_BASE_DN,
                "(cn=john*)"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().get()).isNotEmpty();
        assertThat(result.getData().get().get(0).getCn()).startsWith("john");
    }

    /**
     * Helper method to create a test user with standard attributes.
     */
    private LdapUser createTestUser() {
        LdapUser user = new LdapUser();
        user.setDn(TEST_USER_DN);
        user.setCn(TEST_USER_CN);
        user.setSn("Doe");
        user.setGivenName("John");
        user.setMail("john.doe@test.com");
        user.setUid("jdoe");
        user.setTelephoneNumber("555-0001");
        user.setTitle("Software Engineer");
        return user;
    }
}
