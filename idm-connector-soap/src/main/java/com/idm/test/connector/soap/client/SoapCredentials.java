package com.idm.test.connector.soap.client;

import com.idm.test.connector.soap.config.SoapProperties;
import jakarta.xml.ws.BindingProvider;

/**
 * Applies {@code idm.test.soap.username}/{@code password} to a JAX-WS port as HTTP basic
 * authentication, shared by the role and resource connectors. Both set: sent. Neither set: an
 * unauthenticated endpoint. Only one: refused, because a request with half the credentials is
 * never what was meant. Messages name properties, never values.
 */
final class SoapCredentials {

    private SoapCredentials() {
    }

    static void apply(BindingProvider port, SoapProperties properties) {
        boolean hasUsername = present(properties.getUsername());
        boolean hasPassword = present(properties.getPassword());
        if (hasUsername != hasPassword) {
            throw new IllegalStateException("idm.test.soap." + (hasUsername ? "username" : "password")
                    + " is set but idm.test.soap." + (hasUsername ? "password" : "username")
                    + " is not; set both for basic authentication, or neither");
        }
        if (hasUsername) {
            port.getRequestContext().put(BindingProvider.USERNAME_PROPERTY, properties.getUsername());
            port.getRequestContext().put(BindingProvider.PASSWORD_PROPERTY, properties.getPassword());
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
