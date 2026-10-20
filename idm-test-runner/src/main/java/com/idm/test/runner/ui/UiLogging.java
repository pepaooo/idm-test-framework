package com.idm.test.runner.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The workbench's logging, kept its own around runs. Logging is JVM-wide, and a run's profile may set
 * {@code logging.level.*} (DEBUG for Spring MVC or Tomcat, say), which would then log the workbench's
 * own requests — a lab save, the session cookie — to the console or into the run's {@code run.log}.
 * <ul>
 *   <li>{@link #install()} (the workbench process only): a Logback turbo filter denies every event below
 *       INFO of the {@link #QUIET} loggers before any level is looked at, so no level set at any moment
 *       lets one through; a reset-resistant context listener puts the filter back within any reset of
 *       Logback (which empties the turbo filters).</li>
 *   <li>A workbench run does not let Spring Boot initialise logging (that would reset Logback in the
 *       middle of the workbench's requests); {@link #applyRunLevels} applies the profile's
 *       {@code logging.level.*} and {@code logging.group.*} itself, after keeping every level as it was,
 *       and {@link #restore()} puts them back after the run.</li>
 * </ul>
 * A CLI run uses none of this.
 */
public final class UiLogging {

    /** The loggers of the workbench's request handling: never below INFO in the workbench's process. */
    static final List<String> QUIET = List.of("com.idm.test.runner.ui", "org.springframework.web",
            "org.springframework.http", "org.springframework.security", "org.springframework.boot.web",
            "org.springframework.boot.webmvc", "org.springframework.boot.servlet", "org.springframework.boot.tomcat",
            "org.apache.coyote", "org.apache.tomcat", "org.apache.catalina", "org.apache.juli");

    /** Spring Boot's built-in logger groups, as {@code logging.level.web} / {@code .sql} name them. */
    private static final Map<String, List<String>> BUILT_IN_GROUPS = Map.of(
            "web", List.of("org.springframework.core.codec", "org.springframework.http", "org.springframework.web",
                    "org.springframework.boot.actuate.endpoint.web",
                    "org.springframework.boot.web.servlet.ServletContextInitializerBeans"),
            "sql", List.of("org.springframework.jdbc.core", "org.hibernate.SQL", "org.jooq.tools.LoggerListener"));

    private static final QuietFilter FILTER = new QuietFilter();
    /** Puts {@link #FILTER} back when Logback is reset. */
    static final LoggerContextListener RESET_GUARD = new ResetGuard();

    /** Every logger's level before the current run applied its own; null when no run changed them. */
    private volatile Map<String, Level> before;

    /** Installs the guard in this process's Logback (idempotent) and raises the {@link #QUIET} loggers to INFO. */
    public static synchronized void install() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        if (!context.getCopyOfListenerList().contains(RESET_GUARD)) {
            context.addListener(RESET_GUARD);
        }
        addFilter(context);
        quiet(context);
    }

    /** Takes the guard out again (tests of a CLI run in the same JVM). */
    static synchronized void uninstall() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.removeListener(RESET_GUARD);
            context.getTurboFilterList().remove(FILTER);
        }
    }

    private static void addFilter(LoggerContext context) {
        if (!context.getTurboFilterList().contains(FILTER)) {
            FILTER.setContext(context);
            FILTER.start();
            context.addTurboFilter(FILTER);
        }
    }

    /** Raises every {@link #QUIET} logger, and any descendant given a level, below INFO to INFO. */
    private static void quiet(LoggerContext context) {
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

    static boolean quieted(String name) {
        for (String prefix : QUIET) {
            if (name.startsWith(prefix) && (name.length() == prefix.length() || name.charAt(prefix.length()) == '.')) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps every logger's level, then applies the run's {@code logging.level.*} from {@code environment}
     * (groups of {@code logging.group.*} and Boot's {@code web} and {@code sql} expanded) as Spring Boot
     * would, the {@link #QUIET} loggers held at INFO or above.
     */
    public void applyRunLevels(Environment environment) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        snapshot();
        Binder binder = Binder.get(environment);
        Map<String, List<String>> groups = new HashMap<>(BUILT_IN_GROUPS);
        binder.bind("logging.group", Bindable.mapOf(String.class, String[].class)).orElse(Map.of())
                .forEach((group, members) -> groups.put(group, List.of(members)));
        binder.bind("logging.level", Bindable.mapOf(String.class, String.class)).orElse(Map.of())
                .forEach((name, value) -> {
                    Level level = level(value);
                    if (level == null) {
                        return;
                    }
                    for (String logger : groups.getOrDefault(name, List.of(name))) {
                        context.getLogger(logger.equalsIgnoreCase("root") ? org.slf4j.Logger.ROOT_LOGGER_NAME : logger)
                                .setLevel(level);
                    }
                });
        install();
    }

    /** Keeps every logger's level, for {@link #restore()}. */
    void snapshot() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            Map<String, Level> levels = new LinkedHashMap<>();
            for (Logger logger : context.getLoggerList()) {
                levels.put(logger.getName(), logger.getLevel());
            }
            before = levels;
        }
    }

    /** Puts every logger's level back as it was before the run (a logger made since inherits again). */
    public void restore() {
        Map<String, Level> levels = before;
        before = null;
        if (levels == null || !(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        for (Logger logger : context.getLoggerList()) {
            Level level = levels.get(logger.getName());
            if (level != null || !logger.getName().equals(org.slf4j.Logger.ROOT_LOGGER_NAME)) {
                logger.setLevel(level);
            }
        }
        install();
    }

    /** A {@code logging.level} value as Spring Boot reads it ({@code off} may arrive as YAML's false). */
    private static Level level(String value) {
        String text = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        return switch (text) {
            case "FALSE", "OFF" -> Level.OFF;
            case "FATAL" -> Level.ERROR;
            case "TRACE", "DEBUG", "INFO", "WARN", "ERROR" -> Level.toLevel(text);
            default -> null;
        };
    }

    /** Denies the {@link #QUIET} loggers' events below INFO, before their levels are consulted. */
    static final class QuietFilter extends TurboFilter {

        @Override
        public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params,
                                  Throwable t) {
            if (level == null || level.isGreaterOrEqual(Level.INFO) || logger == null) {
                return FilterReply.NEUTRAL;
            }
            return quieted(logger.getName()) ? FilterReply.DENY : FilterReply.NEUTRAL;
        }
    }

    /** Re-adds the filter as part of every reset of Logback; survives resets itself. */
    private static final class ResetGuard implements LoggerContextListener {

        @Override
        public boolean isResetResistant() {
            return true;
        }

        @Override
        public void onStart(LoggerContext context) {
            addFilter(context);
        }

        @Override
        public void onReset(LoggerContext context) {
            addFilter(context);
        }

        @Override
        public void onStop(LoggerContext context) {
        }

        @Override
        public void onLevelChange(Logger logger, Level level) {
        }
    }
}
