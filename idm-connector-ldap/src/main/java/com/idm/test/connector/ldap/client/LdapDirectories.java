package com.idm.test.connector.ldap.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.pool2.factory.PooledContextSource;

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
public final class LdapDirectories implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LdapDirectories.class);

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

    /**
     * Destroys each directory's connection pool, so a closing Spring context releases its pooled
     * connections. A pool that fails to close is logged (by directory name) and the rest still close.
     */
    @Override
    public void close() {
        templates.forEach((name, template) -> {
            if (template.getContextSource() instanceof PooledContextSource pool) {
                try {
                    pool.destroy();
                } catch (Exception e) {
                    log.warn("Failed to close the connection pool of LDAP directory '{}': {}", name, e.toString());
                }
            }
        });
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
