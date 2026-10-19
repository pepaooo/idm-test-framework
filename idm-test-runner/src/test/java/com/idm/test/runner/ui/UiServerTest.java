package com.idm.test.runner.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
        assertThat(get("/", Map.of("Cookie", "idmtest_token=" + wrong)).status()).isEqualTo(403);
        assertThat(get("/?token=", Map.of()).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("The token in the URL sets an HttpOnly, SameSite=Strict cookie and redirects to the path without it")
    void testTokenSetsCookie() throws Exception {
        Response first = get("/?token=" + server.token(), Map.of());
        assertThat(first.status()).isEqualTo(302);
        assertThat(first.header("Location")).isIn("/", "http://127.0.0.1:" + server.port() + "/");
        String cookie = first.header("Set-Cookie");
        assertThat(cookie).startsWith("idmtest_token=" + server.token() + ";")
                .containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Strict").contains("Path=/");

        Response page = get("/", Map.of("Cookie", "idmtest_token=" + server.token()));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("<title>idmtest</title>");
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
        String cookie = "idmtest_token=" + server.token();
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
        String cookie = "idmtest_token=" + server.token();
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

    // --- api ----------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/health answers the runner version")
    void testHealth() throws Exception {
        Response health = get("/api/health", Map.of("Cookie", "idmtest_token=" + server.token()));
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.header("Content-Type")).startsWith("application/json");
        assertThat(health.body()).matches("\\{\"version\":\"[^\"]+\"}");
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

    private record Response(int status, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private static Response get(String path, Map<String, String> headers) throws IOException {
        return request("GET", path, "127.0.0.1:" + server.port(), headers);
    }

    private static Response request(String method, String path, String host, Map<String, String> headers)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n");
            headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            head.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int split = raw.indexOf("\r\n\r\n");
            List<String> lines = new ArrayList<>(List.of(raw.substring(0, split).split("\r\n")));
            int status = Integer.parseInt(lines.removeFirst().split(" ")[1]);
            Map<String, String> parsed = new LinkedHashMap<>();
            for (String line : lines) {
                int colon = line.indexOf(':');
                parsed.putIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
            String body = raw.substring(split + 4);
            if ("chunked".equalsIgnoreCase(parsed.get("transfer-encoding"))) {
                body = unchunk(body);
            }
            return new Response(status, parsed, body);
        }
    }

    private static String unchunk(String body) {
        StringBuilder result = new StringBuilder();
        int at = 0;
        while (true) {
            int end = body.indexOf("\r\n", at);
            int size = Integer.parseInt(body.substring(at, end).trim(), 16);
            if (size == 0) {
                return result.toString();
            }
            result.append(body, end + 2, end + 2 + size);
            at = end + 2 + size + 2;
        }
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
