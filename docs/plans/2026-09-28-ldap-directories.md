# Several LDAP directories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scenarios can target several named LDAP directories (`idm.test.directories.<name>`, chosen per step with `directory:`), and `ldap.create` can write custom object classes.

**Architecture:**
- **`LdapDirectories`** (connector module) holds one `LdapTemplate` and `EdirectoryConnector` per directory, and resolves the default. `LdapConfiguration` builds it from the new `idm.test.directories` map plus the old `idm.test.ldap` block (as `default`), and still exposes the default directory's template and connector as beans for the Java tier.
- **The four `ldap.*` steps** take `LdapDirectories` and pick a directory through a small shared helper, `LdapTarget`.
- **The real-environment guards** read the directories from the `Environment` with Spring's `Binder` and check only the directories the selected scenarios use.
- **A second embedded directory** in `BaseIntegrationTest` lets a dedicated scenario prove the wiring end to end.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring LDAP (`LdapContextSource`, `PoolingContextSource`, `LdapTemplate`), UnboundID in-memory LDAP, JUnit 5, AssertJ, Mockito (engine tests already use it).

**Spec:** `docs/specs/2026-09-28-ldap-directories-design.md` (commit 9f0ea9e). **Ticket:** ITF-34.

## Global Constraints

- **Config keys:** `idm.test.directories.<name>.{url, base-dn, user-dn, password, pooled, min-idle, max-active, max-total, max-wait}` (the same keys and defaults as `LdapProperties`), and `idm.test.default-directory`.
- **The old block:** `idm.test.ldap` is the directory named `default` when its `url` is non-blank. When `idm.test.directories.default` also exists, it replaces the old block, and this warning is logged: `idm.test.directories.default replaces the idm.test.ldap block`.
- **Default directory resolution, in order:** `idm.test.default-directory` if set (non-blank), which must be configured or startup fails with `idm.test.default-directory is '<x>', which is not configured. Configured directories: [<sorted names>]`; else the only directory; else `default` if configured; else none.
- **Nothing connects at startup.** Building the directories opens no connection.
- **The Java tier is unchanged:** beans `LdapTemplate` and `EdirectoryConnector` are the default directory's (null beans when there is no default). `EdirectoryConnector` is no longer a `@Component`.
- **Step messages, exactly** (final failures, ITF-26):
  - `<step>: unknown directory '<name>'. Configured directories: [<sorted names>]`
  - `<step>: name a directory; there is no default. Configured directories: [<sorted names>]`
- **When more than one directory is configured,** every `ldap.*` step message shows a DN as `<dn> in directory '<name>'`. With one directory, messages are unchanged.
- **`objectClass` on create:** an `objectClass` attribute (name matched case-insensitively; a string or a list) replaces the default `top, person, organizationalPerson, inetOrgPerson`. Empty or blank values fail the create.
- **Guard messages, exactly:**
  - `Scenarios use directory '<name>', which is not configured. Configured directories: [<sorted names>]`
  - `Scenarios use an ldap.* step without directory:, and there is no default directory. Set idm.test.default-directory, or name a directory on each step. Configured directories: [<sorted names>]`
  - a named directory's URL: via the existing `requireNamedValue`, with property `idm.test.directories.<name>.url`, variable `IDM_TEST_DIRECTORIES_<NAME>_URL` (upper case, `-` removed), placeholder `ldap://localhost:389`
  - a named directory's `user-dn` or `password` still at `cn=admin,dc=company,dc=com` / `secret`: via the existing `refusePlaceholder`, naming `idm.test.directories.<name>.user-dn` / `.password`
  - the `default` directory from the old block: today's checks, unchanged
- **Secrets never appear** in messages, logs or reports (ITF-23 rule): never log or print a password.
- **The engine stays Spring-free** (`NoSpringDependencyTest`, main sources only).
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. "Unresolved compilation problems" means stale classes: run `./mvnw clean test`. Green means 0 failures, errors and skips.
- **Commits** end with a blank line, `ITF-34`, and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: `LdapDirectories`, configuration and `objectClass` (connector module)

**Files:**
- Create: `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/LdapDirectories.java`
- Create: `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/config/LdapDirectoriesProperties.java`
- Modify (replace): `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/config/LdapConfiguration.java`
- Modify: `idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/EdirectoryConnector.java` (drop `@Component`; `create` object classes)
- Modify: `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/EdirectoryConnectorTest.java`
- Create: `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/LdapDirectoriesTest.java`
- Create: `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/client/ObjectClassesTest.java`
- Create: `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/config/LdapConfigurationTest.java`

**Interfaces:**
- Produces (later tasks rely on these exact names):
  - `LdapDirectories.DEFAULT` = `"default"`
  - `static LdapDirectories LdapDirectories.of(Map<String, LdapTemplate> templates, String configuredDefault)`
  - `static LdapDirectories LdapDirectories.ofConnectors(Map<String, EdirectoryConnector> connectors, String configuredDefault)`
  - `Set<String> names()` (sorted), `Optional<String> defaultName()`, `Optional<EdirectoryConnector> connector(String name)`, `Optional<LdapTemplate> template(String name)`
  - `static Optional<String> LdapDirectories.resolveDefault(String configured, Set<String> names)`; throws `IllegalStateException` for an unknown configured default
  - a `LdapDirectories` Spring bean, plus `LdapTemplate` and `EdirectoryConnector` beans for the default directory

- [ ] **Step 1: Write the failing tests**

`LdapDirectoriesTest.java`:

```java
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
```

`ObjectClassesTest.java`:

```java
package com.idm.test.connector.ldap.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Object classes on create")
class ObjectClassesTest {

    @Test
    @DisplayName("Without objectClass, the inetOrgPerson chain is used")
    void testDefault() {
        assertThat(EdirectoryConnector.objectClasses(null))
                .containsExactly("top", "person", "organizationalPerson", "inetOrgPerson");
    }

    @Test
    @DisplayName("A single string or a list replaces the default")
    void testStringAndList() {
        assertThat(EdirectoryConnector.objectClasses("user")).containsExactly("user");
        assertThat(EdirectoryConnector.objectClasses(List.of("top", "person", "organizationalPerson", "user")))
                .containsExactly("top", "person", "organizationalPerson", "user");
    }

    @Test
    @DisplayName("An empty list or a blank value is refused")
    void testEmptyRefused() {
        assertThatThrownBy(() -> EdirectoryConnector.objectClasses(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("objectClass must name at least one object class, with no blank values");
        assertThatThrownBy(() -> EdirectoryConnector.objectClasses(List.of("top", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`LdapConfigurationTest.java`:

```java
package com.idm.test.connector.ldap.config;

import com.idm.test.connector.ldap.client.LdapDirectories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

        LdapDirectories withDefault = configuration.ldapDirectories(directory("ldap://edir:389"),
                directories(null, Map.of("ad", directory("ldap://ad:389"))));
        assertThat(configuration.ldapTemplate(withDefault)).isSameAs(withDefault.template("default").orElseThrow());
        assertThat(configuration.edirectoryConnector(withDefault)).isSameAs(withDefault.connector("default").orElseThrow());

        LdapDirectories noDefault = configuration.ldapDirectories(directory(null),
                directories(null, Map.of("ad", directory("ldap://ad:389"), "edir", directory("ldap://edir:389"))));
        assertThat(noDefault.defaultName()).isEmpty();
        assertThat(configuration.ldapTemplate(noDefault)).isNull();
        assertThat(configuration.edirectoryConnector(noDefault)).isNull();
    }

    @Test
    @DisplayName("Building directories opens no connection, pooled or not")
    void testNoConnectionAtStartup() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            String url = "ldap://127.0.0.1:" + server.getLocalPort();
            LdapProperties pooled = directory(url);
            LdapProperties unpooled = directory(url);
            unpooled.setPooled(false);

            new LdapConfiguration().ldapDirectories(directory(null),
                    directories(null, Map.of("pooled", pooled, "unpooled", unpooled)));

            server.setSoTimeout(300);
            assertThatThrownBy(server::accept).isInstanceOf(SocketTimeoutException.class);
        }
    }
}
```

In `EdirectoryConnectorTest.java`:
- change the `@SpringBootTest(classes = {...})` list to `EmbeddedLdapServerConfig.class, LdapConfiguration.class` (remove `EdirectoryConnector.class`: it is now a bean of `LdapConfiguration`, and listing the class too would register a second one)
- add this test at the end of the class (and the imports `org.springframework.ldap.core.AttributesMapper`, `javax.naming.NamingEnumeration`, `java.util.ArrayList`):

```java
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
```

If `@Order(100)` collides with an existing order value, use the next free one above the highest existing.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-connector-ldap -am -Dtest='LdapDirectoriesTest,ObjectClassesTest,LdapConfigurationTest,EdirectoryConnectorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`LdapDirectories`, `LdapDirectoriesProperties`, `objectClasses` not found).

- [ ] **Step 3: Implement `LdapDirectories`**

```java
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
```

- [ ] **Step 4: Implement `LdapDirectoriesProperties`**

```java
package com.idm.test.connector.ldap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Several named LDAP directories, each with the same keys as {@link LdapProperties}:
 * <pre>
 * idm:
 *   test:
 *     default-directory: edirectory
 *     directories:
 *       edirectory:
 *         url: ldaps://edir.company.com:636
 *         base-dn: o=company
 *       ad:
 *         url: ldaps://dc01.company.com:636
 *         base-dn: dc=corp,dc=company,dc=com
 * </pre>
 * The older {@code idm.test.ldap} block is the directory named {@code default}.
 */
@ConfigurationProperties(prefix = "idm.test")
public class LdapDirectoriesProperties {

    private String defaultDirectory;
    private Map<String, LdapProperties> directories = new LinkedHashMap<>();

    public String getDefaultDirectory() {
        return defaultDirectory;
    }

    public void setDefaultDirectory(String defaultDirectory) {
        this.defaultDirectory = defaultDirectory;
    }

    public Map<String, LdapProperties> getDirectories() {
        return directories;
    }

    public void setDirectories(Map<String, LdapProperties> directories) {
        this.directories = directories;
    }
}
```

- [ ] **Step 5: Replace `LdapConfiguration`**

```java
package com.idm.test.connector.ldap.config;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.client.LdapDirectories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.pool.factory.PoolingContextSource;
import org.springframework.ldap.pool.validation.DefaultDirContextValidator;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Builds one LdapTemplate and {@link EdirectoryConnector} per configured directory
 * ({@code idm.test.directories.<name>}, plus the older {@code idm.test.ldap} block as
 * {@code default}), held in {@link LdapDirectories}. The default directory's template and
 * connector are also beans, for the Java tier. Nothing connects here: a connection opens when
 * a directory is first used.
 */
@Configuration
@EnableConfigurationProperties({LdapProperties.class, LdapDirectoriesProperties.class})
public class LdapConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LdapConfiguration.class);

    static final String REPLACED_WARNING = "idm.test.directories.default replaces the idm.test.ldap block";

    @Bean
    public LdapDirectories ldapDirectories(LdapProperties ldapProperties, LdapDirectoriesProperties directoriesProperties) {
        Map<String, LdapProperties> configs =
                directoryConfigs(ldapProperties, directoriesProperties.getDirectories(), log::warn);
        Map<String, LdapTemplate> templates = new TreeMap<>();
        configs.forEach((name, config) -> templates.put(name, buildTemplate(name, config)));
        LdapDirectories directories = LdapDirectories.of(templates, directoriesProperties.getDefaultDirectory());
        log.info("LDAP directories {} (default: {})", directories.names(), directories.defaultName().orElse("none"));
        return directories;
    }

    /** The default directory's template, for the Java tier; null (no bean) when there is no default. */
    @Bean
    public LdapTemplate ldapTemplate(LdapDirectories ldapDirectories) {
        return ldapDirectories.defaultName().flatMap(ldapDirectories::template).orElse(null);
    }

    /** The default directory's connector, for the Java tier; null (no bean) when there is no default. */
    @Bean
    public EdirectoryConnector edirectoryConnector(LdapDirectories ldapDirectories) {
        return ldapDirectories.defaultName().flatMap(ldapDirectories::connector).orElse(null);
    }

    /**
     * The named directories, plus the older {@code idm.test.ldap} block as {@code default} when
     * its url is set. A named {@code default} replaces the older block, with a warning.
     */
    static Map<String, LdapProperties> directoryConfigs(
            LdapProperties legacy, Map<String, LdapProperties> directories, Consumer<String> warn) {
        Map<String, LdapProperties> configs = new TreeMap<>(directories);
        if (legacy != null && legacy.getUrl() != null && !legacy.getUrl().isBlank()) {
            if (configs.containsKey(LdapDirectories.DEFAULT)) {
                warn.accept(REPLACED_WARNING);
            } else {
                configs.put(LdapDirectories.DEFAULT, legacy);
            }
        }
        return configs;
    }

    /** Context source, then an optional pool, then the template: the chain one directory always used. */
    static LdapTemplate buildTemplate(String name, LdapProperties config) {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new IllegalStateException("idm.test.directories." + name + ".url is not set");
        }
        log.info("Configuring LDAP directory '{}': {}", name, config.getUrl());

        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(config.getUrl());
        contextSource.setBase(config.getBaseDn());
        contextSource.setUserDn(config.getUserDn());
        contextSource.setPassword(config.getPassword());
        contextSource.setPooled(false); // PoolingContextSource below does the pooling
        contextSource.setReferral("follow");
        try {
            contextSource.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure LDAP directory '" + name + "'", e);
        }

        LdapTemplate template;
        if (config.isPooled()) {
            PoolingContextSource pool = new PoolingContextSource();
            pool.setContextSource(contextSource);
            pool.setDirContextValidator(new DefaultDirContextValidator());
            pool.setTestOnBorrow(true);
            pool.setTestWhileIdle(true);
            pool.setMinIdle(config.getMinIdle());
            pool.setMaxActive(config.getMaxActive());
            pool.setMaxTotal(config.getMaxTotal());
            pool.setMaxWait(config.getMaxWait());
            template = new LdapTemplate(pool);
        } else {
            template = new LdapTemplate(contextSource);
        }
        template.setIgnorePartialResultException(true);
        return template;
    }
}
```

- [ ] **Step 6: Change `EdirectoryConnector`**

- Remove the `@Component` annotation and its import (`org.springframework.stereotype.Component`). Add to the class javadoc: `Built per directory by {@code LdapConfiguration}; see {@link LdapDirectories}.`
- In `create`, replace

```java
            // Set object classes
            context.setAttributeValues("objectClass", new String[]{"top", "person", "organizationalPerson", "inetOrgPerson"});
```

  with

```java
            // Object classes: the author's, when the attributes name them, else the inetOrgPerson chain
            Map<String, Object> custom = new LinkedHashMap<>(user.getCustomAttributes());
            context.setAttributeValues("objectClass", objectClasses(removeIgnoringCase(custom, "objectClass")));
```

  and change the custom-attribute loop from `user.getCustomAttributes().entrySet()` to `custom.entrySet()`.
- Add these members (and imports `java.util.Collection`, `java.util.Iterator`, `java.util.LinkedHashMap` if missing):

```java
    private static final String[] DEFAULT_OBJECT_CLASSES = {"top", "person", "organizationalPerson", "inetOrgPerson"};

    /** The object classes to write: the author's (one or a list), else the inetOrgPerson chain. */
    static String[] objectClasses(Object requested) {
        if (requested == null) {
            return DEFAULT_OBJECT_CLASSES.clone();
        }
        List<String> classes = requested instanceof Collection<?> values
                ? values.stream().map(String::valueOf).toList()
                : List.of(String.valueOf(requested));
        if (classes.isEmpty() || classes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("objectClass must name at least one object class, with no blank values");
        }
        return classes.toArray(String[]::new);
    }

    /** Removes every entry whose name equals {@code name} ignoring case (LDAP names are case-insensitive), returning the last value. */
    private static Object removeIgnoringCase(Map<String, Object> attributes, String name) {
        Object found = null;
        for (Iterator<Map.Entry<String, Object>> it = attributes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Object> entry = it.next();
            if (entry.getKey().equalsIgnoreCase(name)) {
                found = entry.getValue();
                it.remove();
            }
        }
        return found;
    }
```

An `IllegalArgumentException` from `objectClasses` is inside `create`'s existing `try`, so it becomes an ordinary failed `OperationResult` ("Failed to create user: objectClass must …").

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-connector-ldap -am -Dtest='LdapDirectoriesTest,ObjectClassesTest,LdapConfigurationTest,EdirectoryConnectorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass.

- [ ] **Step 8: Falsify each rule**

One at a time, make the change, run the four test classes, confirm the named test fails, then revert:
- drop the `names.size() == 1` branch → `testResolveDefault` fails
- always `configs.put(DEFAULT, legacy)` (no replace) → `testNamedDefaultReplacesOldBlock` fails
- call `pool.getReadOnlyContext()` (or `template.lookup("")` wrapped in try/catch) at the end of `buildTemplate` → `testNoConnectionAtStartup` fails
- revert `create` to the hard-coded object classes → `testCreateWithCustomObjectClass` fails

Record each result in the report.

- [ ] **Step 9: Run the dependent modules**

The scenarios module now gets `EdirectoryConnector` from `LdapConfiguration` instead of a component scan.

Run: `./mvnw test -pl idm-connector-ldap,idm-test-scenarios -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips.

- [ ] **Step 10: Commit**

```bash
git add idm-connector-ldap
git commit -m "feat: configure several named LDAP directories, and let ldap.create set objectClass

ITF-34

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `directory:` on the `ldap.*` steps

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapTarget.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapCreateStep.java`, `LdapVerifyStep.java`, `LdapUpdateStep.java`, `LdapDeleteStep.java`
- Modify: `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java` (`registry`, and its call)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioValidationTest.java` (two `registry` calls)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java` (the injected field and `registry` call)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/base/BaseIntegrationTest.java` (inject `LdapDirectories`)
- Modify: `docs/reference/writing-scenarios.md` (a `directory` row in the four `ldap.*` tables)

**Interfaces:**
- Consumes (Task 1): `LdapDirectories` (`com.idm.test.connector.ldap.client`) with `names()`, `defaultName()`, `connector(String)`, `ofConnectors(Map<String, EdirectoryConnector>, String)`, `DEFAULT`; a `LdapDirectories` Spring bean.
- Produces: the four step constructors take `LdapDirectories`; `YamlScenarioTest.registry(LdapDirectories ldapDirectories, MultiDatabaseConnector, RoleServiceConnector, ResourceServiceConnector, IdmRestConnector, RestProperties, RestAuthenticator)`; `BaseIntegrationTest.ldapDirectories` (protected, `@Autowired`).

- [ ] **Step 1: Write the failing tests**

In `LdapStepsTest.java`:
- add `import com.idm.test.connector.ldap.client.LdapDirectories;`, a static field `private static LdapDirectories directories;`, and at the end of `startDirectory()`: `directories = only(connector);`
- add the helper:

```java
    private static LdapDirectories only(EdirectoryConnector connector) {
        return LdapDirectories.ofConnectors(Map.of(LdapDirectories.DEFAULT, connector), null);
    }
```

- replace every `Step(connector)` with `Step(directories)` and every `Step(mockConnector)` with `Step(only(mockConnector))` (a mechanical edit: `sed -i '' -e 's/Step(connector)/Step(directories)/g' -e 's/Step(mockConnector)/Step(only(mockConnector))/g'`). The direct `connector.read(...)` / `connector.delete(...)` calls stay.
- add these tests:

```java
    @Test
    @DisplayName("directory: selects that directory's connector, and messages name it when several are configured")
    void testDirectorySelectsConnector() {
        EdirectoryConnector a = mock(EdirectoryConnector.class);
        EdirectoryConnector b = mock(EdirectoryConnector.class);
        when(b.create(any())).thenReturn(OperationResult.success(new LdapUser()));
        LdapDirectories two = LdapDirectories.ofConnectors(Map.of("a", a, "b", b), "a");

        StepOutcome outcome = new LdapCreateStep(two).execute(Map.of(
                "directory", "b", "dn", "cn=x,ou=users", "attributes", Map.of("cn", "x", "sn", "X")), false);

        assertThat(outcome.success()).as(outcome.message()).isTrue();
        assertThat(outcome.message()).isEqualTo("created cn=x,ou=users in directory 'b'");
        verify(b).create(any());
        verify(a, never()).create(any());
    }

    @Test
    @DisplayName("Without directory:, the default directory is used")
    void testDefaultDirectoryUsed() {
        EdirectoryConnector a = mock(EdirectoryConnector.class);
        EdirectoryConnector b = mock(EdirectoryConnector.class);
        when(b.exists(any())).thenReturn(OperationResult.success(true));
        when(b.delete(any())).thenReturn(OperationResult.success(null));
        LdapDirectories two = LdapDirectories.ofConnectors(Map.of("a", a, "b", b), "b");

        StepOutcome outcome = new LdapDeleteStep(two).execute(Map.of("dn", "cn=x,ou=users"), false);

        assertThat(outcome.success()).as(outcome.message()).isTrue();
        assertThat(outcome.message()).isEqualTo("deleted cn=x,ou=users in directory 'b'");
        verify(a, never()).exists(any());
    }

    @Test
    @DisplayName("An unknown directory is a final failure, listing the configured ones")
    void testUnknownDirectoryIsFinal() {
        LdapDirectories two = LdapDirectories.ofConnectors(
                Map.of("ad", mock(EdirectoryConnector.class), "default", mock(EdirectoryConnector.class)), null);

        StepOutcome outcome = new LdapVerifyStep(two).execute(Map.of(
                "directory", "adx", "dn", "cn=x,ou=users", "exists", true), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.isFinal()).isTrue();
        assertThat(outcome.message()).isEqualTo("ldap.verify: unknown directory 'adx'. Configured directories: [ad, default]");
    }

    @Test
    @DisplayName("No directory: and no default is a final failure")
    void testNoDefaultIsFinal() {
        LdapDirectories two = LdapDirectories.ofConnectors(
                Map.of("ad", mock(EdirectoryConnector.class), "edir", mock(EdirectoryConnector.class)), null);

        StepOutcome outcome = new LdapUpdateStep(two).execute(Map.of(
                "dn", "cn=x,ou=users", "attributes", Map.of("title", "T")), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.isFinal()).isTrue();
        assertThat(outcome.message()).isEqualTo("ldap.update: name a directory; there is no default."
                + " Configured directories: [ad, edir]");
    }

    @Test
    @DisplayName("Every ldap.* step accepts directory:")
    void testDirectoryAccepted() {
        for (StepType step : List.of(new LdapCreateStep(directories), new LdapVerifyStep(directories),
                new LdapUpdateStep(directories), new LdapDeleteStep(directories))) {
            assertThat(step.acceptedParams()).as(step.name()).contains("directory");
        }
    }
```

(Add imports `com.idm.test.engine.spi.StepType` and `java.util.List` if missing. `OperationResult.success(...)` is the factory the file's existing mocks already use.)

In `YamlScenarioTest`, `YamlScenarioValidationTest` and `RealEnvironmentScenarios`, the `registry(...)` calls' first argument becomes `ldapDirectories` (Step 4).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=LdapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (the step constructors take `EdirectoryConnector`).

- [ ] **Step 3: Implement `LdapTarget` and the four steps**

`LdapTarget.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.engine.spi.StepOutcome;

import java.util.Map;
import java.util.Optional;

/**
 * The directory an ldap.* step works in: its {@code directory:} parameter, else the default.
 * An unknown directory, or none when there is no default, is a final failure: waiting cannot
 * configure a directory.
 */
final class LdapTarget {

    private final EdirectoryConnector connector;
    private final String suffix;
    private final StepOutcome failure;

    private LdapTarget(EdirectoryConnector connector, String suffix, StepOutcome failure) {
        this.connector = connector;
        this.suffix = suffix;
        this.failure = failure;
    }

    static LdapTarget of(String step, Map<String, Object> params, LdapDirectories directories) {
        Object requested = params.get("directory");
        String name;
        if (requested != null) {
            name = String.valueOf(requested);
        } else {
            Optional<String> fallback = directories.defaultName();
            if (fallback.isEmpty()) {
                return failed(step + ": name a directory; there is no default. Configured directories: "
                        + directories.names());
            }
            name = fallback.get();
        }
        Optional<EdirectoryConnector> connector = directories.connector(name);
        if (connector.isEmpty()) {
            return failed(step + ": unknown directory '" + name + "'. Configured directories: " + directories.names());
        }
        String suffix = directories.names().size() > 1 ? " in directory '" + name + "'" : "";
        return new LdapTarget(connector.get(), suffix, null);
    }

    private static LdapTarget failed(String message) {
        return new LdapTarget(null, "", StepOutcome.finalFailure(message));
    }

    boolean isFailure() {
        return failure != null;
    }

    StepOutcome failure() {
        return failure;
    }

    EdirectoryConnector connector() {
        return connector;
    }

    /** The DN as messages show it: with the directory's name when more than one is configured. */
    String at(String dn) {
        return dn + suffix;
    }
}
```

In each step:
- the field and constructor become `private final LdapDirectories directories;` / `public LdapXStep(LdapDirectories directories)`; import `com.idm.test.connector.ldap.client.LdapDirectories` (keep the `EdirectoryConnector` import only where still used)
- `acceptedParams()`:
  - `LdapCreateStep`: `Set.of("dn", "attributes", "directory")`
  - `LdapVerifyStep`: `Set.of("dn", "expect", "exists", "wait", "directory")`
  - `LdapUpdateStep` (add the override): `Set.of("dn", "attributes", "directory")`
  - `LdapDeleteStep` (add the override): `Set.of("dn", "directory")`
- `execute` starts with:

```java
        String dn = String.valueOf(params.get("dn"));
        LdapTarget target = LdapTarget.of("<step name>", params, directories);
        if (target.isFailure()) {
            return target.failure();
        }
        EdirectoryConnector connector = target.connector();
        String where = target.at(dn);
```

  (with `"ldap.create"`, `"ldap.verify"`, `"ldap.update"` or `"ldap.delete"`), then keeps its current logic, calling the local `connector` with `dn`, and using `where` instead of `dn` **in every message string**. For example, in `LdapCreateStep`: `"ldap.create failed for " + where + ": " + result.getMessage()` and `StepOutcome.success("created " + where, dn)`. The captured values (`dn`, the attributes map) are unchanged.
- In `LdapVerifyStep`, pass `where` to `rejectMalformedAssertion(params, where)` (its messages then show it), and change `verifyExistence` to take the connector and both: `verifyExistence(connector, dn, where, (Boolean) params.get("exists"))`, calling `connector.exists(dn)` and using `where` in its messages.

With a single directory, `where` equals `dn`, so every existing message is unchanged.

- [ ] **Step 4: Update the scenarios module**

- `YamlScenarioTest.registry`: first parameter `LdapDirectories ldapDirectories` (import `com.idm.test.connector.ldap.client.LdapDirectories`; drop the `EdirectoryConnector` import if unused); the four LDAP steps become `new LdapCreateStep(ldapDirectories)` etc. The call in `scenarios()` passes `ldapDirectories`.
- `BaseIntegrationTest`: add, beside `edirectoryConnector`:

```java
    @Autowired
    protected LdapDirectories ldapDirectories;
```

- `YamlScenarioValidationTest`: both `registry(edirectoryConnector, …)` calls become `registry(ldapDirectories, …)`.
- `RealEnvironmentScenarios`: replace the field `private EdirectoryConnector edirectoryConnector;` with `private LdapDirectories ldapDirectories;` (same `@Autowired`), pass it to `registry(...)`, and drop the unused import.
- `ScenarioReferenceTest` passes `null` as the first argument and needs no change.

- [ ] **Step 5: Document the parameter**

In `docs/reference/writing-scenarios.md`, add this row as the **last row** of each of the four `ldap.*` parameter tables (`ldap.create`, `ldap.update`, `ldap.delete`, `ldap.verify`):

```markdown
| `directory` | no | the configured directory to work in (`idm.test.directories.<name>`); without it, the default directory. See **Several directories** under LDAP |
```

(`ScenarioReferenceTest` fails until these rows exist, because `directory` is now an accepted parameter. Task 4 writes the **Several directories** paragraph.)

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=LdapStepsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass.

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='ScenarioReferenceTest,YamlScenarioTest,YamlScenarioValidationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass; the corpus `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`.

- [ ] **Step 7: Falsify each rule**

One at a time, make the change, run `LdapStepsTest`, confirm the named test fails, then revert:
- in `LdapTarget.of`, ignore `directory:` (always use the default) → `testDirectorySelectsConnector` fails
- return `StepOutcome.failure` instead of `finalFailure` in `failed` → `testUnknownDirectoryIsFinal` fails
- always use `""` as the suffix → `testDirectorySelectsConnector` and `testDefaultDirectoryUsed` fail
- remove `"directory"` from `LdapDeleteStep.acceptedParams()` → `testDirectoryAccepted` fails (and `ScenarioReferenceTest` would too)

Record each result in the report.

- [ ] **Step 8: Run the modules**

Run: `./mvnw test -pl idm-test-engine,idm-test-scenarios -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips (includes `NoSpringDependencyTest`).

- [ ] **Step 9: Commit**

```bash
git add idm-test-engine idm-test-scenarios docs/reference/writing-scenarios.md
git commit -m "feat: let ldap.* steps choose a directory, defaulting to the configured default

ITF-34

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Real-environment guards per directory

**Files:**
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioNeeds.java`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentScenarios.java` (`requireEndpoints`, new `requireDirectories`)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioNeedsTest.java`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RealEnvironmentGuardsTest.java`
- Modify: `idm-test-scenarios/src/test/resources/scenarios-real/README.md` (the `ldap.*` row)

**Interfaces:**
- Consumes (Task 1): `LdapDirectories.DEFAULT`, `LdapDirectories.resolveDefault(String, Set<String>)`, `LdapProperties` (getters `getUrl()`, `getUserDn()`, `getPassword()`).
- Produces: `ScenarioNeeds(Set<String> directories, Set<String> databases, boolean roles, boolean resources, Set<String> restTargets)`, `ScenarioNeeds.DEFAULT_DIRECTORY` = `"<default>"`, `boolean ldap()` = `!directories.isEmpty()`; `static void RealEnvironmentScenarios.requireDirectories(Environment environment, Set<String> used)`.

- [ ] **Step 1: Write the failing tests**

`ScenarioNeedsTest.java`:
- every `new ScenarioNeeds(true, …)` becomes `new ScenarioNeeds(java.util.Set.of(ScenarioNeeds.DEFAULT_DIRECTORY), …)`, and every `new ScenarioNeeds(false, …)` becomes `new ScenarioNeeds(java.util.Set.of(), …)`
- add:

```java
    @Test
    @DisplayName("ldap.* steps record their directory, or the default marker when they name none; ${…} names are skipped")
    void testDirectories() {
        ScenarioNeeds needs = needs("""
                name: d
                steps:
                  - ldap.create:
                      directory: ad
                      dn: cn=x,ou=users
                  - ldap.verify:
                      dn: cn=x,ou=users
                      exists: true
                  - ldap.verify:
                      directory: "${dir}"
                      dn: cn=x,ou=users
                      exists: true
                cleanup:
                  - ldap.delete:
                      directory: edir
                      dn: cn=x,ou=users
                """);

        assertThat(needs.directories()).containsExactly(ScenarioNeeds.DEFAULT_DIRECTORY, "ad", "edir");
        assertThat(needs.ldap()).isTrue();
    }
```

(If sorting puts `<default>` elsewhere, keep `containsExactly` in the natural `String` order: `"<default>"` sorts before lowercase letters.)

`RealEnvironmentGuardsTest.java`:
- the constants and every inline `new ScenarioNeeds(true, …)` / `(false, …)` change the same way as above
- add the helpers and tests below (imports: `com.idm.test.connector.ldap.client.LdapDirectories` is not needed; `org.springframework.mock.env.MockEnvironment` is already used):

```java
    private static final ScenarioNeeds AD_ONLY = new ScenarioNeeds(Set.of("ad"), Set.of(), false, false, Set.of());
    private static final ScenarioNeeds DEFAULT_ONLY =
            new ScenarioNeeds(Set.of(ScenarioNeeds.DEFAULT_DIRECTORY), Set.of(), false, false, Set.of());

    /** A dev target with the old block pointing at a real directory, plus the given named-directory properties. */
    private static MockEnvironment directoriesTarget(String... keyValues) {
        MockEnvironment environment = target(REAL_LDAP, null, "dev");
        for (int i = 0; i < keyValues.length; i += 2) {
            environment.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return environment;
    }

    @Test
    @DisplayName("A configured named directory passes")
    void testNamedDirectoryPasses() {
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(
                directoriesTarget("idm.test.directories.ad.url", "ldaps://dc01.example.org:636"), AD_ONLY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A named directory without a url, or with the placeholder, is refused")
    void testNamedDirectoryUrl() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                directoriesTarget("idm.test.directories.ad.base-dn", "dc=corp"), AD_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.directories.ad.url")
                .hasMessageContaining("IDM_TEST_DIRECTORIES_AD_URL");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                directoriesTarget("idm.test.directories.ad.url", "ldap://localhost:389"), AD_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.directories.ad.url")
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("A named directory's placeholder bind DN or password is refused")
    void testNamedDirectoryCredentials() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(directoriesTarget(
                "idm.test.directories.ad.url", "ldaps://dc01.example.org:636",
                "idm.test.directories.ad.user-dn", "cn=admin,dc=company,dc=com"), AD_ONLY))
                .hasMessageContaining("idm.test.directories.ad.user-dn");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(directoriesTarget(
                "idm.test.directories.ad.url", "ldaps://dc01.example.org:636",
                "idm.test.directories.ad.password", "secret"), AD_ONLY))
                .hasMessageContaining("idm.test.directories.ad.password");
    }

    @Test
    @DisplayName("A directory the scenarios use but nobody configured is refused")
    void testUnconfiguredDirectory() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(directoriesTarget(
                "idm.test.directories.ad.url", "ldaps://dc01.example.org:636"),
                new ScenarioNeeds(Set.of("adx"), Set.of(), false, false, Set.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Scenarios use directory 'adx', which is not configured. Configured directories: [ad, default]");
    }

    @Test
    @DisplayName("A step without directory: is refused when there is no default")
    void testNoDefaultDirectory() {
        MockEnvironment environment = target(null, null, "dev");
        environment.setProperty("idm.test.directories.ad.url", "ldaps://dc01.example.org:636");
        environment.setProperty("idm.test.directories.edir.url", "ldaps://edir.example.org:636");

        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(environment, DEFAULT_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Scenarios use an ldap.* step without directory:, and there is no default directory."
                        + " Set idm.test.default-directory, or name a directory on each step."
                        + " Configured directories: [ad, edir]");
    }

    @Test
    @DisplayName("default-directory decides what a step without directory: is checked against")
    void testDefaultDirectorySetting() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(directoriesTarget(
                "idm.test.default-directory", "ad",
                "idm.test.directories.ad.url", "ldap://localhost:389"), DEFAULT_ONLY))
                .hasMessageContaining("idm.test.directories.ad.url");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(directoriesTarget(
                "idm.test.default-directory", "nope",
                "idm.test.directories.ad.url", "ldaps://dc01.example.org:636"), DEFAULT_ONLY))
                .hasMessageContaining("idm.test.default-directory is 'nope'");
    }

    @Test
    @DisplayName("Directories no scenario uses are not checked")
    void testUnusedDirectoryNotChecked() {
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(
                directoriesTarget("idm.test.directories.ad.url", "ldap://localhost:389"), DEFAULT_ONLY))
                .doesNotThrowAnyException();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='ScenarioNeedsTest,RealEnvironmentGuardsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`ScenarioNeeds` takes a `boolean`; no `DEFAULT_DIRECTORY`).

- [ ] **Step 3: Change `ScenarioNeeds`**

- The record header becomes `record ScenarioNeeds(Set<String> directories, Set<String> databases, boolean roles, boolean resources, Set<String> restTargets)`.
- The compact constructor also normalises `directories` the way it does `databases`: `directories = Collections.unmodifiableSortedSet(new TreeSet<>(directories));`
- Add:

```java
    /** Stands for "the default directory" in {@link #directories()}: an ldap.* step that names none. */
    static final String DEFAULT_DIRECTORY = "<default>";

    /** Whether any ldap.* step is used. */
    boolean ldap() {
        return !directories.isEmpty();
    }
```

- In `of`, replace `boolean ldap = false;` with `Set<String> directories = new TreeSet<>();`, and the `ldap.` branch with:

```java
                if (type.startsWith("ldap.")) {
                    Object directory = step.params().get("directory");
                    if (directory == null) {
                        directories.add(DEFAULT_DIRECTORY);
                    } else {
                        addLiteral(directories, directory);
                    }
                }
```

  and return `new ScenarioNeeds(directories, databases, roles, resources, targets)`. Update the class javadoc's description of `ldap` accordingly.

- [ ] **Step 4: Add `requireDirectories` to `RealEnvironmentScenarios`**

- In `requireEndpoints`, replace the whole `if (needs.ldap()) { … }` block with:

```java
        if (needs.ldap()) {
            requireDirectories(environment, needs.directories());
        }
```

- Add (imports: `com.idm.test.connector.ldap.client.LdapDirectories`, `com.idm.test.connector.ldap.config.LdapProperties`, `org.springframework.boot.context.properties.bind.Bindable`, `org.springframework.boot.context.properties.bind.Binder`, `java.util.Optional`):

```java
    /**
     * Guard 2 for LDAP, per directory the selected scenarios use. The directory named
     * {@code default} that the older {@code idm.test.ldap} block configures keeps its original
     * checks; a named directory ({@code idm.test.directories.<name>}) must have a url that is not
     * the placeholder, and neither the placeholder bind DN nor password. A step without
     * {@code directory:} is checked against the default directory. Directories no scenario uses
     * are not checked.
     */
    static void requireDirectories(Environment environment, Set<String> used) {
        Map<String, LdapProperties> named = Binder.get(environment)
                .bind("idm.test.directories", Bindable.mapOf(String.class, LdapProperties.class))
                .orElse(Map.of());
        String legacyUrl = environment.getProperty("idm.test.ldap.url");
        Set<String> names = new TreeSet<>(named.keySet());
        // With no named directories, the old block is the only candidate, and its own checks
        // explain an unset URL better than "not configured" would.
        if (named.isEmpty() || (legacyUrl != null && !legacyUrl.isBlank())) {
            names.add(LdapDirectories.DEFAULT);
        }
        Optional<String> defaultName =
                LdapDirectories.resolveDefault(environment.getProperty("idm.test.default-directory"), names);

        for (String requested : new TreeSet<>(used)) {
            String name = requested;
            if (ScenarioNeeds.DEFAULT_DIRECTORY.equals(requested)) {
                name = defaultName.orElseThrow(() -> new IllegalStateException(
                        "Scenarios use an ldap.* step without directory:, and there is no default directory."
                                + " Set idm.test.default-directory, or name a directory on each step."
                                + " Configured directories: " + names));
            }
            if (!names.contains(name)) {
                throw new IllegalStateException("Scenarios use directory '" + name
                        + "', which is not configured. Configured directories: " + names);
            }
            if (LdapDirectories.DEFAULT.equals(name) && !named.containsKey(LdapDirectories.DEFAULT)) {
                requireLegacyDirectory(environment);
            } else {
                requireNamedDirectory(name, named.get(name));
            }
        }
    }

    /** The older idm.test.ldap block's checks, unchanged. */
    private static void requireLegacyDirectory(Environment environment) {
        requireNamed(environment, "idm.test.ldap.url", "IDM_TEST_LDAP_URL", LDAP_URL_PLACEHOLDER);
        // A blank bind DN or password is anonymous bind, which is legitimate; only the
        // placeholders are refused. The dev profile overrides the DN but not the password.
        if (LDAP_USER_DN_PLACEHOLDER.equals(environment.getProperty("idm.test.ldap.user-dn"))) {
            refusePlaceholder("idm.test.ldap.user-dn");
        }
        if (LDAP_PASSWORD_PLACEHOLDER.equals(environment.getProperty("idm.test.ldap.password"))) {
            refusePlaceholder("idm.test.ldap.password");
        }
    }

    private static void requireNamedDirectory(String name, LdapProperties directory) {
        String prefix = "idm.test.directories." + name + ".";
        requireNamedValue(prefix + "url", directory.getUrl(),
                "IDM_TEST_DIRECTORIES_" + name.toUpperCase(Locale.ROOT).replace("-", "") + "_URL",
                LDAP_URL_PLACEHOLDER);
        if (LDAP_USER_DN_PLACEHOLDER.equals(directory.getUserDn())) {
            refusePlaceholder(prefix + "user-dn");
        }
        if (LDAP_PASSWORD_PLACEHOLDER.equals(directory.getPassword())) {
            refusePlaceholder(prefix + "password");
        }
    }
```

Update the javadoc of `requireEndpoints` to say the LDAP part is per directory (see `requireDirectories`).

- [ ] **Step 5: Update the real-environment README**

In `idm-test-scenarios/src/test/resources/scenarios-real/README.md`, replace the `ldap.*` table row with:

```markdown
| `ldap.*` | For each directory the steps use (a step without `directory:` uses the default directory): the older block's `idm.test.ldap.url` (`IDM_TEST_LDAP_URL`), `user-dn` (`IDM_TEST_LDAP_USERDN`) and `password` (`IDM_TEST_LDAP_PASSWORD`) for the directory `default`, or `idm.test.directories.<name>.url` (`IDM_TEST_DIRECTORIES_<NAME>_URL`), `user-dn` and `password` for a named one; each refused while still the placeholder (`ldap://localhost:389`, `cn=admin,dc=company,dc=com`, `secret`); a blank bind DN or password is allowed (anonymous bind). A directory that isn't configured, or a step without `directory:` when there is no default directory, is refused before anything runs |
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='ScenarioNeedsTest,RealEnvironmentGuardsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass, including every existing guard test unchanged in meaning.

- [ ] **Step 7: Falsify each rule**

One at a time, make the change, run the two test classes, confirm the named test fails, then revert:
- drop `named.isEmpty() ||` → `testUnsetLdapUrlRefused` fails (its message would say "not configured" instead of `IDM_TEST_LDAP_URL`)
- skip `requireNamedDirectory` → `testNamedDirectoryUrl` fails
- check every configured directory instead of `used` → `testUnusedDirectoryNotChecked` fails
- map the default marker to `LdapDirectories.DEFAULT` always → `testDefaultDirectorySetting` fails

Record each result in the report.

- [ ] **Step 8: Run the module**

Run: `./mvnw test -pl idm-test-scenarios -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips (includes `RealEnvironmentLdapOnlyTest`).

- [ ] **Step 9: Commit**

```bash
git add idm-test-scenarios
git commit -m "feat: guard each LDAP directory the real-environment scenarios use

ITF-34

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: A second embedded directory, the end-to-end scenario, and the docs

**Files:**
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/base/BaseIntegrationTest.java` (a second embedded server, configured as `idm.test.directories.second`)
- Create: `idm-test-scenarios/src/test/resources/ldap-second.ldif`
- Create: `idm-test-scenarios/src/test/resources/scenarios-directories/two-directories.yaml`
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/MultiDirectoryScenarioTest.java`
- Modify: `docs/reference/writing-scenarios.md` (LDAP intro, `ldap.create`, §7)
- Modify: `docs/reference/configuration.md` (LDAP Configuration)
- Modify: `docs/architecture/capabilities.md` (one new row)
- Modify: `README.md` (the four `ldap.*` rows of the step table)

**Interfaces:**
- Consumes: Tasks 1–3 (`idm.test.directories.<name>` config, `directory:` on steps, `objectClass` on create, `YamlScenarioTest.registry(LdapDirectories, …)`, `YamlScenarioTest.buildTests(String, ScenarioValidator, ScenarioEngine, Path)`, `BaseIntegrationTest.ldapDirectories`).

- [ ] **Step 1: Add the second embedded directory**

`ldap-second.ldif`:

```
# The second embedded directory (dc=second,dc=com), configured as idm.test.directories.second
# so a scenario can prove it writes to one directory and not another.

dn: ou=users,dc=second,dc=com
objectClass: organizationalUnit
objectClass: top
ou: users
description: User accounts in the second directory
```

In `BaseIntegrationTest`, beside the existing server:

```java
    private static final String SECOND_BASE_DN = "dc=second,dc=com";
    private static final String SECOND_BIND_DN = "cn=admin,dc=second,dc=com";

    /** A second in-memory directory, standard schema, configured as idm.test.directories.second. */
    private static final EmbeddedLdapServer SECOND_LDAP_SERVER = startSecondLdapServer();

    private static EmbeddedLdapServer startSecondLdapServer() {
        try {
            return EmbeddedLdapServer.start(SECOND_BASE_DN, SECOND_BIND_DN, LDAP_BIND_PASSWORD, 0, "ldap-second.ldif");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start the second embedded LDAP server", e);
        }
    }
```

and in `configureProperties`:

```java
        registry.add("idm.test.directories.second.url", SECOND_LDAP_SERVER::getUrl);
        registry.add("idm.test.directories.second.base-dn", () -> SECOND_BASE_DN);
        registry.add("idm.test.directories.second.user-dn", () -> SECOND_BIND_DN);
        registry.add("idm.test.directories.second.password", () -> LDAP_BIND_PASSWORD);
```

The existing server stays the directory `default` (through `idm.test.ldap.*`), and it is the default directory, so the corpus runs unchanged. With two directories, LDAP step messages now end with ` in directory 'default'`.

- [ ] **Step 2: Write the scenario and its test**

`scenarios-directories/two-directories.yaml`:

```yaml
name: One scenario, two directories
description: >
  Creates an entry in the second directory with its own object classes, checks it is there
  and not in the default directory, and deletes it.

fixtures:
  user:
    generate: random-user

steps:
  - ldap.create:
      directory: second
      dn: "cn=${user.userId},ou=users"
      as: createdDn
      attributes:
        objectClass: [top, person, organizationalPerson]
        cn: "${user.userId}"
        sn: "${user.lastName}"

  - ldap.verify:
      directory: second
      dn: "cn=${user.userId},ou=users"
      expect:
        sn: "${user.lastName}"

  - ldap.verify:
      dn: "cn=${user.userId},ou=users"
      exists: false

cleanup:
  - ldap.delete:
      directory: second
      dn: "${createdDn}"
```

`MultiDirectoryScenarioTest.java`:

```java
package com.idm.test.scenarios.engine;

import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.spi.StepType;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs scenarios-directories/ against both embedded directories: the default one and
 * {@code second}. Kept out of the main corpus because ContainerScenarios runs that corpus
 * against a single OpenLDAP container.
 */
@DisplayName("YAML scenarios across two directories")
class MultiDirectoryScenarioTest extends BaseIntegrationTest {

    private static final String SCENARIO_DIR = "scenarios-directories";
    private static final Path RESULTS = Path.of("target", "multi-directory-results.json");

    @Test
    @DisplayName("Both embedded directories are configured, and the old block's is the default")
    void testDirectoriesConfigured() {
        assertThat(ldapDirectories.names()).containsExactly("default", "second");
        assertThat(ldapDirectories.defaultName()).contains("default");
    }

    @TestFactory
    @DisplayName("Each scenario file runs as its own test")
    Stream<DynamicTest> scenarios() throws Exception {
        Map<String, StepType> registry = YamlScenarioTest.registry(
                ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioValidator validator = new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS);
        ScenarioEngine engine = new ScenarioEngine(
                registry,
                new FixtureFactory(testDataGenerator),
                testExecutor,
                executorProperties.getDefaultTimeout(),
                executorProperties.getDefaultPollInterval(),
                new ExpressionEvaluator());

        return YamlScenarioTest.buildTests(SCENARIO_DIR, validator, engine, RESULTS).stream();
    }
}
```

- [ ] **Step 3: Run it**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=MultiDirectoryScenarioTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 2 tests (the configuration check and `two-directories.yaml`), all pass.

Falsify, then revert: change the second step's `directory: second` to nothing (so it verifies in `default`) → the scenario fails. Change the third step to `directory: second` → it fails. Record both.

- [ ] **Step 4: Document several directories in the reference**

In `docs/reference/writing-scenarios.md`, replace the LDAP intro

```markdown
LDAP DNs are **relative to the configured base DN** (`idm.test.ldap.base-dn`). With the base
`dc=test,dc=com`, write `cn=jdoe,ou=users`, not the full DN.
```

with

```markdown
LDAP DNs are **relative to the directory's base DN** (`base-dn`). With the base
`dc=test,dc=com`, write `cn=jdoe,ou=users`, not the full DN.

**Several directories.** A lab can configure several directories, for example your eDirectory
and an Active Directory that IdM provisions into, each under `idm.test.directories.<name>`. An
`ldap.*` step works in the directory its `directory:` names, or in the **default directory**
when it names none:

- `idm.test.default-directory`, when set;
- otherwise the only directory, when there is just one;
- otherwise `default`, which is what the older `idm.test.ldap` block configures.

A `directory:` that isn't configured, or a step without one when there is no default, fails
at once without retrying (`ldap.verify: unknown directory 'adx'. Configured directories: [ad,
default]`), and the real-environment runner refuses such a scenario before running anything.
With more than one directory configured, step messages name the directory
(`created cn=jdoe,ou=users in directory 'ad'`).

```yaml
- ldap.verify:
    directory: ad
    dn: "CN=${user.firstName} ${user.lastName},OU=Users"
    expect:
      mail: ${user.email}
    wait: { timeout: 5m, poll: 10s }
```
```

In the `ldap.create` section, after the `**Captures:**` paragraph, add:

```markdown
**Object classes:** without an `objectClass` attribute the entry is created as
`top, person, organizationalPerson, inetOrgPerson`. Give `objectClass` (one value or a list) to
use other classes, for example for an Active Directory user:

```yaml
- ldap.create:
    directory: ad
    dn: "CN=${user.firstName} ${user.lastName},OU=Users"
    attributes:
      objectClass: [top, person, organizationalPerson, user]
      sAMAccountName: ${user.userId}
      givenName: ${user.firstName}
      sn: ${user.lastName}
```

Active Directory creates such an account disabled and without a password; setting passwords
and enabling accounts is not supported yet (ITF-33).
```

In §7, replace the bullet

```markdown
- **LDAP:** `idm.test.ldap.*`. LDAP steps take DNs relative to `idm.test.ldap.base-dn`.
```

with

```markdown
- **LDAP:** `directory: ad` means `idm.test.directories.ad`; without `directory:`, the default
  directory (the older `idm.test.ldap.*` block is the directory `default`). LDAP steps take DNs
  relative to that directory's `base-dn`. See [LDAP configuration](configuration.md#ldap-configuration).
```

- [ ] **Step 5: Document the configuration**

In `docs/reference/configuration.md`, after the LDAP Configuration section's environment-variable list and before `### Database Configuration`, add:

````markdown
#### Several directories

To check what IdM provisions into more than one directory, name each under
`idm.test.directories`, with the same keys as `idm.test.ldap`:

```yaml
idm:
  test:
    default-directory: edirectory      # used by ldap.* steps without directory:
    directories:
      edirectory:
        url: ldaps://edir.company.com:636
        base-dn: o=company
        user-dn: cn=admin,o=company
        password: ${EDIR_PASSWORD}
      ad:
        url: ldaps://dc01.company.com:636
        base-dn: dc=corp,dc=company,dc=com
        user-dn: CN=svc-idmtest,OU=Service,DC=corp,DC=company,DC=com
        password: ${AD_PASSWORD}
        pooled: false
```

- **The `idm.test.ldap` block above is the directory named `default`,** so existing
  configurations keep working. A `directories.default` entry replaces it (a warning is logged).
- **The default directory** is `default-directory` when set (it must name a configured
  directory, or startup fails), otherwise the only directory, otherwise `default`.
- **Nothing connects at startup:** a directory's connection opens when a step first uses it.
- **Scenarios choose** with `directory: <name>`; see the
  [authoring reference](writing-scenarios.md#ldap).
- **Environment variables** follow Spring's rules, for example
  `IDM_TEST_DIRECTORIES_AD_URL` and `IDM_TEST_DIRECTORIES_AD_PASSWORD`.
````

- [ ] **Step 6: Update the capability matrix**

In `docs/architecture/capabilities.md`, add after the `YAML assertion ldap.verify` row:

```markdown
| Several LDAP directories | Implemented (ITF-34) | Check provisioning into more than one directory (eDirectory, Active Directory, …) | [LdapDirectories][ldap-directories], [LDAP steps][steps] | `idm.test.directories.<name>` and `directory:` on `ldap.*` steps, with a default directory; the older `idm.test.ldap` block is `default`. `ldap.create` takes `objectClass`. AD passwords, account state, binary attributes and paging are ITF-33 |
```

and the link definition beside the other `[ldap]` links:

```markdown
[ldap-directories]: ../../idm-connector-ldap/src/main/java/com/idm/test/connector/ldap/client/LdapDirectories.java
```

(If a `[steps]` link doesn't exist in that file, use the existing link for the step sources.)

In `README.md`'s step table, add `directory` to the optional parameters of the four `ldap.*` rows:

```markdown
| `ldap.create` | action | `dn` | `attributes` (may include `objectClass`), `directory` |
| `ldap.update` | action | `dn`, `attributes` | `directory` |
| `ldap.delete` | action | `dn` | `directory` |
| `ldap.verify` | assertion | `dn`, and exactly one of `expect` or `exists` | `wait`, `directory` |
```

- [ ] **Step 7: Run the full suite and the corpus**

Run: `./mvnw -q clean test`
Expected: exit 0.

Then:
```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -h -o 'failures="[0-9]*"\|errors="[0-9]*"\|skipped="[0-9]*"' */target/surefire-reports/TEST-*.xml | grep -v '"0"' | wc -l
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
grep -c '"status" : "FAILED"' idm-test-scenarios/target/multi-directory-results.json
```
Expected: a test count above 693; `0` non-zero failure/error/skip attributes; both FAILED counts `0`. If `EmbeddedLdapServerTest.testLdifImport` fails (a known intermittent flake in untouched code), re-run once and report both runs.

- [ ] **Step 8: Commit**

```bash
git add idm-test-scenarios docs/reference/writing-scenarios.md docs/reference/configuration.md docs/architecture/capabilities.md README.md
git commit -m "test: prove two directories end to end, and document idm.test.directories

ITF-34

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
