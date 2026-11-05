package com.idm.test.runner.trust;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/** Retrieves a TLS peer's leaf for inspection; the capture connection itself does not authenticate it. */
public final class ServerCertificateFetcher {
    private static final int TIMEOUT_MILLIS = 5000;

    public X509Certificate fetch(ServerEndpoint endpoint) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new CaptureTrustManager()}, null);
            InetAddress address = resolveWithDeadline(() -> InetAddress.getByName(endpoint.host()), deadline);
            try (Socket tcp = new Socket()) {
                tcp.connect(new InetSocketAddress(address, endpoint.port()), remainingMillis(deadline));
                tcp.setSoTimeout(remainingMillis(deadline));
                try (SSLSocket tls = (SSLSocket) context.getSocketFactory()
                        .createSocket(tcp, endpoint.host(), endpoint.port(), true)) {
                    tls.setSoTimeout(remainingMillis(deadline));
                    if (!endpoint.host().contains(":") && !endpoint.host().matches("[0-9.]+")) {
                        SSLParameters params = tls.getSSLParameters();
                        params.setServerNames(java.util.List.of(new SNIHostName(endpoint.host())));
                        tls.setSSLParameters(params);
                    }
                    Thread watchdog = Thread.ofVirtual().start(() -> {
                        try {
                            long wait = deadline - System.nanoTime();
                            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                            tcp.close();
                        }
                        catch (InterruptedException ignored) { }
                        catch (java.io.IOException ignored) { }
                    });
                    try { tls.startHandshake(); }
                    finally { watchdog.interrupt(); }
                    Certificate[] chain = tls.getSession().getPeerCertificates();
                    if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
                        throw new UserTrustStore.TrustException("server did not present an X.509 certificate");
                    }
                    try {
                        leaf.checkValidity();
                    } catch (GeneralSecurityException e) {
                        throw new UserTrustStore.TrustException("server certificate is not currently valid: " + e.getMessage(), e);
                    }
                    if (!matchesHost(leaf, endpoint.host())) {
                        throw new UserTrustStore.TrustException("server certificate does not match hostname " + endpoint.host());
                    }
                    return leaf;
                }
            }
        } catch (UserTrustStore.TrustException e) {
            throw e;
        } catch (Exception e) {
            throw new UserTrustStore.TrustException("cannot retrieve certificate from " + endpoint + ": " + e.getMessage(), e);
        }
    }

    static InetAddress resolveWithDeadline(Callable<InetAddress> lookup, long deadline) throws Exception {
        FutureTask<InetAddress> task = new FutureTask<>(lookup);
        Thread.ofVirtual().start(task);
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("TLS connection deadline exceeded during name resolution");
            return task.get(remaining, TimeUnit.NANOSECONDS);
        } finally {
            task.cancel(true);
        }
    }

    private static int remainingMillis(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("TLS connection deadline exceeded");
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private static boolean matchesHost(X509Certificate leaf, String host) throws Exception {
        boolean ip = host.contains(":") || host.matches("[0-9.]+");
        Collection<java.util.List<?>> names = leaf.getSubjectAlternativeNames();
        boolean hasDnsName = false;
        if (names != null) for (java.util.List<?> name : names) {
            if (name.size() < 2 || !(name.get(0) instanceof Integer type) || !(name.get(1) instanceof String value)) continue;
            if (ip && type == 7 && java.util.Arrays.equals(InetAddress.getByName(host).getAddress(),
                    InetAddress.getByName(value).getAddress())) return true;
            if (!ip && type == 2) {
                hasDnsName = true;
                if (dnsMatches(host, value)) return true;
            }
        }
        if (ip || hasDnsName) return false;
        for (Rdn part : new LdapName(leaf.getSubjectX500Principal().getName()).getRdns()) {
            if (part.getType().equalsIgnoreCase("CN")) return dnsMatches(host, String.valueOf(part.getValue()));
        }
        return false;
    }

    static boolean dnsMatches(String host, String pattern) {
        String name = java.net.IDN.toASCII(host).toLowerCase(java.util.Locale.ROOT);
        if (pattern.startsWith("*.")) {
            String suffix = "." + java.net.IDN.toASCII(pattern.substring(2)).toLowerCase(java.util.Locale.ROOT);
            return name.endsWith(suffix) && name.substring(0, name.length() - suffix.length()).indexOf('.') < 0;
        }
        return name.equals(java.net.IDN.toASCII(pattern).toLowerCase(java.util.Locale.ROOT));
    }

    private static final class CaptureTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
