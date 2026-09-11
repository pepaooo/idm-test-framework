package com.idm.test.connector.ldap.config;

import com.idm.test.utils.ldap.EmbeddedLdapServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Starts the embedded LDAP server used by this module's tests.
 * <p>
 * Port and DNs are fixed here because {@code src/test/resources/application.yml}
 * points at {@code ldap://localhost:10389} with base DN {@code dc=springframework,dc=org}.
 */
@Configuration
public class EmbeddedLdapServerConfig {

    private static final int LDAP_PORT = 10389;
    private static final String BASE_DN = "dc=springframework,dc=org";
    private static final String BIND_DN = "uid=admin,ou=system,dc=springframework,dc=org";
    private static final String BIND_PASSWORD = "secret";
    private static final String INIT_LDIF = "embedded-ldap-init.ldif";

    private EmbeddedLdapServer embeddedLdapServer;

    @PostConstruct
    public void startLdapServer() throws Exception {
        embeddedLdapServer =
                EmbeddedLdapServer.start(BASE_DN, BIND_DN, BIND_PASSWORD, LDAP_PORT, INIT_LDIF);
    }

    @PreDestroy
    public void stopLdapServer() {
        if (embeddedLdapServer != null) {
            embeddedLdapServer.close();
        }
    }

    @Bean
    public InMemoryDirectoryServer inMemoryDirectoryServer() {
        return embeddedLdapServer.getServer();
    }
}
