package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the embedded scenario corpus through {@link RealEnvironmentScenarios} against real
 * OpenLDAP, PostgreSQL and WireMock containers.
 * <p>
 * The containers' addresses are published as {@code idm.test.*} system properties in
 * {@code @BeforeAll}, before Spring loads the context. That is exactly how an operator points
 * the parent runner at a real environment with {@code -D}, so everything after it goes
 * through the parent's guards and configuration path unchanged. That path is what this
 * runner proves. It proves nothing about OpenText IdM: the SOAP services are WireMock stubs.
 * <p>
 * Optional. Named without a {@code Test} suffix, so the Docker-free default build never runs
 * it. Needs a running Docker daemon:
 * <pre>
 * ./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * {@code -Dscenario=<file or glob>} narrows the run, as with {@link YamlScenarioTest}.
 */
@DisplayName("Container scenarios")
public class ContainerScenarios extends RealEnvironmentScenarios {

    static final String CORPUS_DIR = "scenarios";
    static final String DOCKER_UNAVAILABLE =
            "Docker is not available. ContainerScenarios needs a running Docker daemon; the default suite does not.";

    private static final String LDAP_IMAGE = "osixia/openldap:1.5.0";
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String WIREMOCK_IMAGE = "wiremock/wiremock:3.10.0";

    private static final String LDAP_BASE_DN = "dc=test,dc=com";
    private static final String LDAP_ADMIN_DN = "cn=admin,dc=test,dc=com";
    private static final String LDAP_ADMIN_PASSWORD = "test";
    private static final int LDAP_PORT = 389;
    private static final int WIREMOCK_PORT = 8080;

    // Every configured database must accept a connection for the context to start, so all
    // four point at the one PostgreSQL. oracle-hr here is a stand-in and says nothing about Oracle.
    private static final List<String> DATABASES = List.of("test-db", "sap", "oracle-hr", "workday");

    private static final List<GenericContainer<?>> STARTED = new ArrayList<>();
    private static final Map<String, String> PREVIOUS = new LinkedHashMap<>();

    @BeforeAll
    static void startContainers() throws Exception {
        requireDocker(DockerClientFactory.instance().isDockerAvailable());

        GenericContainer<?> ldap = startLdap();
        PostgreSQLContainer<?> postgres = start(new PostgreSQLContainer<>(POSTGRES_IMAGE), "PostgreSQL");
        GenericContainer<?> wiremock = start(new GenericContainer<>(WIREMOCK_IMAGE)
                .withExposedPorts(WIREMOCK_PORT)
                .withCommand("--global-response-templating")
                .withCopyFileToContainer(MountableFile.forClasspathResource("wiremock/mappings"),
                        "/home/wiremock/mappings")
                .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200)), "WireMock");

        publish(ldap, postgres, wiremock);
    }

    /**
     * Restores every system property to what it was before, so nothing leaks into another
     * class in the same JVM. Then stops the containers in reverse order. JUnit runs this even
     * when {@code @BeforeAll} failed part-way, which is what cleans up a partial start.
     */
    @AfterAll
    static void stopContainers() {
        PREVIOUS.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
        PREVIOUS.clear();

        for (int i = STARTED.size() - 1; i >= 0; i--) {
            STARTED.get(i).stop();
        }
        STARTED.clear();
    }

    @Override
    String scenarioDirectory() {
        return CORPUS_DIR;
    }

    static void requireDocker(boolean available) {
        if (!available) {
            throw new IllegalStateException(DOCKER_UNAVAILABLE);
        }
    }

    private static GenericContainer<?> startLdap() throws IOException, InterruptedException {
        GenericContainer<?> ldap = start(new GenericContainer<>(LDAP_IMAGE)
                .withEnv("LDAP_ORGANISATION", "Test Organization")
                .withEnv("LDAP_DOMAIN", "test.com")
                .withEnv("LDAP_ADMIN_PASSWORD", LDAP_ADMIN_PASSWORD)
                .withEnv("LDAP_TLS", "false")
                .withExposedPorts(LDAP_PORT)
                .waitingFor(Wait.forLogMessage(".*slapd starting.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2))), "OpenLDAP");

        String inetOrgPersonDn = exec(ldap, "OpenLDAP schema lookup",
                "ldapsearch", "-Y", "EXTERNAL", "-H", "ldapi:///", "-LLL", "-b", "cn=schema,cn=config",
                "(cn=*inetorgperson)", "dn")
                .lines()
                .filter(line -> line.startsWith("dn: "))
                .map(line -> line.substring("dn: ".length()).strip())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "OpenLDAP schema lookup found no inetorgperson schema entry under cn=schema,cn=config"));

        String extension = classpathText("container-ldap/idm-test-schema.ldif")
                .replace("@INETORGPERSON_DN@", inetOrgPersonDn);
        ldap.copyFileToContainer(Transferable.of(extension), "/tmp/idm-test-schema.ldif");
        exec(ldap, "OpenLDAP schema extension",
                "ldapmodify", "-Y", "EXTERNAL", "-H", "ldapi:///", "-f", "/tmp/idm-test-schema.ldif");

        ldap.copyFileToContainer(MountableFile.forClasspathResource("ldap-init.ldif"), "/tmp/ldap-init.ldif");
        exec(ldap, "OpenLDAP initial entries",
                "ldapadd", "-x", "-H", "ldap://localhost", "-D", LDAP_ADMIN_DN, "-w", LDAP_ADMIN_PASSWORD,
                "-f", "/tmp/ldap-init.ldif");

        return ldap;
    }

    private static void publish(GenericContainer<?> ldap, PostgreSQLContainer<?> postgres, GenericContainer<?> wiremock) {
        String wiremockBase = "http://" + wiremock.getHost() + ":" + wiremock.getMappedPort(WIREMOCK_PORT);

        set("idm.test.ldap.url", "ldap://" + ldap.getHost() + ":" + ldap.getMappedPort(LDAP_PORT));
        set("idm.test.ldap.base-dn", LDAP_BASE_DN);
        set("idm.test.ldap.user-dn", LDAP_ADMIN_DN);
        set("idm.test.ldap.password", LDAP_ADMIN_PASSWORD);
        set("idm.test.soap.url", wiremockBase + "/IDMProv/role/service");
        set("idm.test.soap.resource-url", wiremockBase + "/IDMProv/resource/service");

        for (String database : DATABASES) {
            String prefix = "idm.test.databases." + database + ".";
            set(prefix + "jdbc-url", postgres.getJdbcUrl());
            set(prefix + "username", postgres.getUsername());
            set(prefix + "password", postgres.getPassword());
            set(prefix + "driver-class-name", "org.postgresql.Driver");
        }

        // The default profile waits up to 5 minutes, polling every 5 seconds; the corpus was
        // written against the test profile's 60 seconds and 1 second.
        set("idm.test.executor.default-timeout-seconds", "60");
        set("idm.test.executor.default-poll-interval-seconds", "1");
    }

    private static void set(String key, String value) {
        if (!PREVIOUS.containsKey(key)) {
            PREVIOUS.put(key, System.getProperty(key));
        }
        System.setProperty(key, value);
    }

    private static <C extends GenericContainer<?>> C start(C container, String name) {
        STARTED.add(container);
        try {
            container.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException(name + " container failed to start: " + e.getMessage(), e);
        }
        return container;
    }

    private static String exec(GenericContainer<?> container, String step, String... command)
            throws IOException, InterruptedException {
        ExecResult result = container.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(step + " failed (exit " + result.getExitCode() + "): "
                    + result.getStderr().strip());
        }
        return result.getStdout();
    }

    private static String classpathText(String resource) throws IOException {
        try (InputStream in = ContainerScenarios.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
