package com.idm.test.runner.trust;

import java.net.Inet6Address;
import java.net.InetAddress;

/** A direct TLS endpoint supplied as host:port. */
public record ServerEndpoint(String host, int port) {
    public static ServerEndpoint parse(String value) {
        if (value == null || value.isBlank()) throw invalid(value);
        String host;
        String portText;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 2 || close + 1 >= value.length() || value.charAt(close + 1) != ':') throw invalid(value);
            host = value.substring(1, close);
            portText = value.substring(close + 2);
            try {
                if (!(InetAddress.getByName(host) instanceof Inet6Address)) throw invalid(value);
            } catch (java.net.UnknownHostException e) {
                throw invalid(value);
            }
        } else {
            int colon = value.indexOf(':');
            if (colon < 1 || colon != value.lastIndexOf(':')) throw invalid(value);
            host = value.substring(0, colon);
            portText = value.substring(colon + 1);
            if (!host.matches("[A-Za-z0-9.-]+") || host.startsWith(".") || host.endsWith(".")) throw invalid(value);
        }
        if (!portText.matches("[0-9]+")) throw invalid(value);
        try {
            int port = Integer.parseInt(portText);
            if (port > 0 && port <= 65535) return new ServerEndpoint(host, port);
        } catch (NumberFormatException ignored) { }
        throw invalid(value);
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException("invalid server endpoint '" + value + "'; use host:port or [IPv6]:port");
    }

    @Override public String toString() {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }
}
