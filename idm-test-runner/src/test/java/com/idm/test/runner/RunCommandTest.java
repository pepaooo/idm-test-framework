package com.idm.test.runner;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code idmtest run} end to end, against an embedded LDAP directory and WireMock: one of each for
 * the whole class. The passwords reach the settings only through {@code secrets.env}.
 */
@DisplayName("idmtest run")
class RunCommandTest {

    private static final String BASE_DN = "dc=test,dc=com";
    private static final String BIND_DN = "cn=admin,dc=test,dc=com";
    private static final String LDAP_PASSWORD = "ldap-pw-7Hq2xV";
    private static final String REST_PASSWORD = "rest-pw-9Zk4rT";
    private static final String DB_PASSWORD = "db-pw-3Lm8Qe";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T10:15:30Z"), ZoneOffset.UTC);
    private static final String RUN_ID = "20261016-101530-lab";

    private static final String WRONG_SN = """
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

    private static final String BAD_STEP = """
            name: Bad step
            steps:
              - no.such.step:
                  dn: x
            """;

    private static final String REST_ACCOUNT = """
            name: Account answers
            steps:
              - rest.verify:
                  target: idm
                  path: /accounts/runner
                  expect:
                    status: 200
            """;

    private static EmbeddedLdapServer ldap;
    private static WireMockServer wireMock;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @TempDir
    Path temp;

    @BeforeAll
    static void startSystems() throws Exception {
        ldap = EmbeddedLdapServer.start(BASE_DN, BIND_DN, LDAP_PASSWORD, 0, "ldap-init.ldif");
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(get(urlEqualTo("/accounts/runner"))
                .withBasicAuth("runner", REST_PASSWORD)
                .willReturn(okJson("{\"userId\":\"runner\"}")));
    }

    @AfterAll
    static void stopSystems() {
        if (wireMock != null) {
            wireMock.stop();
        }
        if (ldap != null) {
            ldap.close();
        }
    }

    // --- workspace ----------------------------------------------------------------------------

    private Path workspace(String folder) throws Exception {
        return workspace(folder, database(), null);
    }

    /** A workspace whose test-db has {@code jdbcUrl} and, unless null, {@code driverClassName}. */
    private Path workspace(String folder, String jdbcUrl, String driverClassName) throws Exception {
        Path ws = Files.createDirectories(temp.resolve(folder));
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
     * A file-based H2 database with the corpus's users table: a database the in-memory guard
     * accepts, created here with the password the run reads from secrets.env.
     */
    private String database() throws Exception {
        Path dir = Files.createDirectories(temp.resolve("db"));
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

    private static void scenario(Path ws, String name, String content) throws Exception {
        Path file = ws.resolve("scenarios").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void corpusScenario(Path ws) throws Exception {
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
    }

    private static void corpusScenario(Path ws, String name) throws Exception {
        try (InputStream in = RunCommandTest.class.getResourceAsStream("/scenarios/" + name)) {
            assertThat(in).isNotNull();
            Files.copy(in, ws.resolve("scenarios").resolve(name));
        }
    }

    private int run(Path ws, String... extra) {
        return run(null, ws, extra);
    }

    /** A run that sees {@code environment} as its environment variables, unless null (the real ones). */
    private int run(Map<String, String> environment, Path ws, String... extra) {
        List<String> args = new ArrayList<>(List.of("run", "--workspace", ws.toString()));
        args.addAll(List.of(extra));
        return RunCommand.run(CommandLine.parse(args.toArray(String[]::new)),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8), CLOCK, environment);
    }

    /**
     * Adds two REST targets whose base URL carries {@code ${variable}}: {@code tenant} on WireMock,
     * which answers only at {@code /tenant-<expected>/users}, and {@code offline}, which nothing
     * answers; and one scenario for each.
     */
    private static void tenantTargets(Path ws, String variable, String expected) throws Exception {
        Path settings = ws.resolve("config/application-lab.yml");
        Files.writeString(settings, Files.readString(settings).replace("    executor:",
                "      tenant:\n        base-url: " + wireMock.baseUrl() + "/tenant-${" + variable + "}\n"
                        + "      offline:\n        base-url: http://127.0.0.1:1/tenant-${" + variable + "}\n"
                        + "    executor:"));
        wireMock.stubFor(get(urlEqualTo("/tenant-" + expected + "/users")).willReturn(okJson("[]")));
        scenario(ws, "tenant.yaml", """
                name: Tenant answers
                steps:
                  - rest.verify:
                      target: tenant
                      path: /users
                      expect:
                        status: 200
                """);
        scenario(ws, "offline.yaml", """
                name: Offline tenant
                steps:
                  - rest.verify:
                      target: offline
                      path: /users
                      expect:
                        status: 200
                """);
    }

    /** {@code secret} is nowhere in the run's outputs; the console and results show the mask instead. */
    private void assertMaskedEverywhere(Path ws, String secret) throws Exception {
        Path folder = runFolder(ws);
        String results = Files.readString(folder.resolve("results.json"));
        assertThat(results).doesNotContain(secret).contains("tenant-****");
        assertThat(Files.readString(folder.resolve("report.html"))).doesNotContain(secret).contains("tenant-****");
        assertThat(Files.readString(folder.resolve("run.log"))).doesNotContain(secret);
        assertThat(out() + err()).doesNotContain(secret);
        assertThat(out()).contains("✖ offline.yaml  ", "tenant-****");
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private static Path runFolder(Path ws) {
        return ws.resolve("reports").resolve(RUN_ID);
    }

    // --- runs ---------------------------------------------------------------------------------

    @Test
    @DisplayName("Should run passing LDAP and database scenarios, write results, report and run log, and exit 0")
    void testPassingRun() throws Exception {
        Path ws = workspace("ws");
        corpusScenario(ws);
        corpusScenario(ws, "provisioning-user-to-database.yaml");

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.OK);
        Path folder = runFolder(ws);
        assertThat(folder.resolve("results.json")).isRegularFile();
        assertThat(folder.resolve("report.html")).isRegularFile();
        assertThat(folder.resolve("run.log")).isRegularFile();
        assertThat(out()).contains("✔ provisioning-user-to-ldap.yaml  ", "✔ provisioning-user-to-database.yaml  ");
        assertThat(out().strip()).endsWith(
                "2 scenarios: 2 passed, 0 failed · report: reports/" + RUN_ID + "/report.html");
        String log = Files.readString(folder.resolve("run.log"));
        assertThat(log).contains("provisioning-user-to-ldap.yaml", "Configuring LDAP directory");
        for (String text : List.of(log, out() + err(), Files.readString(folder.resolve("results.json")),
                Files.readString(folder.resolve("report.html")))) {
            assertThat(text).doesNotContain(LDAP_PASSWORD).doesNotContain(REST_PASSWORD).doesNotContain(DB_PASSWORD);
        }
        assertNoPoolThreads();
    }

    @Test
    @DisplayName("Should report a failing scenario with ✖ and exit 1")
    void testFailingRun() throws Exception {
        Path ws = workspace("ws");
        corpusScenario(ws);
        scenario(ws, "wrong-sn.yaml", WRONG_SN);

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.FAILED);
        assertThat(out()).contains("✔ provisioning-user-to-ldap.yaml  ", "✖ wrong-sn.yaml  ", " — ",
                "2 scenarios: 1 passed, 1 failed · report: reports/" + RUN_ID + "/report.html");
        assertThat(out().lines().filter(l -> l.startsWith("✖ wrong-sn.yaml")).findFirst().orElseThrow())
                .endsWith(" · finding");
        String results = Files.readString(runFolder(ws).resolve("results.json"));
        assertThat(results).contains("\"failureKind\" : \"FINDING\"");
        assertThat(results).contains("provisioning-user-to-ldap.yaml", "wrong-sn.yaml");
    }

    @Test
    @DisplayName("Should record a scenario that fails to load beside the ones that run")
    void testReportsWrittenAfterEachScenario() throws Exception {
        Path ws = workspace("ws");
        scenario(ws, "a-bad.yaml", BAD_STEP);
        scenario(ws, "rest/account.yaml", REST_ACCOUNT);

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.FAILED);
        assertThat(out()).contains("✖ a-bad.yaml  0 ms — ", "no.such.step", "✔ rest/account.yaml  ",
                "2 scenarios: 1 passed, 1 failed");
        assertThat(out().lines().filter(l -> l.startsWith("✖ a-bad.yaml")).findFirst().orElseThrow())
                .endsWith(" · setup problem");
        Path folder = runFolder(ws);
        assertThat(Files.readString(folder.resolve("results.json"))).contains("a-bad.yaml", "rest/account.yaml");
        assertThat(Files.readString(folder.resolve("report.html"))).contains("a-bad.yaml", "rest/account.yaml");
    }

    @Test
    @DisplayName("Should speak Spanish on the console and in the report with --locale es")
    void testSpanishRun() throws Exception {
        Path ws = workspace("ws");
        scenario(ws, "wrong-sn.yaml", WRONG_SN);

        int exit = run(ws, "--profile", "lab", "--locale", "es");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.FAILED);
        assertThat(out()).contains("✖ wrong-sn.yaml  ",
                "1 escenarios: 0 superados, 1 fallidos · informe: reports/" + RUN_ID + "/report.html");
        String failureLine = out().lines().filter(l -> l.startsWith("✖ ")).findFirst().orElseThrow();
        assertThat(failureLine).doesNotContain("expected").doesNotContain("Step ").endsWith(" · hallazgo");
        assertThat(Files.readString(runFolder(ws).resolve("report.html"))).contains("lang=\"es\"");
    }

    @Test
    @DisplayName("Should refuse a run whose LDAP URL is not set, before any scenario runs, and exit 2")
    void testGuardRefusal() throws Exception {
        Path ws = workspace("ws");
        Files.writeString(ws.resolve("config/application-lab.yml"), """
                idm:
                  test:
                    ldap:
                      base-dn: dc=test,dc=com
                """);
        corpusScenario(ws);

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("idm.test.ldap.url", "IDM_TEST_LDAP_URL");
        assertThat(out()).doesNotContain("✔").doesNotContain("✖");
        assertThat(runFolder(ws).resolve("results.json")).doesNotExist();
        // The context is lazy: the guards refused before any connector was built.
        assertThat(Files.readString(runFolder(ws).resolve("run.log"))).doesNotContain("Configuring LDAP directory");
    }

    @Test
    @DisplayName("Should let --locale beat the profile file's idm.test.report.locale, on the console and in the report")
    void testCommandLineLocaleBeatsProfile() throws Exception {
        Path ws = workspace("ws");
        Files.writeString(ws.resolve("config/application-lab.yml"),
                Files.readString(ws.resolve("config/application-lab.yml")).replace("    executor:",
                        "    report:\n      locale: en\n    executor:"));
        corpusScenario(ws);

        int exit = run(ws, "--profile", "lab", "--locale", "es");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.OK);
        assertThat(out()).contains("1 escenarios: 1 superados, 0 fallidos · informe: reports/" + RUN_ID + "/report.html");
        assertThat(Files.readString(runFolder(ws).resolve("report.html"))).contains("lang=\"es\"");
    }

    @Test
    @DisplayName("Should keep a secrets.env value an exception quotes out of run.log and --verbose output")
    void testSecretInExceptionIsMasked() throws Exception {
        String secret = "s3cr3tVal-probe";
        Path ws = workspace("ws");
        Files.writeString(ws.resolve("secrets.env"), "IDM_TEST_TIMEOUT=" + secret + "\n", StandardOpenOption.APPEND);
        Files.writeString(ws.resolve("config/application-lab.yml"),
                Files.readString(ws.resolve("config/application-lab.yml"))
                        .replace("default-timeout-seconds: 2", "default-timeout-seconds: ${IDM_TEST_TIMEOUT}"));
        corpusScenario(ws);

        PrintStream previous = System.out;
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
            exit = run(ws, "--profile", "lab", "--verbose");
        } finally {
            System.setOut(previous);
        }

        assertThat(exit).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).startsWith("the run could not start: ").contains("****");
        String log = Files.readString(runFolder(ws).resolve("run.log"));
        assertThat(log).contains("****", "NumberFormatException");
        assertThat(log).doesNotContain(secret);
        assertThat(err()).doesNotContain(secret);
        assertThat(out()).doesNotContain(secret);
        assertThat(console.toString(StandardCharsets.UTF_8)).doesNotContain(secret);
    }

    @Test
    @DisplayName("Should keep a secrets.env value in a REST URL out of results.json, report.html, run.log and the console")
    void testSecretInReportsIsMasked() throws Exception {
        String secret = "Zq9xLeakProbe";
        Path ws = workspace("ws");
        Files.writeString(ws.resolve("secrets.env"), "IDM_TEST_TENANT_KEY=" + secret + "\n", StandardOpenOption.APPEND);
        tenantTargets(ws, "IDM_TEST_TENANT_KEY", secret);

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.FAILED);
        assertThat(out()).contains("✔ tenant.yaml  ");
        assertMaskedEverywhere(ws, secret);
    }

    @Test
    @DisplayName("Should mask the environment variable that overrides a secrets.env value, the value the run uses")
    void testEnvironmentOverrideIsMasked() throws Exception {
        String secret = "EnvWinsProbe7";
        Path ws = workspace("ws");
        Files.writeString(ws.resolve("secrets.env"), "IDM_TEST_TENANT_KEY=FileLoses-1\n", StandardOpenOption.APPEND);
        tenantTargets(ws, "IDM_TEST_TENANT_KEY", secret);

        int exit = run(Map.of("IDM_TEST_TENANT_KEY", secret), ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.FAILED);
        // WireMock answers only at the environment variable's value: the run used it.
        assertThat(out()).contains("✔ tenant.yaml  ");
        assertMaskedEverywhere(ws, secret);
    }

    @Test
    @DisplayName("Should mask an environment variable whose name looks secret, with no secrets.env entry, but not PWD")
    void testSecretNamedEnvironmentVariableIsMasked() throws Exception {
        String secret = "TokenProbe42x";
        Path ws = workspace("ws");
        tenantTargets(ws, "IDM_TEST_TENANT_TOKEN", secret);

        int exit = run(Map.of("IDM_TEST_TENANT_TOKEN", secret, "PWD", ws.toString()), ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.FAILED);
        assertThat(out()).contains("✔ tenant.yaml  ");
        assertMaskedEverywhere(ws, secret);
        // PWD names the current folder, not a password: the workspace path stays readable.
        assertThat(Files.readString(runFolder(ws).resolve("run.log"))).contains("workspace " + ws);
    }

    @Test
    @DisplayName("Should refuse the embedded profile with a message that names --profile, and exit 2")
    void testEmbeddedProfileRefusal() throws Exception {
        Path ws = workspace("ws");
        Files.copy(ws.resolve("config/application-lab.yml"), ws.resolve("config/application-test.yml"));
        corpusScenario(ws);

        int exit = run(ws, "--profile", "test");

        assertThat(exit).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("Active profiles [test] include 'test', the embedded test configuration.",
                "--profile").doesNotContain("IDM_TEST_PROFILE");
        assertThat(out()).doesNotContain("✔").doesNotContain("✖");
    }

    @Test
    @DisplayName("Should keep a secrets.env value out of a log file the profile configures")
    void testUserLogFileGetsNothing() throws Exception {
        String secret = "s3cr3tVal-userlog";
        Path userLog = temp.resolve("user.log");
        Path ws = workspace("ws");
        Files.writeString(ws.resolve("secrets.env"), "IDM_TEST_TIMEOUT=" + secret + "\n", StandardOpenOption.APPEND);
        Files.writeString(ws.resolve("config/application-lab.yml"),
                Files.readString(ws.resolve("config/application-lab.yml"))
                        .replace("default-timeout-seconds: 2", "default-timeout-seconds: ${IDM_TEST_TIMEOUT}")
                + "logging:\n  file:\n    name: " + userLog.toAbsolutePath() + "\n");
        corpusScenario(ws);

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).isEqualTo(ExitCodes.CANNOT_START);
        if (Files.exists(userLog)) {
            assertThat(Files.readString(userLog)).doesNotContain(secret);
        }
        String log = Files.readString(runFolder(ws).resolve("run.log"));
        assertThat(log).contains("NumberFormatException", "****").doesNotContain(secret);
        assertThat(out() + err()).doesNotContain(secret);
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @DisplayName("Should stop with one masked line and exit 2 when the scenarios cannot be listed")
    void testUnlistableScenarios() throws Exception {
        Path ws = workspace("ws");
        corpusScenario(ws);
        // An unreadable scenarios/ folder: listing it fails with a checked IOException.
        Path locked = ws.resolve("scenarios");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            Assumptions.assumeFalse(Files.isReadable(locked), "running as a user who can read anything");

            int exit = run(ws, "--profile", "lab");

            assertThat(exit).isEqualTo(ExitCodes.CANNOT_START);
            assertThat(err()).startsWith("the run could not start: ");
            assertThat(err().strip().lines()).hasSize(1);
            assertThat(out()).doesNotContain("✔");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @DisplayName("Should record an unreadable scenario file as failed while the others run, and exit 1")
    void testUnreadableScenarioFile() throws Exception {
        Path ws = workspace("ws");
        corpusScenario(ws);
        Path unreadable = ws.resolve("scenarios/a-unreadable.yaml");
        Files.writeString(unreadable, REST_ACCOUNT);
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        try {
            Assumptions.assumeFalse(Files.isReadable(unreadable), "running as a user who can read anything");

            int exit = run(ws, "--profile", "lab");

            assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.FAILED);
            assertThat(out()).contains("✖ a-unreadable.yaml  0 ms — ", "✔ provisioning-user-to-ldap.yaml  ",
                    "2 scenarios: 1 passed, 1 failed");
            assertThat(Files.readString(runFolder(ws).resolve("results.json"))).contains("a-unreadable.yaml");
        } finally {
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    @DisplayName("Should stop with one line on stderr when Spring cannot start, and exit 2")
    void testStartupFailure() throws Exception {
        Path ws = workspace("ws");
        Files.writeString(ws.resolve("config/application-lab.yml"), "idm: [unclosed\n");
        corpusScenario(ws);

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).startsWith("the run could not start: ");
        assertThat(err().strip().lines()).hasSize(1);
        assertThat(out()).doesNotContain("✔");
    }

    @Test
    @DisplayName("Should stop with the missing profile file's message and exit 2")
    void testMissingProfile() throws Exception {
        Path ws = workspace("ws");
        corpusScenario(ws);

        assertThat(run(ws, "--profile", "prod")).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("no settings for profile 'prod': config/application-prod.yml not found");
        assertThat(ws.resolve("reports")).doesNotExist();
    }

    @Test
    @DisplayName("Should refuse run without --profile and exit 2")
    void testRunNeedsProfile() throws Exception {
        Path ws = workspace("ws");
        corpusScenario(ws);

        int exit = IdmTest.run(new String[] {"run", "--workspace", ws.toString()},
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("run needs --profile <name>");
    }

    @Test
    @DisplayName("Should stop when no scenario matches --scenario and exit 2")
    void testNoMatchingScenario() throws Exception {
        Path ws = workspace("ws");
        corpusScenario(ws);

        assertThat(run(ws, "--profile", "lab", "--scenario", "nothing-*")).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("no scenario matches 'nothing-*'");
        assertThat(out()).doesNotContain("✔");
    }

    @Test
    @DisplayName("Should warn when secrets.env is tracked by Git and still run")
    void testGitTrackedWarning() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not on the PATH");
        Path ws = workspace("ws");
        corpusScenario(ws);
        git(ws, "init", "-q");
        git(ws, "add", "secrets.env");
        git(ws, "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false",
                "-c", "core.hooksPath=" + Files.createDirectories(temp.resolve("nohooks")), "commit", "-q", "-m", "x");

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.OK);
        assertThat(err()).contains("secrets.env is tracked by Git; remove it with 'git rm --cached secrets.env'");
        assertThat(out()).contains("1 scenarios: 1 passed, 0 failed");
    }

    @Test
    @DisplayName("Should run from a workspace whose path contains spaces")
    void testRunInFolderWithSpaces() throws Exception {
        Path ws = workspace("My Tests/lab runs");
        corpusScenario(ws);

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.OK);
        assertThat(runFolder(ws).resolve("report.html")).isRegularFile();
        assertThat(out()).contains("1 scenarios: 1 passed, 0 failed · report: reports/" + RUN_ID + "/report.html");
    }

    // --- drivers ------------------------------------------------------------------------------

    @Test
    @DisplayName("Should connect through a driver jar in the workspace's drivers/ folder and list it in run.log")
    void testWorkspaceDriver() throws Exception {
        Path ws = workspace("ws", fakeUrl(), null);
        Path jar = fakeDriverJar(ws.resolve("drivers"));
        corpusScenario(ws, "provisioning-user-to-database.yaml");

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.OK);
        assertThat(out()).contains("✔ provisioning-user-to-database.yaml  ");
        assertThat(Files.readString(runFolder(ws).resolve("run.log")))
                .contains("driver jars loaded: [" + jar + "]");
        assertNoPoolThreads();
    }

    @Test
    @DisplayName("Should load a workspace driver named by driver-class-name through the pool")
    void testWorkspaceDriverClassName() throws Exception {
        Path ws = workspace("ws", fakeUrl(), FAKE_DRIVER);
        fakeDriverJar(ws.resolve("drivers"));
        corpusScenario(ws, "provisioning-user-to-database.yaml");

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.OK);
        assertThat(out()).contains("✔ provisioning-user-to-database.yaml  ");
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(RunCommandTest.class.getClassLoader());
        assertNoPoolThreads();
    }

    @Test
    @DisplayName("Should give the context class loader back when a run with workspace drivers cannot start")
    void testClassLoaderRestoredOnFailure() throws Exception {
        Path ws = workspace("ws", fakeUrl(), "fakejdbc.NoSuchDriver");
        fakeDriverJar(ws.resolve("drivers"));
        corpusScenario(ws, "provisioning-user-to-database.yaml");
        ClassLoader before = Thread.currentThread().getContextClassLoader();

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("no JDBC driver for 'jdbc:fake:", "(database 'test-db')");
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(before);
        assertThat(Collections.list(DriverManager.getDrivers())).noneMatch(DelegatingDriver.class::isInstance);
        assertNoPoolThreads();
    }

    @Test
    @DisplayName("Should stop before any scenario when a used database has no driver, and exit 2")
    void testMissingDriver() throws Exception {
        Path ws = workspace("ws", "jdbc:db2://db.example.com/hr;password=${IDM_TEST_DB_PASSWORD}", null);
        corpusScenario(ws);
        corpusScenario(ws, "provisioning-user-to-database.yaml");

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err().strip()).isEqualTo("no JDBC driver for 'jdbc:db2://db.example.com/hr;password=***' "
                + "(database 'test-db'); put the driver jar in " + ws.resolve("drivers") + "/");
        assertThat(out()).doesNotContain("✔").doesNotContain("✖");
        assertThat(runFolder(ws).resolve("results.json")).doesNotExist();
        assertThat(Files.readString(runFolder(ws).resolve("run.log")) + err()).doesNotContain(DB_PASSWORD);
    }

    /** The run's pools are closed with its context: no thread of the test database's pool is left. */
    private static void assertNoPoolThreads() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (poolThreadAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(RunCommandTest::isPoolThread);
    }

    private static boolean poolThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(RunCommandTest::isPoolThread);
    }

    /** Hikari names its threads {@code <pool name>:housekeeper}, {@code <pool name>:connection-adder}, … */
    private static boolean isPoolThread(Thread thread) {
        return thread.isAlive() && thread.getName().startsWith("HikariPool-test-db:");
    }

    private static final String FAKE_DRIVER = "fakejdbc.FakeDriver";

    /** A driver for {@code jdbc:fake:} URLs that hands them on to H2; compiled here, so no class path has it. */
    private static final String FAKE_DRIVER_SOURCE = """
            package fakejdbc;

            import java.sql.*;
            import java.util.Properties;
            import java.util.logging.Logger;

            public final class FakeDriver implements Driver {
                private static final String PREFIX = "jdbc:fake:";
                public Connection connect(String url, Properties info) throws SQLException {
                    return acceptsURL(url) ? DriverManager.getConnection("jdbc:h2:" + url.substring(PREFIX.length()), info)
                            : null;
                }
                public boolean acceptsURL(String url) { return url != null && url.startsWith(PREFIX); }
                public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
                public int getMajorVersion() { return 1; }
                public int getMinorVersion() { return 0; }
                public boolean jdbcCompliant() { return false; }
                public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                    throw new SQLFeatureNotSupportedException();
                }
            }
            """;

    /** The test database's URL under the fake driver's {@code jdbc:fake:} prefix. */
    private String fakeUrl() throws Exception {
        return "jdbc:fake:" + database().substring("jdbc:h2:".length());
    }

    private Path fakeDriverJar(Path drivers) throws Exception {
        Path source = Files.createDirectories(temp.resolve("fake-src/fakejdbc")).resolve("FakeDriver.java");
        Files.writeString(source, FAKE_DRIVER_SOURCE);
        Path classes = Files.createDirectories(temp.resolve("fake-classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler.run(null, null, null, "-d", classes.toString(), source.toString())).isZero();
        Path jar = Files.createDirectories(drivers).resolve("fake-driver.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fakejdbc/FakeDriver.class"));
            out.write(Files.readAllBytes(classes.resolve("fakejdbc/FakeDriver.class")));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
            out.write((FAKE_DRIVER + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    // --- git ----------------------------------------------------------------------------------

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
