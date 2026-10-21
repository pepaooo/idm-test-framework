package com.idm.test.runner.ui.lab;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.idm.test.runner.SecretMask;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/** Test connection per system kind, against an embedded directory, H2 and WireMock. Every credential is fake. */
@DisplayName("Lab connection tester")
class ConnectionTesterTest {

    private static final String BASE_DN = "dc=test,dc=com";
    private static final String BIND_DN = "cn=admin,dc=test,dc=com";
    private static final String BIND_PASSWORD = "fake-bind-password";
    private static final String WRONG_PASSWORD = "fake-wrong-password";
    private static final String HTTP_PASSWORD = "fake-http-password";
    private static final String CLIENT_SECRET = "fake-client-secret";

    private static EmbeddedLdapServer ldap;
    private static WireMockServer wireMock;
    private static ConnectionTester tester;

    @TempDir
    Path temp;

    @BeforeAll
    static void start() throws Exception {
        ldap = EmbeddedLdapServer.start(BASE_DN, BIND_DN, BIND_PASSWORD, 0, null);
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        tester = new ConnectionTester();
    }

    @AfterAll
    static void stop() {
        tester.close();
        wireMock.stop();
        ldap.close();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    private static SecretMask mask(String... extra) {
        List<String> values = new ArrayList<>(List.of(BIND_PASSWORD, WRONG_PASSWORD, HTTP_PASSWORD, CLIENT_SECRET));
        values.addAll(List.of(extra));
        return SecretMask.ofValues(values);
    }

    private ConnectionTester.Result test(String systemId, Map<String, String> properties) {
        return tester.test(LabSettings.SystemRef.parse(systemId), properties, temp.resolve("drivers"), Locale.ENGLISH,
                mask());
    }

    private static Map<String, String> props(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static void assertNoSecret(ConnectionTester.Result result) {
        assertThat(result.message()).doesNotContain(BIND_PASSWORD, WRONG_PASSWORD, HTTP_PASSWORD, CLIENT_SECRET);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    // --- LDAP ------------------------------------------------------------------------------------

    @Test
    @DisplayName("LDAP: a good bind connects")
    void testLdapBindOk() {
        ConnectionTester.Result result = test("ldap", props("idm.test.ldap.url", ldap.getUrl(),
                "idm.test.ldap.base-dn", BASE_DN, "idm.test.ldap.user-dn", BIND_DN, "idm.test.ldap.password", BIND_PASSWORD));

        assertThat(result.ok()).as(result.message()).isTrue();
        assertThat(result.message()).isEqualTo("Connected · bind OK");
        assertThat(result.millis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("LDAP: a wrong password is \"credentials rejected\", not unreachable")
    void testLdapCredentialsRejected() {
        ConnectionTester.Result result = test("directory:edir", props("idm.test.directories.edir.url", ldap.getUrl(),
                "idm.test.directories.edir.user-dn", BIND_DN, "idm.test.directories.edir.password", WRONG_PASSWORD));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).startsWith("credentials rejected");
        assertNoSecret(result);
    }

    @Test
    @DisplayName("LDAP: a closed port is unreachable, well within the timeout")
    void testLdapUnreachable() throws IOException {
        ConnectionTester.Result result = test("ldap", props("idm.test.ldap.url", "ldap://127.0.0.1:" + closedPort(),
                "idm.test.ldap.user-dn", BIND_DN, "idm.test.ldap.password", BIND_PASSWORD));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).startsWith("could not reach");
        assertThat(result.millis()).isLessThan(ConnectionTester.TIMEOUT.toMillis());
        assertNoSecret(result);
    }

    @Test
    @DisplayName("A server that never answers ends the test at the timeout")
    void testTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
             ConnectionTester quick = new ConnectionTester(Duration.ofSeconds(1))) {
            List<Socket> accepted = new ArrayList<>();
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(silent.accept());
                    }
                } catch (IOException e) {
                    // closed
                }
            });
            long start = System.nanoTime();
            ConnectionTester.Result result = quick.test(LabSettings.SystemRef.parse("ldap"),
                    props("idm.test.ldap.url", "ldap://127.0.0.1:" + silent.getLocalPort(),
                            "idm.test.ldap.user-dn", BIND_DN, "idm.test.ldap.password", BIND_PASSWORD),
                    temp, Locale.ENGLISH, mask());
            long took = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertThat(result.ok()).isFalse();
            assertThat(result.message()).startsWith("no answer");
            assertThat(took).isLessThan(5_000);
            silent.close();
            acceptor.join(2_000);
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    @Test
    @DisplayName("Placeholder values are refused first, with the guards' message")
    void testPlaceholderRefused() {
        ConnectionTester.Result result = test("ldap", props("idm.test.ldap.url", "ldap://localhost:389"));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("idm.test.ldap.url", "placeholder");
    }

    @Test
    @DisplayName("Nothing set: the guards say what is missing")
    void testNotSet() {
        ConnectionTester.Result result = test("soap", props());

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("idm.test.soap.url", "is not set");
    }

    // --- database ---------------------------------------------------------------------------------

    @Test
    @DisplayName("Database: H2 opens and answers the validation query")
    void testDatabaseOk() {
        String url = "jdbc:h2:file:" + temp.resolve("db").toAbsolutePath();
        ConnectionTester.Result result = test("database:hr", props("idm.test.databases.hr.jdbc-url", url,
                "idm.test.databases.hr.username", "sa", "idm.test.databases.hr.password", HTTP_PASSWORD));

        assertThat(result.ok()).as(result.message()).isTrue();
        assertThat(result.message()).isEqualTo("Connected · SELECT 1 OK");
    }

    @Test
    @DisplayName("Database: a host that never answers ends at the timeout and frees its thread, whatever the URL's own timeouts")
    void testDatabaseSilentHost() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
             ConnectionTester quick = new ConnectionTester(Duration.ofSeconds(1))) {
            List<Socket> accepted = new ArrayList<>();
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(silent.accept());
                    }
                } catch (IOException e) {
                    // closed
                }
            });
            long start = System.nanoTime();
            ConnectionTester.Result result = quick.test(LabSettings.SystemRef.parse("database:hr"),
                    props("idm.test.databases.hr.jdbc-url", "jdbc:postgresql://127.0.0.1:" + silent.getLocalPort()
                                    + "/hr?connectTimeout=0&socketTimeout=0&loginTimeout=0",
                            "idm.test.databases.hr.username", "tester", "idm.test.databases.hr.password", HTTP_PASSWORD),
                    temp, Locale.ENGLISH, mask());

            assertThat(result.ok()).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(5_000);
            assertNoSecret(result);
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (quick.busyThreads() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(quick.busyThreads()).as("the driver's own timeouts let the pool thread go").isZero();
            silent.close();
            acceptor.join(2_000);
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    @Test
    @DisplayName("A JDBC URL's own timeouts are removed for the drivers given ours, or the test is refused")
    void testUrlTimeoutsRemoved() {
        assertThat(ConnectionTester.withoutTimeouts("jdbc:postgresql://db.test/hr?connectTimeout=0&ssl=true&socketTimeout=0"))
                .isEqualTo("jdbc:postgresql://db.test/hr?ssl=true");
        assertThat(ConnectionTester.withoutTimeouts("jdbc:postgresql://db.test/hr?LOGINTIMEOUT=0"))
                .isEqualTo("jdbc:postgresql://db.test/hr");
        assertThat(ConnectionTester.withoutTimeouts("jdbc:mysql://db.test/hr?connectTimeout=0&useSSL=true"))
                .isEqualTo("jdbc:mysql://db.test/hr?useSSL=true");
        assertThat(ConnectionTester.withoutTimeouts("jdbc:mariadb://db.test/hr?socketTimeout=0"))
                .isEqualTo("jdbc:mariadb://db.test/hr");
        assertThat(ConnectionTester.withoutTimeouts("jdbc:sqlserver://db.test;databaseName=hr;loginTimeout=0;socketTimeout=0"))
                .isEqualTo("jdbc:sqlserver://db.test;databaseName=hr");
        assertThat(ConnectionTester.withoutTimeouts("jdbc:mysql://address=(host=db.test)(connectTimeout=0)/hr")).isNull();
        assertThat(ConnectionTester.withoutTimeouts(
                "jdbc:oracle:thin:@(DESCRIPTION=(CONNECT_TIMEOUT=0)(ADDRESS=(HOST=db.test)(PORT=1521)))")).isNull();
        assertThat(ConnectionTester.withoutTimeouts("jdbc:oracle:thin:@db.test:1521/HR")).isEqualTo("jdbc:oracle:thin:@db.test:1521/HR");
        assertThat(ConnectionTester.withoutTimeouts("jdbc:h2:file:/tmp/x")).isEqualTo("jdbc:h2:file:/tmp/x");
    }

    @Test
    @DisplayName("A refused URL timeout is a failed test with its own message")
    void testUrlTimeoutRefused() {
        ConnectionTester.Result result = test("database:hr", props("idm.test.databases.hr.jdbc-url",
                "jdbc:oracle:thin:@(DESCRIPTION=(CONNECT_TIMEOUT=0)(ADDRESS=(HOST=127.0.0.1)(PORT=1)))"));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).startsWith("the JDBC URL sets a timeout of its own");
    }

    @Test
    @DisplayName("Tests that outlive the limit are counted; while they hold their threads new tests are told so, then served again")
    void testAbandonedThreadsBounded() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
             ConnectionTester small = new ConnectionTester(Duration.ofMillis(500), Duration.ofSeconds(3), 1, 1)) {
            List<Socket> accepted = new ArrayList<>();
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(silent.accept());
                    }
                } catch (IOException e) {
                    // closed
                }
            });
            Map<String, String> silentLdap = props("idm.test.ldap.url", "ldap://127.0.0.1:" + silent.getLocalPort(),
                    "idm.test.ldap.user-dn", BIND_DN, "idm.test.ldap.password", BIND_PASSWORD);
            Map<String, String> goodLdap = props("idm.test.ldap.url", ldap.getUrl(),
                    "idm.test.ldap.user-dn", BIND_DN, "idm.test.ldap.password", BIND_PASSWORD);

            ConnectionTester.Result first = small.test(LabSettings.SystemRef.parse("ldap"), silentLdap, temp,
                    Locale.ENGLISH, mask());
            assertThat(first.ok()).isFalse();
            assertThat(first.message()).startsWith("no answer");
            assertThat(small.abandonedThreads()).isEqualTo(1);

            ConnectionTester.Result second = small.test(LabSettings.SystemRef.parse("ldap"), goodLdap, temp,
                    Locale.ENGLISH, mask());
            assertThat(second.ok()).isFalse();
            assertThat(second.message()).startsWith("1 earlier connection tests are still waiting");

            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (small.abandonedThreads() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(small.abandonedThreads()).isZero();
            ConnectionTester.Result third = small.test(LabSettings.SystemRef.parse("ldap"), goodLdap, temp,
                    Locale.ENGLISH, mask());
            assertThat(third.ok()).as(third.message()).isTrue();
            silent.close();
            acceptor.join(2_000);
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    @Test
    @DisplayName("Driver timeouts go only to drivers known to take them")
    void testDriverTimeouts() {
        assertThat(tester.timeouts("jdbc:postgresql://db.test/hr")).containsKeys("loginTimeout", "connectTimeout",
                "socketTimeout");
        assertThat(tester.timeouts("jdbc:oracle:thin:@db.test:1521/HR")).containsKey("oracle.net.CONNECT_TIMEOUT");
        assertThat(tester.timeouts("jdbc:sqlserver://db.test")).containsKey("loginTimeout");
        assertThat(tester.timeouts("jdbc:h2:file:/tmp/x")).isEmpty();
    }

    @Test
    @DisplayName("Database: an in-memory URL is refused by the guards")
    void testDatabaseInMemoryRefused() {
        ConnectionTester.Result result = test("database:hr", props("idm.test.databases.hr.jdbc-url", "jdbc:h2:mem:x"));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("in-memory");
    }

    @Test
    @DisplayName("Database: a URL no driver understands names the missing driver")
    void testDatabaseNoDriver() {
        ConnectionTester.Result result = test("database:hr",
                props("idm.test.databases.hr.jdbc-url", "jdbc:nosuchdb://db.test/hr"));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("no JDBC driver");
    }

    // --- REST -------------------------------------------------------------------------------------

    @Test
    @DisplayName("REST: any HTTP answer to the base URL is reachable; basic credentials are sent")
    void testRestReachable() {
        wireMock.stubFor(get(urlPathEqualTo("/IDMProv")).willReturn(aResponse().withStatus(200)));

        ConnectionTester.Result result = test("rest:idm", props("idm.test.rest.idm.base-url", wireMock.baseUrl() + "/IDMProv",
                "idm.test.rest.idm.username", "tester", "idm.test.rest.idm.password", HTTP_PASSWORD));

        assertThat(result.ok()).as(result.message()).isTrue();
        assertThat(result.message()).isEqualTo("Connected · HTTP 200");
        wireMock.verify(getRequestedFor(urlPathEqualTo("/IDMProv")).withHeader("Authorization", equalTo(
                "Basic " + java.util.Base64.getEncoder().encodeToString(("tester:" + HTTP_PASSWORD).getBytes()))));
    }

    @Test
    @DisplayName("REST: 401 is \"credentials rejected\"")
    void testRestRejected() {
        wireMock.stubFor(get(urlPathEqualTo("/IDMProv")).willReturn(aResponse().withStatus(401)));

        ConnectionTester.Result result = test("rest:idm", props("idm.test.rest.idm.base-url", wireMock.baseUrl() + "/IDMProv",
                "idm.test.rest.idm.username", "tester", "idm.test.rest.idm.password", HTTP_PASSWORD));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).startsWith("credentials rejected");
        assertNoSecret(result);
    }

    @Test
    @DisplayName("REST: a redirect is an answer and is not followed")
    void testRestRedirectNotFollowed() {
        wireMock.stubFor(get(urlPathEqualTo("/IDMProv"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "http://elsewhere.invalid/login")));

        ConnectionTester.Result result = test("rest:idm", props("idm.test.rest.idm.base-url", wireMock.baseUrl() + "/IDMProv"));

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).isEqualTo("Connected · HTTP 302");
    }

    @Test
    @DisplayName("REST with OAuth: the token is fetched; a rejection is \"credentials rejected\"")
    void testRestOAuth() {
        wireMock.stubFor(post(urlEqualTo("/token")).willReturn(okJson(
                "{\"access_token\":\"fake-access-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        Map<String, String> properties = props("idm.test.rest.sap.base-url", wireMock.baseUrl() + "/api",
                "idm.test.rest.sap.use-oauth", "true", "idm.test.rest.sap.oauth-client-name", "sapclient",
                "spring.security.oauth2.client.registration.sapclient.client-id", "fake-client-id",
                "spring.security.oauth2.client.registration.sapclient.client-secret", CLIENT_SECRET,
                "spring.security.oauth2.client.registration.sapclient.authorization-grant-type", "client_credentials",
                "spring.security.oauth2.client.provider.sapclient.token-uri", wireMock.baseUrl() + "/token");

        ConnectionTester.Result ok = test("rest:sap", properties);
        assertThat(ok.ok()).as(ok.message()).isTrue();
        assertThat(ok.message()).isEqualTo("Connected · token received");
        assertThat(ok.message()).doesNotContain("fake-access-token");

        wireMock.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_client\"}")));
        ConnectionTester.Result rejected = test("rest:sap", properties);
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.message()).startsWith("credentials rejected");
        assertNoSecret(rejected);
    }

    // --- SOAP -------------------------------------------------------------------------------------

    @Test
    @DisplayName("SOAP: the WSDL is fetched with the credentials; 401 is \"credentials rejected\"")
    void testSoapWsdl() {
        wireMock.stubFor(get(urlEqualTo("/IDMProv/role/service?wsdl"))
                .willReturn(aResponse().withStatus(200).withBody("<definitions/>")));
        Map<String, String> properties = props("idm.test.soap.url", wireMock.baseUrl() + "/IDMProv/role/service",
                "idm.test.soap.username", "tester", "idm.test.soap.password", HTTP_PASSWORD);

        ConnectionTester.Result ok = test("soap", properties);
        assertThat(ok.ok()).as(ok.message()).isTrue();
        assertThat(ok.message()).isEqualTo("Connected · WSDL received");
        wireMock.verify(getRequestedFor(urlEqualTo("/IDMProv/role/service?wsdl")).withHeader("Authorization",
                equalTo("Basic " + java.util.Base64.getEncoder().encodeToString(("tester:" + HTTP_PASSWORD).getBytes()))));

        wireMock.stubFor(get(urlEqualTo("/IDMProv/role/service?wsdl")).willReturn(aResponse().withStatus(401)));
        ConnectionTester.Result rejected = test("soap", properties);
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.message()).startsWith("credentials rejected");
        assertNoSecret(rejected);
    }

    @Test
    @DisplayName("Messages are masked with the secret values")
    void testMasked() {
        // the password is part of the URL here, so an unreachable message would show it unmasked
        ConnectionTester.Result result = tester.test(LabSettings.SystemRef.parse("rest:idm"),
                props("idm.test.rest.idm.base-url", "http://user:fake-url-secret@127.0.0.1:1/x"), temp,
                Locale.ENGLISH, mask("fake-url-secret"));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).doesNotContain("fake-url-secret");
    }
}
