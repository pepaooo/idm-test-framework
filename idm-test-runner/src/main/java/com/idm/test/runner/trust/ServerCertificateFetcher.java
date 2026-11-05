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
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/** Retrieves a TLS peer's leaf for inspection; the capture connection itself does not authenticate it. */
public final class ServerCertificateFetcher {
    private static final int TIMEOUT_MILLIS = 5000;

    public X509Certificate fetch(ServerEndpoint endpoint) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new CaptureTrustManager()}, null);
            try (Socket tcp = new Socket()) {
                tcp.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), TIMEOUT_MILLIS);
                tcp.setSoTimeout(TIMEOUT_MILLIS);
                try (SSLSocket tls = (SSLSocket) context.getSocketFactory()
                        .createSocket(tcp, endpoint.host(), endpoint.port(), true)) {
                    tls.setSoTimeout(TIMEOUT_MILLIS);
                    if (!endpoint.host().contains(":") && !endpoint.host().matches("[0-9.]+")) {
                        SSLParameters params = tls.getSSLParameters();
                        params.setServerNames(java.util.List.of(new SNIHostName(endpoint.host())));
                        tls.setSSLParameters(params);
                    }
                    tls.startHandshake();
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

    private static boolean dnsMatches(String host, String pattern) {
        String name = java.net.IDN.toASCII(host).toLowerCase(java.util.Locale.ROOT);
        String expected = java.net.IDN.toASCII(pattern.replace("*.", "wildcard.")).toLowerCase(java.util.Locale.ROOT)
                .replace("wildcard.", "*.");
        if (expected.startsWith("*.")) {
            String suffix = expected.substring(1);
            return name.endsWith(suffix) && name.substring(0, name.length() - suffix.length()).indexOf('.') < 0;
        }
        return name.equals(expected);
    }

    private static final class CaptureTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
