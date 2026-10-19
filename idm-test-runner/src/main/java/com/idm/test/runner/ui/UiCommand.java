package com.idm.test.runner.ui;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.i18n.ReportLocale;
import com.idm.test.engine.spi.Message;
import com.idm.test.runner.CommandLine;
import com.idm.test.runner.ExitCodes;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * {@code idmtest ui}: starts the workbench on {@code 127.0.0.1}, prints its address (with the
 * session token) and opens the browser there, then serves until Ctrl+C. The address goes to the
 * console only: the token is in it, and nothing writes it to a log.
 */
public final class UiCommand {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UiCommand() {
    }

    public static int run(CommandLine line, PrintStream out, PrintStream err) {
        Locale locale;
        try {
            locale = ReportLocale.resolve(line.locale(), System.getenv("IDM_TEST_LOCALE"),
                    System.getProperty("idm.test.locale"));
        } catch (ReportLocale.UnsupportedLocaleException e) {
            err.println(e.getMessage());
            return ExitCodes.CANNOT_START;
        }
        CountDownLatch closed = new CountDownLatch(1);
        UiServer server;
        try {
            server = start(line.port() != null ? line.port() : 0, locale,
                    line.workspaceGiven() ? line.workspace() : null, closed::countDown);
        } catch (RuntimeException e) {
            err.println(Messages.render(Message.of("runner.ui.startFailed", describe(e)), locale));
            if (line.verbose()) {
                e.printStackTrace(err);
            }
            return ExitCodes.CANNOT_START;
        }
        printStarted(out, server.port(), server.token(), locale);
        BrowserOpener.open(URI.create(url(server.port(), server.token())));
        try {
            // Ctrl+C runs Spring's shutdown hook, which closes the context and so releases this.
            closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ExitCodes.OK;
    }

    /** Starts the workbench on {@code port} (0: a free one) of {@code 127.0.0.1}, with the start page. */
    public static UiServer start(int port, Locale locale) {
        return start(port, locale, null);
    }

    /** As {@link #start(int, Locale)}, opening {@code workspace} unless it is null. */
    public static UiServer start(int port, Locale locale, Path workspace) {
        return start(port, locale, workspace, () -> {
        });
    }

    static UiServer start(int port, Locale locale, Path workspace, Runnable onClosed) {
        String token = newToken();
        UiSession session = new UiSession(token, locale, workspace);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(UiServerConfig.class)
                .web(WebApplicationType.SERVLET)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .initializers(started -> started.getBeanFactory().registerSingleton("uiSession", session))
                .listeners(event -> {
                    if (event instanceof ContextClosedEvent) {
                        onClosed.run();
                    }
                })
                // Command-line arguments beat every other source: the built-in "web-application-type: none",
                // and SPRING_CONFIG_LOCATION / SPRING_CONFIG_ADDITIONAL_LOCATION. Only the runner's built-in
                // application.yml is read: no ./application.yml or ./config/ of the current folder.
                .run("--spring.main.web-application-type=servlet",
                        "--spring.config.location=optional:classpath:/",
                        "--spring.config.additional-location=",
                        // the console gets one line from runner.ui.startFailed, not Spring's failure report
                        "--logging.level.org.springframework.boot.diagnostics=OFF",
                        "--server.address=127.0.0.1",
                        "--server.port=" + port);
        int bound = ((WebServerApplicationContext) context).getWebServer().getPort();
        return new UiServer(bound, token, context::close, session);
    }

    static void printStarted(PrintStream out, int port, String token, Locale locale) {
        out.println(Messages.render(Message.of("runner.ui.started", url(port, token)), locale));
    }

    private static String url(int port, String token) {
        return "http://127.0.0.1:" + port + "/?token=" + token;
    }

    /** 32 random bytes, as 64 hex digits. */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * What names the problem of a start-up failure: a port in use says which port; otherwise the
     * deepest cause's first line.
     */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (true) {
            if (root instanceof PortInUseException) {
                return root.getMessage();
            }
            if (root.getCause() == null || root.getCause() == root) {
                break;
            }
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message.lines().findFirst().orElse("");
    }
}
