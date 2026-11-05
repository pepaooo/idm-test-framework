package com.idm.test.runner.trust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

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
            try (SSLSocket socket = (SSLSocket) listener.accept()) { socket.startHandshake(); }
            catch (Exception ignored) { }
        });
        return new LocalTls(listener, worker);
    }

    private record LocalTls(SSLServerSocket listener, Thread worker) implements AutoCloseable {
        int port() { return listener.getLocalPort(); }
        @Override public void close() throws Exception { listener.close(); worker.join(3000); }
    }
}
