package com.idm.test.connector.ldap.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("LDAP directories")
class LdapDirectoriesTest {

    @Test
    @DisplayName("The default is the configured one, else the only one, else 'default', else none")
    void testResolveDefault() {
        assertThat(LdapDirectories.resolveDefault("ad", Set.of("ad", "default", "edir"))).contains("ad");
        assertThat(LdapDirectories.resolveDefault(null, Set.of("edir"))).contains("edir");
        assertThat(LdapDirectories.resolveDefault("  ", Set.of("ad", "default"))).contains("default");
        assertThat(LdapDirectories.resolveDefault(null, Set.of("ad", "edir"))).isEmpty();
        assertThat(LdapDirectories.resolveDefault(null, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("A configured default that isn't a directory is refused, listing the directories")
    void testUnknownConfiguredDefault() {
        assertThatThrownBy(() -> LdapDirectories.resolveDefault("adx", Set.of("edir", "ad")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("idm.test.default-directory is 'adx', which is not configured."
                        + " Configured directories: [ad, edir]");
    }

    @Test
    @DisplayName("Connectors are looked up by name; names are sorted")
    void testOfConnectors() {
        EdirectoryConnector ad = mock(EdirectoryConnector.class);
        EdirectoryConnector edir = mock(EdirectoryConnector.class);

        LdapDirectories directories = LdapDirectories.ofConnectors(Map.of("edir", edir, "ad", ad), "edir");

        assertThat(directories.names()).containsExactly("ad", "edir");
        assertThat(directories.defaultName()).contains("edir");
        assertThat(directories.connector("ad")).containsSame(ad);
        assertThat(directories.connector("nope")).isEmpty();
        assertThat(directories.template("ad")).isEmpty();
    }

    @Test
    @DisplayName("Built from templates, each directory gets a connector and keeps its template")
    void testOfTemplates() {
        LdapTemplate template = new LdapTemplate(new LdapContextSource());

        LdapDirectories directories = LdapDirectories.of(Map.of("default", template), null);

        assertThat(directories.defaultName()).isEqualTo(Optional.of("default"));
        assertThat(directories.template("default")).containsSame(template);
        assertThat(directories.connector("default")).isPresent();
    }
}
