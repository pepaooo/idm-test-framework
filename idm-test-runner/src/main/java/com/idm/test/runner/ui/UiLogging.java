package com.idm.test.runner.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The workbench's logging, kept its own around runs. A run starts Spring Boot in the workbench's JVM,
 * and Boot's logging is JVM-wide: the run resets Logback and applies the profile's
 * {@code logging.level.*}, which would then log the workbench's own requests (a lab save, the session
 * cookie) to the console, or into the run's {@code run.log}. So the loggers of the web server and of
 * the workbench itself are held at {@code INFO} or above whatever a profile or environment variable
 * says ({@link #quiet}), during a run as well, and after each run the workbench's logging is set up
 * again as it was started ({@link #restore}).
 */
public final class UiLogging {

    /** The loggers of the workbench's request handling: never below INFO. */
    static final List<String> QUIET = List.of("com.idm.test.runner.ui", "org.springframework.web",
            "org.springframework.http", "org.springframework.security", "org.springframework.boot.web",
            "org.springframework.boot.webmvc", "org.springframework.boot.servlet", "org.springframework.boot.tomcat",
            "org.apache.coyote", "org.apache.tomcat", "org.apache.catalina", "org.apache.juli");

    private UiLogging() {
    }

    /** Raises every {@link #QUIET} logger (and any of their descendants given a level) below INFO to INFO. */
    public static void quiet() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        for (String name : QUIET) {
            Logger logger = context.getLogger(name);
            if (!logger.getEffectiveLevel().isGreaterOrEqual(Level.INFO)) {
                logger.setLevel(Level.INFO);
            }
        }
        for (Logger logger : context.getLoggerList()) {
            Level level = logger.getLevel();
            if (level != null && !level.isGreaterOrEqual(Level.INFO) && quieted(logger.getName())) {
                logger.setLevel(Level.INFO);
            }
        }
    }

    private static boolean quieted(String name) {
        for (String prefix : QUIET) {
            if (name.equals(prefix) || name.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the JVM's logging up again from the workbench's own {@code environment} — Boot's default
     * configuration, its {@code logging.level.*} — and {@link #quiet}s it: what a run's Spring start
     * changed is gone.
     */
    public static void restore(ConfigurableEnvironment environment) {
        LoggingSystem system = LoggingSystem.get(UiLogging.class.getClassLoader());
        system.cleanUp();
        system.beforeInitialize();
        system.initialize(new LoggingInitializationContext(environment), null, LogFile.get(environment));
        Binder.get(environment).bind("logging.level", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of()).forEach((name, level) -> {
                    try {
                        system.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME.equalsIgnoreCase(name) ? null : name,
                                LogLevel.valueOf(level.strip().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        // not a level (Boot itself refused it when the workbench started)
                    }
                });
        quiet();
    }
}
