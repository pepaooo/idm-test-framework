package com.idm.test.runner.trust;

import com.idm.test.runner.CommandLine;
import com.idm.test.runner.ExitCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.Socket;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerCertificateFetcherTest {
    @TempDir Path temp;

    @Test
    void capturesSelfSignedLeafFromDirectTls() throws Exception {
        try (LocalTls server = server("localhost", "dns:localhost,ip:127.0.0.1", false)) {
            X509Certificate leaf = new ServerCertificateFetcher().fetch(new ServerEndpoint("localhost", server.port()));
            assertThat(leaf.getSubjectX500Principal().getName()).contains("CN=localhost");
        }
    }

    @Test
    void importedLeafEnablesOrdinaryTlsOnlyForMatchingHost() throws Exception {
        try (LocalTls server = server("localhost", "dns:localhost", false)) {
            KeyStore empty = KeyStore.getInstance("PKCS12");
            empty.load(null, "changeit".toCharArray());
            assertThatThrownBy(() -> connect(clientContext(empty), "localhost", server.port()))
                    .isInstanceOf(javax.net.ssl.SSLException.class);

            UserTrustStore store = new UserTrustStore(temp.resolve("integration-home"),
                    Path.of(System.getProperty("java.home")));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int result = TrustCommand.run(CommandLine.parse("trust", "import-server", "localhost:" + server.port()),
                    new BufferedReader(new StringReader("yes\n")), new PrintStream(out),
                    new PrintStream(new ByteArrayOutputStream()), Locale.ENGLISH, store,
                    new ServerCertificateFetcher()::fetch, true);
            assertThat(result).isEqualTo(ExitCodes.OK);
            assertThat(store.exists()).isTrue();

            KeyStore trusted = KeyStore.getInstance(store.path().toFile(), "changeit".toCharArray());
            SSLContext context = clientContext(trusted);
            connect(context, "localhost", server.port());
            assertThatThrownBy(() -> connect(context, "wrong.example", server.port()))
                    .isInstanceOf(javax.net.ssl.SSLException.class);
        }
    }

    private static SSLContext clientContext(KeyStore store) throws Exception {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private static void connect(SSLContext context, String peerHost, int port) throws Exception {
        try (Socket tcp = new Socket("127.0.0.1", port);
             SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(tcp, peerHost, port, true)) {
            var parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.startHandshake();
        }
    }

    @Test
    void rejectsHostnameMismatch() throws Exception {
        try (LocalTls server = server("wrong.example", "dns:wrong.example", false)) {
            assertThatThrownBy(() -> new ServerCertificateFetcher().fetch(new ServerEndpoint("localhost", server.port())))
                    .isInstanceOf(UserTrustStore.TrustException.class).hasMessageContaining("hostname");
        }
    }

    @Test
    void rejectsExpiredLeaf() throws Exception {
        try (LocalTls server = server("localhost", "dns:localhost", true)) {
            assertThatThrownBy(() -> new ServerCertificateFetcher().fetch(new ServerEndpoint("localhost", server.port())))
                    .isInstanceOf(UserTrustStore.TrustException.class).hasMessageContaining("valid");
        }
    }

    @Test
    void connectionFailureDoesNotProduceCertificate() throws Exception {
        int port;
        try (var unused = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = unused.getLocalPort();
        }
        assertThatThrownBy(() -> new ServerCertificateFetcher().fetch(new ServerEndpoint("127.0.0.1", port)))
                .isInstanceOf(UserTrustStore.TrustException.class).hasMessageContaining("cannot retrieve certificate");
    }

    @Test
    void stalledHandshakeTimesOut() throws Exception {
        try (var listener = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread worker = Thread.ofVirtual().start(() -> {
                try (var ignored = listener.accept()) { Thread.sleep(7000); }
                catch (Exception ignored) { }
            });
            assertThatThrownBy(() -> new ServerCertificateFetcher().fetch(
                    new ServerEndpoint("127.0.0.1", listener.getLocalPort())))
                    .isInstanceOf(UserTrustStore.TrustException.class).hasMessageContaining("cannot retrieve certificate");
            listener.close();
            worker.interrupt();
        }
    }

    @Test
    void peerWithoutCertificateCannotBeImported() throws Exception {
        try (var listener = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread worker = Thread.ofVirtual().start(() -> {
                try (var ignored = listener.accept()) { /* Close without a TLS certificate. */ }
                catch (Exception ignored) { }
            });
            assertThatThrownBy(() -> new ServerCertificateFetcher().fetch(
                    new ServerEndpoint("127.0.0.1", listener.getLocalPort())))
                    .isInstanceOf(UserTrustStore.TrustException.class).hasMessageContaining("cannot retrieve certificate");
            worker.join(3000);
        }
    }

    @Test
    void literalWildcardLabelIsNotAWildcard() {
        assertThat(ServerCertificateFetcher.dnsMatches("wildcard.example.com", "wildcard.example.com")).isTrue();
        assertThat(ServerCertificateFetcher.dnsMatches("admin.example.com", "wildcard.example.com")).isFalse();
        assertThat(ServerCertificateFetcher.dnsMatches("foo.wildcard.example.com", "foo.wildcard.example.com")).isTrue();
        assertThat(ServerCertificateFetcher.dnsMatches("a.example.com", "*.example.com")).isTrue();
        assertThat(ServerCertificateFetcher.dnsMatches("a.b.example.com", "*.example.com")).isFalse();
    }

    @Test
    void tricklingTlsPeerCannotExtendHandshakeIndefinitely() throws Exception {
        try (var listener = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread worker = Thread.ofVirtual().start(() -> {
                try (var socket = listener.accept(); var out = socket.getOutputStream()) {
                    byte[] record = {0x16, 0x03, 0x03, 0x00, 0x20, 0, 0, 0, 0, 0};
                    for (byte part : record) { out.write(part); out.flush(); Thread.sleep(800); }
                } catch (Exception ignored) { }
            });
            long started = System.nanoTime();
            assertThatThrownBy(() -> new ServerCertificateFetcher().fetch(
                    new ServerEndpoint("127.0.0.1", listener.getLocalPort())))
                    .isInstanceOf(UserTrustStore.TrustException.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(7));
            worker.interrupt();
        }
    }

    @Test
    void slowNameResolutionRespectsDeadline() {
        long start = System.nanoTime();
        long deadline = start + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100);
        assertThatThrownBy(() -> ServerCertificateFetcher.resolveWithDeadline(() -> {
            Thread.sleep(1000);
            return java.net.InetAddress.getByName("127.0.0.1");
        }, deadline)).isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                .isLessThan(java.time.Duration.ofMillis(500));
    }

    private LocalTls server(String commonName, String san, boolean expired) throws Exception {
        Path file = temp.resolve("server-" + commonName + ".p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var args = new java.util.ArrayList<>(java.util.List.of(keytool, "-genkeypair", "-alias", "server",
                "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", "changeit", "-keypass", "changeit", "-dname", "CN=" + commonName,
                "-ext", "SAN=" + san, "-validity", "1", "-noprompt"));
        if (expired) { args.add("-startdate"); args.add("-3d"); }
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
        KeyStore keys = KeyStore.getInstance(file.toFile(), "changeit".toCharArray());
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        SSLServerSocket listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0);
        Thread worker = Thread.ofVirtual().start(() -> {
            while (!listener.isClosed()) {
                try (SSLSocket socket = (SSLSocket) listener.accept()) { socket.startHandshake(); }
                catch (Exception ignored) { if (listener.isClosed()) return; }
            }
        });
        return new LocalTls(listener, worker);
    }

    private record LocalTls(SSLServerSocket listener, Thread worker) implements AutoCloseable {
        int port() { return listener.getLocalPort(); }
        @Override public void close() throws Exception { listener.close(); worker.join(3000); }
    }
}
