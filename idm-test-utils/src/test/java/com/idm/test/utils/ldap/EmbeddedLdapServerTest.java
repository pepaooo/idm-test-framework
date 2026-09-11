package com.idm.test.utils.ldap;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchResultEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EmbeddedLdapServer")
class EmbeddedLdapServerTest {

    private static final String BASE_DN = "dc=test,dc=com";
    private static final String BIND_DN = "cn=admin,dc=test,dc=com";
    private static final String BIND_PASSWORD = "test";

    @Test
    @DisplayName("Should bind an ephemeral port and expose a usable URL")
    void testEphemeralPort() throws Exception {
        try (EmbeddedLdapServer server =
                     EmbeddedLdapServer.start(BASE_DN, BIND_DN, BIND_PASSWORD, 0, null)) {

            assertThat(server.getPort()).isGreaterThan(0);
            assertThat(server.getUrl()).isEqualTo("ldap://localhost:" + server.getPort());

            try (LDAPConnection connection =
                         new LDAPConnection("localhost", server.getPort(), BIND_DN, BIND_PASSWORD)) {
                SearchResultEntry entry = connection.getEntry(BASE_DN);
                assertThat(entry).isNotNull();
                assertThat(entry.getAttributeValue("dc")).isEqualTo("test");
            }
        }
    }

    @Test
    @DisplayName("Should import entries from a classpath LDIF resource")
    void testLdifImport() throws Exception {
        try (EmbeddedLdapServer server = EmbeddedLdapServer.start(
                BASE_DN, BIND_DN, BIND_PASSWORD, 0, "embedded-ldap-server-test.ldif")) {

            try (LDAPConnection connection =
                         new LDAPConnection("localhost", server.getPort(), BIND_DN, BIND_PASSWORD)) {
                assertThat(connection.getEntry("ou=users," + BASE_DN)).isNotNull();
                assertThat(connection.getEntry("ou=roles," + BASE_DN)).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("Should enforce standard schema so invalid entries are rejected")
    void testSchemaIsEnforced() throws Exception {
        try (EmbeddedLdapServer server =
                     EmbeddedLdapServer.start(BASE_DN, BIND_DN, BIND_PASSWORD, 0, null)) {

            try (LDAPConnection connection =
                         new LDAPConnection("localhost", server.getPort(), BIND_DN, BIND_PASSWORD)) {

                // inetOrgPerson requires 'sn'; omitting it must fail
                assertThatThrownBy(() -> connection.add(
                        "dn: cn=broken," + BASE_DN,
                        "objectClass: inetOrgPerson",
                        "cn: broken"))
                        .isInstanceOf(LDAPException.class);
            }
        }
    }

    @Test
    @DisplayName("Should release the port after close")
    void testCloseReleasesServer() throws Exception {
        EmbeddedLdapServer server =
                EmbeddedLdapServer.start(BASE_DN, BIND_DN, BIND_PASSWORD, 0, null);
        int port = server.getPort();
        server.close();

        assertThatThrownBy(() -> new LDAPConnection("localhost", port, BIND_DN, BIND_PASSWORD))
                .isInstanceOf(LDAPException.class);
    }
}
