package com.idm.test.engine.expr;

import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.BreakStatement;
import org.mozilla.javascript.ast.CatchClause;
import org.mozilla.javascript.ast.ContinueStatement;
import org.mozilla.javascript.ast.ElementGet;
import org.mozilla.javascript.ast.ForInLoop;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.GeneratorMethodDefinition;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.UnaryExpression;
import org.mozilla.javascript.ast.VariableDeclaration;
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
 * {@code typeof}, a break/continue label, a generator method's key, declared anywhere in the
 * expression, or a standard JavaScript global. Declared anywhere counts as local — no lexical
 * scoping — so a local that shadows a scenario name is simply not reported: this can only
 * under-report, never reject a valid expression. A plain-assignment target
 * ({@code total = user.a}), a destructuring assignment ({@code [x, y] = [1, 2]}) and a
 * {@code for-in}/{@code for-of} variable without {@code var} all count as declared too, because
 * expressions run in sloppy mode where assigning to an undeclared name creates a global rather
 * than throwing; a compound assignment ({@code x += 1}) is still reported, since that is a real
 * ReferenceError when {@code x} does not exist. Any name it does report must come from the
 * scenario's scope at run time, or it is a guaranteed ReferenceError. Never throws.
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
        try {
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
                } else if (node instanceof Assignment assignment && assignment.getType() == Token.ASSIGN) {
                    // Sloppy mode: assigning to an undeclared name creates a global, it does not throw.
                    addNames(assignment.getLeft(), declared);
                } else if (node instanceof ForInLoop forIn && !(forIn.getIterator() instanceof VariableDeclaration)) {
                    // "for (k in user)" without var: k becomes a global too, same reasoning as above.
                    addNames(forIn.getIterator(), declared);
                }
                return true;
            });
        } catch (RuntimeException e) {
            return Set.of();
        }

        Set<String> free = new LinkedHashSet<>();
        try {
            root.visit(node -> {
                if (node instanceof Name name && isReference(name)) {
                    String identifier = name.getIdentifier();
                    if (!declared.contains(identifier) && !STANDARD_GLOBALS.contains(identifier)) {
                        free.add(identifier);
                    }
                }
                return true;
            });
        } catch (RuntimeException e) {
            return Set.of();
        }
        return free;
    }

    /**
     * False for a name that is not read as a variable: a property, an object key, a typeof
     * operand, a break/continue label, or a generator method's key.
     */
    private static boolean isReference(Name name) {
        AstNode parent = name.getParent();
        if (parent instanceof PropertyGet get && get.getProperty() == name) {
            return false;
        }
        if (parent instanceof ObjectProperty property && property.getLeft() == name) {
            return false;
        }
        if (parent instanceof UnaryExpression unary && unary.getType() == Token.TYPEOF) {
            return false;
        }
        if (parent instanceof BreakStatement breakStatement && breakStatement.getBreakLabel() == name) {
            return false;
        }
        if (parent instanceof ContinueStatement continueStatement && continueStatement.getLabel() == name) {
            return false;
        }
        return !(parent instanceof GeneratorMethodDefinition generator && generator.getMethodName() == name);
    }

    /**
     * Every name in a declaration or assignment target, including inside a destructuring pattern.
     * Stops at a member-expression target ({@code o.p} or {@code o[p]}): its object is a reference,
     * not a declaration, so it is left for the normal free-name pass to report.
     */
    private static void addNames(AstNode pattern, Set<String> into) {
        if (pattern == null) {
            return;
        }
        pattern.visit(node -> {
            if (node instanceof PropertyGet || node instanceof ElementGet) {
                return false;
            }
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
