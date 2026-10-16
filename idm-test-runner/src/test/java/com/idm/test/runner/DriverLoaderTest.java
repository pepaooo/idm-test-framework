package com.idm.test.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DriverLoader} against a copy of the H2 jar, loaded where the test's own H2 cannot be seen:
 * the context class loader refuses {@code org.h2.*}, and the class path's H2 driver is taken out of
 * {@link DriverManager} for the test, so a connection can only come from the copied jar.
 */
@DisplayName("Workspace JDBC drivers")
class DriverLoaderTest {

    @TempDir
    Path temp;

    private final Thread thread = Thread.currentThread();
    private final ClassLoader previousLoader = thread.getContextClassLoader();
    private final List<Driver> classPathDrivers = new ArrayList<>();

    @AfterEach
    void restoreDrivers() throws Exception {
        thread.setContextClassLoader(previousLoader);
        for (Driver driver : Collections.list(DriverManager.getDrivers())) {
            if (driver instanceof DelegatingDriver delegating) {
                DriverManager.deregisterDriver(delegating);
                // The copied H2 registered itself as well; only its own class may take it out again.
                Class<?> isolated = delegating.wrapped().getClass();
                if (isolated.getName().equals("org.h2.Driver") && isolated != org.h2.Driver.class) {
                    isolated.getMethod("unload").invoke(null);
                }
            }
        }
        for (Driver driver : classPathDrivers) {
            DriverManager.registerDriver(driver);
        }
    }

    @Test
    @DisplayName("Should register a driver jar from drivers/ so DriverManager connects through it")
    void testLoadsDriverFromFolder() throws Exception {
        Path drivers = Files.createDirectories(temp.resolve("my drivers"));
        Path h2 = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path copy = Files.copy(h2, drivers.resolve("h2.jar"));
        Files.writeString(drivers.resolve("README.md"), "not a jar");
        thread.setContextClassLoader(new HidingClassLoader(previousLoader));
        removeClassPathH2();

        List<Path> loaded = DriverLoader.load(drivers);

        assertThat(loaded).containsExactly(copy);
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:driver-loader-test");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT 1")) {
            assertThat(rows.next()).isTrue();
            assertThat(connection.getClass().getClassLoader()).isNotSameAs(org.h2.Driver.class.getClassLoader());
        }
        assertThat(Collections.list(DriverManager.getDrivers())).filteredOn(DelegatingDriver.class::isInstance)
                .hasSize(1);
    }

    @Test
    @DisplayName("Should load nothing when there is no drivers/ folder")
    void testMissingFolder() {
        assertThat(DriverLoader.load(temp.resolve("drivers"))).isEmpty();
    }

    @Test
    @DisplayName("Should list the jars of loader.path folders and files that exist, sorted")
    void testInstallJars() throws Exception {
        Path lib = Files.createDirectories(temp.resolve("lib drivers"));
        Path b = Files.writeString(lib.resolve("b.jar"), "");
        Path a = Files.writeString(lib.resolve("a.jar"), "");
        Files.writeString(lib.resolve("notes.txt"), "");
        Path single = Files.writeString(temp.resolve("single.jar"), "");

        List<Path> jars = DriverLoader.installJars(lib + "," + temp.resolve("missing") + ", " + single);

        assertThat(jars).containsExactly(a, b, single);
        assertThat(DriverLoader.installJars(null)).isEmpty();
    }

    private void removeClassPathH2() throws Exception {
        for (Driver driver : Collections.list(DriverManager.getDrivers())) {
            if (driver.getClass() == org.h2.Driver.class) {
                DriverManager.deregisterDriver(driver);
                classPathDrivers.add(driver);
            }
        }
    }

    /** A parent that refuses {@code org.h2.*}, so the H2 a child loader finds is its own copy. */
    private static final class HidingClassLoader extends ClassLoader {

        HidingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.h2.")) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
