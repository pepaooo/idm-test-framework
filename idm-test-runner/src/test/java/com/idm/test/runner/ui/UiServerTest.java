package com.idm.test.runner.ui;

import com.idm.test.runner.ui.UiHttp.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code idmtest ui}'s server, over plain HTTP/1.1 on a socket so the test controls every header
 * (Host included) and sees redirects as they are.
 */
@DisplayName("idmtest ui server")
class UiServerTest {

    private static UiServer server;

    @BeforeAll
    static void start() {
        server = UiCommand.start(0, Locale.ENGLISH);
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    // --- binding ------------------------------------------------------------------------------

    @Test
    @DisplayName("Should listen on 127.0.0.1 only")
    void testLoopbackOnly() throws Exception {
        InetAddress other = nonLoopbackAddress();
        Assumptions.assumeTrue(other != null, "this machine has no non-loopback IPv4 address");
        try (Socket loopback = new Socket()) {
            loopback.connect(new InetSocketAddress("127.0.0.1", server.port()), 2000);
        }
        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(other, server.port()), 2000);
            }
        }).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("A 32-byte hex token, never shown by toString")
    void testToken() {
        assertThat(server.token()).matches("[0-9a-f]{64}");
        assertThat(server.toString()).doesNotContain(server.token());
    }

    // --- token --------------------------------------------------------------------------------

    @Test
    @DisplayName("Should refuse a request without the token")
    void testNoToken() throws Exception {
        assertThat(get("/", Map.of()).status()).isEqualTo(403);
        assertThat(get("/api/health", Map.of()).status()).isEqualTo(403);
        assertThat(get("/index.html", Map.of()).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("Should refuse a wrong token in the URL or the cookie")
    void testWrongToken() throws Exception {
        String wrong = "0".repeat(64);
        assertThat(get("/?token=" + wrong, Map.of()).status()).isEqualTo(403);
        assertThat(get("/", Map.of("Cookie", cookieName() + "=" + wrong)).status()).isEqualTo(403);
        assertThat(get("/?token=", Map.of()).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("The token in the URL sets an HttpOnly, SameSite=Strict cookie and redirects to the path without it")
    void testTokenSetsCookie() throws Exception {
        Response first = get("/?token=" + server.token(), Map.of());
        assertThat(first.status()).isEqualTo(302);
        assertThat(first.header("Location")).isIn("/", "http://127.0.0.1:" + server.port() + "/");
        String cookie = first.header("Set-Cookie");
        assertThat(cookie).startsWith(cookieName() + "=" + server.token() + ";")
                .containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Strict").contains("Path=/");

        Response page = get("/", Map.of("Cookie", cookie()));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("<title>idmtest</title>");
        assertThat(get("/", Map.of("Cookie", "idmtest_token=" + server.token())).status())
                .as("the cookie of no port, or of another port, is not this workbench's").isEqualTo(403);
        assertThat(get("/", Map.of("Cookie", "idmtest_token_1=" + server.token())).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("The redirect keeps the path and the other parameters")
    void testRedirectKeepsOtherParameters() throws Exception {
        Response first = get("/index.html?a=1&token=" + server.token() + "&b=2", Map.of());
        assertThat(first.status()).isEqualTo(302);
        assertThat(first.header("Location")).endsWith("/index.html?a=1&b=2");
    }

    // --- host and origin ----------------------------------------------------------------------

    @Test
    @DisplayName("Should refuse another Host, even with the token")
    void testForeignHost() throws Exception {
        String cookie = cookie();
        assertThat(request("GET", "/", "evil.example", Map.of("Cookie", cookie)).status()).isEqualTo(403);
        assertThat(request("GET", "/?token=" + server.token(), "evil.example:" + server.port(), Map.of())
                .status()).isEqualTo(403);
        assertThat(request("GET", "/", "127.0.0.1:1", Map.of("Cookie", cookie)).status()).isEqualTo(403);
        assertThat(request("GET", "/", "localhost:" + server.port(), Map.of("Cookie", cookie)).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Should refuse a foreign Origin, even with the token")
    void testForeignOrigin() throws Exception {
        String cookie = cookie();
        Response foreign = request("POST", "/api/health", "127.0.0.1:" + server.port(),
                Map.of("Cookie", cookie, "Origin", "http://evil.example", "Content-Length", "0"));
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(request("GET", "/api/health", "127.0.0.1:" + server.port(),
                Map.of("Cookie", cookie, "Origin", "null")).status()).isEqualTo(403);
        Response own = request("POST", "/api/health", "127.0.0.1:" + server.port(),
                Map.of("Cookie", cookie, "Origin", "http://127.0.0.1:" + server.port(), "Content-Length", "0"));
        assertThat(own.status()).isNotEqualTo(403);
        assertThat(request("GET", "/api/health", "127.0.0.1:" + server.port(),
                Map.of("Cookie", cookie, "Origin", "http://localhost:" + server.port())).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Should refuse a request the browser marks as coming from another site")
    void testSecFetchSite() throws Exception {
        assertThat(get("/api/health", Map.of("Cookie", cookie(), "Sec-Fetch-Site", "same-site")).status())
                .isEqualTo(403);
        assertThat(get("/api/health", Map.of("Cookie", cookie(), "Sec-Fetch-Site", "cross-site")).status())
                .isEqualTo(403);
        assertThat(get("/api/health", Map.of("Cookie", cookie(), "Sec-Fetch-Site", "same-origin")).status())
                .isEqualTo(200);
        assertThat(get("/?token=" + server.token(), Map.of("Sec-Fetch-Site", "none")).status()).isEqualTo(302);
    }

    @Test
    @DisplayName("A served page may not be framed or content-sniffed")
    void testSecurityHeaders() throws Exception {
        for (String path : new String[] {"/", "/api/health"}) {
            Response page = get(path, Map.of("Cookie", cookie()));
            assertThat(page.status()).isEqualTo(200);
            assertThat(page.header("X-Frame-Options")).isEqualTo("DENY");
            assertThat(page.header("Content-Security-Policy")).isEqualTo("frame-ancestors 'none'");
            assertThat(page.header("X-Content-Type-Options")).isEqualTo("nosniff");
        }
    }

    @Test
    @DisplayName("Config locations from outside (system properties, like environment variables) are ignored")
    void testConfigLocationPinned() throws Exception {
        java.nio.file.Path extra = java.nio.file.Files.createTempFile("ui-extra", ".yml");
        java.nio.file.Files.writeString(extra, "server:\n  servlet:\n    context-path: /moved\n");
        System.setProperty("spring.config.location", "optional:file:" + extra);
        System.setProperty("spring.config.additional-location", "optional:file:" + extra);
        try (UiServer other = UiCommand.start(0, Locale.ENGLISH, null)) {
            Response health = request("GET", "/api/health", "127.0.0.1:" + other.port(),
                    Map.of("Cookie", "idmtest_token_" + other.port() + "=" + other.token()), other.port());
            assertThat(health.status()).isEqualTo(200);
        } finally {
            System.clearProperty("spring.config.location");
            System.clearProperty("spring.config.additional-location");
            java.nio.file.Files.deleteIfExists(extra);
        }
    }

    @Test
    @DisplayName("idmtest ui on a port in use says so on stderr and exits 2")
    void testPortInUse() throws Exception {
        try (java.net.ServerSocket taken = new java.net.ServerSocket()) {
            taken.bind(new InetSocketAddress("127.0.0.1", 0));
            ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
            int exit = UiCommand.run(com.idm.test.runner.CommandLine.parse("ui", "--port",
                            String.valueOf(taken.getLocalPort())),
                    new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                    new PrintStream(errBytes, true, StandardCharsets.UTF_8));
            assertThat(exit).isEqualTo(com.idm.test.runner.ExitCodes.CANNOT_START);
            assertThat(errBytes.toString(StandardCharsets.UTF_8)).startsWith("cannot start the workbench: ")
                    .contains(String.valueOf(taken.getLocalPort()));
            assertThat(outBytes.toString(StandardCharsets.UTF_8)).doesNotContain("token=");
        }
    }

    @Test
    @DisplayName("The session knows whether --workspace was given")
    void testSessionWorkspace() throws Exception {
        java.nio.file.Path ws = java.nio.file.Path.of("/some/ws");
        try (UiServer other = UiCommand.start(0, Locale.ENGLISH, ws)) {
            assertThat(other.session().workspace()).isEqualTo(ws);
            assertThat(other.session().toString()).doesNotContain(other.token());
        }
        assertThat(server.session().workspace()).isNull();
    }

    // --- api ----------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/health answers the runner version, git, the UI's language and no workspace")
    void testHealth() throws Exception {
        Response health = get("/api/health", Map.of("Cookie", cookie()));
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.header("Content-Type")).startsWith("application/json");
        assertThat(health.body())
                .matches("\\{\"version\":\"[^\"]+\",\"git\":(true|false),\"locale\":\"en\",\"workspace\":null}");
    }

    @Test
    @DisplayName("GET /api/health answers --locale and the --workspace the page opens straight into")
    void testHealthLocaleAndWorkspace() throws Exception {
        java.nio.file.Path ws = java.nio.file.Path.of("/some/ws").toAbsolutePath();
        try (UiServer other = UiCommand.start(0, Locale.of("es"), ws)) {
            Response health = request("GET", "/api/health", "127.0.0.1:" + other.port(),
                    Map.of("Cookie", UiHttp.cookie(other)), other.port());
            assertThat(health.status()).isEqualTo(200);
            assertThat(health.body()).contains("\"locale\":\"es\"")
                    .contains("\"workspace\":" + tools.jackson.databind.json.JsonMapper.builder().build()
                            .writeValueAsString(ws.toString()));
        }
    }

    // --- front end ----------------------------------------------------------------------------

    @Test
    @DisplayName("/ serves the built workbench app, not a placeholder")
    void testServesBuiltApp() throws Exception {
        Response page = get("/", Map.of("Cookie", cookie()));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.header("Content-Type")).startsWith("text/html");
        assertThat(page.body()).contains("<title>idmtest</title>", "<div id=\"root\"></div>")
                .containsPattern("<script type=\"module\" crossorigin src=\"/assets/index-[^\"]+\\.js\"></script>")
                .doesNotContain("Placeholder", "/src/main.tsx");
        java.util.regex.Matcher script = java.util.regex.Pattern.compile("src=\"(/assets/index-[^\"]+\\.js)\"")
                .matcher(page.body());
        assertThat(script.find()).isTrue();
        Response bundle = get(script.group(1), Map.of("Cookie", cookie()));
        assertThat(bundle.status()).isEqualTo(200);
        assertThat(bundle.header("Content-Type")).contains("javascript");
    }

    // --- command ------------------------------------------------------------------------------

    @Test
    @DisplayName("idmtest ui prints the URL with the token and how to stop")
    void testBanner() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        UiCommand.printStarted(out, 8123, "abc", Locale.ENGLISH);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains(
                "http://127.0.0.1:8123/?token=abc", "Press Ctrl+C to stop");
        bytes.reset();
        UiCommand.printStarted(out, 8123, "abc", Locale.of("es"));
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains(
                "http://127.0.0.1:8123/?token=abc", "Ctrl+C");
    }

    // --- helpers ------------------------------------------------------------------------------

    private static String cookieName() {
        return "idmtest_token_" + server.port();
    }

    private static String cookie() {
        return UiHttp.cookie(server);
    }

    private static Response get(String path, Map<String, String> headers) throws IOException {
        return request("GET", path, "127.0.0.1:" + server.port(), headers);
    }

    private static Response request(String method, String path, String host, Map<String, String> headers)
            throws IOException {
        return request(method, path, host, headers, server.port());
    }

    private static Response request(String method, String path, String host, Map<String, String> headers,
                                    int port) throws IOException {
        return UiHttp.request(method, path, host, headers, null, port);
    }

    private static InetAddress nonLoopbackAddress() throws IOException {
        for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!face.isUp() || face.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(face.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address;
                }
            }
        }
        return null;
    }
}
