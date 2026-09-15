package com.idm.test.engine.expr;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.Map;

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

    private final ContextFactory contextFactory;

    public ExpressionEvaluator() {
        this(DEFAULT_INSTRUCTION_BUDGET);
    }

    public ExpressionEvaluator(int instructionBudget) {
        this.contextFactory = new BudgetedContextFactory(instructionBudget);
    }

    /**
     * @param expression a JavaScript expression; its final value is the result
     * @param variables  scenario variables, made available by name
     * @return a plain Java value — String, Double, Boolean, Map, List or null
     * @throws ExpressionException on a syntax error, a sandbox violation, an unknown variable,
     *                             or an expression that exceeds its instruction budget
     */
    public Object evaluate(String expression, Map<String, Object> variables) {
        Context cx = contextFactory.enterContext();
        try {
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
