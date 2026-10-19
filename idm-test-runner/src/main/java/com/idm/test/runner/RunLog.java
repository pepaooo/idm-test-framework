package com.idm.test.runner;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.LayoutBase;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The run's {@code run.log}: a Logback file appender on the root logger for the length of one run.
 * Every line it writes, stack traces included, passes through the run's {@link SecretMask}, so a
 * {@code secrets.env} value an exception quotes never reaches the file (ITF-23).
 * <p>
 * While the run lasts, the run's appenders are the only log output: every other appender, on the
 * root logger and on any other logger (a console, or a file a user's {@code logging.*} settings
 * added), is detached, and given back on {@link #close()}. With {@code --verbose} a console appender of
 * the run's, masked the same way, echoes the log; without it the console shows progress only.
 * Spring Boot re-initialises Logback when it starts, which removes every appender, so
 * {@link #listener()} attaches the run's appenders again right after.
 */
public final class RunLog implements AutoCloseable {

    static final String APPENDER_NAME = "IDMTEST_RUN_LOG";
    static final String CONSOLE_NAME = "IDMTEST_RUN_CONSOLE";
    private static final String PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n";

    private final Path file;
    private final boolean verbose;
    private final SecretMask mask;
    private final List<Detached> detached = new ArrayList<>();
    private FileAppender<ILoggingEvent> appender;
    private ConsoleAppender<ILoggingEvent> console;

    private RunLog(Path file, boolean verbose, SecretMask mask) {
        this.file = file;
        this.verbose = verbose;
        this.mask = mask;
    }

    /** Starts logging to {@code file}; with {@code verbose}, the console echoes the (masked) log. */
    public static RunLog start(Path file, boolean verbose, SecretMask mask) {
        RunLog log = new RunLog(file, verbose, mask);
        log.attach();
        return log;
    }

    /** Re-attaches the run's appenders once Spring Boot has configured logging. */
    public ApplicationListener<ApplicationEnvironmentPreparedEvent> listener() {
        // A class, not a lambda, so Spring can see the event type and calls it for this event only.
        return new ApplicationListener<>() {
            @Override
            public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
                attach();
            }
        };
    }

    synchronized void attach() {
        Logger root = root();
        LoggerContext context = root.getLoggerContext();
        for (Logger logger : context.getLoggerList()) {
            List<Appender<ILoggingEvent>> others = new ArrayList<>();
            for (Iterator<Appender<ILoggingEvent>> it = logger.iteratorForAppenders(); it.hasNext(); ) {
                Appender<ILoggingEvent> candidate = it.next();
                if (candidate != appender && candidate != console) {
                    others.add(candidate);
                }
            }
            for (Appender<ILoggingEvent> other : others) {
                logger.detachAppender(other);
                detached.add(new Detached(logger, other));
            }
        }
        if (!attached(root, appender)) {
            if (appender != null) {
                appender.stop();
            }
            FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
            fileAppender.setFile(file.toAbsolutePath().toString());
            fileAppender.setAppend(true);
            appender = configure(fileAppender, APPENDER_NAME, context);
            root.addAppender(appender);
        }
        if (verbose && !attached(root, console)) {
            if (console != null) {
                console.stop();
            }
            console = configure(new ConsoleAppender<>(), CONSOLE_NAME, context);
            root.addAppender(console);
        }
    }

    private static boolean attached(Logger root, Appender<ILoggingEvent> candidate) {
        return candidate != null && candidate.isStarted() && root.getAppender(candidate.getName()) == candidate;
    }

    private <A extends OutputStreamAppender<ILoggingEvent>> A configure(A target, String name, LoggerContext context) {
        PatternLayout pattern = new PatternLayout();
        pattern.setContext(context);
        pattern.setPattern(PATTERN);
        pattern.start();

        MaskingLayout layout = new MaskingLayout(pattern, mask);
        layout.setContext(context);
        layout.start();

        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
        encoder.setContext(context);
        encoder.setLayout(layout);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        target.setContext(context);
        target.setName(name);
        target.setEncoder(encoder);
        target.start();
        return target;
    }

    /** Detaches and closes the run's appenders, and gives the console back the appenders this run took. */
    @Override
    public synchronized void close() {
        Logger root = root();
        stop(root, appender);
        stop(root, console);
        appender = null;
        console = null;
        for (Detached previous : detached) {
            Appender<ILoggingEvent> other = previous.appender();
            if (other.isStarted() && previous.logger().getAppender(other.getName()) == null) {
                previous.logger().addAppender(other);
            }
        }
        detached.clear();
    }

    private static void stop(Logger root, Appender<ILoggingEvent> own) {
        if (own != null) {
            root.detachAppender(own);
            own.stop();
        }
    }

    private static Logger root() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    /** An appender this run took from a logger, to give back when it ends. */
    private record Detached(Logger logger, Appender<ILoggingEvent> appender) {
    }

    /** A pattern layout whose formatted event (message and stack trace alike) is masked. */
    static final class MaskingLayout extends LayoutBase<ILoggingEvent> {

        private final PatternLayout pattern;
        private final SecretMask mask;

        MaskingLayout(PatternLayout pattern, SecretMask mask) {
            this.pattern = pattern;
            this.mask = mask;
        }

        @Override
        public String doLayout(ILoggingEvent event) {
            return mask.apply(pattern.doLayout(event));
        }
    }
}
