package com.idm.test.runner;

import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.engine.result.ReportUrls;
import com.idm.test.engine.spi.Message;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;

/**
 * The pre-flight driver check of {@code idmtest run}: before any scenario runs, every database the
 * selected scenarios use must have a JDBC driver. With a {@code driver-class-name}, that class must
 * load, as the connection pool loads it (the context class loader, then the runner's own);
 * otherwise some driver registered with {@link DriverManager} must accept the URL. Databases that
 * are not configured, or have no URL, are left to the real-environment guards.
 */
public final class DriverCheck {

    private DriverCheck() {
    }

    /**
     * @throws UsageException {@code runner.driver.missing} for the first used database, by name,
     *                        without a driver; its URL is shown without credentials
     */
    public static void check(DatabaseProperties databases, Set<String> usedDatabases, Path workspaceDrivers) {
        for (String name : new TreeSet<>(usedDatabases)) {
            DatabaseProperties.DatabaseConfig config = databases.getDatabases().get(name);
            if (config == null || config.getJdbcUrl() == null || config.getJdbcUrl().isBlank()) {
                continue;
            }
            if (!hasDriver(config)) {
                throw new UsageException(Message.of("runner.driver.missing",
                        ReportUrls.sanitise(config.getJdbcUrl()), name, workspaceDrivers.toString()));
            }
        }
    }

    private static boolean hasDriver(DatabaseProperties.DatabaseConfig config) {
        String className = config.getDriverClassName();
        if (className != null && !className.isBlank()) {
            return loads(className.strip(), Thread.currentThread().getContextClassLoader())
                    || loads(className.strip(), DriverCheck.class.getClassLoader());
        }
        try {
            DriverManager.getDriver(config.getJdbcUrl());
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean loads(String className, ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        try {
            return java.sql.Driver.class.isAssignableFrom(Class.forName(className, false, loader));
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
