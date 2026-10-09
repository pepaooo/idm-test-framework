package com.idm.test.connector.ldap.config;

import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ldap.pool2.factory.PooledContextSource;

import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LDAP configuration")
class LdapConfigurationTest {

    private static LdapProperties directory(String url) {
        LdapProperties properties = new LdapProperties();
        properties.setUrl(url);
        properties.setBaseDn("dc=test,dc=com");
        return properties;
    }

    private static LdapDirectoriesProperties directories(String defaultDirectory, Map<String, LdapProperties> map) {
        LdapDirectoriesProperties properties = new LdapDirectoriesProperties();
        properties.setDefaultDirectory(defaultDirectory);
        properties.setDirectories(new LinkedHashMap<>(map));
        return properties;
    }

    @Test
    @DisplayName("The old idm.test.ldap block is the directory 'default', beside the named ones")
    void testOldBlockIsDefault() {
        LdapProperties legacy = directory("ldap://edir:389");
        LdapProperties ad = directory("ldap://ad:389");
        List<String> warnings = new ArrayList<>();

        Map<String, LdapProperties> configs = LdapConfiguration.directoryConfigs(legacy, Map.of("ad", ad), warnings::add);

        assertThat(configs).containsOnlyKeys("ad", "default");
        assertThat(configs.get("default")).isSameAs(legacy);
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("idm.test.directories.default replaces the old block, with a warning")
    void testNamedDefaultReplacesOldBlock() {
        LdapProperties legacy = directory("ldap://old:389");
        LdapProperties named = directory("ldap://new:389");
        List<String> warnings = new ArrayList<>();

        Map<String, LdapProperties> configs =
                LdapConfiguration.directoryConfigs(legacy, Map.of("default", named), warnings::add);

        assertThat(configs.get("default")).isSameAs(named);
        assertThat(warnings).containsExactly("idm.test.directories.default replaces the idm.test.ldap block");
    }

    @Test
    @DisplayName("An old block without a url is not a directory")
    void testOldBlockWithoutUrlIgnored() {
        assertThat(LdapConfiguration.directoryConfigs(directory(null), Map.of("ad", directory("ldap://ad:389")), w -> { }))
                .containsOnlyKeys("ad");
        assertThat(LdapConfiguration.directoryConfigs(directory(" "), Map.of(), w -> { })).isEmpty();
    }

    @Test
    @DisplayName("A named directory without a url fails startup, naming it")
    void testNamedDirectoryWithoutUrl() {
        LdapConfiguration configuration = new LdapConfiguration();

        assertThatThrownBy(() -> configuration.ldapDirectories(directory(null), directories(null, Map.of("ad", directory(null)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("idm.test.directories.ad.url is not set");
    }

    @Test
    @DisplayName("An unknown default-directory fails startup")
    void testUnknownDefaultDirectory() {
        LdapConfiguration configuration = new LdapConfiguration();

        assertThatThrownBy(() -> configuration.ldapDirectories(directory("ldap://edir:389"),
                directories("adx", Map.of("ad", directory("ldap://ad:389")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("idm.test.default-directory is 'adx', which is not configured."
                        + " Configured directories: [ad, default]");
    }

    @Test
    @DisplayName("The default beans are the default directory's; null when there is no default")
    void testDefaultBeans() {
        LdapConfiguration configuration = new LdapConfiguration();

        try (LdapDirectories withDefault = configuration.ldapDirectories(directory("ldap://edir:389"),
                directories(null, Map.of("ad", directory("ldap://ad:389"))));
             LdapDirectories noDefault = configuration.ldapDirectories(directory(null),
                directories(null, Map.of("ad", directory("ldap://ad:389"), "edir", directory("ldap://edir:389"))))) {
            assertThat(configuration.ldapTemplate(withDefault)).isSameAs(withDefault.template("default").orElseThrow());
            assertThat(configuration.edirectoryConnector(withDefault)).isSameAs(withDefault.connector("default").orElseThrow());

            assertThat(noDefault.defaultName()).isEmpty();
            assertThat(configuration.ldapTemplate(noDefault)).isNull();
            assertThat(configuration.edirectoryConnector(noDefault)).isNull();
        }
    }

    @Test
    @DisplayName("Building directories opens no connection, pooled or not")
    void testNoConnectionAtStartup() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            String url = "ldap://127.0.0.1:" + server.getLocalPort();
            LdapProperties pooled = directory(url);
            LdapProperties unpooled = directory(url);
            unpooled.setPooled(false);

            try (LdapDirectories built = new LdapConfiguration().ldapDirectories(directory(null),
                    directories(null, Map.of("pooled", pooled, "unpooled", unpooled)))) {
                server.setSoTimeout(300);
                assertThatThrownBy(server::accept).isInstanceOf(SocketTimeoutException.class);
            }
        }
    }

    @Test
    @DisplayName("Closing the directories destroys each connection pool and releases its connections")
    void testCloseDestroysPools() throws Exception {
        try (EmbeddedLdapServer server = EmbeddedLdapServer.start(
                "dc=test,dc=com", "cn=admin,dc=test,dc=com", "secret", 0, null)) {
            LdapProperties pooled = directory(server.getUrl());
            pooled.setUserDn("cn=admin,dc=test,dc=com");
            pooled.setPassword("secret");
            LdapDirectories directories = new LdapConfiguration().ldapDirectories(directory(null),
                    directories(null, Map.of("pooled", pooled)));

            directories.template("pooled").orElseThrow().lookup("");
            PooledContextSource pool =
                    (PooledContextSource) directories.template("pooled").orElseThrow().getContextSource();
            assertThat(pool.getNumIdle()).isGreaterThan(0);

            directories.close();

            assertThat(pool.getNumIdle()).isZero();
            assertThatThrownBy(pool::getReadOnlyContext).isInstanceOf(RuntimeException.class);
        }
    }
}
