package com.idm.test.runner;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
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
 * Unless {@code --verbose}, the console appenders are detached, so framework logging goes to the
 * file only and the console shows progress. Spring Boot re-initialises Logback when it starts,
 * which removes every appender, so {@link #listener()} attaches the file again right after.
 */
final class RunLog implements AutoCloseable {

    static final String APPENDER_NAME = "IDMTEST_RUN_LOG";
    private static final String PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n";

    private final Path file;
    private final boolean verbose;
    private final List<Appender<ILoggingEvent>> detached = new ArrayList<>();
    private FileAppender<ILoggingEvent> appender;

    private RunLog(Path file, boolean verbose) {
        this.file = file;
        this.verbose = verbose;
    }

    /** Starts logging to {@code file}; with {@code verbose} false, the console no longer shows the log. */
    static RunLog start(Path file, boolean verbose) {
        RunLog log = new RunLog(file, verbose);
        log.attach();
        return log;
    }

    /** Re-attaches the file (and detaches the console again) once Spring Boot has configured logging. */
    ApplicationListener<ApplicationEnvironmentPreparedEvent> listener() {
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
        if (appender == null || !appender.isStarted() || root.getAppender(APPENDER_NAME) != appender) {
            if (appender != null) {
                appender.stop();
            }
            appender = newAppender(root.getLoggerContext());
            root.addAppender(appender);
        }
        if (!verbose) {
            for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
                Appender<ILoggingEvent> candidate = it.next();
                if (candidate instanceof ConsoleAppender<ILoggingEvent>) {
                    root.detachAppender(candidate);
                    detached.add(candidate);
                }
            }
        }
    }

    private FileAppender<ILoggingEvent> newAppender(LoggerContext context) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        FileAppender<ILoggingEvent> file = new FileAppender<>();
        file.setContext(context);
        file.setName(APPENDER_NAME);
        file.setFile(this.file.toAbsolutePath().toString());
        file.setAppend(true);
        file.setEncoder(encoder);
        file.start();
        return file;
    }

    /** Detaches and closes the file, and gives the console back the appenders this run took. */
    @Override
    public synchronized void close() {
        Logger root = root();
        if (appender != null) {
            root.detachAppender(appender);
            appender.stop();
            appender = null;
        }
        for (Appender<ILoggingEvent> console : detached) {
            if (console.isStarted() && root.getAppender(console.getName()) == null) {
                root.addAppender(console);
            }
        }
        detached.clear();
    }

    private static Logger root() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
