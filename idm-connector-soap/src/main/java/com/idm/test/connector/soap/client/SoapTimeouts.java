package com.idm.test.connector.soap.client;

import com.idm.test.connector.soap.config.SoapProperties;
import jakarta.xml.ws.BindingProvider;

/**
 * Applies {@code idm.test.soap.connection-timeout} and {@code read-timeout} to a JAX-WS port, shared
 * by the role and resource connectors, so a hung service fails a step instead of blocking it on
 * the stack's defaults. The property names are the JAX-WS reference implementation's.
 */
final class SoapTimeouts {

    static final String CONNECT_TIMEOUT = "com.sun.xml.ws.connect.timeout";
    static final String REQUEST_TIMEOUT = "com.sun.xml.ws.request.timeout";

    private SoapTimeouts() {
    }

    static void apply(BindingProvider port, SoapProperties properties) {
        requirePositive("connection-timeout", properties.getConnectionTimeout());
        requirePositive("read-timeout", properties.getReadTimeout());
        port.getRequestContext().put(CONNECT_TIMEOUT, properties.getConnectionTimeout());
        port.getRequestContext().put(REQUEST_TIMEOUT, properties.getReadTimeout());
    }

    private static void requirePositive(String key, int millis) {
        if (millis <= 0) {
            throw new IllegalStateException(
                    "idm.test.soap." + key + " must be a positive number of milliseconds");
        }
    }
}
