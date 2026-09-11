package com.idm.test.scenarios;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.OperationResult;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic test to verify LDAP container and initialization.
 */
@DisplayName("LDAP Diagnostic Tests")
class LdapDiagnosticTest extends BaseIntegrationTest {

    @Autowired(required = false)
    private EdirectoryConnector edirectoryConnector;

    @Test
    @DisplayName("Should have LDAP connector available")
    void testLdapConnectorAvailable() {
        log.info("Testing LDAP connector availability...");
        assertThat(edirectoryConnector)
                .as("EdirectoryConnector should be available")
                .isNotNull();
        log.info("LDAP connector is available");
    }

    @Test
    @DisplayName("Should create a simple user in LDAP")
    void testCreateSimpleUser() {

        log.info("Creating a simple test user...");

        String userDn = "cn=testuser001,ou=users,dc=test,dc=com";

        LdapUser user = new LdapUser();
        user.setDn(userDn);
        user.setCn("testuser001");
        user.setGivenName("Test");
        user.setSn("User");
        user.setMail("test.user@example.com");

        log.info("Attempting to create user: {}", userDn);
        OperationResult<LdapUser> result = edirectoryConnector.create(user);

        log.info("Create result - Success: {}, Message: {}",
                result.isSuccess(), result.getMessage());

        if (result.isFailure()) {
            log.error("Create failed: {}", result.getMessage());
            if (result.getThrowable().isPresent()) {
                log.error("Exception: ", result.getThrowable().get());
            }
        }

        assertThat(result.isSuccess())
                .as("User creation should succeed. Message: " + result.getMessage())
                .isTrue();

        trackLdapDn(userDn);
        log.info("User created successfully");
    }
}
