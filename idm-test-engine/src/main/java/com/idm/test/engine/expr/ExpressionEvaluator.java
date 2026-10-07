package com.idm.test.engine.expr;

import com.idm.test.engine.spi.Explained;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.ArrayList;
import java.util.List;
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
 * Expressions are plain ES6. There is deliberately no helper object: standard JavaScript
 * already covers date arithmetic, capitalisation, padding, conditionals and JSON.
 */
public final class ExpressionEvaluator {

    /** Built-in functions every expression can call; scenario names may not reuse them. */
    public static final Set<String> HELPERS = Set.of("containsAll", "excludesAll", "sameMembers");

    /**
     * The list helpers. Values compare by canonical text: a primitive as String(v), an object as
     * its sorted keys with their values' canonical text, an array element by element. A helper
     * that returns false records one note in __checkNotes, which evaluateExplained reads back.
     */
    private static final String PRELUDE = """
            var __checkNotes = [];
            function __plain(v) {
              return v instanceof Date || v instanceof RegExp;
            }
            function __key(v, nested) {
              if (Array.isArray(v)) {
                return '\u0001[' + v.map(function (e) { return __key(e, true); }).join(',') + ']';
              }
              if (v !== null && typeof v === 'object' && !__plain(v)) {
                return '\u0001{' + Object.keys(v).sort().map(function (k) {
                  return JSON.stringify(k) + ':' + __key(v[k], true);
                }).join(',') + '}';
              }
              return nested ? JSON.stringify(String(v)) : String(v);
            }
            function __disp(v, nested) {
              if (Array.isArray(v)) {
                return '[' + v.map(function (e) { return __disp(e, true); }).join(', ') + ']';
              }
              if (v !== null && typeof v === 'object' && !__plain(v)) {
                return '{' + Object.keys(v).sort().map(function (k) {
                  return k + ': ' + __disp(v[k], true);
                }).join(', ') + '}';
              }
              return nested && typeof v === 'string' ? JSON.stringify(v) : String(v);
            }
            function __list(v) {
              return v === null || v === undefined ? [] : (Array.isArray(v) ? v : [v]);
            }
            function __select(from, against, wantPresent) {
              var known = Object.create(null), seen = Object.create(null), out = [];
              against.forEach(function (v) { known['k' + __key(v, false)] = true; });
              from.forEach(function (v) {
                var c = 'k' + __key(v, false);
                if (seen[c]) return;
                seen[c] = true;
                if ((known[c] === true) === wantPresent) out.push(__disp(v, false));
              });
              return out;
            }
            function __show(values) {
              var shown = values.slice(0, 10);
              if (values.length > 10) shown.push('\\u2026 ' + (values.length - 10) + ' more');
              return '[' + shown.join(', ') + ']';
            }
            function containsAll(list, values) {
              var missing = __select(__list(values), __list(list), false);
              if (missing.length === 0) return true;
              __checkNotes.push('containsAll: missing ' + __show(missing));
              return false;
            }
            function excludesAll(list, values) {
              var found = __select(__list(values), __list(list), true);
              if (found.length === 0) return true;
              __checkNotes.push('excludesAll: found ' + __show(found));
              return false;
            }
            function sameMembers(list, values) {
              var a = __list(list), b = __list(values);
              var missing = __select(b, a, false), unexpected = __select(a, b, false);
              if (missing.length === 0 && unexpected.length === 0) return true;
              var parts = [];
              if (missing.length) parts.push('missing ' + __show(missing));
              if (unexpected.length) parts.push('unexpected ' + __show(unexpected));
              __checkNotes.push('sameMembers: ' + parts.join('; '));
              return false;
            }
            """;

    /** Enough for any reasonable expression; a runaway loop exceeds it in milliseconds. */
    private static final int DEFAULT_INSTRUCTION_BUDGET = 100_000;

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
     *                             an expression that exceeds its instruction budget, a result
     *                             that cannot be converted back to a plain Java value, or a
     *                             foreign Rhino Context already open on this thread
     */
    public Object evaluate(String expression, Map<String, Object> variables) {
        return evaluateExplained(expression, variables).value();
    }

    /**
     * Like {@link #evaluate}, plus the notes the list helpers recorded while the expression ran.
     *
     * @throws ExpressionException as {@link #evaluate} does
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
     * @throws ExpressionException on a syntax error, with the expression text in the message, or
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

            // A fresh scope per evaluation: nothing one expression defines is visible to the next.
            Scriptable scope = cx.initStandardObjects();
            variables.forEach((name, value) ->
                    ScriptableObject.putProperty(scope, name, JsValues.toJs(cx, scope, value)));

            // Deny every Java class. Set after the scope is populated, though JsValues never
            // wraps a Java object anyway, which is the point of it.
            cx.setClassShutter(className -> false);

            // The helpers are defined after the scenario's names, so they always mean the
            // built-ins; the validator refuses scenario names that would collide with them.
            cx.evaluateString(scope, PRELUDE, "helpers", 1, null);

            Object value = JsValues.fromJs(cx.evaluateString(scope, expression, "expression", 1, null));
            return new Explained(value, notesIn(scope));

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

    private static List<String> notesIn(Scriptable scope) {
        List<String> result = new ArrayList<>();
        Object notes;
        try {
            notes = JsValues.fromJs(ScriptableObject.getProperty(scope, "__checkNotes"));
        } catch (RuntimeException e) {
            return result;
        }
        if (notes instanceof List<?> list) {
            list.forEach(note -> result.add(String.valueOf(note)));
        }
        return result;
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
