package com.idm.test.runner;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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
    private final Logger logger = (Logger) LoggerFactory.getLogger(DriverLoader.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Class<?> isolatedH2;

    @BeforeEach
    void captureLog() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreDrivers() throws Exception {
        thread.setContextClassLoader(previousLoader);
        // The copied H2 registered itself as well; only its own class may take it out again.
        if (isolatedH2 != null) {
            isolatedH2.getMethod("unload").invoke(null);
        }
        for (Driver driver : Collections.list(DriverManager.getDrivers())) {
            if (driver instanceof DelegatingDriver delegating) {
                DriverManager.deregisterDriver(delegating);
            }
        }
        for (Driver driver : classPathDrivers) {
            DriverManager.registerDriver(driver);
        }
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("Should register a driver jar from drivers/ so DriverManager connects through it")
    void testLoadsDriverFromFolder() throws Exception {
        Path drivers = Files.createDirectories(temp.resolve("my drivers"));
        Path copy = copyH2(drivers);
        Files.writeString(drivers.resolve("README.md"), "not a jar");
        isolate();

        try (DriverLoader.Drivers loaded = DriverLoader.open(drivers, thread.getContextClassLoader())) {
            assertThat(loaded.jars()).containsExactly(copy);
            assertThat(loaded.registered()).hasSize(1);
            isolatedH2 = ((DelegatingDriver) loaded.registered().getFirst()).wrapped().getClass();
            try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:driver-loader-test");
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(connection.getClass().getClassLoader()).isNotSameAs(org.h2.Driver.class.getClassLoader());
            }
        }
        assertThat(Collections.list(DriverManager.getDrivers())).noneMatch(DelegatingDriver.class::isInstance);
    }

    @Test
    @DisplayName("Should skip a jar whose driver cannot be loaded, log it, and register the others")
    void testBrokenJarSkipped() throws Exception {
        Path drivers = Files.createDirectories(temp.resolve("drivers"));
        serviceOnlyJar(drivers.resolve("a-broken.jar"), "# a comment\nno.such.Driver\n");
        copyH2(drivers);
        isolate();

        try (DriverLoader.Drivers loaded = DriverLoader.open(drivers, thread.getContextClassLoader())) {
            assertThat(loaded.registered()).hasSize(1);
            isolatedH2 = ((DelegatingDriver) loaded.registered().getFirst()).wrapped().getClass();
            assertThat(log()).anyMatch(line -> line.startsWith(
                    "drivers/a-broken.jar: could not load JDBC driver no.such.Driver; skipped: "));
            try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:driver-loader-broken")) {
                assertThat(connection.isValid(1)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("Should keep a built-in driver and say so when drivers/ has a jar for it")
    void testBuiltInDriverNotReplaced() throws Exception {
        Path drivers = Files.createDirectories(temp.resolve("drivers"));
        serviceOnlyJar(drivers.resolve("postgresql-x.jar"), "org.postgresql.Driver\n");

        try (DriverLoader.Drivers loaded = DriverLoader.open(drivers, thread.getContextClassLoader())) {
            assertThat(loaded.registered()).isEmpty();
            assertThat(log()).contains(
                    "drivers/postgresql-x.jar: driver org.postgresql.Driver is already built in; the built-in one is used");
        }
    }

    @Test
    @DisplayName("Should load nothing when there is no drivers/ folder")
    void testMissingFolder() {
        try (DriverLoader.Drivers loaded = DriverLoader.open(temp.resolve("drivers"), previousLoader)) {
            assertThat(loaded.jars()).isEmpty();
            assertThat(loaded.loader()).isNull();
        }
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

    private static Path copyH2(Path drivers) throws Exception {
        Path h2 = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return Files.copy(h2, drivers.resolve("h2.jar"));
    }

    /** Hides the class path's H2 from the loader and from DriverManager, so only a copied jar can serve it. */
    private void isolate() throws Exception {
        thread.setContextClassLoader(new HidingClassLoader(previousLoader));
        removeClassPathH2();
    }

    private static void serviceOnlyJar(Path jar, String services) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
            out.write(services.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private List<String> log() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
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
