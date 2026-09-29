# response.xml Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `http.verify` checks can read an XML or SOAP body as plain objects through `response.xml`.

**Architecture:**
- **`XmlObjects`** (new, engine module) turns XML text into maps, lists and strings with the JDK parser, hardened against XXE and deep nesting.
- **`HttpVerifyStep`** adds `xml` to `response` for XML media types, and appends a hint naming why `response.xml` is `null` when a check that reads it fails. ITF-24's rule that made such failures final is removed.
- **Docs** describe the mapping in the authoring reference and retire the "arrives with ITF-25" wording.

**Tech Stack:** Java 21, JDK `javax.xml.parsers` (DOM), Rhino via the existing `ExpressionEvaluator`, JUnit 5, AssertJ, WireMock.

**Spec:** `docs/specs/2026-09-28-xml-response-design.md`. **Ticket:** ITF-25.

## Global Constraints

- **The engine stays Spring-free.** No `import org.springframework` under `idm-test-engine/src/main/java` (`NoSpringDependencyTest`). No new Maven dependency.
- **`XmlObjects.parse(String)`** returns `Map<String, Object>`: one entry, root local name → root value. Values are only `String`, `LinkedHashMap<String, Object>` or (for repeats) `ArrayList<Object>`.
- **Mapping:**
  - keys are **local names**; `xmlns`/`xmlns:*` attributes never appear
  - a repeated sibling local name becomes a list in document order (even when interleaved); a single one is its value
  - an element with no attributes and no child elements → its trimmed text (`""` when empty or whitespace-only)
  - otherwise a map with `@<local name>` per attribute, one key per child element, and `#text` = the element's own non-empty trimmed text nodes joined with one space (absent when empty)
  - two attributes with the same local name become a list
  - CDATA is text; comments and processing instructions are ignored
  - values are always strings
- **Parser settings, exactly:** namespace-aware; `FEATURE_SECURE_PROCESSING` true; `http://apache.org/xml/features/disallow-doctype-decl` true; `ACCESS_EXTERNAL_DTD` and `ACCESS_EXTERNAL_SCHEMA` `""`; `http://www.oracle.com/xml/jaxp/properties/maxElementDepth` `"90"`; XInclude off; `setExpandEntityReferences(false)`; coalescing on; comments ignored; an `ErrorHandler` that prints nothing.
- **Malformed input** throws `IllegalArgumentException` with message `not well-formed XML at line <l>, column <c>: <parser message>`, or `not well-formed XML: <parser message>` when the parser gives no line.
- **`response.xml`** is parsed when the first `Content-Type` value's media type (before `;`, trimmed, lower-cased) is `text/xml` or `application/xml`, or ends in `+xml`, and the body isn't blank. Otherwise, or when parsing fails, it is `null`.
- **Why `xml` is null** (checked in this order):
  1. blank body → `the body is empty`
  2. no `Content-Type` → `no Content-Type`
  3. non-XML media type → `Content-Type is <media type>`
  4. parse failure → `the body is <XmlObjects message>`
- **The hint:** when a check returns `false` or throws, its source contains `response.xml`, and `xml` is `null`, the failure message ends with ` (response.xml is null: <TokenFailureText.safe(reason, null)>)`. Otherwise no hint. The failure is an ordinary (retried) failure. A non-boolean result is still a final failure, with no hint.
- **Every other `http.verify` message is unchanged** (`<where>: check <i> of <n> is false: <source> (status <s>, body: <excerpt>)`, etc.).
- **The string "ITF-25" must not appear** in `HttpVerifyStep` or in its messages.
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. "Unresolved compilation problems" means stale classes: run `./mvnw clean test`. Green means 0 failures, errors and skips.
- **Commits** end with a blank line, `ITF-25`, and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: `XmlObjects`, the XML-to-object mapping

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/XmlObjects.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/XmlObjectsTest.java`

**Interfaces:**
- Produces: `public static Map<String, Object> XmlObjects.parse(String text)`; throws `IllegalArgumentException` for malformed or refused input (message per Global Constraints). Task 2 calls it.

- [ ] **Step 1: Write the failing tests**

Create `XmlObjectsTest.java`:

```java
package com.idm.test.engine.steps;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
        String deep = "<a>".repeat(101) + "</a>".repeat(101);
        String allowed = "<a>".repeat(100) + "</a>".repeat(100);

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
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=XmlObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: variable XmlObjects`.

- [ ] **Step 3: Write the implementation**

Create `XmlObjects.java`:

```java
package com.idm.test.engine.steps;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns XML text into plain maps, lists and strings, so a js:{} check can read an XML or SOAP
 * body without a helper library (ITF-7's rule). Elements are keyed by local name, attributes
 * are {@code @name}, an element's own text beside attributes or children is {@code #text}, and
 * a repeated element becomes a list. Every value is a string.
 * <p>
 * The parser refuses any DOCTYPE, loads nothing external and stops at 90 levels of nesting, so
 * a response body can't read files, expand entities or exhaust the stack.
 */
public final class XmlObjects {

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String MAX_ELEMENT_DEPTH = "http://www.oracle.com/xml/jaxp/properties/maxElementDepth";

    /** Reports nothing and rethrows fatal errors, so the default handler's stderr output never appears. */
    private static final ErrorHandler SILENT = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {
        }

        @Override
        public void error(SAXParseException e) {
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    };

    private XmlObjects() {
    }

    /**
     * @return a one-entry map, the root element's local name to its value
     * @throws IllegalArgumentException when the text is not well-formed XML, has a DOCTYPE or
     *                                  nests deeper than 90 elements
     */
    public static Map<String, Object> parse(String text) {
        Document document;
        try {
            DocumentBuilder builder = factory().newDocumentBuilder();
            builder.setErrorHandler(SILENT);
            document = builder.parse(new InputSource(new StringReader(text)));
        } catch (SAXParseException e) {
            String where = e.getLineNumber() > 0
                    ? " at line " + e.getLineNumber() + ", column " + e.getColumnNumber() : "";
            throw new IllegalArgumentException("not well-formed XML" + where + ": " + e.getMessage(), e);
        } catch (SAXException | IOException e) {
            throw new IllegalArgumentException("not well-formed XML: " + e.getMessage(), e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("the XML parser cannot be configured securely", e);
        }
        Element root = document.getDocumentElement();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(localName(root), valueOf(root));
        return result;
    }

    private static DocumentBuilderFactory factory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(DISALLOW_DOCTYPE, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute(MAX_ELEMENT_DEPTH, "90");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setCoalescing(true);
        factory.setIgnoringComments(true);
        return factory;
    }

    private static Object valueOf(Element element) {
        Map<String, Object> map = new LinkedHashMap<>();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                add(map, "@" + localName(attribute), attribute.getValue());
            }
        }
        List<String> texts = new ArrayList<>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement) {
                add(map, localName(childElement), valueOf(childElement));
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String text = child.getNodeValue().strip();
                if (!text.isEmpty()) {
                    texts.add(text);
                }
            }
        }
        String text = String.join(" ", texts);
        if (map.isEmpty()) {
            return text;
        }
        if (!text.isEmpty()) {
            map.put("#text", text);
        }
        return map;
    }

    /** Element and attribute values are never lists, so an existing list here is a repeat list. */
    @SuppressWarnings("unchecked")
    private static void add(Map<String, Object> map, String key, Object value) {
        Object existing = map.get(key);
        if (existing == null) {
            map.put(key, value);
        } else if (existing instanceof List<?> repeats) {
            ((List<Object>) repeats).add(value);
        } else {
            List<Object> repeats = new ArrayList<>();
            repeats.add(existing);
            repeats.add(value);
            map.put(key, repeats);
        }
    }

    private static String localName(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=XmlObjectsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Falsify each rule**

One at a time, make the change, run `XmlObjectsTest`, confirm the named test fails, then revert:
- remove `setFeature(DISALLOW_DOCTYPE, true)` → `testDoctypeRefused` fails
- remove the `MAX_ELEMENT_DEPTH` line → `testDepthLimit` fails
- remove `builder.setErrorHandler(SILENT)` → `testSilentOnError` fails
- remove the `XMLNS_ATTRIBUTE_NS_URI` check → `testNamespaces` fails
- replace `add(map, localName(childElement), …)` with `map.put(…)` → `testRepeatedElements` fails
- remove `setCoalescing(true)` and the `CDATA_SECTION_NODE` branch → `testCdataCommentsAndInstructions` fails

Record each result in the report.

- [ ] **Step 6: Run the module**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips (includes `NoSpringDependencyTest`).

- [ ] **Step 7: Commit**

```bash
git add idm-test-engine/src/main/java/com/idm/test/engine/steps/XmlObjects.java idm-test-engine/src/test/java/com/idm/test/engine/steps/XmlObjectsTest.java
git commit -m "feat: map XML text to plain objects for js:{} checks, refusing DOCTYPEs

ITF-25

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `response.xml` in `http.verify`

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/HttpVerifyStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/HttpVerifyStepsTest.java`

**Interfaces:**
- Consumes: `XmlObjects.parse(String) → Map<String, Object>`, throwing `IllegalArgumentException` with a `not well-formed XML…` message (Task 1).
- Produces: `response` with keys `status`, `headers`, `body`, `json`, `xml`, in that order.

- [ ] **Step 1: Replace the ITF-24 `response.xml` test and add the new tests**

In `HttpVerifyStepsTest.java`, delete `testXmlHint` (the test with `@DisplayName("Reading response.xml fails with the ITF-25 hint and ends a wait at once")`) and add, after `testNonBooleanIsFinal`:

```java
    private static final String SOAP = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
              <soapenv:Body>
                <ns2:getUserResponse xmlns:ns2="http://idm.example/users">
                  <ns2:user id="42">
                    <ns2:status>ACTIVE</ns2:status>
                    <ns2:role>Admin</ns2:role>
                    <ns2:role>HR</ns2:role>
                  </ns2:user>
                </ns2:getUserResponse>
              </soapenv:Body>
            </soapenv:Envelope>
            """;

    private static void stubBody(String contentType, String body) {
        var response = aResponse().withStatus(200).withBody(body);
        if (contentType != null) {
            response.withHeader("Content-Type", contentType);
        }
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).willReturn(response));
    }

    @Test
    @DisplayName("A SOAP envelope is read through response.xml by local names, attributes and lists")
    void testSoapEnvelope() {
        stubBody("text/xml; charset=UTF-8", SOAP);

        StepResult step = run(params("check", List.of(
                "js:{ response.xml.Envelope.Body.getUserResponse.user.status === 'ACTIVE' }",
                "js:{ response.xml.Envelope.Body.getUserResponse.user['@id'] === '42' }",
                "js:{ [].concat(response.xml.Envelope.Body.getUserResponse.user.role).includes('HR') }",
                "js:{ response.json === null }")));

        assertThat(step.success()).as(step.message()).isTrue();
    }

    @Test
    @DisplayName("application/soap+xml and application/xml are parsed")
    void testXmlMediaTypes() {
        for (String type : List.of("application/soap+xml; charset=UTF-8", "application/xml")) {
            stubBody(type, SOAP);

            StepResult step = run(params("check", "js:{ response.xml.Envelope.Body.getUserResponse.user.status === 'ACTIVE' }"));

            assertThat(step.success()).as(type + ": " + step.message()).isTrue();
        }
    }

    @Test
    @DisplayName("When response.xml is null, a failing check that reads it says why")
    void testXmlNullHints() {
        stubBody("text/html", "<html><body>down</body></html>");
        assertThat(run(params("check", "js:{ response.xml.Envelope !== undefined }")).message())
                .endsWith(" (response.xml is null: Content-Type is text/html)");

        stubBody(null, SOAP);
        assertThat(run(params("check", "js:{ response.xml !== null }")).message())
                .endsWith(" (response.xml is null: no Content-Type)");

        stubBody("text/xml", "");
        assertThat(run(params("check", "js:{ response.xml !== null }")).message())
                .endsWith(" (response.xml is null: the body is empty)");

        stubBody("text/xml", "<Envelope>");
        assertThat(run(params("check", "js:{ response.xml !== null }")).message())
                .contains(" (response.xml is null: the body is not well-formed XML at line 1, column ");
    }

    @Test
    @DisplayName("A failing check that doesn't read response.xml, or reads a parsed one, gets no hint")
    void testNoHintOtherwise() {
        stubBody("text/html", "<html/>");
        assertThat(run(params("check", "js:{ response.status === 201 }")).message())
                .doesNotContain("response.xml is null");

        stubBody("text/xml", SOAP);
        assertThat(run(params("check", "js:{ response.xml.Envelope.Body.nothing === 'x' }")).message())
                .doesNotContain("response.xml is null");
    }

    @Test
    @DisplayName("A failing response.xml check is retried under wait, not final")
    void testXmlFailureRetried() {
        stubJson(200, "{}");

        StepResult step = run(params("check", "js:{ response.xml.Envelope !== undefined }",
                "wait", Map.of("timeout", "2s", "poll", "1s")));

        assertThat(step.success()).isFalse();
        assertThat(step.attempts()).isGreaterThan(1);
        assertThat(step.message()).contains("(response.xml is null: Content-Type is application/json)")
                .endsWith("(last observed value after retrying)")
                .doesNotContain("ITF-25");
    }

    @Test
    @DisplayName("Under wait, malformed XML then a valid envelope passes on the second attempt")
    void testMalformedThenValid() {
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).inScenario("restart")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("up")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml").withBody("<Envelope>")));
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).inScenario("restart")
                .whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml").withBody(SOAP)));

        StepResult step = run(params("check", "js:{ response.xml.Envelope.Body.getUserResponse.user.status === 'ACTIVE' }",
                "wait", Map.of("timeout", "2s", "poll", "1s")));

        assertThat(step.success()).as(step.message()).isTrue();
        assertThat(step.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("as: binds response.xml, and a later step can read into it")
    void testCaptureXml() {
        stubBody("text/xml", SOAP);

        ScenarioResult result = runSteps(List.of(
                new StepSpec("http.verify", params("check", "js:{ response.status === 200 }", "as", "soap")),
                new StepSpec("set", Map.of("status", "${soap.xml.Envelope.Body.getUserResponse.user.status}"))));

        StepResult setStep = result.steps().get(1);
        assertThat(setStep.success()).as(setStep.message()).isTrue();
        assertThat(setStep.resolvedParams()).containsEntry("status", "ACTIVE");
    }
```

If WireMock turns out to add a `Content-Type` header when none is stubbed (check with `response.headers` in a throwaway check), report it as a concern instead of weakening the assertion.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=HttpVerifyStepsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the new tests fail (no `xml` key; `testXmlFailureRetried` fails on `attempts` because the old rule is final); the other tests pass.

- [ ] **Step 3: Implement**

Replace `HttpVerifyStep.java` with:

```java
package com.idm.test.engine.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.client.TokenFailureText;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.DeferredExpression;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sends a raw-text request like http.request, then evaluates each js:{} condition in
 * {@code check} against {@code response}. All must return true. Retryable: under wait:, the
 * request and the checks repeat until they pass or the timeout expires. A check that throws is
 * retried (for example before provisioning, response.json can be null); a check that returns
 * something other than true or false is an authoring mistake and ends the wait at once.
 * <p>
 * {@code response.json} and {@code response.xml} are the body parsed for a JSON or XML media
 * type, else null. When a failing check reads {@code response.xml} while it is null, the
 * message says why (the Content-Type, an empty body, or where the XML is malformed).
 */
public final class HttpVerifyStep implements StepType {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IdmRestConnector connector;
    private final RestProperties properties;
    private final RestAuthenticator authenticator;

    public HttpVerifyStep(IdmRestConnector connector, RestProperties properties, RestAuthenticator authenticator) {
        this.connector = connector;
        this.properties = properties;
        this.authenticator = authenticator;
    }

    @Override
    public String name() {
        return "http.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("target", "method", "path", "check");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("target", "method", "path", "check", "headers", "body", "wait");
    }

    @Override
    public Set<String> deferredParams() {
        return Set.of("check");
    }

    @Override
    public Set<String> deferredBindings() {
        return Set.of("response");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        List<DeferredExpression> checks = checksOf(params.get("check"));
        if (checks == null || checks.isEmpty()) {
            return StepOutcome.finalFailure("http.verify 'check' must be js:{ … } expressions");
        }

        HttpExchange.Prepared request = HttpExchange.prepare("http.verify", params, properties, authenticator);
        if (request.isFailure()) {
            return request.failure();
        }
        OperationResult<TextResponse> result = HttpExchange.send(connector, request);
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("http.verify failed for " + request.url() + ": " + result.getMessage());
        }

        TextResponse response = result.getData().get();
        Bound bound = responseOf(response);
        String where = "http.verify " + request.method() + " " + request.url();
        int total = checks.size();
        for (int i = 0; i < total; i++) {
            DeferredExpression check = checks.get(i);
            String label = where + ": check " + (i + 1) + " of " + total;
            String hint = bound.xmlNullReason() != null && check.source().contains("response.xml")
                    ? " (response.xml is null: " + TokenFailureText.safe(bound.xmlNullReason(), null) + ")"
                    : "";
            Object value;
            try {
                value = check.evaluate(Map.of("response", bound.response()));
            } catch (RuntimeException e) {
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return StepOutcome.failure(label + " failed: " + TokenFailureText.safe(error, null) + context(response) + hint);
            }
            if (!(value instanceof Boolean passed)) {
                return StepOutcome.finalFailure(
                        label + " must return true or false, got " + TokenFailureText.safe(describe(value), null));
            }
            if (!passed) {
                return StepOutcome.failure(label + " is false: " + check.source() + context(response) + hint);
            }
        }

        String message = request.method() + " " + request.url() + " passed " + total
                + " check(s) (status " + response.status() + ")";
        return captureRequested ? StepOutcome.success(message, bound.response()) : StepOutcome.success(message);
    }

    /** The response map the checks read, and why its xml is null (null when xml was parsed). */
    private record Bound(Map<String, Object> response, String xmlNullReason) {
    }

    private static List<DeferredExpression> checksOf(Object raw) {
        List<?> items = raw instanceof List<?> list ? list : raw == null ? List.of() : List.of(raw);
        List<DeferredExpression> checks = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof DeferredExpression expression)) {
                return null;
            }
            checks.add(expression);
        }
        return checks;
    }

    /** status, headers and body as http.request captures them, plus the parsed JSON and XML bodies. */
    private static Bound responseOf(TextResponse response) {
        Map<String, Object> bound = new LinkedHashMap<>(HttpExchange.capture(response));
        String mediaType = mediaTypeOf(response);
        bound.put("json", jsonOf(mediaType, response.body()));
        Object xml = null;
        String reason;
        if (response.body().isBlank()) {
            reason = "the body is empty";
        } else if (mediaType.isEmpty()) {
            reason = "no Content-Type";
        } else if (!(mediaType.equals("text/xml") || mediaType.equals("application/xml") || mediaType.endsWith("+xml"))) {
            reason = "Content-Type is " + mediaType;
        } else {
            try {
                xml = XmlObjects.parse(response.body());
                reason = null;
            } catch (IllegalArgumentException malformed) {
                reason = "the body is " + malformed.getMessage();
            }
        }
        bound.put("xml", xml);
        return new Bound(bound, reason);
    }

    /** The first Content-Type value before any parameters, lower-cased; "" when absent. */
    private static String mediaTypeOf(TextResponse response) {
        List<String> types = response.headers().getOrDefault("content-type", List.of());
        return types.isEmpty() ? "" : types.get(0).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static Object jsonOf(String mediaType, String body) {
        if (!(mediaType.equals("application/json") || mediaType.endsWith("+json")) || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(body, Object.class);
        } catch (Exception notJson) {
            return null; // declared JSON but isn't: the check sees null, as IdmRestConnector does
        }
    }

    private static String context(TextResponse response) {
        return " (status " + response.status() + ", body: " + TokenFailureText.safe(response.body(), null) + ")";
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text + "\"";
        }
        return String.valueOf(value);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=HttpVerifyStepsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass, 0 failures, errors and skips.

- [ ] **Step 5: Falsify each rule**

One at a time, make the change, run `HttpVerifyStepsTest`, confirm the named test fails, then revert:
- drop `|| mediaType.endsWith("+xml")` → `testXmlMediaTypes` fails
- always set `hint = ""` → `testXmlNullHints` fails
- drop `bound.xmlNullReason() != null &&` → `testNoHintOtherwise` fails
- return `StepOutcome.finalFailure` for a false check → `testXmlFailureRetried` fails
- remove `bound.put("xml", xml)` → `testSoapEnvelope` and `testCaptureXml` fail

Record each result in the report.

- [ ] **Step 6: Run the module and check for leftovers**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips.

Run: `git grep -n "ITF-25" -- idm-test-engine`
Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add idm-test-engine/src/main/java/com/idm/test/engine/steps/HttpVerifyStep.java idm-test-engine/src/test/java/com/idm/test/engine/steps/HttpVerifyStepsTest.java
git commit -m "feat: bind response.xml in http.verify and say why it is null when a check fails

ITF-25

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Documentation and the full suite

**Files:**
- Modify: `docs/reference/writing-scenarios.md` (the `http.verify` section, around lines 510–580)
- Modify: `README.md` (line 131, the `http.verify` row; lines 289–299, the "Next" table)
- Modify: `docs/architecture/capabilities.md` (line 17)
- Modify: `docs/overview.md` (line 70)

**Interfaces:**
- Consumes: the behaviour of Tasks 1 and 2, as described in Global Constraints.

- [ ] **Step 1: Update the `response` table in the reference**

In `docs/reference/writing-scenarios.md`, in the `http.verify` section, add this row after the `response.json` row:

```markdown
| `response.xml` | the body parsed, when the response is XML (`text/xml`, `application/xml` or `…+xml`, which includes SOAP 1.2's `application/soap+xml`); otherwise `null`. See **Reading XML** below |
```

and replace the line

```markdown
`response.xml` (a parsed XML or SOAP body) arrives with ITF-25; until then, check `response.body`.
```

with the "Reading XML" block below. Keep it a bold paragraph, not a heading: `ScenarioReferenceTest` reads each step's first table up to the next heading.

````markdown
**Reading XML.** `response.xml` turns the body into names you can follow with dots:

- **Start at the root element:** `response.xml.Envelope.Body…`.
- **Elements are named without their namespace prefix:** `<soapenv:Body>` is `Body` and
  `<ns2:status>` is `status`, whatever prefix the server uses.
- **An element holding only text is that text:** `<name>Ana</name>` is `'Ana'`, and an empty
  element is `''`.
- **Attributes are `'@name'`:** `user['@id']`. When an element has attributes or child elements,
  its own text is `'#text'`: `status['#text']`.
- **An element that appears once is a single value; one that repeats is a list:** `role[1]`.
  When a check may see one or several, `[].concat(user.role)` is always a list:
  `[].concat(user.role).includes('HR')`.
- **Every value is text:** compare with `'42'`, or convert with `Number(user['@id'])`.
- **A body with a DOCTYPE, or nested more than 90 elements deep, is refused** as not
  well-formed, so a response can't make the check read files or expand entities.

```xml
<user id="42">
  <status code="1">ACTIVE</status>
  <name>Ana</name>
  <role>Admin</role>
  <role>HR</role>
  <manager/>
</user>
```

reads as

```js
{ user: { '@id': '42',
          status: { '@code': '1', '#text': 'ACTIVE' },
          name: 'Ana',
          role: ['Admin', 'HR'],
          manager: '' } }
```
````

- [ ] **Step 2: Update "Fails when" in the reference**

In the same section, replace these bullets:

```markdown
- a check is `false`: the message names it (`check 2 of 3 is false: …`) with the status and the
  start of the body. Retried under `wait:`, unless its source reads `response.xml` (see below).
- a check throws, for example `response.json.department` while `response.json` is still `null`.
  Retried under `wait:`, unless its source reads `response.xml` (see below).
```

with

```markdown
- a check is `false`: the message names it (`check 2 of 3 is false: …`) with the status and the
  start of the body. Retried under `wait:`.
- a check throws, for example `response.json.department` while `response.json` is still `null`.
  Retried under `wait:`.
- when a failing check reads `response.xml` and it is `null`, the message ends with the reason,
  for example `(response.xml is null: Content-Type is text/html)` or
  `(response.xml is null: the body is not well-formed XML at line 3, column 7: …)`. This is
  retried like any failing check.
```

and delete this bullet:

```markdown
- a failing check (false or throwing) whose source reads `response.xml`: this ends the wait at
  once too, because `response.xml` cannot become available before ITF-25 no matter how long the
  wait polls.
```

- [ ] **Step 3: Update the SOAP example in the reference**

In the second `http.verify` example, replace

```yaml
    check: "js:{ response.status === 200 && response.body.includes('<status>ACTIVE</status>') }"
```

with

```yaml
    check:
      - "js:{ response.status === 200 }"
      - "js:{ response.xml.Envelope.Body.getUserResponse.user.status === 'ACTIVE' }"
```

- [ ] **Step 4: Update the README, capability matrix and overview**

`README.md`, the `http.verify` row: replace `(JavaScript conditions on `response`)` with
`(JavaScript conditions on `response`, including its parsed JSON and XML)`.

`README.md`, the "Next" table: delete the rows for ITF-24, ITF-25 and ITF-10 (all done). The remaining rows are ITF-16, ITF-17 and ITF-18, unchanged.

`docs/architecture/capabilities.md`, line 17: replace
`; reading XML (`response.xml`) is ITF-25` with
`; checks read the parsed JSON (`response.json`) and XML or SOAP body (`response.xml`, ITF-25)`.

`docs/overview.md`, line 70: replace
`Checking HTTP responses in JavaScript and reading XML responses are planned (ITF-24, ITF-25). CLI, frontend and HTML reporting are deferred.`
with
`HTTP responses are checked with JavaScript conditions, reading JSON and XML bodies as plain objects (ITF-24, ITF-25). CLI, frontend and HTML reporting are deferred.`

- [ ] **Step 5: Check for leftovers**

Run: `git grep -n -e "ITF-25" -e "response\.xml" -- ':!docs/specs' ':!docs/plans'`
Expected: only the capability-matrix line from Step 4, and `response.xml` mentions in `docs/reference/writing-scenarios.md`, `HttpVerifyStep.java` and `HttpVerifyStepsTest.java`. No "arrives with ITF-25" and no "cannot become available".

- [ ] **Step 6: Run the full suite and the corpus**

Run: `./mvnw -q clean test`
Expected: exit 0.

Then:
```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
```
Expected: the test count is 672 plus the new tests (14 in `XmlObjectsTest`, 6 net new in `HttpVerifyStepsTest`: 7 added, 1 removed, so 692 in all), with 0 failures, errors and skips; the corpus prints `0`.

- [ ] **Step 7: Commit**

```bash
git add docs/reference/writing-scenarios.md README.md docs/architecture/capabilities.md docs/overview.md
git commit -m "docs: describe response.xml and the XML mapping in the authoring reference

ITF-25

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
