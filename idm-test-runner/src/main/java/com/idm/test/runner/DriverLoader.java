package com.idm.test.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Loads the JDBC drivers of a workspace's {@code drivers/} folder. Every {@code *.jar} there goes into
 * one class loader, a child of the context class loader, and each {@link Driver} the jars provide
 * (found through {@link ServiceLoader}) is registered with {@link DriverManager} through a
 * {@link DelegatingDriver}, the standard way to use a driver from a class loader of one's own. Drivers
 * from {@code <install>/lib/drivers/} need none of this: {@code -Dloader.path} puts them on the
 * class path at launch, and {@link #installJars(String)} only lists them for {@code run.log}.
 */
public final class DriverLoader {

    private static final Logger LOG = LoggerFactory.getLogger(DriverLoader.class);

    private DriverLoader() {
    }

    /**
     * Registers the drivers of {@code driversFolder}'s jars, which stay registered.
     *
     * @return the jars loaded, sorted by name; empty when the folder does not exist
     */
    public static List<Path> load(Path driversFolder) {
        return open(driversFolder, Thread.currentThread().getContextClassLoader()).jars();
    }

    /**
     * Registers the drivers of {@code driversFolder}'s jars, in a class loader that is a child of
     * {@code parent}; {@link Drivers#close()} deregisters them and closes the class loader.
     */
    static Drivers open(Path driversFolder, ClassLoader parent) {
        List<Path> jars = jars(driversFolder);
        if (jars.isEmpty()) {
            return new Drivers(jars, null, List.of());
        }
        URLClassLoader loader = new URLClassLoader("idmtest-workspace-drivers", urls(jars), parent);
        List<Driver> registered = new ArrayList<>();
        try {
            // Only the providers of the folder's jars: the parent's own drivers are registered already.
            Iterator<ServiceLoader.Provider<Driver>> providers =
                    ServiceLoader.load(Driver.class, loader).stream().iterator();
            while (providers.hasNext()) {
                ServiceLoader.Provider<Driver> provider = providers.next();
                if (provider.type().getClassLoader() != loader) {
                    continue;
                }
                Driver driver = new DelegatingDriver(provider.get());
                DriverManager.registerDriver(driver);
                registered.add(driver);
                LOG.info("Registered JDBC driver {} from {}", provider.type().getName(), driversFolder);
            }
        } catch (ServiceConfigurationError | SQLException e) {
            // A broken jar leaves its databases without a driver, which the driver check then names.
            LOG.warn("Could not load every JDBC driver in {}: {}", driversFolder, e.toString());
        }
        return new Drivers(jars, loader, registered);
    }

    /**
     * The jars of {@code loaderPath} (the {@code loader.path} system property: comma-separated
     * folders or jars) that exist; a folder contributes its {@code *.jar} files, sorted by name.
     */
    static List<Path> installJars(String loaderPath) {
        List<Path> jars = new ArrayList<>();
        if (loaderPath == null || loaderPath.isBlank()) {
            return jars;
        }
        for (String entry : loaderPath.split(",")) {
            String location = entry.strip();
            if (location.startsWith("file:")) {
                location = location.substring("file:".length());
            }
            if (location.isEmpty()) {
                continue;
            }
            Path path = Path.of(location);
            if (Files.isDirectory(path)) {
                jars.addAll(jars(path));
            } else if (isJar(path)) {
                jars.add(path);
            }
        }
        return jars;
    }

    private static List<Path> jars(Path folder) {
        if (!Files.isDirectory(folder)) {
            return new ArrayList<>();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return new ArrayList<>(files.filter(DriverLoader::isJar)
                    .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static URL[] urls(List<Path> jars) {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = jars.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return urls;
    }

    /**
     * The drivers of one folder: the jars, their class loader (null when there were none) and the
     * delegating drivers registered for them.
     */
    record Drivers(List<Path> jars, ClassLoader loader, List<Driver> registered) implements AutoCloseable {

        Drivers {
            jars = List.copyOf(jars);
            registered = List.copyOf(registered);
        }

        @Override
        public void close() {
            for (Driver driver : registered) {
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (SQLException e) {
                    LOG.warn("Could not deregister {}: {}", driver, e.toString());
                }
            }
            if (loader instanceof URLClassLoader closeable) {
                try {
                    closeable.close();
                } catch (IOException e) {
                    LOG.warn("Could not close the workspace drivers' class loader: {}", e.toString());
                }
            }
        }
    }
}
