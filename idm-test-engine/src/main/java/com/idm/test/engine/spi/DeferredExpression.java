package com.idm.test.engine.spi;

import java.util.Map;

/**
 * A step parameter the engine did not resolve up front, because the step must evaluate it
 * itself, usually once per attempt against something only the step knows (for example the
 * response it just received). See {@link StepType#deferredParams()}.
 */
public interface DeferredExpression {

    /** The expression exactly as written, e.g. {@code js:{ response.status === 200 }}, for messages. */
    String source();

    /**
     * Evaluates against the scenario's names as they were when the step started, plus
     * {@code bindings}. A binding wins over a scenario name of the same name.
     *
     * @return a plain Java value, as any {@code js:{}} expression produces
     * @throws RuntimeException when the expression throws, or produces {@code undefined}
     */
    Object evaluate(Map<String, Object> bindings);
}
