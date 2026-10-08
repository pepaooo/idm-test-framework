package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.Timeouts;
import com.idm.test.connector.rest.model.RestResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.HttpEvidence;

import java.util.Locale;
import java.util.Map;

/** Sends a rest.* request and records the exchange as evidence. */
final class RestExchange {

    /** The connector's result and the evidence, null when the method was refused before sending. */
    record Sent(OperationResult<RestResponse> result, HttpEvidence evidence) {
    }

    private RestExchange() {
    }

    static Sent send(IdmRestConnector connector, String method, String url, Object body,
                     Map<String, String> headers, Timeouts timeouts) {
        long started = System.currentTimeMillis();
        OperationResult<RestResponse> result = connector.exchange(method, url, body, headers, timeouts);
        long millis = System.currentTimeMillis() - started;
        String upper = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (!IdmRestConnector.SUPPORTED_METHODS.contains(upper)) {
            return new Sent(result, null);
        }
        RestResponse response = result.isSuccess() ? result.getData().orElse(null) : null;
        return new Sent(result, new HttpEvidence(upper, url, HttpEvidence.headersOf(headers), body,
                response == null ? null : response.status(),
                response == null ? null : response.headers(),
                response == null ? null : response.body(),
                millis, response == null ? result.getMessage() : null));
    }
}
