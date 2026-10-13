package com.idm.test.engine.steps;

import com.idm.test.engine.i18n.Messages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("XML to plain objects")
class XmlObjectsTest {

    @Test
    @DisplayName("Maps the spec's example exactly")
    void testSpecExample() {
        String xml = """
                <user id="42">
                  <status code="1">ACTIVE</status>
                  <name>Ana</name>
                  <role>Admin</role>
                  <role>HR</role>
                  <manager/>
                </user>
                """;

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("@id", "42");
        user.put("status", Map.of("@code", "1", "#text", "ACTIVE"));
        user.put("name", "Ana");
        user.put("role", List.of("Admin", "HR"));
        user.put("manager", "");
        assertThat(XmlObjects.parse(xml)).isEqualTo(Map.of("user", user));
    }

    @Test
    @DisplayName("Keys elements by local name and drops namespace declarations")
    void testNamespaces() {
        String xml = """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                  <soapenv:Body>
                    <getUserResponse xmlns="http://idm.example/users">
                      <ns2:status xmlns:ns2="http://idm.example/status">ACTIVE</ns2:status>
                    </getUserResponse>
                  </soapenv:Body>
                </soapenv:Envelope>
                """;

        assertThat(XmlObjects.parse(xml)).isEqualTo(
                Map.of("Envelope", Map.of("Body", Map.of("getUserResponse", Map.of("status", "ACTIVE")))));
    }

    @Test
    @DisplayName("A single element is a value; repeated siblings are a list in document order, even interleaved")
    void testRepeatedElements() {
        assertThat(XmlObjects.parse("<r><a>1</a></r>")).isEqualTo(Map.of("r", Map.of("a", "1")));
        assertThat(XmlObjects.parse("<r><a>1</a><b>x</b><a>2</a><a>3</a></r>"))
                .isEqualTo(Map.of("r", Map.of("a", List.of("1", "2", "3"), "b", "x")));
    }

    @Test
    @DisplayName("Attributes are @name; #text sits beside attributes or children, and is absent when empty")
    void testAttributesAndText() {
        assertThat(XmlObjects.parse("<p id=\"1\">Hello <b>x</b> world</p>"))
                .isEqualTo(Map.of("p", Map.of("@id", "1", "b", "x", "#text", "Hello world")));
        assertThat(XmlObjects.parse("<s code=\"1\"/>")).isEqualTo(Map.of("s", Map.of("@code", "1")));
        assertThat(XmlObjects.parse("<s code=\"1\">  </s>")).isEqualTo(Map.of("s", Map.of("@code", "1")));
    }

    @Test
    @DisplayName("Two attributes with the same local name become a list")
    void testSameLocalNameAttributes() {
        assertThat(XmlObjects.parse("<r xmlns:x=\"urn:x\" xmlns:y=\"urn:y\" x:id=\"1\" y:id=\"2\"/>"))
                .isEqualTo(Map.of("r", Map.of("@id", List.of("1", "2"))));
    }

    @Test
    @DisplayName("Empty and whitespace-only elements are empty strings")
    void testEmptyElements() {
        assertThat(XmlObjects.parse("<r><a/><b>   </b><c></c></r>"))
                .isEqualTo(Map.of("r", Map.of("a", "", "b", "", "c", "")));
        assertThat(XmlObjects.parse("<r/>")).isEqualTo(Map.of("r", ""));
    }

    @Test
    @DisplayName("CDATA is text; comments and processing instructions are ignored")
    void testCdataCommentsAndInstructions() {
        assertThat(XmlObjects.parse("<r><!-- note --><?pi data?><a><![CDATA[<x> & y]]></a></r>"))
                .isEqualTo(Map.of("r", Map.of("a", "<x> & y")));
    }

    @Test
    @DisplayName("Values stay strings")
    void testValuesAreStrings() {
        Map<String, Object> result = XmlObjects.parse("<r n=\"42\"><flag>true</flag><count>7</count></r>");

        assertThat(result).isEqualTo(Map.of("r", Map.of("@n", "42", "flag", "true", "count", "7")));
    }

    @Test
    @DisplayName("Malformed XML names the line and column")
    void testMalformed() {
        assertThatThrownBy(() -> XmlObjects.parse("<r>\n  <a>\n</r>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("not well-formed XML at line 3, column 3: ");
        assertThatThrownBy(() -> XmlObjects.parse("not xml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("not well-formed XML at line 1, column 1: ");
    }

    @Test
    @DisplayName("A DOCTYPE is refused as malformed")
    void testDoctypeRefused() {
        assertThatThrownBy(() -> XmlObjects.parse("<!DOCTYPE r [<!ENTITY e \"x\">]><r>&e;</r>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("not well-formed XML at line 1, column ");
    }

    @Test
    @DisplayName("An external entity (XXE) can't read a file")
    void testExternalEntityRefused(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-VALUE");
        String xml = "<!DOCTYPE r [<!ENTITY e SYSTEM \"" + secret.toUri() + "\">]><r>&e;</r>";

        assertThatThrownBy(() -> XmlObjects.parse(xml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("not well-formed XML")
                .hasMessageNotContaining("TOP-SECRET-VALUE");
    }

    @Test
    @DisplayName("Entity expansion (billion laughs) is refused")
    void testEntityExpansionRefused() {
        String xml = """
                <!DOCTYPE lolz [
                  <!ENTITY lol "lol">
                  <!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
                  <!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">
                  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
                ]>
                <lolz>&lol3;</lolz>
                """;

        assertThatThrownBy(() -> XmlObjects.parse(xml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("not well-formed XML");
    }

    @Test
    @DisplayName("Nesting deeper than 90 elements is refused")
    void testDepthLimit() {
        // JDK 24+ already caps depth at 100 by default, so refusing 91 proves the explicit cap.
        String deep = "<a>".repeat(91) + "</a>".repeat(91);
        String allowed = "<a>".repeat(90) + "</a>".repeat(90);

        assertThatThrownBy(() -> XmlObjects.parse(deep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("not well-formed XML at line 1, column ");
        assertThat(XmlObjects.parse(allowed)).containsKey("a");
    }

    @Test
    @DisplayName("The parser writes nothing to stderr")
    void testSilentOnError() {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            assertThatThrownBy(() -> XmlObjects.parse("<r>")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            System.setErr(original);
        }

        assertThat(captured.toString()).isEmpty();
    }

    @Test
    @DisplayName("Malformed XML renders in Spanish, the parser's own text unchanged")
    void testMalformedRendersInSpanish() {
        try {
            XmlObjects.parse("<r>\n  <a>\n</r>");
        } catch (XmlObjects.MalformedXmlException e) {
            String parser = e.getMessage().substring("not well-formed XML at line 3, column 3: ".length());
            assertThat(Messages.render(e.detail(), Locale.of("es")))
                    .isEqualTo("XML mal formado en la línea 3, columna 3: " + parser);
            return;
        }
        throw new AssertionError("no MalformedXmlException");
    }
}
