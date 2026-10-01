package com.idm.test.connector.ldap.client;

import com.idm.test.connector.ldap.config.LdapConfiguration;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.naming.NamingEnumeration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for EdirectoryConnector.
 * Tests against embedded UnboundID LDAP server.
 */
@SpringBootTest(classes = {
    com.idm.test.connector.ldap.config.EmbeddedLdapServerConfig.class,
    LdapConfiguration.class
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
        assertThat(result.getMessage()).isEqualTo("Attribute 'sn': expected=Wrong, actual=Doe");
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

    // ------------------------------------------------------------------
    // Attribute-complete read (ITF-8 Task 1)
    //
    // UserContextMapper used to map ten hardcoded names and never populate
    // custom attributes, so verifyAttributes compared every other attribute
    // against null and reported a mismatch that was not real.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("read should return attributes outside the ten mapped names")
    void testReadReturnsCustomAttributes() {
        String dn = "cn=custom.attr," + TEST_BASE_DN;
        LdapUser user = new LdapUser();
        user.setDn(dn);
        user.setCn("custom.attr");
        user.setSn("Custom");
        user.addCustomAttribute("employeeNumber", "E12345");

        assertThat(connector.create(user).isSuccess()).isTrue();

        OperationResult<LdapUser> read = connector.read(dn);

        assertThat(read.isSuccess()).isTrue();
        assertThat(read.getData().orElseThrow().getCustomAttributes())
                .containsEntry("employeeNumber", "E12345");

        connector.delete(dn);
    }

    @Test
    @DisplayName("verifyAttributes should pass for an attribute outside the ten mapped names")
    void testVerifyAttributesSeesCustomAttributes() {
        String dn = "cn=custom.verify," + TEST_BASE_DN;
        LdapUser user = new LdapUser();
        user.setDn(dn);
        user.setCn("custom.verify");
        user.setSn("Custom");
        user.addCustomAttribute("employeeNumber", "E67890");

        assertThat(connector.create(user).isSuccess()).isTrue();

        // Before the fix this failed with "expected=E67890, actual=null" even though
        // the directory genuinely held the value.
        assertThat(connector.verifyAttributes(dn, Map.of("employeeNumber", "E67890")).isSuccess())
                .isTrue();

        connector.delete(dn);
    }

    @Test
    @DisplayName("read should return a multi-valued attribute as a list")
    void testReadReturnsMultiValuedAttributeAsList() {
        String dn = "cn=multi.valued," + TEST_BASE_DN;
        LdapUser user = new LdapUser();
        user.setDn(dn);
        user.setCn("multi.valued");
        user.setSn("Multi");

        assertThat(connector.create(user).isSuccess()).isTrue();

        Object objectClass = connector.read(dn).getData().orElseThrow()
                .getCustomAttributes().get("objectClass");

        assertThat(objectClass).isInstanceOf(List.class);
        assertThat((List<?>) objectClass).map(String::valueOf).contains("inetOrgPerson");

        connector.delete(dn);
    }

    @Test
    @DisplayName("read should never expose userPassword, which would leak into reports")
    void testReadExcludesUserPassword() {
        String dn = "cn=secret.holder," + TEST_BASE_DN;
        LdapUser user = new LdapUser();
        user.setDn(dn);
        user.setCn("secret.holder");
        user.setSn("Secret");
        user.setUserPassword("s3cr3t");

        assertThat(connector.create(user).isSuccess()).isTrue();

        assertThat(connector.read(dn).getData().orElseThrow().getCustomAttributes())
                .doesNotContainKey("userPassword");

        connector.delete(dn);
    }

    // ------------------------------------------------------------------
    // ITF-38: multi-valued attributes
    // ------------------------------------------------------------------

    private static final String DEV = "cn=developers,ou=groups,dc=test,dc=com";
    private static final String VPN = "cn=vpn,ou=groups,dc=test,dc=com";

    private String createMultiValued(String cn) {
        String dn = "cn=" + cn + "," + TEST_BASE_DN;
        connector.delete(dn);
        LdapUser user = new LdapUser();
        user.setDn(dn);
        user.setCn(cn);
        user.setSn("Multi");
        user.addCustomAttribute("ou", List.of("Engineering", "Security"));
        user.addCustomAttribute("seeAlso", List.of(DEV, VPN));
        user.setUserPassword("s3cr3t");
        OperationResult<LdapUser> created = connector.create(user);
        assertThat(created.isSuccess()).as(created.getMessage()).isTrue();
        return dn;
    }

    @Test
    @DisplayName("readAttributes returns every value of a multi-valued attribute, built-in or not, and no userPassword")
    void testReadAttributesReturnsLists() {
        String dn = createMultiValued("multi.read");

        OperationResult<Map<String, Object>> read = connector.readAttributes(dn);

        assertThat(read.isSuccess()).as(read.getMessage()).isTrue();
        Map<String, Object> attributes = read.getData().orElseThrow();
        assertThat((List<?>) attributes.get("ou")).map(String::valueOf)
                .containsExactlyInAnyOrder("Engineering", "Security");
        assertThat((List<?>) attributes.get("seeAlso")).map(String::valueOf)
                .containsExactlyInAnyOrder(DEV, VPN);
        assertThat(attributes.get("sn")).isEqualTo("Multi");
        assertThat(attributes.get("SN")).as("keys ignore case").isEqualTo("Multi");
        assertThat(attributes.keySet()).noneMatch(key -> key.equalsIgnoreCase("userPassword"));

        connector.delete(dn);
    }

    @Test
    @DisplayName("readAttributes reports a missing entry as NOT_FOUND")
    void testReadAttributesMissingEntry() {
        OperationResult<Map<String, Object>> read = connector.readAttributes("cn=nobody.itf38," + TEST_BASE_DN);

        assertThat(read.isSuccess()).isFalse();
        assertThat(read.getErrorType()).contains(ErrorType.NOT_FOUND);
        assertThat(read.getMessage()).contains("not found");
    }

    @Test
    @DisplayName("verifyAttributes matches lists in any order, DNs ignoring case, and contains/excludes")
    void testVerifyAttributesMultiValued() {
        String dn = createMultiValued("multi.verify");

        assertThat(connector.verifyAttributes(dn, Map.of("ou", List.of("Security", "Engineering"))).isSuccess())
                .isTrue();
        assertThat(connector.verifyAttributes(dn, Map.of("seeAlso",
                Map.of("contains", "CN=Developers, OU=Groups, DC=test, DC=com",
                        "excludes", "cn=finance,ou=groups,dc=test,dc=com"))).isSuccess())
                .isTrue();

        OperationResult<Boolean> scalar = connector.verifyAttributes(dn, Map.of("ou", "Engineering"));
        assertThat(scalar.isSuccess()).isFalse();
        assertThat(scalar.getErrorType()).contains(ErrorType.VALIDATION_ERROR);
        assertThat(scalar.getMessage()).startsWith("Attribute 'ou': expected=Engineering, actual=[");

        connector.delete(dn);
    }

    @Test
    @DisplayName("update writes a list as several values, and an empty list removes the attribute")
    void testUpdateWritesLists() {
        String dn = createMultiValued("multi.update");

        assertThat(connector.update(dn, Map.of("seeAlso", List.of(DEV))).isSuccess()).isTrue();
        assertThat(connector.readAttributes(dn).getData().orElseThrow().get("seeAlso")).isEqualTo(DEV);

        assertThat(connector.update(dn, Map.of("ou", List.of("A", "B", "C"))).isSuccess()).isTrue();
        assertThat((List<?>) connector.readAttributes(dn).getData().orElseThrow().get("ou")).hasSize(3);

        assertThat(connector.update(dn, Map.of("seeAlso", List.of())).isSuccess()).isTrue();
        assertThat(connector.readAttributes(dn).getData().orElseThrow()).doesNotContainKey("seeAlso");

        connector.delete(dn);
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

    @Test
    @Order(100)
    @DisplayName("create writes the objectClass given in the attributes instead of the inetOrgPerson default")
    void testCreateWithCustomObjectClass() {
        LdapUser user = new LdapUser();
        user.setDn(TEST_USER_DN);
        user.setCn(TEST_USER_CN);
        user.setSn("Doe");
        user.addCustomAttribute("objectClass", List.of("top", "person", "organizationalPerson"));

        OperationResult<LdapUser> result = connector.create(user);

        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        List<String> classes = ldapTemplate.lookup(TEST_USER_DN, (AttributesMapper<List<String>>) attributes -> {
            List<String> values = new ArrayList<>();
            NamingEnumeration<?> all = attributes.get("objectClass").getAll();
            while (all.hasMore()) {
                values.add(String.valueOf(all.next()));
            }
            return values;
        });
        assertThat(classes).containsExactlyInAnyOrder("top", "person", "organizationalPerson");
    }
}
