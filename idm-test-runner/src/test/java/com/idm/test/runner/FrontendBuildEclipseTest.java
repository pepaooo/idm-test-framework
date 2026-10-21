package com.idm.test.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eclipse m2e must not run the front-end build (Node install, {@code npm ci}, build, test) when it
 * imports the project: each {@code frontend-maven-plugin} execution of this module's POM carries
 * {@code <?m2e ignore?>}, which Maven itself does not read.
 */
@DisplayName("Front-end build in Eclipse")
class FrontendBuildEclipseTest {

    @Test
    @DisplayName("Should mark every frontend-maven-plugin execution as ignored by m2e")
    void testExecutionsIgnoredByM2e() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element project = factory.newDocumentBuilder().parse(new File("pom.xml")).getDocumentElement();

        List<String> executions = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        NodeList plugins = project.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if (!"frontend-maven-plugin".equals(text(plugin, "artifactId"))) {
                continue;
            }
            NodeList list = plugin.getElementsByTagName("execution");
            for (int j = 0; j < list.getLength(); j++) {
                Element execution = (Element) list.item(j);
                String id = text(execution, "id");
                executions.add(id);
                for (Node child = execution.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child instanceof ProcessingInstruction pi && pi.getTarget().equals("m2e")
                            && pi.getData().strip().equals("ignore")) {
                        ignored.add(id);
                    }
                }
            }
        }

        assertThat(executions).containsExactly("install-node", "npm-ci", "npm-build", "npm-test");
        assertThat(ignored).isEqualTo(executions);
    }

    private static String text(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals(name)) {
                return element.getTextContent().strip();
            }
        }
        return null;
    }
}
