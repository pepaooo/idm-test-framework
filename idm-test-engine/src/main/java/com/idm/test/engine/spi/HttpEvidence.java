package com.idm.test.engine.spi;

import com.idm.test.engine.result.ReportUrls;

import java.util.List;
import java.util.Map;

/**
 * One HTTP exchange as a step made it. Bodies are text for http.* and JSON structures for rest.*.
 * The constructor masks secret headers and body values, sanitises the URL, the {@code Location} and
 * {@code Content-Location} header values and the error text as URLs, and cuts long bodies.
 *
 * @param status   the response status, or null when there was no response
 * @param error    why there was no response, or null
 */
public record HttpEvidence(String method, String url, Map<String, List<String>> requestHeaders, Object requestBody,
                           Integer status, Map<String, List<String>> responseHeaders, Object responseBody,
                           long durationMillis, String error) implements Evidence {

    public HttpEvidence {
        url = url == null ? null : ReportUrls.sanitise(url);
        requestHeaders = EvidenceMasking.headers(requestHeaders);
        requestBody = EvidenceMasking.body(requestBody);
        responseHeaders = EvidenceMasking.headers(responseHeaders);
        responseBody = EvidenceMasking.body(responseBody);
        error = ReportUrls.sanitise(error);
    }

    /** Request headers as the steps hold them, one value per name. */
    public static Map<String, List<String>> headersOf(Map<String, String> headers) {
        return EvidenceMasking.singleValued(headers);
    }
}
