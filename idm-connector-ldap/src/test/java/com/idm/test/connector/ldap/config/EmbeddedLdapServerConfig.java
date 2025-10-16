package com.idm.test.connector.ldap.config;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.LDAPException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Configuration for embedded LDAP server for testing.
 * Uses UnboundID InMemoryDirectoryServer.
 */
@Configuration
public class EmbeddedLdapServerConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedLdapServerConfig.class);

    private static final int LDAP_PORT = 10389;
    private static final String BASE_DN = "dc=springframework,dc=org";
    private static final String BIND_DN = "uid=admin,ou=system,dc=springframework,dc=org";
    private static final String BIND_PASSWORD = "secret";

    private InMemoryDirectoryServer directoryServer;

    @PostConstruct
    public void startLdapServer() throws Exception {
        log.info("Starting embedded LDAP server on port {}", LDAP_PORT);

        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);

        // Configure listener
        config.setListenerConfigs(
                InMemoryListenerConfig.createLDAPConfig("default", LDAP_PORT)
        );

        // Configure authentication
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);

        // Create and start server
        directoryServer = new InMemoryDirectoryServer(config);

        // Add base entry
        directoryServer.add(
                "dn: " + BASE_DN,
                "objectClass: top",
                "objectClass: domain",
                "dc: springframework"
        );

        // Add organizational units
        directoryServer.add(
                "dn: ou=people," + BASE_DN,
                "objectClass: top",
                "objectClass: organizationalUnit",
                "ou: people"
        );

        directoryServer.add(
                "dn: ou=groups," + BASE_DN,
                "objectClass: top",
                "objectClass: organizationalUnit",
                "ou: groups"
        );

        // Add system organizational unit for admin
        directoryServer.add(
                "dn: ou=system," + BASE_DN,
                "objectClass: top",
                "objectClass: organizationalUnit",
                "ou: system"
        );

        directoryServer.add(
                "dn: " + BIND_DN,
                "objectClass: top",
                "objectClass: person",
                "objectClass: organizationalPerson",
                "objectClass: inetOrgPerson",
                "uid: admin",
                "cn: Admin",
                "sn: Administrator",
                "userPassword: " + BIND_PASSWORD
        );

        directoryServer.startListening();

        log.info("Embedded LDAP server started successfully on ldap://localhost:{}", LDAP_PORT);
    }

    @PreDestroy
    public void stopLdapServer() {
        if (directoryServer != null) {
            log.info("Stopping embedded LDAP server");
            directoryServer.shutDown(true);
        }
    }

    @Bean
    public InMemoryDirectoryServer inMemoryDirectoryServer() {
        return directoryServer;
    }
}
