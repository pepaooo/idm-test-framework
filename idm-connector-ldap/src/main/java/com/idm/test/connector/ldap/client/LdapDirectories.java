package com.idm.test.connector.ldap.client;

import org.springframework.ldap.core.LdapTemplate;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The configured LDAP directories, by name: {@code idm.test.directories.<name>}, plus the older
 * {@code idm.test.ldap} block as {@value #DEFAULT}. Each has its own template and
 * {@link EdirectoryConnector}. The default directory is the one an ldap.* step uses when it
 * names none.
 */
public final class LdapDirectories {

    /** The name of the directory the older {@code idm.test.ldap} block configures. */
    public static final String DEFAULT = "default";

    private final SortedMap<String, LdapTemplate> templates;
    private final SortedMap<String, EdirectoryConnector> connectors;
    private final String defaultName;

    private LdapDirectories(Map<String, LdapTemplate> templates, Map<String, EdirectoryConnector> connectors,
                            String configuredDefault) {
        this.templates = Collections.unmodifiableSortedMap(new TreeMap<>(templates));
        this.connectors = Collections.unmodifiableSortedMap(new TreeMap<>(connectors));
        this.defaultName = resolveDefault(configuredDefault, this.connectors.keySet()).orElse(null);
    }

    /** One connector per template, sharing that template (and so its connection pool). */
    public static LdapDirectories of(Map<String, LdapTemplate> templates, String configuredDefault) {
        Map<String, EdirectoryConnector> connectors = new TreeMap<>();
        templates.forEach((name, template) -> connectors.put(name, new EdirectoryConnector(template)));
        return new LdapDirectories(templates, connectors, configuredDefault);
    }

    /** Directories from ready-made connectors, with no templates: for tests and embedding. */
    public static LdapDirectories ofConnectors(Map<String, EdirectoryConnector> connectors, String configuredDefault) {
        return new LdapDirectories(Map.of(), connectors, configuredDefault);
    }

    /** The configured names, sorted. */
    public Set<String> names() {
        return connectors.keySet();
    }

    public Optional<String> defaultName() {
        return Optional.ofNullable(defaultName);
    }

    public Optional<EdirectoryConnector> connector(String name) {
        return Optional.ofNullable(connectors.get(name));
    }

    public Optional<LdapTemplate> template(String name) {
        return Optional.ofNullable(templates.get(name));
    }

    /**
     * The default directory: {@code configured} when set, else the only directory, else
     * {@value #DEFAULT} when configured, else none.
     *
     * @throws IllegalStateException when {@code configured} names no configured directory
     */
    public static Optional<String> resolveDefault(String configured, Set<String> names) {
        if (configured != null && !configured.isBlank()) {
            if (!names.contains(configured)) {
                throw new IllegalStateException("idm.test.default-directory is '" + configured
                        + "', which is not configured. Configured directories: " + new TreeSet<>(names));
            }
            return Optional.of(configured);
        }
        if (names.size() == 1) {
            return Optional.of(names.iterator().next());
        }
        return names.contains(DEFAULT) ? Optional.of(DEFAULT) : Optional.empty();
    }
}
