package com.idm.test.runner.ui.lab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Editing a profile's settings file in place: one key at a time, every other byte kept. */
@DisplayName("Profile settings editor")
class ProfileYamlEditorTest {

    @TempDir
    Path temp;

    private static String template() throws IOException {
        try (InputStream in = ProfileYamlEditorTest.class.getResourceAsStream("/com/idm/test/runner/init/application-lab.yml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The indexes of the lines that differ; both texts must have as many lines. */
    private static List<Integer> changedLines(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        assertThat(b).hasSameSizeAs(a);
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(b[i])) {
                changed.add(i);
            }
        }
        return changed;
    }

    @Test
    @DisplayName("Editing url in the idmtest init template uncomments only that line and its parents; comments stay")
    void testCommentsAndLayoutKept() throws IOException {
        String template = template();

        String edited = ProfileYamlEditor.edit(template, "idm.test.ldap.url", "ldap://directory.test:389");

        List<Integer> changed = changedLines(template, edited);
        String[] lines = edited.split("\n", -1);
        assertThat(changed).hasSize(4);
        assertThat(changed.stream().map(i -> lines[i]).toList()).containsExactly(
                "idm:",
                "  test:",
                "    ldap:",
                "      url: ldap://directory.test:389   # replace with your directory");
        assertThat(edited).contains("#       base-dn: o=data", "# Settings for the \"lab\" profile",
                "    # Databases: one entry per database", "#       password: ${IDM_TEST_LDAP_PASSWORD}");
        assertThat(ProfileYamlEditor.flatten(edited)).containsEntry("idm.test.ldap.url", "ldap://directory.test:389")
                .hasSize(1);
    }

    @Test
    @DisplayName("A file with CRLF line endings keeps them, and a byte order mark stays")
    void testCrlfAndBomKept() throws IOException {
        String template = "﻿" + template().replace("\n", "\r\n");

        String edited = ProfileYamlEditor.edit(template, "idm.test.ldap.url", "ldap://directory.test:389");

        assertThat(edited).startsWith("﻿");
        assertThat(edited.replace("\r\n", "")).doesNotContain("\n", "\r");
        assertThat(changedLines(template.replace("\r\n", "\n"), edited.replace("\r\n", "\n"))).hasSize(4);
        assertThat(edited).contains("\r\n      url: ldap://directory.test:389   # replace with your directory\r\n");
    }

    @Test
    @DisplayName("A second key of the same block uncomments its own template line only")
    void testSecondKeyOfBlock() throws IOException {
        String once = ProfileYamlEditor.edit(template(), "idm.test.ldap.url", "ldap://directory.test:389");

        String twice = ProfileYamlEditor.edit(once, "idm.test.ldap.user-dn", "cn=tester,o=data");

        assertThat(changedLines(once, twice)).hasSize(1);
        assertThat(twice).contains("\n      user-dn: cn=tester,o=data\n", "#       base-dn: o=data");
        assertThat(ProfileYamlEditor.flatten(twice)).containsEntry("idm.test.ldap.user-dn", "cn=tester,o=data");
    }

    @Test
    @DisplayName("A database the template does not name goes under its databases: line, uncommented")
    void testNewMapEntryUnderTemplateBlock() throws IOException {
        String template = template();

        String edited = ProfileYamlEditor.edit(template, "idm.test.databases.hr.jdbc-url", "jdbc:postgresql://db.test/hr");

        assertThat(ProfileYamlEditor.flatten(edited))
                .containsExactly(Map.entry("idm.test.databases.hr.jdbc-url", "jdbc:postgresql://db.test/hr"));
        assertThat(edited).contains("    databases:\n      hr:\n        jdbc-url: jdbc:postgresql://db.test/hr\n#       sap:");
        // every template line is still there, only uncommented where needed
        assertThat(edited).contains("#     ldap:", "#       sap:", "#     rest:");
    }

    @Test
    @DisplayName("An active key is replaced in place, its trailing comment kept")
    void testActiveKeyReplaced() {
        String text = "# lab\nidm:\n  test:\n    ldap:\n      url: ldap://old.test:389  # the old one\n\n"
                + "      base-dn: o=data\n";

        String edited = ProfileYamlEditor.edit(text, "idm.test.ldap.url", "ldap://new.test:389");

        assertThat(edited).isEqualTo("# lab\nidm:\n  test:\n    ldap:\n      url: ldap://new.test:389  # the old one\n\n"
                + "      base-dn: o=data\n");
    }

    @Test
    @DisplayName("Missing parents are created at the file's indentation, after the block's last line")
    void testMissingParentsCreated() {
        String text = "idm:\n    test:\n        soap:\n            url: https://soap.test/role\n# end\n";

        String edited = ProfileYamlEditor.edit(text, "idm.test.ldap.url", "ldap://new.test:389");

        assertThat(edited).isEqualTo("idm:\n    test:\n        soap:\n            url: https://soap.test/role\n"
                + "        ldap:\n            url: ldap://new.test:389\n# end\n");
    }

    @Test
    @DisplayName("An empty file gets the whole path; a file without a final newline gets one between")
    void testEmptyAndNoFinalNewline() {
        assertThat(ProfileYamlEditor.edit("", "idm.test.soap.url", "https://soap.test/role"))
                .isEqualTo("idm:\n  test:\n    soap:\n      url: https://soap.test/role\n");
        assertThat(ProfileYamlEditor.edit("spring:\n  main:\n    banner-mode: off", "idm.test.soap.username", "tester"))
                .isEqualTo("spring:\n  main:\n    banner-mode: off\nidm:\n  test:\n    soap:\n      username: tester\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "no", "on", "null", "~", "", " leading", "trailing ", "a: b", "x #y", "*star",
            "&anchor", "!tag", "@at", "`tick", "%pct", "'quoted'", "\"dq\"", "[list]", "{map}", "- dash", "?q",
            "it's", "0x1F", "1e3", "012", "true", "10000", "${IDM_TEST_LDAP_PASSWORD}", "café", "#hash", "|", ">"})
    @DisplayName("Values YAML would misread are quoted, so they read back exactly")
    void testQuotedWhenNeeded(String value) {
        String edited = ProfileYamlEditor.edit("idm:\n  test:\n    soap:\n      url: x\n", "idm.test.soap.username", value);

        assertThat(ProfileYamlEditor.flatten(edited)).containsEntry("idm.test.soap.username", value);
    }

    @Test
    @DisplayName("Plain values stay unquoted")
    void testPlainUnquoted() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("url", "ldap://directory.test:389");
        values.put("password", "${IDM_TEST_LDAP_PASSWORD}");
        values.put("pooled", "true");
        values.put("max-active", "8");
        String text = "";
        for (Map.Entry<String, String> entry : values.entrySet()) {
            text = ProfileYamlEditor.edit(text, "idm.test.ldap." + entry.getKey(), entry.getValue());
        }
        assertThat(text).isEqualTo("idm:\n  test:\n    ldap:\n      url: ldap://directory.test:389\n"
                + "      password: ${IDM_TEST_LDAP_PASSWORD}\n      pooled: true\n      max-active: 8\n");
    }

    @Test
    @DisplayName("A key that is a block of other keys, or a flow mapping, is not overwritten")
    void testRefusesStructures() {
        assertThatThrownBy(() -> ProfileYamlEditor.edit("idm:\n  test:\n    ldap:\n      url: x\n", "idm.test.ldap", "y"))
                .isInstanceOf(ProfileYamlEditor.CannotEditException.class);
        assertThatThrownBy(() -> ProfileYamlEditor.edit("idm: {test: {ldap: {url: x}}}\n", "idm.test.ldap.url", "y"))
                .isInstanceOf(ProfileYamlEditor.CannotEditException.class);
        assertThatThrownBy(() -> ProfileYamlEditor.edit("idm: [\n", "idm.test.ldap.url", "y"))
                .isInstanceOf(ProfileYamlEditor.CannotEditException.class);
    }

    @Test
    @DisplayName("set() creates a missing profile file, writes in place, and never touches application.yml")
    void testSetFile() throws IOException {
        Path config = temp.resolve("config");
        Path profile = config.resolve("application-lab.yml");

        ProfileYamlEditor.set(profile, "idm.test.ldap.url", "ldap://directory.test:389");

        assertThat(Files.readString(profile)).isEqualTo("idm:\n  test:\n    ldap:\n      url: ldap://directory.test:389\n");
        Path shared = config.resolve("application.yml");
        Files.writeString(shared, "idm:\n  test:\n    ldap:\n      url: ldap://shared.test:389\n");
        assertThatThrownBy(() -> ProfileYamlEditor.set(shared, "idm.test.ldap.url", "ldap://other.test:389"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(shared)).isEqualTo("idm:\n  test:\n    ldap:\n      url: ldap://shared.test:389\n");
    }

    @Test
    @DisplayName("commentedValue finds the template's value for a key, to reuse its ${…} name")
    void testCommentedValue() throws IOException {
        assertThat(ProfileYamlEditor.commentedValue(template(), "idm.test.databases.sap.password"))
                .isEqualTo("${IDM_TEST_SAP_DB_PASSWORD}");
        assertThat(ProfileYamlEditor.commentedValue(template(), "idm.test.databases.hr.password")).isNull();
    }

    @Test
    @DisplayName("Keys are matched as Spring binds them: baseDn and base_dn are base-dn")
    void testRelaxedKeys() {
        String edited = ProfileYamlEditor.edit("idm:\n  test:\n    ldap:\n      baseDn: o=old\n", "idm.test.ldap.base-dn", "o=new");

        assertThat(edited).isEqualTo("idm:\n  test:\n    ldap:\n      baseDn: o=new\n");
        assertThat(ProfileYamlEditor.get(ProfileYamlEditor.flatten(edited), "idm.test.ldap.base-dn")).isEqualTo("o=new");
    }
}
