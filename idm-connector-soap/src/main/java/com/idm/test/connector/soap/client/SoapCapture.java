package com.idm.test.connector.soap.client;

import jakarta.xml.soap.MimeHeaders;
import jakarta.xml.ws.Binding;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.handler.Handler;
import jakarta.xml.ws.handler.MessageContext;
import jakarta.xml.ws.handler.soap.SOAPHandler;
import jakarta.xml.ws.handler.soap.SOAPMessageContext;

import javax.xml.namespace.QName;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Copies each SOAP call's envelopes, endpoint, SOAPAction and HTTP status, so a step can show
 * what it exchanged. Kept per thread: JAX-WS calls are synchronous and run handlers on the
 * calling thread, so the thread that made a call reads its own exchange. Never throws and never
 * stops a message, and records no HTTP headers, so the Basic credentials are never captured.
 */
final class SoapCapture implements SOAPHandler<SOAPMessageContext> {

    private final ThreadLocal<SoapExchange> last = new ThreadLocal<>();
    private final ThreadLocal<Long> started = new ThreadLocal<>();

    /** Adds a capture to the end of the port's handler chain and returns it. */
    @SuppressWarnings("rawtypes")
    static SoapCapture installOn(BindingProvider port) {
        SoapCapture capture = new SoapCapture();
        Binding binding = port.getBinding();
        List<Handler> chain = new ArrayList<>(binding.getHandlerChain());
        chain.add(capture);
        binding.setHandlerChain(chain);
        return capture;
    }

    /** Forgets the last exchange; called at the start of every connector operation. */
    void clear() {
        last.remove();
        started.remove();
    }

    /**
     * The last exchange on this thread. When no response arrived, its duration runs to now, which
     * is when the connector call ended.
     */
    Optional<SoapExchange> last() {
        SoapExchange exchange = last.get();
        Long start = started.get();
        if (exchange != null && exchange.status() == null && exchange.responseEnvelope() == null && start != null) {
            exchange = new SoapExchange(exchange.endpoint(), exchange.soapAction(), exchange.requestEnvelope(),
                    null, null, System.currentTimeMillis() - start);
        }
        return Optional.ofNullable(exchange);
    }

    @Override
    public Set<QName> getHeaders() {
        return Set.of();
    }

    @Override
    public boolean handleMessage(SOAPMessageContext context) {
        record(context);
        return true;
    }

    @Override
    public boolean handleFault(SOAPMessageContext context) {
        record(context);
        return true;
    }

    @Override
    public void close(MessageContext context) {
        // nothing held per message
    }

    private void record(SOAPMessageContext context) {
        try {
            boolean outbound = Boolean.TRUE.equals(context.get(MessageContext.MESSAGE_OUTBOUND_PROPERTY));
            String envelope = envelope(context);
            if (outbound) {
                started.set(System.currentTimeMillis());
                last.set(new SoapExchange(stringOf(context.get(BindingProvider.ENDPOINT_ADDRESS_PROPERTY)),
                        soapAction(context), envelope, null, null, 0));
                return;
            }
            SoapExchange request = last.get();
            if (request == null) {
                return;
            }
            Long start = started.get();
            Object code = context.get(MessageContext.HTTP_RESPONSE_CODE);
            last.set(new SoapExchange(request.endpoint(), request.soapAction(), request.requestEnvelope(),
                    code instanceof Integer status ? status : null, envelope,
                    start == null ? 0 : System.currentTimeMillis() - start));
        } catch (RuntimeException e) {
            // Capturing is a convenience for the report; it must never break the call.
        }
    }

    private static String envelope(SOAPMessageContext context) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            context.getMessage().writeTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(could not read envelope: " + e.getClass().getSimpleName() + ")";
        }
    }

    /** The SOAPAction property, else the SOAPAction MIME header of the message, else the HTTP header. */
    @SuppressWarnings("unchecked")
    private static String soapAction(SOAPMessageContext context) {
        Object property = context.get(BindingProvider.SOAPACTION_URI_PROPERTY);
        if (property != null && !String.valueOf(property).isEmpty()) {
            return String.valueOf(property);
        }
        try {
            MimeHeaders mime = context.getMessage().getMimeHeaders();
            String[] values = mime == null ? null : mime.getHeader("SOAPAction");
            if (values != null && values.length > 0) {
                return values[0];
            }
        } catch (RuntimeException ignored) {
            // fall through to the HTTP headers
        }
        Object headers = context.get(MessageContext.HTTP_REQUEST_HEADERS);
        if (headers instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("SOAPAction".equalsIgnoreCase(String.valueOf(entry.getKey()))
                        && entry.getValue() instanceof List<?> values && !values.isEmpty()) {
                    return String.valueOf(values.get(0));
                }
            }
        }
        return null;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
