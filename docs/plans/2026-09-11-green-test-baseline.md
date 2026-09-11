# Green Test Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `mvn clean test` pass with zero skipped tests and zero Docker dependency, producing a baseline trustworthy enough to point at a real OpenText IdM environment in phase (b).

**Architecture:** Replace the archived `osixia/openldap` Testcontainers path in `idm-test-scenarios` with the UnboundID `InMemoryDirectoryServer` already proven in `idm-connector-ldap`, extracted into `idm-test-utils` as a single shared component. Make connector injection required so a missing bean fails loudly instead of silently skipping. Then run the suite for the first time in its history and triage what surfaces.

**Tech Stack:** Java 21, Spring Boot 3.5.6, JUnit 5, AssertJ, UnboundID LDAP SDK 7.0.1, WireMock 3.10.0, H2 2.3.232, Maven.

**Source spec:** `docs/specs/2026-09-11-suite-green-baseline-design.md`

## Global Constraints

- Java 21. Use records over Lombok; Lombok is not a dependency and must not be added.
- Maven wrapper is not executable: run it as `sh ./mvnw ...`, never `./mvnw ...`.
- No JPA/Hibernate. Database validation is raw SQL via `JdbcTemplate`.
- No new mocking of external systems beyond the existing WireMock SOAP stubs.
- `idm-connector-ldap` keeps its existing contract **unchanged**: fixed port `10389`, base DN `dc=springframework,dc=org`, bind DN `uid=admin,ou=system,dc=springframework,dc=org`, password `secret`. Its `src/test/resources/application.yml` must not be edited.
- `idm-test-scenarios` uses base DN `dc=test,dc=com`, bind DN `cn=admin,dc=test,dc=com`, password `test`, and an **ephemeral** port.
- UnboundID standard schema validation stays **enabled**. Do not call `setSchema(null)`.
- Out of scope, do not implement: OAuth2 wiring, `IdmRestConnector` authentication, populating `idm-connector-idm`, a ResourceService connector, new test scenarios.
- Commit messages end with the trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

---

## File Structure

**Created:**
- `idm-test-utils/src/main/java/com/idm/test/utils/ldap/EmbeddedLdapServer.java` — the single shared embedded LDAP server. Starts an UnboundID in-memory directory, creates the base entry, optionally imports an LDIF from the classpath, exposes the bound port and URL. One responsibility: run an LDAP server for tests.
- `idm-connector-ldap/src/test/resources/embedded-ldap-init.ldif` — the directory structure that `EmbeddedLdapServerConfig` currently builds with inline Java calls, moved to data.
- `idm-test-utils/src/test/java/com/idm/test/utils/ldap/EmbeddedLdapServerTest.java` — unit tests for the new component.
- `docs/history/README.md` — staleness banner for archived reports.

**Modified:**
- `idm-test-utils/pom.xml` — add `unboundid-ldapsdk` compile dependency.
- `idm-connector-ldap/pom.xml` — add `idm-test-utils` test dependency; remove the unused `apacheds-all` test dependency.
- `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/config/EmbeddedLdapServerConfig.java` — delegate to `EmbeddedLdapServer` instead of hand-rolling the server.
- `idm-test-scenarios/pom.xml` — remove three `org.testcontainers` dependencies.
- `idm-test-scenarios/src/test/java/com/idm/test/scenarios/base/BaseIntegrationTest.java` — swap container for embedded server; required injection; delete skip helpers.
- Five workflow test classes — remove 49 `requireConnector(...)` call sites.
- `README.md`, `CLAUDE.md` — status correction and link updates.

**Deleted:**
- `idm-test-scenarios/src/test/java/com/idm/test/scenarios/UserProvisioningIntegrationTest.java`
- `idm-test-scenarios/src/test/java/com/idm/test/scenarios/LdapDiagnosticTest.java`

**Moved:** eight root markdown reports into `docs/history/`, three into `docs/reference/`.

---

## Task 1: Extract the shared embedded LDAP server

Extracts the working UnboundID setup into `idm-test-utils` so both modules share one implementation. The existing 14 `idm-connector-ldap` tests are the regression check: they must pass identically before and after.

**Files:**
- Create: `idm-test-utils/src/main/java/com/idm/test/utils/ldap/EmbeddedLdapServer.java`
- Create: `idm-test-utils/src/test/java/com/idm/test/utils/ldap/EmbeddedLdapServerTest.java`
- Create: `idm-connector-ldap/src/test/resources/embedded-ldap-init.ldif`
- Modify: `idm-test-utils/pom.xml`
- Modify: `idm-connector-ldap/pom.xml`
- Modify: `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/config/EmbeddedLdapServerConfig.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `com.idm.test.utils.ldap.EmbeddedLdapServer` with exactly these public members, relied on by Task 2:
  - `static EmbeddedLdapServer start(String baseDn, String bindDn, String bindPassword, int port, String ldifClasspathResource) throws LDAPException, LDIFException, IOException` — `port` of `0` binds an ephemeral port; `ldifClasspathResource` may be `null` for no import.
  - `int getPort()`
  - `String getUrl()` — returns `ldap://localhost:<port>`
  - `InMemoryDirectoryServer getServer()`
  - `void close()` — from `AutoCloseable`, does not throw

- [ ] **Step 1: Record the baseline so the refactor can be proven behaviour-preserving**

```bash
sh ./mvnw -pl idm-connector-ldap -am test 2>&1 | grep -E "Tests run:.*EdirectoryConnectorTest"
```

Expected: `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 2: Add the UnboundID dependency to idm-test-utils**

In `idm-test-utils/pom.xml`, insert this block immediately after the `commons-lang3` dependency and before the `<!-- Testing -->` comment:

```xml
        <!-- UnboundID LDAP SDK - powers the shared embedded test directory -->
        <dependency>
            <groupId>com.unboundid</groupId>
            <artifactId>unboundid-ldapsdk</artifactId>
        </dependency>
```

The version is managed by the parent POM (`7.0.1`); do not add a `<version>` tag.

- [ ] **Step 3: Write the failing test for the new component**

Create `idm-test-utils/src/test/java/com/idm/test/utils/ldap/EmbeddedLdapServerTest.java`:

```java
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
```

Create the fixture it loads, `idm-test-utils/src/test/resources/embedded-ldap-server-test.ldif`:

```ldif
dn: ou=users,dc=test,dc=com
objectClass: top
objectClass: organizationalUnit
ou: users

dn: ou=roles,dc=test,dc=com
objectClass: top
objectClass: organizationalUnit
ou: roles
```

- [ ] **Step 4: Run the test to verify it fails**

```bash
sh ./mvnw -pl idm-test-utils -am test -Dtest=EmbeddedLdapServerTest
```

Expected: compilation failure — `cannot find symbol: class EmbeddedLdapServer`.

- [ ] **Step 5: Implement EmbeddedLdapServer**

Create `idm-test-utils/src/main/java/com/idm/test/utils/ldap/EmbeddedLdapServer.java`:

```java
package com.idm.test.utils.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
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
     * @param baseDn               root suffix, e.g. {@code dc=test,dc=com}
     * @param bindDn               DN accepted for authentication; need not exist as an entry
     * @param bindPassword         password for {@code bindDn}
     * @param port                 TCP port, or {@code 0} to bind an ephemeral port
     * @param ldifClasspathResource classpath LDIF to import after the base entry, or {@code null}
     * @return a started server; the caller owns it and must {@link #close()} it
     */
    public static EmbeddedLdapServer start(
            String baseDn,
            String bindDn,
            String bindPassword,
            int port,
            String ldifClasspathResource) throws LDAPException, LDIFException, IOException {

        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(baseDn);
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
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
sh ./mvnw -pl idm-test-utils -am test -Dtest=EmbeddedLdapServerTest
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 7: Move the LDAP module's directory structure into an LDIF**

Create `idm-connector-ldap/src/test/resources/embedded-ldap-init.ldif` with exactly the entries `EmbeddedLdapServerConfig` builds inline today:

```ldif
dn: ou=people,dc=springframework,dc=org
objectClass: top
objectClass: organizationalUnit
ou: people

dn: ou=groups,dc=springframework,dc=org
objectClass: top
objectClass: organizationalUnit
ou: groups

dn: ou=system,dc=springframework,dc=org
objectClass: top
objectClass: organizationalUnit
ou: system

dn: uid=admin,ou=system,dc=springframework,dc=org
objectClass: top
objectClass: person
objectClass: organizationalPerson
objectClass: inetOrgPerson
uid: admin
cn: Admin
sn: Administrator
userPassword: secret
```

- [ ] **Step 8: Add the test dependency to idm-connector-ldap and drop the unused one**

In `idm-connector-ldap/pom.xml`, replace this block:

```xml
        <!-- Embedded Apache Directory Server for tests -->
        <dependency>
            <groupId>org.apache.directory.server</groupId>
            <artifactId>apacheds-all</artifactId>
            <scope>test</scope>
        </dependency>
```

with:

```xml
        <!-- Shared embedded LDAP server for tests -->
        <dependency>
            <groupId>com.idm.test</groupId>
            <artifactId>idm-test-utils</artifactId>
            <scope>test</scope>
        </dependency>
```

`apacheds-all` is unused — this module runs on UnboundID — and pulls a large dependency tree for nothing.

- [ ] **Step 9: Delegate EmbeddedLdapServerConfig to the shared component**

Replace the entire contents of `idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/config/EmbeddedLdapServerConfig.java`:

```java
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
```

- [ ] **Step 10: Verify the refactor preserved behaviour**

```bash
sh ./mvnw -pl idm-connector-ldap -am test 2>&1 | grep -E "Tests run:.*EdirectoryConnectorTest"
```

Expected: `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` — identical to Step 1. If the count or result differs, the extraction changed behaviour; fix before continuing.

- [ ] **Step 11: Commit**

```bash
git add idm-test-utils/pom.xml \
        idm-test-utils/src/main/java/com/idm/test/utils/ldap/EmbeddedLdapServer.java \
        idm-test-utils/src/test/java/com/idm/test/utils/ldap/EmbeddedLdapServerTest.java \
        idm-test-utils/src/test/resources/embedded-ldap-server-test.ldif \
        idm-connector-ldap/pom.xml \
        idm-connector-ldap/src/test/resources/embedded-ldap-init.ldif \
        idm-connector-ldap/src/test/java/com/idm/test/connector/ldap/config/EmbeddedLdapServerConfig.java
git commit -m "refactor: extract shared EmbeddedLdapServer into idm-test-utils

Both idm-connector-ldap and idm-test-scenarios need an in-memory LDAP
directory. Extracts the working UnboundID setup so there is one
implementation. idm-connector-ldap keeps its fixed port 10389 and
dc=springframework,dc=org contract; its 14 tests are the regression check.
Drops the unused apacheds-all test dependency.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Replace the container with the embedded server in scenarios

This is the task that removes the build blocker. `BaseIntegrationTest` stops using Docker entirely.

**Files:**
- Modify: `idm-test-scenarios/pom.xml:73-91`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/base/BaseIntegrationTest.java`

**Interfaces:**
- Consumes: `EmbeddedLdapServer.start(String, String, String, int, String)`, `getUrl()`, `close()` from Task 1.
- Produces: `BaseIntegrationTest` protected fields unchanged in name and type for the five workflow subclasses — `edirectoryConnector`, `databaseConnector`, `roleServiceConnector`, `testExecutor`, `executorProperties`, `testDataGenerator`, plus `trackLdapDn(String)`, `trackDatabaseRecord(String, String, String)`, `trackRoleAssignment(String, String)`, `assertDataPresent(OperationResult<T>, String)` and `assertDataPresent(OperationResult<T>, String, String)`.

- [ ] **Step 1: Confirm the failure you are fixing**

```bash
sh ./mvnw -pl idm-test-scenarios test -Dtest=UserProvisioningWorkflowTest 2>&1 | grep -E "client version|Tests run:"
```

Expected: an error mentioning `client version 1.32 is too old`.

- [ ] **Step 2: Remove the Testcontainers dependencies**

In `idm-test-scenarios/pom.xml`, delete these three dependency blocks in full, including the `<!-- Testcontainers -->` comment:

```xml
        <!-- Testcontainers -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <scope>test</scope>
        </dependency>

        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>

        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: Replace the imports in BaseIntegrationTest**

Delete these imports:

```java
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
```

Add:

```java
import com.idm.test.utils.ldap.EmbeddedLdapServer;
```

`java.time.Duration` is no longer used by this class after Step 4 — remove that import too if the compiler flags it as unused in your IDE; it is harmless either way.

- [ ] **Step 4: Replace the class annotations and static infrastructure**

Replace everything from the `@SpringBootTest` annotation block down to the closing brace of the `configureProperties` method (the container declaration, `WIREMOCK_SERVER`, `ldapInitialized`, `LDAP_INIT_LOCK`, the `static {}` block and `@DynamicPropertySource`) with:

```java
@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class})
public abstract class BaseIntegrationTest {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    // ===================================================================
    // Test infrastructure - started once for the whole JVM
    // ===================================================================

    private static final String LDAP_BASE_DN = "dc=test,dc=com";
    private static final String LDAP_BIND_DN = "cn=admin,dc=test,dc=com";
    private static final String LDAP_BIND_PASSWORD = "test";

    /**
     * In-memory LDAP directory. Binds an ephemeral port so parallel module builds
     * cannot collide, and imports ldap-init.ldif to create ou=users, ou=roles, ou=groups.
     */
    private static final EmbeddedLdapServer LDAP_SERVER = startLdapServer();

    /**
     * WireMock server for SOAP mocking, on a dynamically allocated port.
     */
    private static final WireMockServer WIREMOCK_SERVER = new WireMockServer(
            WireMockConfiguration.options()
                    .dynamicPort()
                    .usingFilesUnderClasspath("wiremock")
                    .globalTemplating(true));

    static {
        WIREMOCK_SERVER.start();
        LoggerFactory.getLogger(BaseIntegrationTest.class)
                .info("WireMock server started on port {}", WIREMOCK_SERVER.port());
    }

    private static EmbeddedLdapServer startLdapServer() {
        try {
            return EmbeddedLdapServer.start(
                    LDAP_BASE_DN, LDAP_BIND_DN, LDAP_BIND_PASSWORD, 0, "ldap-init.ldif");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start embedded LDAP server", e);
        }
    }

    /**
     * Point the connectors at the embedded infrastructure.
     * Everything else comes from src/test/resources/application.yml, so switching to a
     * real directory in phase (b) is a profile change rather than a code change.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("idm.test.ldap.url", LDAP_SERVER::getUrl);
        registry.add("idm.test.ldap.base-dn", () -> LDAP_BASE_DN);
        registry.add("idm.test.ldap.user-dn", () -> LDAP_BIND_DN);
        registry.add("idm.test.ldap.password", () -> LDAP_BIND_PASSWORD);
        registry.add("idm.test.soap.url",
                () -> "http://localhost:" + WIREMOCK_SERVER.port() + "/IDMProv/role/service");
    }
```

- [ ] **Step 5: Delete the container-only lifecycle helpers**

Delete the methods `verifyLdapConnectivity()` and `initializeLdapStructure()` in full. Both drive `LDAP_CONTAINER.execInContainer(...)`, which no longer exists; the LDIF is now imported when the server starts.

Then replace the `setUp()` method with:

```java
    @BeforeEach
    void setUp() {
        log.info("=".repeat(80));
        log.info("Starting test: {}", getTestName());
        log.info("=".repeat(80));

        // Subclasses can override setupTest() for custom setup
        setupTest();
    }
```

- [ ] **Step 6: Compile to verify nothing else referenced the container**

```bash
sh ./mvnw -pl idm-test-scenarios -am test-compile
```

Expected: `BUILD SUCCESS`. Any `cannot find symbol: LDAP_CONTAINER` means a reference was missed — delete it.

- [ ] **Step 7: Verify the scenario context now starts without Docker**

```bash
sh ./mvnw -pl idm-test-scenarios test -Dtest=UserProvisioningWorkflowTest 2>&1 | grep -E "Embedded LDAP server started|client version|Tests run:"
```

Expected: a line containing `Embedded LDAP server started on ldap://localhost:`, and **no** `client version` error. Individual test assertions may still fail — that is Task 5's job. The gate here is that the context starts and tests execute.

- [ ] **Step 8: Verify Testcontainers is gone from the module**

```bash
sh ./mvnw -pl idm-test-scenarios dependency:tree | grep -c testcontainers
```

Expected: `0`

- [ ] **Step 9: Commit**

```bash
git add idm-test-scenarios/pom.xml \
        idm-test-scenarios/src/test/java/com/idm/test/scenarios/base/BaseIntegrationTest.java
git commit -m "fix: replace osixia/openldap container with embedded LDAP server

Testcontainers 1.20.4 negotiates Docker API 1.32 against an engine that
requires 1.40+, so every BaseIntegrationTest subclass died before
@BeforeEach. The osixia image has been unmaintained since 2021. Switches
to the shared UnboundID server on an ephemeral port and moves WireMock
off its hardcoded port 8090. The scenarios module no longer needs Docker.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: Make missing connectors fail loudly

Without this, a missing bean silently skips its test and the build still reports success. This is the mechanism that enforces the zero-skip goal.

**Files:**
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/base/BaseIntegrationTest.java`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/provisioning/UserProvisioningWorkflowTest.java` (7 call sites)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/provisioning/RoleAssignmentWorkflowTest.java` (11 call sites)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/modification/UserModificationWorkflowTest.java` (9 call sites)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/deprovisioning/UserDeprovisioningWorkflowTest.java` (12 call sites)
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/integration/MultiSystemIntegrationTest.java` (10 call sites)

**Interfaces:**
- Consumes: the field names produced by Task 2.
- Produces: `BaseIntegrationTest` no longer declares `requireConnector(Object, String)`, `isConnectorAvailable(Object)`, or `logConnectorAvailability()`. Any later task referencing them is wrong.

- [ ] **Step 1: Make all six injections required**

In `BaseIntegrationTest`, replace the connector block with:

```java
    // ===================================================================
    // Connectors - injected by Spring, required
    // ===================================================================
    // These are deliberately NOT optional. A missing bean must fail context
    // startup loudly rather than silently skipping the tests that need it.

    @Autowired
    protected EdirectoryConnector edirectoryConnector;

    @Autowired
    protected MultiDatabaseConnector databaseConnector;

    @Autowired
    protected RoleServiceConnector roleServiceConnector;

    @Autowired
    protected TestExecutor testExecutor;

    @Autowired
    protected TestExecutorProperties executorProperties;

    @Autowired
    protected TestDataGenerator testDataGenerator;
```

- [ ] **Step 2: Delete the three now-meaningless helpers**

Delete `requireConnector(Object, String)`, `isConnectorAvailable(Object)`, and `logConnectorAvailability()` from `BaseIntegrationTest` in full. `logConnectorAvailability()` existed only to explain skips; every connector is now guaranteed present.

Also remove the now-unused import if present:

```java
import org.junit.jupiter.api.Assumptions;
```

(The current code fully qualifies `org.junit.jupiter.api.Assumptions` inline, so there may be no import to remove.)

- [ ] **Step 3: Remove all 49 call sites**

Each call site is a single self-contained statement line, for example `requireConnector(edirectoryConnector, "EdirectoryConnector");`. Delete those lines:

```bash
for f in idm-test-scenarios/src/test/java/com/idm/test/scenarios/provisioning/UserProvisioningWorkflowTest.java \
         idm-test-scenarios/src/test/java/com/idm/test/scenarios/provisioning/RoleAssignmentWorkflowTest.java \
         idm-test-scenarios/src/test/java/com/idm/test/scenarios/modification/UserModificationWorkflowTest.java \
         idm-test-scenarios/src/test/java/com/idm/test/scenarios/deprovisioning/UserDeprovisioningWorkflowTest.java \
         idm-test-scenarios/src/test/java/com/idm/test/scenarios/integration/MultiSystemIntegrationTest.java; do
  sed -i '' '/requireConnector(/d' "$f"
done
grep -rc "requireConnector" idm-test-scenarios/src/test/java/com/idm/test/scenarios/ | grep -v ":0" || echo "all call sites removed"
```

Expected: `all call sites removed`

Some call sites are followed by a now-orphaned comment such as `// Require LDAP connector for this test`. Leave them or delete them; they are cosmetic and do not affect the gate.

- [ ] **Step 4: Compile**

```bash
sh ./mvnw -pl idm-test-scenarios -am test-compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Verify zero skips in the scenarios module**

```bash
sh ./mvnw -pl idm-test-scenarios test 2>&1 | grep -E "^\[(INFO|ERROR)\] Tests run:.*Skipped"
```

Expected: every line ends with `Skipped: 0`. Failures and errors are still acceptable at this point — Task 5 resolves those. A non-zero `Skipped` count means a call site survived.

- [ ] **Step 6: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/
git commit -m "test: require connectors instead of skipping when absent

@Autowired(required = false) plus Assumptions.assumeTrue(false) meant a
missing bean skipped its test while the build still reported success.
Injection is now required, so a missing bean fails context startup.
Removes requireConnector and its 49 call sites.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: Delete the dead test artifacts

**Files:**
- Delete: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/UserProvisioningIntegrationTest.java`
- Delete: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/LdapDiagnosticTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. No other class references either file.

- [ ] **Step 1: Confirm neither file is referenced**

```bash
grep -rn "UserProvisioningIntegrationTest\|LdapDiagnosticTest" --include="*.java" idm-test-scenarios/src | grep -v "scenarios/UserProvisioningIntegrationTest.java\|scenarios/LdapDiagnosticTest.java" || echo "no external references"
```

Expected: `no external references`

- [ ] **Step 2: Delete both files**

```bash
git rm idm-test-scenarios/src/test/java/com/idm/test/scenarios/UserProvisioningIntegrationTest.java \
       idm-test-scenarios/src/test/java/com/idm/test/scenarios/LdapDiagnosticTest.java
```

`UserProvisioningIntegrationTest` has its workflow body entirely commented out, duplicates the real `provisioning/` tests, and is the source of the one genuine pre-existing failure. `LdapDiagnosticTest` was a debugging aid for the container problem removed in Task 2.

- [ ] **Step 3: Compile**

```bash
sh ./mvnw -pl idm-test-scenarios -am test-compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git commit -m "test: remove dead scenario test artifacts

UserProvisioningIntegrationTest's workflow body is entirely commented
out and duplicates the provisioning/ tests; its only live assertion
failed because the class never had a test profile. LdapDiagnosticTest
diagnosed the container problem that no longer exists.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: Run the full suite and triage

**This task is a procedure, not a fixed set of edits.** The five workflow classes have never executed past `@BeforeAll`, so the failures cannot be enumerated in advance — that is stated in section 9 of the spec. The steps below define exactly how to run, classify, and decide, and every step is concrete.

**Files:**
- Modify: whichever connector or test files triage identifies. Unknown until Step 1 runs.

**Interfaces:**
- Consumes: everything from Tasks 1 through 4.
- Produces: a green suite.

- [ ] **Step 1: Run the full suite and capture output**

```bash
sh ./mvnw clean test 2>&1 | tee /tmp/idm-suite-run1.log | tail -40
```

- [ ] **Step 2: Extract the scoreboard**

```bash
grep -E "^\[(INFO|ERROR)\] Tests run:" /tmp/idm-suite-run1.log
grep -E "^\[ERROR\]   [A-Za-z]" /tmp/idm-suite-run1.log
```

Record the totals. If failures and errors are both zero and every `Skipped` is `0`, skip to Step 6.

- [ ] **Step 3: Classify every failure before fixing anything**

For each failing test, read the assertion and the connector code it exercises, then write it into one of two buckets:

- **Connector bug** — the production code in `idm-connector-*` behaves incorrectly and the test's expectation is right. Fix the connector.
- **Wrong test expectation** — the test encodes an expectation that was never correct, or that assumed the container environment (for example an `osixia`-specific DN, or an attribute the OpenLDAP image added implicitly). Fix the test.

A third pattern is likely given the October reports: `NoSuchElementException` from `result.getData().get()` on a failed `OperationResult`. That is usually a *symptom*, not the bug — the real question is why the operation failed. Do not paper over it by adding an `isPresent()` guard until you know what made the call fail. `assertDataPresent(...)` in `BaseIntegrationTest` is the correct tool once the underlying cause is understood.

- [ ] **Step 4: Report the classification before weakening any assertion**

Post the classification to the user in this shape:

```
Run 1: X tests, Y failures, Z errors, 0 skipped

CONNECTOR BUGS (fixing):
  - <TestClass>.<method> — <what the connector does wrong>

WRONG TEST EXPECTATIONS (need your call):
  - <TestClass>.<method> — <what the test assumes> vs <actual behaviour>
```

Per the spec's triage principle, **stop and get a decision on the second bucket before editing those assertions.** Relaxing a test to match whatever the code does is how a suite becomes decorative, and the entire purpose of this phase is a baseline that can be trusted in phase (b). The first bucket needs no permission — fix those directly.

- [ ] **Step 5: Fix, re-run, repeat**

After each fix:

```bash
sh ./mvnw -pl idm-test-scenarios test -Dtest=<TestClassName>
```

Expected: the target class reports `Failures: 0, Errors: 0, Skipped: 0`.

Commit each fix separately, with a message naming the root cause rather than the symptom:

```bash
git add <files>
git commit -m "fix: <root cause in one line>

<why the test was failing and what actually changed>

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Full verification against the spec's criteria**

```bash
sh ./mvnw clean test 2>&1 | tee /tmp/idm-suite-final.log | tail -20
grep -E "^\[(INFO|ERROR)\] Tests run:" /tmp/idm-suite-final.log
grep -c "BUILD SUCCESS" /tmp/idm-suite-final.log
```

Expected: `BUILD SUCCESS`, and every `Tests run:` line showing `Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 7: Prove the Docker dependency is gone**

Stop Docker Desktop, then:

```bash
sh ./mvnw clean test 2>&1 | tail -20
```

Expected: `BUILD SUCCESS` with Docker not running. This is the criterion from spec section 8 that cannot be faked. Restart Docker afterwards if you use it for other work.

---

## Task 6: Restructure the documentation

Mechanical, and deliberately separate from the test work so it cannot destabilise the build.

**Files:**
- Create: `docs/history/README.md`
- Move: 3 files into `docs/reference/`, 8 files into `docs/history/`
- Modify: `README.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: nothing.
- Produces: the layout in spec section 6.

- [ ] **Step 1: Create the folders and move the reference documents**

```bash
mkdir -p docs/reference docs/history
git mv SPECIFICATION.md docs/reference/specification.md
git mv CONFIG.md docs/reference/configuration.md
git mv RUNNING_TESTS.md docs/reference/running-tests.md
```

- [ ] **Step 2: Move the historical reports**

```bash
git mv PHASE9_SUMMARY.md docs/history/phase-09-summary.md
git mv PHASE9_COMPLETE.md docs/history/phase-09-complete.md
git mv TESTS_FIXED.md docs/history/phase-09-tests-fixed.md
git mv FIX_TESTS.md docs/history/phase-09-fix-tests.md
git mv PHASE10_COMPLETE.md docs/history/phase-10-complete.md
git mv PHASE10_TEST_RESULTS.md docs/history/phase-10-test-results.md
git mv FIXES_APPLIED.md docs/history/phase-10-fixes-applied.md
git mv NULL_SAFETY_FIXES_COMPLETE.md docs/history/phase-10-null-safety.md
```

Do not edit the contents of any of these files. Editing a dated report to be "correct" destroys the record without adding anything.

- [ ] **Step 3: Write the staleness banner**

Create `docs/history/README.md`:

```markdown
# Historical Reports — Frozen

These documents are **point-in-time records** of past development sessions.
They are not maintained, several are inaccurate, and they contradict each other.

**Do not treat anything here as evidence of current state.** For that, see:

- [Specification](../reference/specification.md) — architecture and design decisions
- [Configuration](../reference/configuration.md) — how to configure the framework
- [Running tests](../reference/running-tests.md) — how to run the suite
- [Specs](../specs/) — design documents for in-flight work
- The build itself — `sh ./mvnw clean test`

Known inaccuracies, recorded so nobody re-derives them:

- `phase-10-complete.md` and `phase-09-complete.md` declare phases complete
  that were not. The root `README.md` repeated those claims while the build
  was failing.
- `phase-10-test-results.md` reports "13/31 passing" and diagnoses a
  `DirContext` pool failure. Those numbers predate a dependency change that
  made the LDAP container unreachable, so the five workflow test classes had
  in fact never run to completion when it was written.
```

- [ ] **Step 4: Update every inbound link**

In `CLAUDE.md`, replace both references to the old path:

```bash
sed -i '' 's|`SPECIFICATION.md`|`docs/reference/specification.md`|g; s|Full specification: `SPECIFICATION.md`|Full specification: `docs/reference/specification.md`|g' CLAUDE.md
grep -n "SPECIFICATION.md" CLAUDE.md || echo "CLAUDE.md clean"
```

Expected: `CLAUDE.md clean`

In `README.md`, update the documentation links section so each entry points at its new path — `PHASE9_COMPLETE.md` becomes `docs/history/phase-09-complete.md`, `PHASE10_COMPLETE.md` becomes `docs/history/phase-10-complete.md`, `TESTS_FIXED.md` becomes `docs/history/phase-09-tests-fixed.md`, and any `SPECIFICATION.md`, `CONFIG.md` or `RUNNING_TESTS.md` link points into `docs/reference/`. Add a line pointing at `docs/history/README.md` so readers know that folder is frozen.

- [ ] **Step 5: Verify no markdown is stranded and no link is broken**

```bash
ls *.md
```

Expected: exactly `CLAUDE.md` and `README.md`.

```bash
grep -oE '\]\([^)#][^)]*\.md\)' README.md CLAUDE.md | sed 's/.*](//; s/)$//' | sort -u | while read -r link; do
  [ -f "$link" ] || echo "BROKEN: $link"
done; echo "link check done"
```

Expected: `link check done` with no `BROKEN:` lines.

- [ ] **Step 6: Commit**

```bash
git add -A docs README.md CLAUDE.md
git commit -m "docs: classify markdown into reference, specs and history

Eleven markdown files sat flat at the repository root with no signal about
which were maintained, which is why README.md asserted 'Phase 10 Complete'
while PHASE10_COMPLETE.md opened with 'INCOMPLETE'. Reference docs move to
docs/reference/, dated session reports to docs/history/ behind a staleness
banner. Historical content is moved, not rewritten. All inbound links updated.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: Correct the README status

Last, because it can only be done truthfully once the build is actually green.

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: the green build from Task 5.
- Produces: nothing.

- [ ] **Step 1: Re-confirm the build is green**

```bash
sh ./mvnw clean test 2>&1 | grep -E "BUILD SUCCESS|BUILD FAILURE"
```

Expected: `BUILD SUCCESS`. If not, this task is premature — return to Task 5.

- [ ] **Step 2: Capture the real numbers**

```bash
sh ./mvnw clean test 2>&1 | grep -E "^\[INFO\] Tests run:.*Skipped: 0" | tail -10
```

Record the per-module totals; use them verbatim in Step 3 rather than estimating.

- [ ] **Step 3: Replace the Build Status block**

In `README.md`, replace the existing `### Build Status` list with the following, substituting the real totals from Step 2:

```markdown
### Build Status

Last verified: 2026-09-11 via `sh ./mvnw clean test`

- All 9 modules compile and test successfully
- Total: <N> tests, 0 failures, 0 errors, 0 skipped
- No Docker required — the suite runs on an embedded UnboundID LDAP server
- WireMock provides SOAP stubs on a dynamically allocated port
- Docker Compose and the CI workflow remain available for phase (b),
  when the framework is pointed at a real IdM environment
```

- [ ] **Step 4: Correct the phase table**

In the `### ✅ Phase 1-10: COMPLETED` table, change the Phase 10 row's status from `✅ Complete` to:

```markdown
| Phase 10 | Docker & CI/CD automation | ⚠️ Superseded — see [green baseline spec](docs/specs/2026-09-11-suite-green-baseline-design.md) |
```

The Testcontainers path that phase built has been removed; claiming it complete would restate the contradiction this work set out to fix.

- [ ] **Step 5: Verify links still resolve**

```bash
grep -oE '\]\([^)#][^)]*\.md\)' README.md | sed 's/.*](//; s/)$//' | sort -u | while read -r link; do
  [ -f "$link" ] || echo "BROKEN: $link"
done; echo "link check done"
```

Expected: `link check done` with no `BROKEN:` lines.

- [ ] **Step 6: Commit**

```bash
git add README.md
git commit -m "docs: correct build status to match reality

The status block claimed Testcontainers integration was working while
the build failed. Records the verified numbers and marks Phase 10
superseded, since the container path it added has been removed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Done When

All criteria from spec section 8, each independently checkable:

- [ ] `sh ./mvnw clean test` returns `BUILD SUCCESS`
- [ ] Every `Tests run:` line reports `Failures: 0, Errors: 0, Skipped: 0`
- [ ] `sh ./mvnw -pl idm-test-scenarios dependency:tree | grep -c testcontainers` returns `0`
- [ ] The full suite passes with the Docker daemon stopped
- [ ] `ls *.md` returns exactly `CLAUDE.md` and `README.md`
- [ ] No broken relative markdown links from `README.md` or `CLAUDE.md`
- [ ] Any assertion weakened during Task 5 triage was explicitly approved by the user
