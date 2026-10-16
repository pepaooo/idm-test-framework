package com.idm.test.runner;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Stands in {@link java.sql.DriverManager} for a driver from a workspace's {@code drivers/} folder.
 * {@code DriverManager} hands a registered driver only to callers whose class loader can load the
 * driver's class, and a driver from a child class loader is visible to no one else; this class is
 * on the runner's own class path, so every caller can use it, and it forwards everything.
 */
final class DelegatingDriver implements Driver {

    private final Driver wrapped;

    DelegatingDriver(Driver wrapped) {
        this.wrapped = wrapped;
    }

    Driver wrapped() {
        return wrapped;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return wrapped.connect(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return wrapped.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return wrapped.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return wrapped.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return wrapped.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return wrapped.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return wrapped.getParentLogger();
    }

    @Override
    public String toString() {
        return "DelegatingDriver[" + wrapped.getClass().getName() + "]";
    }
}
