package com.idm.test.runner;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
                        jdbc-url: jdbc:h2:mem:runner;DB_CLOSE_DELAY=-1
                        username: sa
                        password: ""
                    rest:
                      idm:
                        base-url: %s
                        username: runner
                        password: ${IDM_TEST_REST_PASSWORD}
                    executor:
                      default-timeout-seconds: 2
                      default-poll-interval-seconds: 1
                """.formatted(ldap.getUrl(), BASE_DN, BIND_DN, wireMock.baseUrl()));
        Files.writeString(ws.resolve("secrets.env"), "# lab secrets\nIDM_TEST_LDAP_PASSWORD=" + LDAP_PASSWORD
                + "\nIDM_TEST_REST_PASSWORD=" + REST_PASSWORD + "\n");
        return ws;
    }

    private static void scenario(Path ws, String name, String content) throws Exception {
        Path file = ws.resolve("scenarios").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void corpusScenario(Path ws) throws Exception {
        try (InputStream in = RunCommandTest.class.getResourceAsStream("/scenarios/provisioning-user-to-ldap.yaml")) {
            assertThat(in).isNotNull();
            Files.copy(in, ws.resolve("scenarios/provisioning-user-to-ldap.yaml"));
        }
    }

    private int run(Path ws, String... extra) {
        List<String> args = new ArrayList<>(List.of("run", "--workspace", ws.toString()));
        args.addAll(List.of(extra));
        return RunCommand.run(CommandLine.parse(args.toArray(String[]::new)),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8), CLOCK);
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
    @DisplayName("Should run a passing scenario, write results, report and run log, and exit 0")
    void testPassingRun() throws Exception {
        Path ws = workspace("ws");
        corpusScenario(ws);

        int exit = run(ws, "--profile", "lab");

        assertThat(exit).as(out() + err()).isEqualTo(ExitCodes.OK);
        Path folder = runFolder(ws);
        assertThat(folder.resolve("results.json")).isRegularFile();
        assertThat(folder.resolve("report.html")).isRegularFile();
        assertThat(folder.resolve("run.log")).isRegularFile();
        assertThat(out()).contains("✔ provisioning-user-to-ldap.yaml  ");
        assertThat(out().strip()).endsWith(
                "1 scenarios: 1 passed, 0 failed · report: reports/" + RUN_ID + "/report.html");
        String log = Files.readString(folder.resolve("run.log"));
        assertThat(log).contains("provisioning-user-to-ldap.yaml", "Configuring LDAP directory");
        assertThat(log).doesNotContain(LDAP_PASSWORD).doesNotContain(REST_PASSWORD);
        assertThat(out() + err()).doesNotContain(LDAP_PASSWORD).doesNotContain(REST_PASSWORD);
        assertThat(Files.readString(folder.resolve("results.json")))
                .doesNotContain(LDAP_PASSWORD).doesNotContain(REST_PASSWORD);
        assertThat(Files.readString(folder.resolve("report.html")))
                .doesNotContain(LDAP_PASSWORD).doesNotContain(REST_PASSWORD);
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
        String results = Files.readString(runFolder(ws).resolve("results.json"));
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
        assertThat(out()).contains("✖ a-bad.yaml — ", "no.such.step", "✔ rest/account.yaml  ",
                "2 scenarios: 1 passed, 1 failed");
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
        assertThat(failureLine).doesNotContain("expected").doesNotContain("Step ");
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
