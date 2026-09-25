package com.idm.test.engine.expr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("JsReferences")
class JsReferencesTest {

    @Test
    @DisplayName("Reports the root of a property chain, not the properties")
    void testRootOfPropertyChain() {
        assertThat(JsReferences.freeNames("user.firstName.charAt(0)")).containsExactly("user");
        assertThat(JsReferences.freeNames("rows[0].STATUS")).containsExactly("rows");
        assertThat(JsReferences.freeNames("'/users/' + user.userId")).containsExactly("user");
    }

    @Test
    @DisplayName("Reports an object literal's values, not its keys")
    void testObjectValueReportedKeyNot() {
        assertThat(JsReferences.freeNames("({ dn: created.dn, cn: 'x' })")).containsExactly("created");
    }

    @Test
    @DisplayName("Treats shorthand {a} as reading a")
    void testShorthandPropertyIsAReference() {
        assertThat(JsReferences.freeNames("({a, b: c})")).containsExactly("a", "c");
    }

    @Test
    @DisplayName("Reports free names inside a template literal's substitutions")
    void testTemplateLiteral() {
        assertThat(JsReferences.freeNames("`hello ${user.firstName}`")).containsExactly("user");
        assertThat(JsReferences.freeNames("`${ 1 + 1 }`")).isEmpty();
    }

    @Test
    @DisplayName("Treats function and arrow parameters as local")
    void testParametersAreLocal() {
        assertThat(JsReferences.freeNames("items.map(x => x.id)")).containsExactly("items");
        assertThat(JsReferences.freeNames("items.filter(function (y) { return y > limit; })"))
                .containsExactly("items", "limit");
    }

    @Test
    @DisplayName("Treats var, let, function names and catch parameters as local")
    void testDeclaredNamesAreLocal() {
        assertThat(JsReferences.freeNames("var d = 1; d + n")).containsExactly("n");
        assertThat(JsReferences.freeNames("let k = 2; k")).isEmpty();
        assertThat(JsReferences.freeNames("(function f(){ try { x } catch (e) { return e + z; } })()"))
                .containsExactly("x", "z");
    }

    @Test
    @DisplayName("Ignores standard globals")
    void testStandardGlobalsIgnored() {
        assertThat(JsReferences.freeNames(
                "Math.max(1, n) + JSON.stringify(o) + String(undefined) + NaN + Infinity"))
                .containsExactly("n", "o");
        assertThat(JsReferences.freeNames(
                "var d = new Date(user.startDate); d.setDate(d.getDate() + 30); d.toISOString()"))
                .containsExactly("user");
    }

    @Test
    @DisplayName("Ignores the operand of typeof, which is legal for a name that does not exist")
    void testTypeofOperandIgnored() {
        assertThat(JsReferences.freeNames("typeof missing")).isEmpty();
    }

    @Test
    @DisplayName("Returns nothing, and never throws, for a malformed or empty expression")
    void testMalformedReturnsEmpty() {
        assertThatCode(() -> JsReferences.freeNames("user.firstName.(")).doesNotThrowAnyException();
        assertThat(JsReferences.freeNames("user.firstName.(")).isEmpty();
        assertThat(JsReferences.freeNames("")).isEmpty();
        assertThat(JsReferences.freeNames("   ")).isEmpty();
    }

    @Test
    @DisplayName("Treats plain assignment targets as local, since sloppy mode creates them")
    void testAssignmentTargetsAreLocal() {
        assertThat(JsReferences.freeNames("total = user.a; total")).containsExactly("user");
        assertThat(JsReferences.freeNames("[x, y] = [1, 2]; x + y")).isEmpty();
        assertThat(JsReferences.freeNames("a = b = 1; a + b")).isEmpty();
        assertThat(JsReferences.freeNames("o.p = 1")).containsExactly("o");
    }

    @Test
    @DisplayName("Still reports a compound assignment target, which is a real ReferenceError")
    void testCompoundAssignmentStillReported() {
        assertThat(JsReferences.freeNames("x += 1")).containsExactly("x");
    }

    @Test
    @DisplayName("Treats a for-in variable without var as local")
    void testForInVariableIsLocal() {
        assertThat(JsReferences.freeNames("for (k in user) { n = k }")).containsExactly("user");
    }

    @Test
    @DisplayName("Ignores break and continue labels")
    void testLabelsIgnored() {
        assertThat(JsReferences.freeNames("lbl: for (var i = 0; i < 1; i++) { break lbl; }")).isEmpty();
        assertThat(JsReferences.freeNames("lbl: for (var i = 0; i < 1; i++) { continue lbl; }")).isEmpty();
    }

    @Test
    @DisplayName("Ignores a generator method's key")
    void testGeneratorMethodKeyIgnored() {
        assertThat(JsReferences.freeNames("({ *gen() { yield 1; } })")).isEmpty();
    }
}
