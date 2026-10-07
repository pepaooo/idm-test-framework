package com.idm.test.engine;

import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.spi.DeferredExpression;
import com.idm.test.engine.spi.Explained;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The engine's {@link DeferredExpression}: a js:{} body plus the scope it was written in. */
final class ScopedExpression implements DeferredExpression {

    private final String source;
    private final String body;
    private final Map<String, Object> scope;
    private final ExpressionEvaluator evaluator;

    ScopedExpression(String source, String body, Map<String, Object> scope, ExpressionEvaluator evaluator) {
        this.source = source;
        this.body = body;
        this.scope = Collections.unmodifiableMap(new LinkedHashMap<>(scope));
        this.evaluator = evaluator;
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public Object evaluate(Map<String, Object> bindings) {
        return explain(bindings).value();
    }

    @Override
    public Explained explain(Map<String, Object> bindings) {
        Map<String, Object> variables = new LinkedHashMap<>(scope);
        variables.putAll(bindings);
        return evaluator.evaluateExplained(body, variables);
    }

    @Override
    public String toString() {
        return source;
    }
}
