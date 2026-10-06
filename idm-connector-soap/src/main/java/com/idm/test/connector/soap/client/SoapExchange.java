package com.idm.test.connector.soap.client;

/**
 * One SOAP call as it went over the wire: what was sent and what came back. Holds no HTTP
 * headers, so the Basic credentials are never part of it.
 *
 * @param endpoint         the address the request went to
 * @param soapAction       the SOAPAction sent, or null
 * @param requestEnvelope  the outgoing envelope as text
 * @param status           the HTTP status, or null when no SOAP response arrived
 * @param responseEnvelope the incoming envelope, a normal response or a fault, or null
 * @param durationMillis   from sending to receiving, or to when the call ended without a response
 */
public record SoapExchange(String endpoint, String soapAction, String requestEnvelope, Integer status,
                           String responseEnvelope, long durationMillis) {
}
