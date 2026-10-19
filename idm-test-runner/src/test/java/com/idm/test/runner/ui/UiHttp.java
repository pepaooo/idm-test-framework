package com.idm.test.runner.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plain HTTP/1.1 over a socket to a workbench, so a test controls every header (Host included) and
 * sees redirects as they are. Shared by the workbench's server and API tests.
 */
public final class UiHttp {

    private UiHttp() {
    }

    /** A response: status, headers by lower-case name (first value), and the body as UTF-8. */
    public record Response(int status, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** The session cookie of {@code server}, as a {@code Cookie} header value. */
    public static String cookie(UiServer server) {
        return TokenFilter.COOKIE_PREFIX + server.port() + "=" + server.token();
    }

    /** A GET with the session cookie. */
    public static Response get(UiServer server, String path) throws IOException {
        return get(server, path, Map.of());
    }

    /** A GET with the session cookie and {@code headers}. */
    public static Response get(UiServer server, String path, Map<String, String> headers) throws IOException {
        return send(server, "GET", path, null, headers);
    }

    /** A request with the session cookie and, unless null, a body (JSON unless a header says otherwise). */
    public static Response send(UiServer server, String method, String path, String json) throws IOException {
        return send(server, method, path, json, Map.of());
    }

    /** As {@link #send(UiServer, String, String, String)}, with more headers. */
    public static Response send(UiServer server, String method, String path, String json,
                                Map<String, String> headers) throws IOException {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("Cookie", cookie(server));
        all.putAll(headers);
        return request(method, path, "127.0.0.1:" + server.port(), all, json, server.port());
    }

    /** Exactly the given headers (no cookie unless among them), to {@code port}. */
    public static Response request(String method, String path, String host, Map<String, String> headers,
                                   String json, int port) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(30_000);
            byte[] body = json == null ? null : json.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n");
            headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            if (body != null) {
                if (headers.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
                    head.append("Content-Type: application/json\r\n");
                }
                head.append("Content-Length: ").append(body.length).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] raw = in.readAllBytes();
            int split = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), 0);
            List<String> lines = new ArrayList<>(List.of(
                    new String(raw, 0, split, StandardCharsets.ISO_8859_1).split("\r\n")));
            int status = Integer.parseInt(lines.removeFirst().split(" ")[1]);
            Map<String, String> parsed = new LinkedHashMap<>();
            for (String line : lines) {
                int colon = line.indexOf(':');
                parsed.putIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
            byte[] rest = java.util.Arrays.copyOfRange(raw, split + 4, raw.length);
            if ("chunked".equalsIgnoreCase(parsed.get("transfer-encoding"))) {
                rest = unchunk(rest);
            }
            return new Response(status, parsed, new String(rest, StandardCharsets.UTF_8));
        }
    }

    private static byte[] unchunk(byte[] bytes) {
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        byte[] crlf = {'\r', '\n'};
        int at = 0;
        while (true) {
            int end = indexOf(bytes, crlf, at);
            int size = Integer.parseInt(new String(bytes, at, end - at, StandardCharsets.US_ASCII).trim(), 16);
            if (size == 0) {
                return result.toByteArray();
            }
            result.write(bytes, end + 2, size);
            at = end + 2 + size + 2;
        }
    }

    private static int indexOf(byte[] bytes, byte[] what, int from) {
        outer:
        for (int i = from; i <= bytes.length - what.length; i++) {
            for (int j = 0; j < what.length; j++) {
                if (bytes[i + j] != what[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IllegalStateException("malformed HTTP response");
    }
}
