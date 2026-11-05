package com.idm.test.connector.ldap.config;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.pool2.factory.PoolConfig;
import org.springframework.ldap.pool2.factory.PooledContextSource;

import javax.naming.directory.DirContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link PooledContextSource} that reports why a connection could not be opened. The pool wraps
 * every failure in "Failed to borrow DirContext from pool.", and Spring LDAP's own exception often
 * says no more than the host; the reason (connection refused, an untrusted certificate, a refused
 * login) is further down the cause chain. Here the message is that chain's texts, and the cause is
 * kept, so the connector still classifies the failure from it.
 */
final class CauseReportingPooledContextSource extends PooledContextSource {

    CauseReportingPooledContextSource(ContextSource contextSource, PoolConfig poolConfig) {
        super(contextSource, poolConfig);
    }

    @Override
    public DirContext getReadOnlyContext() {
        return withCause(super::getReadOnlyContext);
    }

    @Override
    public DirContext getReadWriteContext() {
        return withCause(super::getReadWriteContext);
    }

    private static DirContext withCause(Supplier<DirContext> borrow) {
        try {
            return borrow.get();
        } catch (DataAccessResourceFailureException e) {
            if (e.getCause() == null) {
                throw e;
            }
            throw new DataAccessResourceFailureException(describe(e.getCause()), e.getCause());
        }
    }

    /** Each distinct message along the cause chain, outermost first, joined by ": ". */
    static String describe(Throwable failure) {
        List<String> parts = new ArrayList<>();
        for (Throwable t = failure; t != null && parts.size() < 8; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage();
            if (message == null || message.isBlank()) {
                continue;
            }
            String text = message.strip();
            if (parts.stream().noneMatch(part -> part.contains(text))) {
                parts.add(text);
            }
        }
        return parts.isEmpty() ? failure.getClass().getSimpleName() : String.join(": ", parts);
    }
}
