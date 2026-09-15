package com.idm.test.engine.expr;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Evaluates one JavaScript expression against a set of scenario variables.
 * <p>
 * The governing rule is that <strong>expressions compute values, they never perform
 * actions</strong>. Every Java class is denied, so an expression cannot reach a connector, the
 * filesystem, the network or the clock's host process; and an instruction budget stops one that
 * never terminates. Steps remain the only things in a scenario that do anything, which is what
 * keeps a scenario's step list its complete execution order.
 * <p>
 * Expressions are plain ES6. There is deliberately no helper object: standard JavaScript
 * already covers date arithmetic, capitalisation, padding, conditionals and JSON.
 */
public final class ExpressionEvaluator {

    /** Enough for any reasonable expression; a runaway loop exceeds it in milliseconds. */
    private static final int DEFAULT_INSTRUCTION_BUDGET = 100_000;

    /**
     * How long the calling thread waits for {@link #evaluateIsolated} to finish. The worker
     * thread is itself bounded by the instruction budget, so it should return in milliseconds;
     * this is only a last-resort safety net against an unforeseen Rhino behaviour, so it is set
     * generously rather than tuned tightly.
     */
    private static final long ISOLATION_TIMEOUT_MILLIS = 30_000L;

    private final ContextFactory contextFactory;
    private final int instructionBudget;

    public ExpressionEvaluator() {
        this(DEFAULT_INSTRUCTION_BUDGET);
    }

    public ExpressionEvaluator(int instructionBudget) {
        this.instructionBudget = instructionBudget;
        this.contextFactory = new BudgetedContextFactory(instructionBudget);
    }

    /**
     * @param expression a JavaScript expression; its final value is the result
     * @param variables  scenario variables, made available by name
     * @return a plain Java value — String, Double, Boolean, Map, List or null
     * @throws ExpressionException on a syntax error, a sandbox violation, an unknown variable,
     *                             an expression that exceeds its instruction budget, or a result
     *                             that cannot be converted back to a plain Java value
     */
    public Object evaluate(String expression, Map<String, Object> variables) {
        Context current = Context.getCurrentContext();
        if (current != null && current.getFactory() != contextFactory) {
            // A Context created by unrelated code (a different ExpressionEvaluator, or some other
            // Rhino use entirely) is already bound to this thread. Rhino permanently ties a
            // Context's instruction-budget callback to whichever ContextFactory created it
            // (Context#getFactory is a final field, set once at construction), so entering our
            // own factory here would just reuse that foreign Context and inherit ITS callback -
            // which does nothing. The budget would silently never fire and a runaway expression
            // would hang this thread forever. Evaluating on a fresh thread, where nothing is
            // bound yet, guarantees our factory creates its own Context and the budget genuinely
            // applies.
            return evaluateIsolated(expression, variables);
        }
        return doEvaluate(expression, variables);
    }

    private Object doEvaluate(String expression, Map<String, Object> variables) {
        Context cx = contextFactory.enterContext();
        try {
            // Belt and braces: onContextCreated only runs the first time a Context is created,
            // not on every reuse, so the threshold might not have been set for this particular
            // entry. Setting it again here is harmless when it already was.
            cx.setInstructionObserverThreshold(instructionBudget);
            cx.setLanguageVersion(Context.VERSION_ES6);

            // A fresh scope per evaluation: nothing one expression defines is visible to the next.
            Scriptable scope = cx.initStandardObjects();
            variables.forEach((name, value) ->
                    ScriptableObject.putProperty(scope, name, JsValues.toJs(cx, scope, value)));

            // Deny every Java class. Set after the scope is populated, though JsValues never
            // wraps a Java object anyway, which is the point of it.
            cx.setClassShutter(className -> false);

            return JsValues.fromJs(cx.evaluateString(scope, expression, "expression", 1, null));

        } catch (InstructionBudgetExceeded e) {
            throw new ExpressionException(
                    "expression ran too long and was stopped: js:{ " + expression + " }", e);
        } catch (ExpressionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ExpressionException(
                    "could not evaluate js:{ " + expression + " }: " + e.getMessage(), e);
        } finally {
            Context.exit();
        }
    }

    /**
     * Runs {@link #doEvaluate} on a dedicated, throwaway thread so it starts with no Context
     * bound at all, regardless of what the calling thread is doing with Rhino. See the comment
     * in {@link #evaluate} for why that is necessary.
     */
    private Object evaluateIsolated(String expression, Map<String, Object> variables) {
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                result.set(doEvaluate(expression, variables));
            } catch (RuntimeException e) {
                failure.set(e);
            }
        }, "expression-evaluator-isolated");
        worker.setDaemon(true);
        worker.start();

        try {
            worker.join(ISOLATION_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExpressionException(
                    "interrupted while evaluating js:{ " + expression + " }", e);
        }
        if (worker.isAlive()) {
            // Should not happen - doEvaluate's own instruction budget bounds it - but this is
            // the contract's last line of defence: never hang the caller.
            throw new ExpressionException(
                    "expression did not finish within " + ISOLATION_TIMEOUT_MILLIS
                            + "ms on an isolated thread: js:{ " + expression + " }", null);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    /**
     * Stops an expression that exceeds its instruction budget.
     */
    private static final class BudgetedContextFactory extends ContextFactory {

        private final int budget;

        private BudgetedContextFactory(int budget) {
            this.budget = budget;
        }

        @Override
        protected void onContextCreated(Context cx) {
            cx.setInstructionObserverThreshold(budget);
            super.onContextCreated(cx);
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            throw new InstructionBudgetExceeded();
        }
    }

    /** Internal signal; callers see an ExpressionException. */
    private static final class InstructionBudgetExceeded extends RuntimeException {
        private InstructionBudgetExceeded() {
            super("instruction budget exceeded");
        }
    }

    /**
     * An expression could not be evaluated. The message always includes the expression text,
     * because the reader is often not a programmer.
     */
    public static class ExpressionException extends RuntimeException {
        public ExpressionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
