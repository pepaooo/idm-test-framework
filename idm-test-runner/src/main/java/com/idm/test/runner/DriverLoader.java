package com.idm.test.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Loads the JDBC drivers of a workspace's {@code drivers/} folder. Every {@code *.jar} there goes into
 * one class loader, a child of the run's context class loader, and each {@link Driver} the jars provide
 * (named in their {@link ServiceLoader} provider file) is registered with {@link DriverManager} through a
 * {@link DelegatingDriver}, the standard way to use a driver from a class loader of one's own. Drivers
 * from {@code <install>/lib/drivers/} need none of this: {@code -Dloader.path} puts them on the
 * class path at launch, and {@link #installJars(String)} only lists them for {@code run.log}.
 */
public final class DriverLoader {

    private static final Logger LOG = LoggerFactory.getLogger(DriverLoader.class);
    private static final String SERVICE_FILE = "META-INF/services/java.sql.Driver";

    private DriverLoader() {
    }

    /**
     * Registers the drivers of {@code driversFolder}'s jars, in a class loader that is a child of
     * {@code parent}; {@link Drivers#close()} deregisters them and closes the class loader. A jar
     * whose driver cannot be loaded is logged and skipped, and the others are still registered.
     */
    public static Drivers open(Path driversFolder, ClassLoader parent) {
        List<Path> jars = jars(driversFolder);
        if (jars.isEmpty()) {
            return new Drivers(jars, null, List.of());
        }
        URLClassLoader loader = new URLClassLoader("idmtest-workspace-drivers", urls(jars), parent);
        List<Driver> registered = new ArrayList<>();
        try {
            for (Path jar : jars) {
                String shown = driversFolder.getFileName() + "/" + jar.getFileName();
                List<String> providers = providers(jar);
                if (providers.isEmpty()) {
                    LOG.info("{}: has no {} entry; its classes are used only through a driver-class-name",
                            shown, SERVICE_FILE);
                }
                for (String provider : providers) {
                    register(provider, shown, loader, registered);
                }
            }
        } catch (RuntimeException | Error e) {
            new Drivers(jars, loader, registered).close();
            throw e;
        }
        // A driver that registers itself in its static initialiser (most do) stays in DriverManager
        // after close() and keeps the closed class loader reachable. DriverManager hands it to no
        // caller outside that loader, so it is never used again; and the CLI runs once per JVM.
        return new Drivers(jars, loader, registered);
    }

    /**
     * Registers one provider of {@code shown}'s {@value #SERVICE_FILE}, as {@link ServiceLoader}
     * would find it, but one jar at a time so a log line can name the jar. A class that resolves to
     * the parent is a built-in (or install-folder) driver, which the parent has registered already.
     */
    private static void register(String provider, String shown, URLClassLoader loader, List<Driver> registered) {
        try {
            Class<?> type = Class.forName(provider, false, loader);
            if (type.getClassLoader() != loader) {
                LOG.info("{}: driver {} is already built in; the built-in one is used", shown, provider);
                return;
            }
            if (!Driver.class.isAssignableFrom(type)) {
                LOG.warn("{}: {} is not a java.sql.Driver; skipped", shown, provider);
                return;
            }
            Driver driver = new DelegatingDriver(type.asSubclass(Driver.class).getConstructor().newInstance());
            DriverManager.registerDriver(driver);
            registered.add(driver);
            LOG.info("{}: registered JDBC driver {}", shown, provider);
        } catch (ReflectiveOperationException | LinkageError | SQLException | RuntimeException e) {
            // That jar's databases are left without a driver, which the driver check then names.
            LOG.warn("{}: could not load JDBC driver {}; skipped: {}", shown, provider, e.toString());
        }
    }

    /** The provider names in {@code jar}'s {@value #SERVICE_FILE}: one per line, {@code #} starts a comment. */
    private static List<String> providers(Path jar) {
        List<String> names = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(SERVICE_FILE);
            if (entry == null) {
                return names;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(entry), StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    int comment = line.indexOf('#');
                    String name = (comment < 0 ? line : line.substring(0, comment)).strip();
                    if (!name.isEmpty() && !names.contains(name)) {
                        names.add(name);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("{}: cannot be read as a jar; skipped: {}", jar.getFileName(), e.toString());
        }
        return names;
    }

    /**
     * The jars of {@code loaderPath} (the {@code loader.path} system property: comma-separated
     * folders or jars) that exist; a folder contributes its {@code *.jar} files, sorted by name.
     */
    public static List<Path> installJars(String loaderPath) {
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
    public record Drivers(List<Path> jars, ClassLoader loader, List<Driver> registered) implements AutoCloseable {

        public Drivers {
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
