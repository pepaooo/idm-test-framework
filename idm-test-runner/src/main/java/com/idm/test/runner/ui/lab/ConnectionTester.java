package com.idm.test.runner.ui.lab;

import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.core.model.ErrorType;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.Message;
import com.idm.test.runner.DriverLoader;
import com.idm.test.runner.SecretMask;
import com.idm.test.wiring.RealEnvironmentGuards;
import com.idm.test.wiring.ScenarioNeeds;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The lab panel's Test connection: an LDAP bind with the bind DN; a database connection and its
 * validation query ({@code SELECT 1}, Oracle {@code SELECT 1 FROM DUAL}); for a REST target its OAuth
 * token when it uses OAuth, otherwise a GET of its base URL (any HTTP answer is reachable, 401 and 403
 * are credentials rejected); for the SOAP services a GET of the WSDL ({@code <url>?wsdl}) with their
 * credentials. Before contacting anything, the values go through the same checks a run's guards make
 * (a placeholder, an in-memory database, a half-given credential), with the guards' messages.
 * <p>
 * Each test runs on a small bounded pool and gets {@link #TIMEOUT} in all; redirects are never
 * followed; TLS uses the JVM's own trust (no trust-all). Every message is masked with the secret
 * values given, and never carries a password, token or header value. Settings come only from the
 * values given — never from this process's environment.
 */
public final class ConnectionTester implements AutoCloseable {

    /** The most one test takes. */
    public static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_PARALLEL = 4;
    /** How many tests that went past {@link #TIMEOUT} may still hold a thread before new tests wait. */
    private static final int MAX_ABANDONED = 4;

    /** How long a test is waited for. */
    private final Duration limit;
    /** The connect, read and query timeouts each probe gives its client (the same as {@link #limit} in production). */
    private final Duration timeout;
    private final int parallel;
    private final int abandonedLimit;
    private final ThreadPoolExecutor executor;
    /** Tests running and waited for. */
    private final AtomicInteger running = new AtomicInteger();
    /** Tests no longer waited for whose thread has not come back yet. */
    private final AtomicInteger abandoned = new AtomicInteger();

    /** The outcome of a test: whether it connected, what to show, and how long it took. */
    public record Result(boolean ok, String message, long millis) {
    }

    /** What a probe found, before masking. */
    private record Outcome(boolean ok, Message message) {
        static Outcome ok(String key, Object... args) {
            return new Outcome(true, Message.of(key, args));
        }

        static Outcome failed(String key, Object... args) {
            return new Outcome(false, Message.of(key, args));
        }
    }

    public ConnectionTester() {
        this(TIMEOUT);
    }

    /** A tester whose tests end after {@code timeout} (tests use a short one). */
    ConnectionTester(Duration timeout) {
        this(timeout, timeout, MAX_PARALLEL, MAX_ABANDONED);
    }

    /**
     * A tester that waits {@code limit} for a test whose clients get {@code timeout}; at most
     * {@code parallel} tests run at once, and while {@code abandonedLimit} tests that went past the limit
     * still hold their thread, new tests are answered "busy" instead of piling up threads.
     */
    ConnectionTester(Duration limit, Duration timeout, int parallel, int abandonedLimit) {
        this.limit = limit;
        this.timeout = timeout;
        this.parallel = parallel;
        this.abandonedLimit = abandonedLimit;
        this.executor = new ThreadPoolExecutor(0, parallel + abandonedLimit, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "idmtest-lab-test");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * Tests {@code system} with {@code properties} (full property names: {@code idm.test.ldap.url},
     * {@code spring.security.oauth2.client.registration.<client>.client-secret}, …).
     *
     * @param drivers the workspace's {@code drivers/} folder, for databases
     * @param mask    hides every secret value in the message
     */
    public Result test(LabSettings.SystemRef system, Map<String, String> properties, Path drivers, Locale locale,
                       SecretMask mask) {
        long start = System.nanoTime();
        try {
            guards(system, properties, locale);
        } catch (RealEnvironmentGuards.Refusal refusal) {
            return new Result(false, mask.apply(refusal.getMessage()), millis(start));
        }
        int stillWaiting = abandoned.get();
        if (stillWaiting >= abandonedLimit) {
            return result(Outcome.failed("runner.ui.lab.busyWaiting", String.valueOf(stillWaiting)), locale, mask, start);
        }
        if (running.incrementAndGet() > parallel) {
            running.decrementAndGet();
            return result(Outcome.failed("runner.ui.lab.busy"), locale, mask, start);
        }
        // 0 running, 1 done, 2 abandoned: whoever moves it first decides who counts the abandoned thread
        AtomicInteger state = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        Future<Outcome> future;
        try {
            // never cancelled (a task cancelled before it starts would never count its thread back):
            // giving up interrupts the worker instead
            future = executor.submit(() -> {
                worker.set(Thread.currentThread());
                try {
                    return probe(system, properties, drivers);
                } finally {
                    synchronized (worker) {
                        worker.set(null);
                    }
                    if (!state.compareAndSet(0, 1)) {
                        abandoned.decrementAndGet();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            running.decrementAndGet();
            return result(Outcome.failed("runner.ui.lab.busy"), locale, mask, start);
        }
        try {
            return result(future.get(limit.toMillis(), TimeUnit.MILLISECONDS), locale, mask, start);
        } catch (TimeoutException e) {
            abandon(state, worker);
            return result(timedOut(system, properties), locale, mask, start);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return result(Outcome.failed("runner.ui.lab.failed", describe(cause)), locale, mask, start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon(state, worker);
            return result(timedOut(system, properties), locale, mask, start);
        } finally {
            running.decrementAndGet();
        }
    }

    /** Stops waiting for a test: interrupts it, and counts its thread until it comes back. */
    private void abandon(AtomicInteger state, AtomicReference<Thread> worker) {
        if (state.compareAndSet(0, 2)) {
            abandoned.incrementAndGet();
        }
        synchronized (worker) {
            Thread thread = worker.get();
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    /** How many tests went past the limit and still hold a thread. */
    int abandonedThreads() {
        return abandoned.get();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private Result result(Outcome outcome, Locale locale, SecretMask mask, long start) {
        return new Result(outcome.ok(), mask.apply(Messages.render(outcome.message(), locale)), millis(start));
    }

    private static long millis(long start) {
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    private Outcome timedOut(LabSettings.SystemRef system, Map<String, String> properties) {
        return Outcome.failed("runner.ui.lab.timeout", endpoint(system, properties), String.valueOf(limit.toSeconds()));
    }

    // --- the guards' checks ---------------------------------------------------------------------

    /** The guards' checks for this one system, over the given values only. */
    private static void guards(LabSettings.SystemRef system, Map<String, String> properties, Locale locale) {
        ConfigurableEnvironment environment = new AbstractEnvironment() {
        };
        environment.getPropertySources().addFirst(new MapPropertySource("lab", new LinkedHashMap<>(properties)));
        Binder binder = Binder.get(environment);
        switch (system.kind()) {
            case LDAP -> RealEnvironmentGuards.requireDirectories(environment,
                    Set.of(system.name() == null ? "default" : system.name()), locale);
            case DATABASE -> RealEnvironmentGuards.requireDatabases(Set.of(system.name()),
                    binder.bind("idm.test", DatabaseProperties.class).orElseGet(DatabaseProperties::new), locale);
            case REST -> {
                RestProperties rest = binder.bind("idm.test", RestProperties.class).orElseGet(RestProperties::new);
                RealEnvironmentGuards.requireRestTargets(Set.of(system.name()), rest, locale);
                RealEnvironmentGuards.requireCredentials(Set.of(system.name()), false, rest, new SoapProperties(),
                        environment, locale);
            }
            case SOAP -> {
                boolean resources = !blank(properties.get("idm.test.soap.resource-url"));
                boolean roles = !blank(properties.get("idm.test.soap.url")) || !resources;
                RealEnvironmentGuards.requireEndpoints(environment,
                        new ScenarioNeeds(Set.of(), Set.of(), roles, resources, Set.of()), locale);
                SoapProperties soap = binder.bind("idm.test.soap", SoapProperties.class).orElseGet(SoapProperties::new);
                RealEnvironmentGuards.requireCredentials(Set.of(), true, new RestProperties(), soap, environment, locale);
            }
        }
    }

    // --- probes ---------------------------------------------------------------------------------

    private Outcome probe(LabSettings.SystemRef system, Map<String, String> properties, Path drivers) {
        String prefix = system.prefix() + ".";
        return switch (system.kind()) {
            case LDAP -> ldap(properties.get(prefix + "url"), properties.get(prefix + "user-dn"),
                    properties.get(prefix + "password"));
            case DATABASE -> database(properties.get(prefix + "jdbc-url"), properties.get(prefix + "username"),
                    properties.get(prefix + "password"), properties.get(prefix + "driver-class-name"), drivers);
            case REST -> "true".equalsIgnoreCase(String.valueOf(properties.get(prefix + "use-oauth")).strip())
                    ? oauth(system.name(), properties.get(prefix + "oauth-client-name"), properties)
                    : http(properties.get(prefix + "base-url"), properties.get(prefix + "username"),
                    properties.get(prefix + "password"), false);
            case SOAP -> {
                String url = properties.get(prefix + "url");
                if (blank(url)) {
                    url = properties.get(prefix + "resource-url");
                }
                yield http(url + (url.contains("?") ? "&wsdl" : "?wsdl"), properties.get(prefix + "username"),
                        properties.get(prefix + "password"), true);
            }
        };
    }

    private Outcome ldap(String url, String userDn, String password) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return Outcome.failed("runner.ui.lab.badUrl", url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("ldap") && !scheme.equals("ldaps") || uri.getHost() == null) {
            return Outcome.failed("runner.ui.lab.badUrl", url);
        }
        boolean secure = scheme.equals("ldaps");
        int port = uri.getPort() > 0 ? uri.getPort() : secure ? 636 : 389;
        String where = uri.getHost() + ":" + port;
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis((int) timeout.toMillis());
        options.setResponseTimeoutMillis(timeout.toMillis());
        options.setFollowReferrals(false);
        options.setUseSynchronousMode(true);
        if (blank(password) && !blank(userDn)) {
            return Outcome.failed("runner.ui.lab.credentialsRejected", Message.of("runner.ui.lab.noPassword"));
        }
        LDAPConnection connection = null;
        try {
            if (secure) {
                options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true));
                connection = new LDAPConnection(SSLContext.getDefault().getSocketFactory(), options, uri.getHost(), port);
            } else {
                connection = new LDAPConnection(options, uri.getHost(), port);
            }
            if (blank(userDn)) {
                return Outcome.ok("runner.ui.lab.connected.anonymous");
            }
            connection.bind(userDn, password);
            return Outcome.ok("runner.ui.lab.connected.bind");
        } catch (LDAPException e) {
            ResultCode code = e.getResultCode();
            String detail = e.getDiagnosticMessage() != null ? e.getDiagnosticMessage() : e.getMessage();
            if (code == ResultCode.INVALID_CREDENTIALS || code == ResultCode.INAPPROPRIATE_AUTHENTICATION) {
                return Outcome.failed("runner.ui.lab.credentialsRejected", detail);
            }
            if (code == ResultCode.TIMEOUT) {
                return Outcome.failed("runner.ui.lab.timeout", where, String.valueOf(timeout.toSeconds()));
            }
            if (code == ResultCode.CONNECT_ERROR || code == ResultCode.SERVER_DOWN) {
                return Outcome.failed("runner.ui.lab.unreachable", where, e.getMessage());
            }
            return Outcome.failed("runner.ui.lab.failed", e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            return Outcome.failed("runner.ui.lab.failed", describe(e));
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private Outcome database(String givenUrl, String username, String password, String driverClass,
                             Path driversFolder) {
        String url = withoutTimeouts(givenUrl);
        if (url == null) {
            return Outcome.failed("runner.ui.lab.urlTimeout");
        }
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        try (DriverLoader.Drivers drivers = DriverLoader.open(driversFolder, parent)) {
            ClassLoader loader = drivers.loader() != null ? drivers.loader() : parent;
            Driver driver;
            if (!blank(driverClass)) {
                try {
                    driver = (Driver) Class.forName(driverClass.strip(), true, loader).getConstructor().newInstance();
                } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
                    return Outcome.failed("runner.ui.lab.noDriver", driverClass);
                }
            } else {
                try {
                    driver = DriverManager.getDriver(url);
                } catch (SQLException e) {
                    return Outcome.failed("runner.ui.lab.noDriver", url);
                }
            }
            Properties info = new Properties();
            if (!blank(username)) {
                info.setProperty("user", username);
            }
            if (password != null && !password.isEmpty()) {
                info.setProperty("password", password);
            }
            info.putAll(timeouts(url));
            String query = url.toLowerCase(Locale.ROOT).startsWith("jdbc:oracle:") ? "SELECT 1 FROM DUAL" : "SELECT 1";
            try (Connection connection = driver.connect(url, info)) {
                if (connection == null) {
                    return Outcome.failed("runner.ui.lab.noDriver", url);
                }
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout((int) Math.max(1, timeout.toSeconds()));
                    statement.execute(query);
                }
                return Outcome.ok("runner.ui.lab.connected.query", query);
            }
        } catch (SQLException e) {
            String state = e.getSQLState() == null ? "" : e.getSQLState();
            if (state.startsWith("28")) {
                return Outcome.failed("runner.ui.lab.credentialsRejected", e.getMessage());
            }
            if (state.startsWith("08") || causedBy(e, ConnectException.class) || causedBy(e, UnknownHostException.class)) {
                return Outcome.failed("runner.ui.lab.unreachable", url, e.getMessage());
            }
            return Outcome.failed("runner.ui.lab.failed", e.getMessage());
        }
    }

    /**
     * Connect and read timeouts for the drivers that take them as connection properties, so a host that
     * never answers cannot keep a pool thread past the test's time (JDBC connects do not stop when the
     * thread is interrupted). Only for drivers known to accept them: H2, for one, refuses unknown settings.
     * {@code DriverManager.setLoginTimeout} is left alone: it is global to the JVM, and the test calls the
     * driver directly.
     */
    Properties timeouts(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        long millis = Math.max(1000, timeout.toMillis());
        String seconds = String.valueOf(Math.max(1, timeout.toSeconds()));
        Properties properties = new Properties();
        if (lower.startsWith("jdbc:postgresql:")) {
            properties.setProperty("loginTimeout", seconds);
            properties.setProperty("connectTimeout", seconds);
            properties.setProperty("socketTimeout", seconds);
        } else if (lower.startsWith("jdbc:sqlserver:")) {
            properties.setProperty("loginTimeout", seconds);
            properties.setProperty("socketTimeout", String.valueOf(millis));
        } else if (lower.startsWith("jdbc:oracle:")) {
            properties.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(millis));
            properties.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(millis));
        } else if (lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:")) {
            properties.setProperty("connectTimeout", String.valueOf(millis));
            properties.setProperty("socketTimeout", String.valueOf(millis));
        }
        return properties;
    }

    /**
     * {@code url} without the timeout settings it carries for the drivers {@link #timeouts} sets them for
     * (those drivers prefer the URL's to ours, so a URL's own {@code connectTimeout=0} would let a test
     * hang): PostgreSQL, MySQL and MariaDB {@code ?key=value&…} parameters and SQL Server
     * {@code ;key=value} settings whose name contains {@code timeout} are removed. Null when a timeout is
     * still named afterwards (an Oracle descriptor's {@code CONNECT_TIMEOUT}, a MySQL address's own
     * settings), so the test refuses rather than run unbounded. Other URLs are returned as they are.
     */
    static String withoutTimeouts(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        String result;
        if (lower.startsWith("jdbc:postgresql:") || lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:")) {
            int query = url.indexOf('?');
            result = query < 0 ? url : url.substring(0, query) + kept(url.substring(query + 1), "&", "?");
        } else if (lower.startsWith("jdbc:sqlserver:")) {
            int first = url.indexOf(';');
            result = first < 0 ? url : url.substring(0, first) + kept(url.substring(first + 1), ";", ";");
        } else if (lower.startsWith("jdbc:oracle:")) {
            result = url;
        } else {
            return url;
        }
        return result.toLowerCase(Locale.ROOT).contains("timeout") ? null : result;
    }

    /** The {@code separator}-joined settings of {@code settings} whose name has no "timeout", after {@code lead}. */
    private static String kept(String settings, String separator, String lead) {
        java.util.List<String> keep = new java.util.ArrayList<>();
        for (String setting : settings.split(Pattern.quote(separator), -1)) {
            int eq = setting.indexOf('=');
            String name = (eq < 0 ? setting : setting.substring(0, eq)).toLowerCase(Locale.ROOT);
            if (!setting.isEmpty() && !name.contains("timeout")) {
                keep.add(setting);
            }
        }
        return keep.isEmpty() ? "" : lead + String.join(separator, keep);
    }

    /** How many tests are running on the pool now (tests check a timed-out one lets go of its thread). */
    int busyThreads() {
        return executor.getActiveCount();
    }

    private Outcome oauth(String target, String client, Map<String, String> properties) {
        String registration = "spring.security.oauth2.client.registration." + client + ".";
        String tokenUri = properties.get("spring.security.oauth2.client.provider." + client + ".token-uri");
        ClientRegistration.Builder builder = ClientRegistration.withRegistrationId(client)
                .clientId(properties.get(registration + "client-id"))
                .clientSecret(properties.get(registration + "client-secret"))
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokenUri);
        String scope = properties.get(registration + "scope");
        if (!blank(scope)) {
            builder.scope(Arrays.stream(scope.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
        }
        ClientRegistration built;
        try {
            built = builder.build();
        } catch (IllegalArgumentException e) {
            return Outcome.failed("runner.ui.lab.failed", e.getMessage());
        }
        RestAuthenticator authenticator = new RestAuthenticator(new InMemoryClientRegistrationRepository(built), null);
        RestProperties.RestApiConfig config = new RestProperties.RestApiConfig();
        config.setUseOAuth(true);
        config.setOauthClientName(client);
        RestAuthenticator.Authorization authorization = authenticator.authorize(target, config);
        if (!authorization.isFailure()) {
            return Outcome.ok("runner.ui.lab.connected.token");
        }
        String detail = authorization.problem().english();
        ErrorType type = authorization.errorType();
        return switch (type) {
            case AUTHENTICATION_ERROR -> Outcome.failed("runner.ui.lab.credentialsRejected", detail);
            case NETWORK_ERROR -> Outcome.failed("runner.ui.lab.unreachable", where(tokenUri), detail);
            case TIMEOUT -> Outcome.failed("runner.ui.lab.timeout", where(tokenUri), String.valueOf(timeout.toSeconds()));
            default -> Outcome.failed("runner.ui.lab.failed", detail);
        };
    }

    /** A GET of {@code url}; for SOAP only 200 counts, for REST any answer but 401 and 403. */
    private Outcome http(String url, String username, String password, boolean wsdl) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return Outcome.failed("runner.ui.lab.badUrl", url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            return Outcome.failed("runner.ui.lab.badUrl", url);
        }
        String where = where(url);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try (client) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).GET();
            if (!blank(username) && password != null && !password.isEmpty()) {
                request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8)));
            }
            int status = client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 401 || status == 403) {
                return Outcome.failed("runner.ui.lab.credentialsRejected", "HTTP " + status);
            }
            if (wsdl) {
                return status == 200 ? Outcome.ok("runner.ui.lab.connected.wsdl")
                        : Outcome.failed("runner.ui.lab.httpStatus", String.valueOf(status));
            }
            return Outcome.ok("runner.ui.lab.connected.http", String.valueOf(status));
        } catch (HttpConnectTimeoutException e) {
            return Outcome.failed("runner.ui.lab.timeout", where, String.valueOf(timeout.toSeconds()));
        } catch (HttpTimeoutException e) {
            return Outcome.failed("runner.ui.lab.timeout", where, String.valueOf(timeout.toSeconds()));
        } catch (SSLException e) {
            return Outcome.failed("runner.ui.lab.unreachable", where, describe(e));
        } catch (IOException e) {
            return Outcome.failed("runner.ui.lab.unreachable", where, describe(e));
        } catch (IllegalArgumentException e) {
            return Outcome.failed("runner.ui.lab.badUrl", url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.failed("runner.ui.lab.timeout", where, String.valueOf(timeout.toSeconds()));
        }
    }

    // --- texts ----------------------------------------------------------------------------------

    /** What a message names as the system: host and port, or the JDBC URL. */
    private static String endpoint(LabSettings.SystemRef system, Map<String, String> properties) {
        String prefix = system.prefix() + ".";
        return switch (system.kind()) {
            case LDAP -> where(properties.get(prefix + "url"));
            case DATABASE -> String.valueOf(properties.get(prefix + "jdbc-url"));
            case REST -> where(properties.get(prefix + "base-url"));
            case SOAP -> where(firstNonBlank(properties.get(prefix + "url"), properties.get(prefix + "resource-url")));
        };
    }

    /** {@code host:port} of a URL (never its user part), or the text itself when it is not one. */
    private static String where(String url) {
        try {
            URI uri = URI.create(String.valueOf(url).strip());
            if (uri.getHost() != null) {
                return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
            }
        } catch (IllegalArgumentException e) {
            // not a URL: shown as given (masked)
        }
        return String.valueOf(url);
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root && root.getMessage() == null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        return blank(first) ? second : first;
    }
}
