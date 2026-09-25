package com.idm.test.engine.expr;

import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.CatchClause;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.UnaryExpression;
import org.mozilla.javascript.ast.VariableInitializer;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Finds the scenario names a {@code js:{ ... }} expression reads, so they can be checked at load
 * time like {@code ${...}} references and a cleanup entry can be skipped when one was never bound.
 * <p>
 * Static analysis only: the expression is parsed, never evaluated, which keeps validation free of
 * side effects. A name is free unless it is a property name, an object-literal key, the operand of
 * {@code typeof}, declared anywhere in the expression, or a standard JavaScript global. Declared
 * anywhere counts as local — no lexical scoping — so a local that shadows a scenario name is
 * simply not reported: this can only under-report, never reject a valid expression. Any name it
 * does report must come from the scenario's scope at run time, or it is a guaranteed
 * ReferenceError.
 */
public final class JsReferences {

    private static final Set<String> STANDARD_GLOBALS = standardGlobals();

    private JsReferences() {
    }

    /**
     * @param expression the body of a {@code js:{ ... }} expression
     * @return the free root names it reads, in source order; empty for a malformed expression.
     * Never throws: this is a query, and a malformed expression is reported by
     * {@link ExpressionEvaluator#validate} and at evaluation.
     */
    public static Set<String> freeNames(String expression) {
        AstRoot root;
        try {
            CompilerEnvirons environment = new CompilerEnvirons();
            environment.setLanguageVersion(Context.VERSION_ES6);
            root = new Parser(environment).parse(expression, "expression", 1);
        } catch (RuntimeException malformed) {
            return Set.of();
        }

        Set<String> declared = new HashSet<>();
        root.visit(node -> {
            if (node instanceof VariableInitializer initializer) {
                addNames(initializer.getTarget(), declared);
            } else if (node instanceof FunctionNode function) {
                if (function.getFunctionName() != null) {
                    declared.add(function.getFunctionName().getIdentifier());
                }
                function.getParams().forEach(parameter -> addNames(parameter, declared));
            } else if (node instanceof CatchClause catchClause && catchClause.getVarName() != null) {
                declared.add(catchClause.getVarName().getIdentifier());
            }
            return true;
        });

        Set<String> free = new LinkedHashSet<>();
        root.visit(node -> {
            if (node instanceof Name name && isReference(name)) {
                String identifier = name.getIdentifier();
                if (!declared.contains(identifier) && !STANDARD_GLOBALS.contains(identifier)) {
                    free.add(identifier);
                }
            }
            return true;
        });
        return free;
    }

    /** False for a name that is not read as a variable: a property, an object key, a typeof operand. */
    private static boolean isReference(Name name) {
        AstNode parent = name.getParent();
        if (parent instanceof PropertyGet get && get.getProperty() == name) {
            return false;
        }
        if (parent instanceof ObjectProperty property && property.getLeft() == name) {
            return false;
        }
        return !(parent instanceof UnaryExpression unary && unary.getType() == Token.TYPEOF);
    }

    /** Every name in a declaration target, including inside a destructuring pattern. */
    private static void addNames(AstNode pattern, Set<String> into) {
        if (pattern == null) {
            return;
        }
        pattern.visit(node -> {
            if (node instanceof Name name) {
                into.add(name.getIdentifier());
            }
            return true;
        });
    }

    private static Set<String> standardGlobals() {
        Context context = new ContextFactory().enterContext();
        try {
            context.setLanguageVersion(Context.VERSION_ES6);
            ScriptableObject scope = context.initStandardObjects();
            Set<String> names = new HashSet<>();
            for (Object id : scope.getAllIds()) {
                names.add(String.valueOf(id));
            }
            names.add("arguments");
            return Set.copyOf(names);
        } finally {
            Context.exit();
        }
    }
}
