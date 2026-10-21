package com.idm.test.runner.ui.api;

import com.idm.test.engine.spi.Message;
import com.idm.test.runner.ui.UiSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * The workspace is the session's, shared by every tab: when one tab opens another workspace, a tab
 * still showing the first must not save, change the lab of, run or read the second without knowing.
 * So each request of a workspace-scoped endpoint ({@link #PATHS}: files, lab, runs, reports) says
 * which workspace it is for — header {@value #HEADER} (the URL-encoded path) on a fetch, query
 * parameter {@value #PARAMETER} where a browser cannot set headers (EventSource, a link) — and is
 * refused with 409 {@code ui.workspace.switched} when that is not the session's current workspace
 * (compared as real paths) or is missing. While no workspace is open, the endpoints answer
 * {@code ui.workspace.none} themselves. The start page's endpoints, health, the schema and
 * validation do not depend on the workspace and are not checked.
 */
public final class WorkspaceGuard implements HandlerInterceptor {

    public static final String HEADER = "X-Idmtest-Workspace";
    public static final String PARAMETER = "workspace";
    /** The workspace-scoped paths, as Spring MVC path patterns. */
    public static final List<String> PATHS = List.of("/api/files", "/api/files/**", "/api/lab", "/api/lab/**",
            "/api/runs", "/api/runs/**", "/reports/**");

    private final UiSession session;

    public WorkspaceGuard(UiSession session) {
        this.session = session;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Path current = session.workspace();
        if (current == null) {
            return true;
        }
        String claimed = claimed(request);
        if (claimed == null || !same(claimed, current)) {
            Path name = current.getFileName();
            throw new ApiError(HttpStatus.CONFLICT, Message.of("runner.ui.workspace.switched",
                    name == null ? current.toString() : name.toString()));
        }
        return true;
    }

    /** The workspace the request is for: the header (decoded), else the query parameter; null when neither. */
    private static String claimed(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null) {
            try {
                return URLDecoder.decode(header, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return request.getParameter(PARAMETER);
    }

    private static boolean same(String claimed, Path current) {
        if (claimed.isBlank()) {
            return false;
        }
        try {
            Path path = Path.of(claimed);
            return path.isAbsolute() && real(path).equals(real(current));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** The real path, or the normalized absolute one when it cannot be resolved (gone, unreadable). */
    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
