package com.idm.test.runner.ui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * The workbench's only door. A request passes when its {@code Host} is {@code 127.0.0.1:<port>} or
 * {@code localhost:<port>}, its {@code Origin} (when it has one) is {@code http://} one of those, its
 * {@code Sec-Fetch-Site} (when it has one) is {@code same-origin} or {@code none}, and it carries the
 * session token: in the {@code idmtest_token_<port>} cookie (one per port, so two workbenches do not
 * replace each other's), or once in {@code ?token=}, which sets the cookie ({@code HttpOnly},
 * {@code SameSite=Strict}) and redirects to the same address without the token. Everything else is
 * 403. A response that passes may not be framed or content-sniffed. Tokens are compared in constant
 * time and never logged.
 */
final class TokenFilter extends OncePerRequestFilter {

    static final String COOKIE_PREFIX = "idmtest_token_";
    private static final String PARAMETER = "token";

    private final byte[] token;

    TokenFilter(String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int port = request.getLocalPort();
        if (!isLocal(request.getHeader("Host"), port, "") || !originAllowed(request.getHeader("Origin"), port)
                || !fetchSiteAllowed(request.getHeader("Sec-Fetch-Site"))) {
            forbid(response);
            return;
        }
        String cookieName = COOKIE_PREFIX + port;
        Query query = Query.of(request.getQueryString());
        if (!query.tokens().isEmpty()) {
            if (!query.tokens().stream().allMatch(this::matches)) {
                forbid(response);
                return;
            }
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Set-Cookie", ResponseCookie.from(cookieName, new String(token, StandardCharsets.UTF_8))
                    .path("/").httpOnly(true).sameSite("Strict").build().toString());
            response.setHeader("Location", location(request.getRequestURI(), query.rest()));
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            return;
        }
        if (!cookieMatches(request.getCookies(), cookieName)) {
            forbid(response);
            return;
        }
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }

    private static boolean isLocal(String host, int port, String scheme) {
        return host != null && (host.equals(scheme + "127.0.0.1:" + port) || host.equals(scheme + "localhost:" + port));
    }

    private static boolean originAllowed(String origin, int port) {
        return origin == null || isLocal(origin, port, "http://");
    }

    /** A browser's own word for where the request comes from: only this origin, or the user (typed, bookmark). */
    private static boolean fetchSiteAllowed(String site) {
        return site == null || site.equals("same-origin") || site.equals("none");
    }

    private boolean cookieMatches(Cookie[] cookies, String cookieName) {
        if (cookies == null) {
            return false;
        }
        boolean found = false;
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName()) && matches(cookie.getValue())) {
                found = true;
            }
        }
        return found;
    }

    private boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), token);
    }

    /** The request's own path (one leading slash, so never another host) and its other parameters. */
    private static String location(String path, String rest) {
        String own = "/" + path.replaceFirst("^/+", "");
        return rest.isEmpty() ? own : own + "?" + rest;
    }

    private static void forbid(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("Forbidden\n");
    }

    /** The {@code token} values of a query string, and the rest of it as it was. */
    private record Query(List<String> tokens, String rest) {

        static Query of(String query) {
            if (query == null || query.isEmpty()) {
                return new Query(List.of(), "");
            }
            List<String> tokens = new ArrayList<>();
            List<String> rest = new ArrayList<>();
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                String name = decode(equals < 0 ? pair : pair.substring(0, equals));
                if (PARAMETER.equals(name)) {
                    tokens.add(equals < 0 ? "" : decode(pair.substring(equals + 1)));
                } else if (!pair.isEmpty()) {
                    rest.add(pair);
                }
            }
            return new Query(tokens, String.join("&", rest));
        }

        private static String decode(String text) {
            try {
                return URLDecoder.decode(text, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return text;
            }
        }
    }
}
