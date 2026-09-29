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
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(DISALLOW_DOCTYPE, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        // stays below the JavaScript sandbox's 100-level limit, which also counts the response map around the body
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
