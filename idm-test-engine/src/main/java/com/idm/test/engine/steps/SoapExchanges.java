package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.SoapExchange;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.HttpEvidence;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a SOAP call captured by a connector into HTTP evidence: a SOAP call is a POST of an
 * envelope, so it is shown, masked and cut like any other HTTP exchange.
 */
final class SoapExchanges {

    private SoapExchanges() {
    }

    /** The call's evidence, or null when nothing was sent. */
    static HttpEvidence evidence(Optional<SoapExchange> captured, OperationResult<?> result) {
        if (captured.isEmpty()) {
            return null;
        }
        SoapExchange exchange = captured.get();
        Map<String, List<String>> headers = exchange.soapAction() == null
                ? Map.of()
                : Map.of("SOAPAction", List.of(exchange.soapAction()));
        String error = exchange.status() == null && !result.isSuccess() ? result.getMessage() : null;
        return new HttpEvidence("POST", exchange.endpoint(), headers, exchange.requestEnvelope(),
                exchange.status(), null, exchange.responseEnvelope(), exchange.durationMillis(), error);
    }
}
