package com.idm.test.utils.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.ldif.LDIFException;
import com.unboundid.ldif.LDIFReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * An UnboundID in-memory LDAP directory for tests.
 * <p>
 * Shared by {@code idm-connector-ldap} (fixed port, dc=springframework,dc=org) and
 * {@code idm-test-scenarios} (ephemeral port, dc=test,dc=com). Standard schema validation
 * is deliberately left enabled so that malformed entries fail here rather than surfacing
 * later against a real directory.
 */
public final class EmbeddedLdapServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedLdapServer.class);

    private final InMemoryDirectoryServer server;
    private final int port;

    private EmbeddedLdapServer(InMemoryDirectoryServer server, int port) {
        this.server = server;
        this.port = port;
    }

    /**
     * Starts an in-memory directory.
     *
     * @param baseDn                root suffix, e.g. {@code dc=test,dc=com}
     * @param bindDn                DN accepted for authentication; need not exist as an entry
     * @param bindPassword          password for {@code bindDn}
     * @param port                  TCP port, or {@code 0} to bind an ephemeral port
     * @param ldifClasspathResource classpath LDIF to import after the base entry, or {@code null}
     * @return a started server; the caller owns it and must {@link #close()} it
     */
    public static EmbeddedLdapServer start(
            String baseDn,
            String bindDn,
            String bindPassword,
            int port,
            String ldifClasspathResource) throws LDAPException, LDIFException, IOException {

        return start(baseDn, bindDn, bindPassword, port, ldifClasspathResource, null);
    }

    /**
     * Starts an in-memory directory using a specific schema.
     *
     * @param schema schema to enforce, or {@code null} for the standard schema. Pass a
     *               custom schema to model attributes a real directory defines but the
     *               LDAP standard does not; passing {@code null} keeps validation on,
     *               and validation should never be disabled outright.
     * @see #start(String, String, String, int, String)
     */
    public static EmbeddedLdapServer start(
            String baseDn,
            String bindDn,
            String bindPassword,
            int port,
            String ldifClasspathResource,
            Schema schema) throws LDAPException, LDIFException, IOException {

        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(baseDn);
        if (schema != null) {
            config.setSchema(schema);
        }
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", port));
        config.addAdditionalBindCredentials(bindDn, bindPassword);

        InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
        server.add(buildBaseEntry(baseDn));
        server.startListening();

        int boundPort = server.getListenPort();
        EmbeddedLdapServer embedded = new EmbeddedLdapServer(server, boundPort);

        if (ldifClasspathResource != null) {
            embedded.importLdif(ldifClasspathResource);
        }

        log.info("Embedded LDAP server started on {} with base DN {}", embedded.getUrl(), baseDn);
        return embedded;
    }

    /**
     * Builds the root entry from the base DN's first RDN.
     * {@code dc=test,dc=com} yields a domain entry with {@code dc: test};
     * {@code o=example} yields an organization entry with {@code o: example}.
     */
    private static Entry buildBaseEntry(String baseDn) throws LDIFException {
        String firstRdn = baseDn.split(",")[0].trim();
        int separator = firstRdn.indexOf('=');
        String attribute = firstRdn.substring(0, separator).trim();
        String value = firstRdn.substring(separator + 1).trim();

        // Note: Entry has no (String dn, String... ldifLines) constructor. The varargs
        // constructor takes full LDIF lines, so the DN must be passed as "dn: ..." here.
        if ("dc".equalsIgnoreCase(attribute)) {
            return new Entry(
                    "dn: " + baseDn, "objectClass: top", "objectClass: domain", "dc: " + value);
        }
        if ("o".equalsIgnoreCase(attribute)) {
            return new Entry(
                    "dn: " + baseDn, "objectClass: top", "objectClass: organization", "o: " + value);
        }
        throw new IllegalArgumentException(
                "Unsupported base DN '" + baseDn + "': first RDN must use 'dc' or 'o'");
    }

    private void importLdif(String classpathResource) throws IOException {
        try (InputStream stream =
                     EmbeddedLdapServer.class.getClassLoader().getResourceAsStream(classpathResource)) {

            if (stream == null) {
                throw new IOException("LDIF resource not found on classpath: " + classpathResource);
            }

            try (LDIFReader reader = new LDIFReader(stream)) {
                Entry entry;
                int count = 0;
                while ((entry = reader.readEntry()) != null) {
                    server.add(entry);
                    count++;
                }
                log.info("Imported {} entries from {}", count, classpathResource);
            } catch (LDIFException | LDAPException e) {
                throw new IOException("Failed to import LDIF " + classpathResource, e);
            }
        }
    }

    public int getPort() {
        return port;
    }

    public String getUrl() {
        return "ldap://localhost:" + port;
    }

    public InMemoryDirectoryServer getServer() {
        return server;
    }

    @Override
    public void close() {
        log.info("Stopping embedded LDAP server on port {}", port);
        server.shutDown(true);
    }
}
