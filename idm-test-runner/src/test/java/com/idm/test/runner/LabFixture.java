package com.idm.test.runner;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.idm.test.utils.ldap.EmbeddedLdapServer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lab the run tests run against: an embedded LDAP directory and WireMock (one of each per test
 * class), a file H2 database, and a workspace whose profile {@code lab} reaches them with passwords
 * read only from {@code secrets.env}. Shared by {@code RunCommandTest}, {@code RunServiceTest} and the
 * workbench's runs API test. Every credential here is fake.
 */
public final class LabFixture implements AutoCloseable {

    public static final String BASE_DN = "dc=test,dc=com";
    public static final String BIND_DN = "cn=admin,dc=test,dc=com";
    public static final String LDAP_PASSWORD = "ldap-pw-7Hq2xV";
    public static final String REST_PASSWORD = "rest-pw-9Zk4rT";
    public static final String DB_PASSWORD = "db-pw-3Lm8Qe";

    /** Creates a user, then waits for a surname it never gets: a finding; its cleanup deletes the user. */
    public static final String WRONG_SN = """
            name: Wrong surname
            fixtures:
              user:
                generate: random-user
            steps:
              - ldap.create:
                  dn: "cn=${user.userId},ou=users"
                  as: createdDn
                  attributes:
                    cn: "${user.userId}"
                    sn: "${user.lastName}"
              - ldap.verify:
                  dn: "cn=${user.userId},ou=users"
                  wait: true
                  expect:
                    sn: "Not-${user.lastName}"
            cleanup:
              - ldap.delete:
                  dn: "${createdDn}"
            """;

    /** A scenario that does not validate. */
    public static final String BAD_STEP = """
            name: Bad step
            steps:
              - no.such.step:
                  dn: x
            """;

    private final EmbeddedLdapServer ldap;
    private final WireMockServer wireMock;

    private LabFixture(EmbeddedLdapServer ldap, WireMockServer wireMock) {
        this.ldap = ldap;
        this.wireMock = wireMock;
    }

    /** Starts the directory (with the corpus's {@code ldap-init.ldif}) and WireMock, stubbing nothing. */
    public static LabFixture start() throws Exception {
        EmbeddedLdapServer ldap = EmbeddedLdapServer.start(BASE_DN, BIND_DN, LDAP_PASSWORD, 0, "ldap-init.ldif");
        WireMockServer wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        return new LabFixture(ldap, wireMock);
    }

    public EmbeddedLdapServer ldap() {
        return ldap;
    }

    public WireMockServer wireMock() {
        return wireMock;
    }

    /**
     * A workspace in {@code folder} whose profile {@code lab} reaches the directory, {@code test-db} at
     * {@code jdbcUrl} (with {@code driverClassName} unless null) and the REST target {@code idm} on
     * WireMock, the passwords in {@code secrets.env}; with a fast executor (2 s waits, 1 s polls).
     */
    public Path workspace(Path folder, String jdbcUrl, String driverClassName) throws Exception {
        Path ws = Files.createDirectories(folder);
        Files.createDirectories(ws.resolve("scenarios"));
        Files.createDirectories(ws.resolve("config"));
        Files.writeString(ws.resolve("config/application-lab.yml"), """
                idm:
                  test:
                    ldap:
                      url: %s
                      base-dn: %s
                      user-dn: %s
                      password: ${IDM_TEST_LDAP_PASSWORD}
                    databases:
                      test-db:
                        jdbc-url: %s
                        username: sa
                        password: ${IDM_TEST_DB_PASSWORD}
                %s    rest:
                      idm:
                        base-url: %s
                        username: runner
                        password: ${IDM_TEST_REST_PASSWORD}
                    executor:
                      default-timeout-seconds: 2
                      default-poll-interval-seconds: 1
                """.formatted(ldap.getUrl(), BASE_DN, BIND_DN, jdbcUrl,
                driverClassName == null ? "" : "        driver-class-name: " + driverClassName + "\n",
                wireMock.baseUrl()));
        Files.writeString(ws.resolve("secrets.env"), "# lab secrets\nIDM_TEST_LDAP_PASSWORD=" + LDAP_PASSWORD
                + "\nIDM_TEST_REST_PASSWORD=" + REST_PASSWORD + "\nIDM_TEST_DB_PASSWORD=" + DB_PASSWORD + "\n");
        return ws;
    }

    /**
     * Adds to the profile {@code lab} the REST target {@code tenant} on WireMock, whose base URL is
     * {@code /tenant-${variable}}.
     */
    public void tenantTarget(Path ws, String variable) throws Exception {
        Path settings = ws.resolve("config/application-lab.yml");
        Files.writeString(settings, Files.readString(settings).replace("    executor:",
                "      tenant:\n        base-url: " + wireMock.baseUrl() + "/tenant-${" + variable + "}\n"
                        + "    executor:"));
    }

    /**
     * A file-based H2 database in {@code dir} with the corpus's users table: a database the in-memory
     * guard accepts, created with the password the run reads from secrets.env. Returns its URL.
     */
    public static String database(Path dir) throws Exception {
        Files.createDirectories(dir);
        String url = "jdbc:h2:file:" + dir.resolve("lab").toAbsolutePath().toString().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(url, "sa", DB_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        user_id VARCHAR(100) NOT NULL UNIQUE,
                        first_name VARCHAR(100) NOT NULL,
                        last_name VARCHAR(100) NOT NULL,
                        email VARCHAR(255) NOT NULL,
                        department VARCHAR(100),
                        job_title VARCHAR(100),
                        active BOOLEAN DEFAULT TRUE)
                    """);
        }
        return url;
    }

    /** Writes {@code content} as {@code scenarios/<name>}, creating its folders. */
    public static void scenario(Path ws, String name, String content) throws Exception {
        Path file = ws.resolve("scenarios").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** Copies the corpus scenario {@code name} (a test resource under {@code /scenarios/}) into the workspace. */
    public static void corpusScenario(Path ws, String name) throws Exception {
        try (InputStream in = LabFixture.class.getResourceAsStream("/scenarios/" + name)) {
            assertThat(in).isNotNull();
            Path file = ws.resolve("scenarios").resolve(name);
            Files.createDirectories(file.getParent());
            Files.copy(in, file);
        }
    }

    @Override
    public void close() {
        wireMock.stop();
        ldap.close();
    }
}
