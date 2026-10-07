package com.idm.test.engine.expr;

import com.idm.test.engine.spi.Explained;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.Map;
import java.util.Set;

/**
 * Evaluates one JavaScript expression against a set of scenario variables.
 * <p>
 * The governing rule is that <strong>expressions compute values, they never perform
 * actions</strong>. Every Java class is denied, so an expression cannot reach a connector, the
 * filesystem, the network or the clock's host process; and an instruction budget stops one that
 * never terminates. Steps remain the only things in a scenario that do anything, which is what
 * keeps a scenario's step list its complete execution order.
 * <p>
 * Expressions are plain ES6: standard JavaScript already covers date arithmetic,
 * capitalisation, padding, conditionals and JSON. The one addition is the three list helpers
 * ({@link #HELPERS}), global functions implemented in Java by {@link ListHelpers}; there is no
 * helper object.
 */
public final class ExpressionEvaluator {

    /** Built-in functions every expression can call; scenario names may not reuse them. */
    public static final Set<String> HELPERS = Set.of("containsAll", "excludesAll", "sameMembers");

    /** Enough for any reasonable expression; a runaway loop exceeds it in milliseconds. */
    private static final int DEFAULT_INSTRUCTION_BUDGET = 100_000;

    /**
     * Frames an expression may nest before it is stopped. Deep enough for any reasonable
     * recursion; endless recursion reaches it long before the Java stack overflows. It sits
     * below the roughly 870 frames at which the default instruction budget would stop
     * recursion (Rhino charges about 100 units per call), so recursion gets its own message.
     */
    static final int MAX_STACK_DEPTH = 700;

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
     *                             an expression that exceeds its instruction budget or nests or
     *                             recurses deeper than {@link #MAX_STACK_DEPTH}, a result
     *                             that cannot be converted back to a plain Java value, or a
     *                             foreign Rhino Context already open on this thread
     */
    public Object evaluate(String expression, Map<String, Object> variables) {
        return evaluateExplained(expression, variables).value();
    }

    /**
     * Like {@link #evaluate}, plus the notes the list helpers recorded while the expression ran.
     *
     * @throws ExpressionException as {@link #evaluate} does, including for an expression that
     *                             nests or recurses too deeply
     */
    public Explained evaluateExplained(String expression, Map<String, Object> variables) {
        Context current = Context.getCurrentContext();
        if (current != null && current.getFactory() != contextFactory) {
            // A Context created by unrelated code (a different ExpressionEvaluator, or some other
            // Rhino use entirely) is already bound to this thread. Rhino permanently ties a
            // Context's instruction-budget callback to whichever ContextFactory created it
            // (Context#getFactory is a final field, set once at construction), so entering our
            // own factory here would just reuse that foreign Context and inherit ITS callback -
            // which does nothing, and the budget would silently never fire. Rather than trying to
            // dodge that on a separate thread (which was tried, and turned out to trade one hole
            // for two: Rhino honours no interrupt, so a stuck worker cannot be stopped on timeout,
            // and a worker that catches only RuntimeException lets an Error escape it silently as
            // a fabricated null result), fail fast instead: refuse to evaluate at all so the
            // caller finds out immediately, deterministically, and via the same exception type as
            // every other failure mode.
            throw new ExpressionException(
                    "cannot evaluate js:{ " + expression + " }: another Rhino Context is already "
                            + "open on this thread, so the instruction budget would not apply to it",
                    null);
        }
        return doEvaluate(expression, variables);
    }

    /**
     * Checks that {@code expression} is syntactically valid JavaScript, without running it.
     * <p>
     * Compiling is the check: nothing here evaluates the expression, so this is safe to call
     * during scenario validation, before any step has run — the governing rule is that
     * expressions compute values and never perform actions, and validating must not itself be
     * an action. {@code ScenarioValidator} is the intended caller, alongside
     * {@link com.idm.test.engine.Interpolator#expressionBody(String)} for the structural checks
     * (embedded, unterminated, empty) that this method does not repeat.
     *
     * @param expression a JavaScript expression, as extracted from a {@code js:{ ... }} value
     * @throws ExpressionException on a syntax error or an expression nested too deeply to
     *                             compile, with the expression text in the message, or
     *                             a foreign Rhino Context already open on this thread (see
     *                             {@link #evaluate})
     */
    public void validate(String expression) {
        Context current = Context.getCurrentContext();
        if (current != null && current.getFactory() != contextFactory) {
            throw new ExpressionException(
                    "cannot validate js:{ " + expression + " }: another Rhino Context is already "
                            + "open on this thread", null);
        }

        Context cx = contextFactory.enterContext();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            // compileString parses and never executes, which is exactly the "check, don't run"
            // this method promises: a syntax error throws here, and a well-formed expression's
            // compiled Script is simply discarded.
            cx.compileString(expression, "expression", 1, null);
        } catch (StackOverflowError e) {
            throw new ExpressionException(
                    "invalid expression js:{ " + expression + " }: it is nested too deeply", e);
        } catch (RuntimeException e) {
            throw new ExpressionException(
                    "invalid expression js:{ " + expression + " }: " + e.getMessage(), e);
        } finally {
            Context.exit();
        }
    }

    private Explained doEvaluate(String expression, Map<String, Object> variables) {
        Context cx = contextFactory.enterContext();
        try {
            // Belt and braces: onContextCreated only runs the first time a Context is created,
            // not on every reuse, so the threshold might not have been set for this particular
            // entry. Setting it again here is harmless when it already was.
            cx.setInstructionObserverThreshold(instructionBudget);
            cx.setLanguageVersion(Context.VERSION_ES6);

            // Interpreted mode makes recursion depth countable: Rhino stops an expression that
            // nests deeper than MAX_STACK_DEPTH with its own error instead of overflowing the
            // Java stack. The instruction budget still applies but counts work differently in
            // this mode, so slightly more work fits in it than before.
            cx.setInterpretedMode(true);
            cx.setMaximumInterpreterStackDepth(MAX_STACK_DEPTH);

            // A fresh scope per evaluation: nothing one expression defines is visible to the next.
            Scriptable scope = cx.initStandardObjects();
            variables.forEach((name, value) ->
                    ScriptableObject.putProperty(scope, name, JsValues.toJs(cx, scope, value)));

            // Deny every Java class. Set after the scope is populated, though JsValues never
            // wraps a Java object anyway, which is the point of it.
            cx.setClassShutter(className -> false);

            // The helpers are defined after the scenario's names, so they always mean the
            // built-ins; the validator refuses scenario names that would collide with them. Their
            // notes are collected in Java, out of the expression's reach.
            ListHelpers helpers = ListHelpers.defineIn(cx, scope);

            Object value = JsValues.fromJs(cx.evaluateString(scope, expression, "expression", 1, null));
            return new Explained(value, helpers.notes());

        } catch (InstructionBudgetExceeded e) {
            throw new ExpressionException(
                    "expression ran too long and was stopped: js:{ " + expression + " }", e);
        } catch (ExpressionException e) {
            throw e;
        } catch (StackOverflowError e) {
            // Recursion the interpreter cannot count, through Java, such as a getter that calls
            // a list helper on its own object. The stack has unwound by now; Context.exit() below
            // still runs, and the next evaluation gets a fresh scope.
            throw recursedTooDeeply(expression, e);
        } catch (RuntimeException e) {
            if (e instanceof org.mozilla.javascript.EvaluatorException
                    && String.valueOf(e.getMessage()).contains("Exceeded maximum stack depth")) {
                throw recursedTooDeeply(expression, e);
            }
            throw new ExpressionException(
                    "could not evaluate js:{ " + expression + " }: " + e.getMessage(), e);
        } finally {
            Context.exit();
        }
    }

    private static ExpressionException recursedTooDeeply(String expression, Throwable cause) {
        return new ExpressionException(
                "expression nested or recursed too deeply and was stopped: js:{ " + expression + " }", cause);
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
