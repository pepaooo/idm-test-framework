package com.idm.test.runner.ui.api;

import com.idm.test.runner.InitCommand;
import com.idm.test.runner.SecretsEnv;
import com.idm.test.runner.ui.PlainSecretLint;
import com.idm.test.runner.ui.UiCommand;
import com.idm.test.runner.ui.UiHttp;
import com.idm.test.runner.ui.UiHttp.Response;
import com.idm.test.runner.ui.UiServer;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The lab settings API over HTTP, against a running workbench. Every credential here is fake. */
@DisplayName("Workbench lab settings API")
class LabControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE_DN = "dc=test,dc=com";
    private static final String BIND_DN = "cn=admin,dc=test,dc=com";
    private static final String BIND_PASSWORD = "fake-bind-password-1";
    private static final String ENV_PASSWORD = "fake-env-password-2";

    @TempDir
    static Path home;
    private static String realHome;
    /** A workbench whose process environment sets nothing for the lab. */
    private static UiServer server;
    /** A workbench whose process environment sets the LDAP URL and password. */
    private static UiServer envServer;
    private static EmbeddedLdapServer ldap;

    @TempDir
    Path temp;

    @BeforeAll
    static void start() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        server = UiCommand.start(0, Locale.ENGLISH, null, Map.of());
        envServer = UiCommand.start(0, Locale.ENGLISH, null,
                Map.of("IDM_TEST_LDAP_URL", "ldap://env-directory.test:389", "IDM_TEST_LDAP_PASSWORD", ENV_PASSWORD));
        ldap = EmbeddedLdapServer.start(BASE_DN, BIND_DN, BIND_PASSWORD, 0, null);
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
        if (envServer != null) {
            envServer.close();
        }
        if (ldap != null) {
            ldap.close();
        }
        System.setProperty("user.home", realHome);
    }

    private Path openWorkspace(UiServer on) throws IOException {
        Path ws = temp.resolve("ws");
        InitCommand.run(ws, new PrintStream(OutputStream.nullOutputStream()));
        Response opened = UiHttp.send(on, "POST", "/api/workspaces/open",
                JSON.writeValueAsString(Map.of("path", ws.toString())));
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        return ws;
    }

    private static JsonNode lab(UiServer on, String profile) throws IOException {
        Response response = UiHttp.get(on, "/api/lab?profile=" + profile);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static JsonNode system(JsonNode lab, String id) {
        for (JsonNode system : lab.get("systems")) {
            if (system.get("id").asString().equals(id)) {
                return system;
            }
        }
        throw new AssertionError("no system " + id + " in " + lab);
    }

    private static JsonNode field(JsonNode system, String key) {
        for (JsonNode field : system.get("fields")) {
            if (field.get("key").asString().equals(key)) {
                return field;
            }
        }
        throw new AssertionError("no field " + key + " in " + system);
    }

    private static Response put(UiServer on, String profile, String systemId, Map<String, String> values,
                                Map<String, String> secrets) throws IOException {
        return UiHttp.send(on, "PUT", "/api/lab?profile=" + profile, JSON.writeValueAsString(
                Map.of("systemId", systemId, "values", values, "secrets", secrets)));
    }

    private static List<String> snapshot(Path ws) throws IOException {
        List<String> all = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ws)) {
            for (Path file : files.sorted().toList()) {
                all.add(ws.relativize(file) + (Files.isRegularFile(file) ? "=" + Files.readString(file) : ""));
            }
        }
        return all;
    }

    @Test
    @DisplayName("GET lists the systems the scenarios use, with their fields; it creates no file")
    void testReadNeededSystems() throws IOException {
        Path ws = openWorkspace(server);
        List<String> before = snapshot(ws);

        JsonNode ldapSystem = system(lab(server, "uat"), "ldap");

        assertThat(ldapSystem.get("kind").asString()).isEqualTo("ldap");
        assertThat(ldapSystem.get("name").asString()).isEqualTo("Directory");
        assertThat(ldapSystem.get("usedByScenarios").get(0).asString()).isEqualTo("example.yaml");
        assertThat(ldapSystem.get("status").asString()).isEqualTo("missing");
        assertThat(ldapSystem.get("lastTest").isNull()).isTrue();
        JsonNode url = field(ldapSystem, "url");
        assertThat(url.get("state").asString()).isEqualTo("not set");
        assertThat(url.get("secret").asBoolean()).isFalse();
        assertThat(url.get("label").asString()).isEqualTo("URL");
        JsonNode password = field(ldapSystem, "password");
        assertThat(password.get("secret").asBoolean()).isTrue();
        assertThat(password.get("value").isNull()).isTrue();
        assertThat(password.get("state").asString()).isEqualTo("not set");
        assertThat(snapshot(ws)).isEqualTo(before);
    }

    @Test
    @DisplayName("A value from application.yml shows as inherited; saving writes an override to the profile file only")
    void testInheritedFromApplicationYml() throws IOException {
        Path ws = openWorkspace(server);
        Path shared = ws.resolve("config/application.yml");
        String sharedText = "# shared\nidm:\n  test:\n    ldap:\n      url: ldap://shared.test:389\n      base-dn: o=data\n";
        Files.writeString(shared, sharedText);

        JsonNode url = field(system(lab(server, "lab"), "ldap"), "url");
        assertThat(url.get("state").asString()).isEqualTo("application.yml");
        assertThat(url.get("value").asString()).isEqualTo("ldap://shared.test:389");

        Response saved = put(server, "lab", "ldap", Map.of("url", "ldap://lab.test:389"), Map.of());

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(Files.readString(shared)).isEqualTo(sharedText);
        String profile = Files.readString(ws.resolve("config/application-lab.yml"));
        assertThat(profile).contains("\n      url: ldap://lab.test:389   # replace with your directory\n",
                "# Settings for the \"lab\" profile", "#       base-dn: o=data");
        JsonNode after = system(lab(server, "lab"), "ldap");
        assertThat(field(after, "url").get("state").asString()).isEqualTo("set");
        assertThat(field(after, "url").get("value").asString()).isEqualTo("ldap://lab.test:389");
        assertThat(field(after, "base-dn").get("state").asString()).isEqualTo("application.yml");
    }

    @Test
    @DisplayName("A saved password goes to secrets.env and never comes back: not in the lab, tree, health or page")
    void testSecretNeverReturned() throws IOException {
        Path ws = openWorkspace(server);

        Response saved = put(server, "lab", "ldap", Map.of("url", ldap.getUrl(), "user-dn", BIND_DN),
                Map.of("password", BIND_PASSWORD));

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(saved.body()).doesNotContain(BIND_PASSWORD);
        assertThat(SecretsEnv.read(ws.resolve("secrets.env"))).containsEntry("IDM_TEST_LDAP_PASSWORD", BIND_PASSWORD);
        String profile = Files.readString(ws.resolve("config/application-lab.yml"));
        assertThat(profile).contains("\n      password: ${IDM_TEST_LDAP_PASSWORD}\n").doesNotContain(BIND_PASSWORD);
        JsonNode password = field(system(lab(server, "lab"), "ldap"), "password");
        assertThat(password.get("state").asString()).isEqualTo("set");
        assertThat(password.get("value").isNull()).isTrue();

        for (String path : List.of("/api/lab?profile=lab", "/api/files/tree", "/api/health", "/",
                "/api/files?path=" + URLEncoder.encode("config/application-lab.yml", StandardCharsets.UTF_8))) {
            Response response = UiHttp.get(server, path);
            assertThat(response.status()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).doesNotContain(BIND_PASSWORD);
        }
    }

    @Test
    @DisplayName("Values from the environment show as such, never with a secret's value, and cannot be saved")
    void testEnvironmentValues() throws IOException {
        openWorkspace(envServer);

        Response raw = UiHttp.get(envServer, "/api/lab?profile=lab");
        assertThat(raw.body()).doesNotContain(ENV_PASSWORD);
        JsonNode ldapSystem = system(JSON.readTree(raw.body()), "ldap");
        assertThat(field(ldapSystem, "url").get("state").asString()).isEqualTo("environment");
        assertThat(field(ldapSystem, "url").get("value").asString()).isEqualTo("ldap://env-directory.test:389");
        assertThat(field(ldapSystem, "password").get("state").asString()).isEqualTo("environment");
        assertThat(field(ldapSystem, "password").get("value").isNull()).isTrue();

        Response url = put(envServer, "lab", "ldap", Map.of("url", "ldap://other.test:389"), Map.of());
        assertThat(url.status()).isEqualTo(400);
        assertThat(JSON.readTree(url.body()).get("key").asString()).isEqualTo("ui.lab.fromEnvironment");
        assertThat(url.body()).contains("IDM_TEST_LDAP_URL");

        Response password = put(envServer, "lab", "ldap", Map.of(), Map.of("password", "fake-typed-password"));
        assertThat(password.status()).isEqualTo(400);
        assertThat(JSON.readTree(password.body()).get("key").asString()).isEqualTo("ui.lab.fromEnvironment");
        assertThat(password.body()).doesNotContain("fake-typed-password", ENV_PASSWORD);
    }

    @Test
    @DisplayName("Test connection saves nothing; a pass makes the system ready once those values are saved")
    void testReadyAfterPassingTest() throws IOException {
        Path ws = openWorkspace(server);
        List<String> before = snapshot(ws);
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("systemId", "ldap");
        form.put("profile", "lab");
        form.put("values", Map.of("url", ldap.getUrl(), "base-dn", BASE_DN, "user-dn", BIND_DN));
        form.put("secrets", Map.of("password", BIND_PASSWORD));

        Response tested = UiHttp.send(server, "POST", "/api/lab/test", JSON.writeValueAsString(form));

        assertThat(tested.status()).as(tested.body()).isEqualTo(200);
        JsonNode result = JSON.readTree(tested.body());
        assertThat(result.get("ok").asBoolean()).as(tested.body()).isTrue();
        assertThat(result.get("message").asString()).isEqualTo("Connected · bind OK");
        assertThat(result.has("millis")).isTrue();
        assertThat(snapshot(ws)).isEqualTo(before);
        assertThat(system(lab(server, "lab"), "ldap").get("status").asString()).isEqualTo("missing");

        Response saved = put(server, "lab", "ldap", Map.of("url", ldap.getUrl(), "base-dn", BASE_DN, "user-dn", BIND_DN),
                Map.of("password", BIND_PASSWORD));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        JsonNode ready = system(lab(server, "lab"), "ldap");
        assertThat(ready.get("status").asString()).isEqualTo("ready");
        assertThat(ready.get("lastTest").get("ok").asBoolean()).isTrue();

        put(server, "lab", "ldap", Map.of("user-dn", "cn=other,dc=test,dc=com"), Map.of());
        JsonNode changed = system(lab(server, "lab"), "ldap");
        assertThat(changed.get("status").asString()).isEqualTo("untested");
        assertThat(changed.get("lastTest").isNull()).isTrue();
    }

    @Test
    @DisplayName("A failed test reports the reason, masked, and the password left as dots is the saved one")
    void testFailedTestUsesSavedSecret() throws IOException {
        openWorkspace(server);
        put(server, "lab", "ldap", Map.of("url", ldap.getUrl(), "user-dn", BIND_DN), Map.of("password", "fake-wrong-pass-3"));

        Response tested = UiHttp.send(server, "POST", "/api/lab/test", JSON.writeValueAsString(
                Map.of("systemId", "ldap", "profile", "lab", "values", Map.of(), "secrets", Map.of())));

        JsonNode result = JSON.readTree(tested.body());
        assertThat(result.get("ok").asBoolean()).isFalse();
        assertThat(result.get("message").asString()).startsWith("credentials rejected");
        assertThat(tested.body()).doesNotContain("fake-wrong-pass-3");
        JsonNode untested = system(lab(server, "lab"), "ldap");
        assertThat(untested.get("status").asString()).isEqualTo("untested");
        assertThat(untested.get("lastTest").get("ok").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("A new system can be added; its secret is named as the runner documents and referenced from the profile")
    void testAddDatabase() throws IOException {
        Path ws = openWorkspace(server);

        Response saved = put(server, "lab", "database:hr", Map.of("jdbc-url", "jdbc:postgresql://db.test:5432/hr",
                "username", "tester"), Map.of("password", "fake-db-password-4"));

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(SecretsEnv.read(ws.resolve("secrets.env")))
                .containsEntry("IDM_TEST_DATABASES_HR_PASSWORD", "fake-db-password-4");
        assertThat(Files.readString(ws.resolve("config/application-lab.yml")))
                .contains("    databases:\n      hr:\n        jdbc-url: jdbc:postgresql://db.test:5432/hr\n"
                        + "        username: tester\n        password: ${IDM_TEST_DATABASES_HR_PASSWORD}\n");
        JsonNode hr = system(lab(server, "lab"), "database:hr");
        assertThat(hr.get("usedByScenarios").size()).isZero();
        assertThat(field(hr, "password").get("state").asString()).isEqualTo("set");
    }

    @Test
    @DisplayName("An OAuth REST system saved by the lab panel gets no plain-text password warning")
    void testOAuthSaveLintsClean() throws IOException {
        Path ws = openWorkspace(server);

        Response saved = put(server, "lab", "rest:hr", Map.of("base-url", "https://hr.example.test/api",
                "use-oauth", "true", "oauth-client-name", "hr", "client-id", "workbench",
                "token-uri", "https://idp.example.test/oauth/token"), Map.of("client-secret", "fake-client-secret-6"));

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        String written = Files.readString(ws.resolve("config/application-lab.yml"));
        assertThat(written).contains("authorization-grant-type: client_credentials")
                .contains("token-uri: https://idp.example.test/oauth/token");
        assertThat(PlainSecretLint.check(written, PlainSecretLint.FileKind.SETTINGS)).isEmpty();
    }

    @Test
    @DisplayName("The template's own ${…} name is kept when its line is uncommented")
    void testTemplateSecretNameReused() throws IOException {
        Path ws = openWorkspace(server);

        put(server, "lab", "database:sap", Map.of(), Map.of("password", "fake-db-password-5"));

        assertThat(SecretsEnv.read(ws.resolve("secrets.env"))).containsEntry("IDM_TEST_SAP_DB_PASSWORD", "fake-db-password-5");
        assertThat(Files.readString(ws.resolve("config/application-lab.yml")))
                .contains("\n        password: ${IDM_TEST_SAP_DB_PASSWORD}\n");
    }

    @Test
    @DisplayName("Refused saves: a line break in a secret, an unknown system or field, a bad profile; nothing written")
    void testRefusedSaves() throws IOException {
        Path ws = openWorkspace(server);
        List<String> before = snapshot(ws);

        Response newline = put(server, "lab", "ldap", Map.of("url", "ldap://a.test:389"), Map.of("password", "fake\nX=y"));
        assertThat(newline.status()).isEqualTo(400);
        assertThat(JSON.readTree(newline.body()).get("key").asString()).isEqualTo("ui.lab.lineBreak");
        assertThat(newline.body()).doesNotContain("fake");

        assertThat(put(server, "lab", "nosuch", Map.of(), Map.of()).status()).isEqualTo(400);
        Response unknownField = put(server, "lab", "ldap", Map.of("colour", "blue"), Map.of());
        assertThat(JSON.readTree(unknownField.body()).get("key").asString()).isEqualTo("ui.lab.unknownField");
        Response secretAsValue = put(server, "lab", "ldap", Map.of("password", "fake-plain-6"), Map.of());
        assertThat(secretAsValue.status()).isEqualTo(400);
        assertThat(secretAsValue.body()).doesNotContain("fake-plain-6");
        Response badProfile = put(server, "../x", "ldap", Map.of("url", "ldap://a.test:389"), Map.of());
        assertThat(JSON.readTree(badProfile.body()).get("key").asString()).isEqualTo("ui.lab.badProfile");
        assertThat(snapshot(ws)).isEqualTo(before);
    }

    @Test
    @DisplayName("Two profiles save their LDAP passwords independently, under different names")
    void testProfilesKeepTheirOwnSecrets() throws IOException {
        Path ws = openWorkspace(server);
        String labPassword = "fake-lab-password-8";
        String uatPassword = "fake-uat-password-9";

        assertThat(put(server, "lab", "ldap", Map.of("url", "ldap://lab.test:389"), Map.of("password", labPassword))
                .status()).isEqualTo(200);
        assertThat(put(server, "uat", "ldap", Map.of("url", "ldap://uat.test:389"), Map.of("password", uatPassword))
                .status()).isEqualTo(200);

        String labProfile = Files.readString(ws.resolve("config/application-lab.yml"));
        String uatProfile = Files.readString(ws.resolve("config/application-uat.yml"));
        assertThat(labProfile).contains("password: ${IDM_TEST_LDAP_PASSWORD}");
        assertThat(uatProfile).contains("password: ${IDM_TEST_UAT_LDAP_PASSWORD}");
        Map<String, String> secrets = SecretsEnv.read(ws.resolve("secrets.env"));
        // compared without printing the values
        assertThat(labPassword.equals(secrets.get("IDM_TEST_LDAP_PASSWORD"))).isTrue();
        assertThat(uatPassword.equals(secrets.get("IDM_TEST_UAT_LDAP_PASSWORD"))).isTrue();

        // saving again in each profile keeps using its own name
        put(server, "uat", "ldap", Map.of(), Map.of("password", "fake-uat-password-10"));
        Map<String, String> again = SecretsEnv.read(ws.resolve("secrets.env"));
        assertThat(labPassword.equals(again.get("IDM_TEST_LDAP_PASSWORD"))).isTrue();
        assertThat("fake-uat-password-10".equals(again.get("IDM_TEST_UAT_LDAP_PASSWORD"))).isTrue();
        assertThat(again).hasSize(2);
    }

    @Test
    @DisplayName("A secret inherited from application.yml's ${X} is overridden in the profile file; X is left alone")
    void testInheritedSecretOverridden() throws IOException {
        Path ws = openWorkspace(server);
        String sharedText = "idm:\n  test:\n    ldap:\n      url: ldap://shared.test:389\n"
                + "      password: ${IDM_TEST_SHARED_LDAP}\n";
        Files.writeString(ws.resolve("config/application.yml"), sharedText);
        Files.writeString(ws.resolve("secrets.env"), "IDM_TEST_SHARED_LDAP=fake-shared-password-11\n");

        Response saved = put(server, "lab", "ldap", Map.of(), Map.of("password", "fake-lab-password-12"));

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(Files.readString(ws.resolve("config/application.yml"))).isEqualTo(sharedText);
        assertThat(Files.readString(ws.resolve("config/application-lab.yml")))
                .contains("password: ${IDM_TEST_LAB_SHARED_LDAP}");
        Map<String, String> secrets = SecretsEnv.read(ws.resolve("secrets.env"));
        assertThat("fake-shared-password-11".equals(secrets.get("IDM_TEST_SHARED_LDAP"))).isTrue();
        assertThat("fake-lab-password-12".equals(secrets.get("IDM_TEST_LAB_SHARED_LDAP"))).isTrue();
    }

    @Test
    @DisplayName("A value with ${ and a malformed OAuth client name are refused; nothing is written")
    void testReferencesAndClientNameRefused() throws IOException {
        Path ws = openWorkspace(server);
        List<String> before = snapshot(ws);

        Response plain = put(server, "lab", "ldap", Map.of("url", "${IDM_TEST_OTHER}"), Map.of());
        Response secret = put(server, "lab", "ldap", Map.of(), Map.of("password", "fake-${IDM_TEST_OTHER}"));
        Response client = put(server, "lab", "rest:idm", Map.of("use-oauth", "true", "oauth-client-name", "a.b"), Map.of());
        Response tested = UiHttp.send(server, "POST", "/api/lab/test", JSON.writeValueAsString(
                Map.of("systemId", "ldap", "profile", "lab", "values", Map.of("url", "${IDM_TEST_OTHER}"),
                        "secrets", Map.of())));

        assertThat(JSON.readTree(plain.body()).get("key").asString()).isEqualTo("ui.lab.reference");
        assertThat(JSON.readTree(secret.body()).get("key").asString()).isEqualTo("ui.lab.reference");
        assertThat(secret.body()).doesNotContain("fake-");
        assertThat(JSON.readTree(client.body()).get("key").asString()).isEqualTo("ui.lab.badClientName");
        assertThat(tested.status()).isEqualTo(400);
        assertThat(plain.status()).isEqualTo(400);
        assertThat(client.status()).isEqualTo(400);
        assertThat(snapshot(ws)).isEqualTo(before);
    }

    @Test
    @DisplayName("A profile file that links to application.yml is refused before secrets.env changes")
    void testProfileLinkedToApplicationYml() throws IOException {
        Path ws = openWorkspace(server);
        Path shared = ws.resolve("config/application.yml");
        Files.writeString(shared, "idm:\n  test:\n    ldap:\n      url: ldap://shared.test:389\n");
        Path profile = ws.resolve("config/application-lab.yml");
        Files.delete(profile);
        Files.createSymbolicLink(profile, Path.of("application.yml"));
        List<String> before = snapshot(ws);

        Response saved = put(server, "lab", "ldap", Map.of("user-dn", "cn=tester,o=data"),
                Map.of("password", "fake-lab-password-13"));

        assertThat(saved.status()).isEqualTo(400);
        assertThat(JSON.readTree(saved.body()).get("key").asString()).isEqualTo("ui.lab.cannotEdit");
        assertThat(Files.exists(ws.resolve("secrets.env"))).isFalse();
        assertThat(snapshot(ws)).isEqualTo(before);
    }

    @Test
    @DisplayName("A secrets.env that links outside the workspace is refused; nothing is written anywhere")
    void testSecretsEnvLinkedOutside() throws IOException {
        Path ws = openWorkspace(server);
        Path outside = temp.resolve("outside.env");
        String outsideText = "# not the workspace's\nIDM_TEST_OTHER=fake-outside-14\n";
        Files.writeString(outside, outsideText);
        Files.createSymbolicLink(ws.resolve("secrets.env"), outside);
        List<String> before = snapshot(ws);

        Response saved = put(server, "lab", "ldap", Map.of("url", "ldap://lab.test:389"),
                Map.of("password", "fake-lab-password-15"));

        assertThat(saved.status()).isEqualTo(400);
        assertThat(JSON.readTree(saved.body()).get("key").asString()).isEqualTo("ui.lab.cannotEdit");
        assertThat(saved.body()).doesNotContain("fake-");
        assertThat(Files.readString(outside)).isEqualTo(outsideText);
        assertThat(snapshot(ws)).isEqualTo(before);
    }

    @Test
    @DisplayName("Another profile file that cannot be read counts as using the name: the profile gets its own")
    void testUnreadableOtherProfile() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path ws = openWorkspace(server);
        Path other = ws.resolve("config/application-uat.yml");
        Files.writeString(other, "idm:\n  test:\n    ldap:\n      password: ${IDM_TEST_LDAP_PASSWORD}\n");
        Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(other), "running as a user who reads every file (root)");

            Response saved = put(server, "lab", "ldap", Map.of(), Map.of("password", "fake-lab-password-16"));

            assertThat(saved.status()).as(saved.body()).isEqualTo(200);
            assertThat(Files.readString(ws.resolve("config/application-lab.yml")))
                    .contains("password: ${IDM_TEST_LAB_LDAP_PASSWORD}");
            assertThat(SecretsEnv.read(ws.resolve("secrets.env"))).containsOnlyKeys("IDM_TEST_LAB_LDAP_PASSWORD");
        } finally {
            Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    @DisplayName("A profile file that is not valid YAML is reported, not overwritten")
    void testBrokenProfileFile() throws IOException {
        Path ws = openWorkspace(server);
        Path profile = ws.resolve("config/application-lab.yml");
        Files.writeString(profile, "idm: [\n");

        Response read = UiHttp.get(server, "/api/lab?profile=lab");
        Response save = put(server, "lab", "ldap", Map.of("url", "ldap://a.test:389"), Map.of());

        assertThat(read.status()).isEqualTo(400);
        assertThat(JSON.readTree(read.body()).get("key").asString()).isEqualTo("ui.lab.settingsUnreadable");
        assertThat(save.status()).isEqualTo(400);
        assertThat(Files.readString(profile)).isEqualTo("idm: [\n");
    }

    // --- the default directory ------------------------------------------------------------------

    /** Two named directories, edirectory and ad, in application.yml; {@code more} is added under idm.test. */
    private static String twoDirectories(String more) {
        return "idm:\n  test:\n" + more
                + "    directories:\n"
                + "      edirectory:\n        url: ldap://edir.test:389\n"
                + "      ad:\n        url: ldap://ad.test:389\n";
    }

    private static boolean hasSystem(JsonNode lab, String id) {
        for (JsonNode system : lab.get("systems")) {
            if (system.get("id").asString().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> strings(JsonNode array) {
        List<String> all = new ArrayList<>();
        array.forEach(node -> all.add(node.asString()));
        return all;
    }

    private static Response putDefault(String profile, String name, Map<String, String> headers) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        return UiHttp.send(server, "PUT", "/api/lab/default-directory?profile=" + profile,
                JSON.writeValueAsString(body), headers);
    }

    @Test
    @DisplayName("Steps without directory: and two directories with no default ask for a default, not the old Directory")
    void testAmbiguousDefaultDirectory() throws IOException {
        Path ws = openWorkspace(server);
        Files.writeString(ws.resolve("config/application.yml"), twoDirectories(""));

        JsonNode view = lab(server, "lab");

        assertThat(hasSystem(view, "ldap")).as(view.toString()).isFalse();
        JsonNode choice = view.get("defaultDirectory");
        assertThat(choice.isObject()).as(view.toString()).isTrue();
        assertThat(strings(choice.get("usedByScenarios"))).containsExactly("example.yaml");
        assertThat(strings(choice.get("choices"))).containsExactly("ad", "edirectory");
        assertThat(choice.get("value").isNull()).isTrue();
        assertThat(choice.get("source").isNull()).isTrue();
        assertThat(choice.get("state").asString()).isEqualTo("missing");
        assertThat(system(view, "directory:ad").get("usedByScenarios").size()).isZero();
        assertThat(system(view, "directory:edirectory").get("usedByScenarios").size()).isZero();
    }

    @Test
    @DisplayName("A default-directory that names no configured directory is reported as invalid, with its value")
    void testInvalidDefaultDirectory() throws IOException {
        Path ws = openWorkspace(server);
        Files.writeString(ws.resolve("config/application.yml"), twoDirectories("    default-directory: nope\n"));

        JsonNode view = lab(server, "lab");

        assertThat(hasSystem(view, "ldap")).as(view.toString()).isFalse();
        JsonNode choice = view.get("defaultDirectory");
        assertThat(choice.isObject()).as(view.toString()).isTrue();
        assertThat(choice.get("state").asString()).isEqualTo("invalid");
        assertThat(choice.get("value").asString()).isEqualTo("nope");
        assertThat(choice.get("source").asString()).isEqualTo("application.yml");
        assertThat(strings(choice.get("choices"))).containsExactly("ad", "edirectory");
        assertThat(strings(choice.get("usedByScenarios"))).containsExactly("example.yaml");
    }

    @Test
    @DisplayName("An invalid default-directory is reported even when every ldap.* step names its directory, as a run refuses it")
    void testInvalidDefaultWithNamedSteps() throws IOException {
        Path ws = openWorkspace(server);
        Path example = ws.resolve("scenarios/example.yaml");
        String named = Files.readString(example).replaceAll("(?m)^(  - ldap\\.\\w+:\n)", "$1      directory: edirectory\n");
        assertThat(named).contains("  - ldap.create:\n      directory: edirectory\n",
                "  - ldap.delete:\n      directory: edirectory\n");
        Files.writeString(example, named);
        Files.writeString(ws.resolve("config/application-lab.yml"), "idm:\n  test:\n    default-directory: nope\n");
        Files.writeString(ws.resolve("config/application.yml"), twoDirectories(""));

        JsonNode view = lab(server, "lab");

        JsonNode choice = view.get("defaultDirectory");
        assertThat(choice.isObject()).as(view.toString()).isTrue();
        assertThat(choice.get("state").asString()).isEqualTo("invalid");
        assertThat(choice.get("value").asString()).isEqualTo("nope");
        assertThat(choice.get("source").asString()).isEqualTo("profile");
        assertThat(strings(choice.get("choices"))).containsExactly("ad", "edirectory");
        assertThat(strings(choice.get("usedByScenarios"))).containsExactly("example.yaml");
        assertThat(strings(system(view, "directory:edirectory").get("usedByScenarios"))).containsExactly("example.yaml");

        Files.writeString(ws.resolve("config/application-lab.yml"), "idm:\n  test:\n    default-directory: ad\n");
        assertThat(lab(server, "lab").get("defaultDirectory").isNull()).isTrue();
    }

    @Test
    @DisplayName("A default-directory from the environment says so, and saving over it is refused")
    void testDefaultDirectoryFromEnvironment() throws Exception {
        try (UiServer withEnv = UiCommand.start(0, Locale.ENGLISH, null, Map.of("IDM_TEST_DEFAULTDIRECTORY", "nope"))) {
            Path ws = openWorkspace(withEnv);
            Files.writeString(ws.resolve("config/application.yml"), twoDirectories(""));
            List<String> before = snapshot(ws);

            JsonNode choice = lab(withEnv, "lab").get("defaultDirectory");
            assertThat(choice.get("state").asString()).isEqualTo("invalid");
            assertThat(choice.get("value").asString()).isEqualTo("nope");
            assertThat(choice.get("source").asString()).isEqualTo("environment");

            Response saved = UiHttp.send(withEnv, "PUT", "/api/lab/default-directory?profile=lab",
                    JSON.writeValueAsString(Map.of("name", "edirectory")));
            assertThat(saved.status()).as(saved.body()).isEqualTo(400);
            assertThat(JSON.readTree(saved.body()).get("key").asString()).isEqualTo("ui.lab.fromEnvironment");
            assertThat(saved.body()).contains("IDM_TEST_DEFAULTDIRECTORY");
            assertThat(snapshot(ws)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("An invalid default-directory is reported even when no scenario has an ldap.* step, as every run fails to start")
    void testInvalidDefaultWithoutLdapSteps() throws IOException {
        Path ws = openWorkspace(server);
        Files.delete(ws.resolve("scenarios/example.yaml"));
        Files.writeString(ws.resolve("config/application.yml"), twoDirectories("    default-directory: nope\n"));

        JsonNode view = lab(server, "lab");

        JsonNode choice = view.get("defaultDirectory");
        assertThat(choice.isObject()).as(view.toString()).isTrue();
        assertThat(choice.get("state").asString()).isEqualTo("invalid");
        assertThat(choice.get("value").asString()).isEqualTo("nope");
        assertThat(strings(choice.get("choices"))).containsExactly("ad", "edirectory");
        assertThat(choice.get("usedByScenarios").size()).isZero();
    }

    @Test
    @DisplayName("A profile default-directory that is a ${VAR} reference set by the environment cannot be saved over")
    void testDefaultDirectoryReferenceFromEnvironment() throws Exception {
        try (UiServer withEnv = UiCommand.start(0, Locale.ENGLISH, null, Map.of("IDM_FAKE_DEFAULT_DIR", "nope"))) {
            Path ws = openWorkspace(withEnv);
            Files.writeString(ws.resolve("config/application.yml"), twoDirectories(""));
            Files.writeString(ws.resolve("config/application-lab.yml"),
                    "idm:\n  test:\n    default-directory: ${IDM_FAKE_DEFAULT_DIR}\n");
            List<String> before = snapshot(ws);

            JsonNode choice = lab(withEnv, "lab").get("defaultDirectory");
            assertThat(choice.get("source").asString()).isEqualTo("environment");

            Response saved = UiHttp.send(withEnv, "PUT", "/api/lab/default-directory?profile=lab",
                    JSON.writeValueAsString(Map.of("name", "edirectory")));
            assertThat(saved.status()).as(saved.body()).isEqualTo(400);
            assertThat(JSON.readTree(saved.body()).get("key").asString()).isEqualTo("ui.lab.fromEnvironment");
            assertThat(saved.body()).contains("IDM_FAKE_DEFAULT_DIR");
            assertThat(snapshot(ws)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("A default that resolves (one directory, the old block's URL, a valid default) asks for nothing new")
    void testResolvableDefaultUnchanged() throws IOException {
        Path ws = openWorkspace(server);
        Path shared = ws.resolve("config/application.yml");

        JsonNode fresh = lab(server, "lab");
        assertThat(fresh.get("defaultDirectory").isNull()).as(fresh.toString()).isTrue();
        assertThat(strings(system(fresh, "ldap").get("usedByScenarios"))).containsExactly("example.yaml");

        Files.writeString(shared, "idm:\n  test:\n    directories:\n      edirectory:\n        url: ldap://edir.test:389\n");
        JsonNode one = lab(server, "lab");
        assertThat(one.get("defaultDirectory").isNull()).as(one.toString()).isTrue();
        assertThat(strings(system(one, "directory:edirectory").get("usedByScenarios"))).containsExactly("example.yaml");
        assertThat(hasSystem(one, "ldap")).isFalse();

        Files.writeString(shared, twoDirectories("    ldap:\n      url: ldap://old.test:389\n"));
        JsonNode legacy = lab(server, "lab");
        assertThat(legacy.get("defaultDirectory").isNull()).as(legacy.toString()).isTrue();
        assertThat(strings(system(legacy, "ldap").get("usedByScenarios"))).containsExactly("example.yaml");
        assertThat(system(legacy, "directory:ad").get("usedByScenarios").size()).isZero();

        Files.writeString(shared, twoDirectories("    default-directory: ad\n"));
        JsonNode chosen = lab(server, "lab");
        assertThat(chosen.get("defaultDirectory").isNull()).as(chosen.toString()).isTrue();
        assertThat(strings(system(chosen, "directory:ad").get("usedByScenarios"))).containsExactly("example.yaml");
        assertThat(system(chosen, "directory:edirectory").get("usedByScenarios").size()).isZero();
        assertThat(hasSystem(chosen, "ldap")).isFalse();
    }

    @Test
    @DisplayName("Saving the default directory uncomments the template's line in the profile file and nothing else")
    void testSaveDefaultDirectory() throws IOException {
        Path ws = openWorkspace(server);
        Path shared = ws.resolve("config/application.yml");
        String sharedText = twoDirectories("");
        Files.writeString(shared, sharedText);
        Path profile = ws.resolve("config/application-lab.yml");
        String template = Files.readString(profile);
        assertThat(template).contains("# idm:\n#   test:\n", "\n#     default-directory: edirectory\n");

        Response saved = putDefault("lab", "edirectory", Map.of());

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        String expected = template.replace("# idm:\n#   test:\n", "idm:\n  test:\n")
                .replace("\n#     default-directory: edirectory\n", "\n    default-directory: edirectory\n");
        assertThat(Files.readString(profile)).isEqualTo(expected);
        assertThat(Files.readString(shared)).isEqualTo(sharedText);
        assertThat(Files.exists(ws.resolve("secrets.env"))).isFalse();
        JsonNode view = JSON.readTree(saved.body());
        assertThat(view.get("defaultDirectory").isNull()).as(saved.body()).isTrue();
        assertThat(strings(system(view, "directory:edirectory").get("usedByScenarios"))).containsExactly("example.yaml");
        assertThat(hasSystem(view, "ldap")).isFalse();
        assertThat(lab(server, "lab").get("defaultDirectory").isNull()).isTrue();
    }

    @Test
    @DisplayName("Saving a default directory that is not one of the choices is refused, in English and Spanish")
    void testBadDefaultDirectory() throws IOException {
        Path ws = openWorkspace(server);
        Files.writeString(ws.resolve("config/application.yml"), twoDirectories(""));
        List<String> before = snapshot(ws);

        Response english = putDefault("lab", "nope", Map.of());
        Response spanish = putDefault("lab", "nope", Map.of("Accept-Language", "es"));
        Response none = putDefault("lab", null, Map.of());

        assertThat(english.status()).as(english.body()).isEqualTo(400);
        JsonNode en = JSON.readTree(english.body());
        assertThat(en.get("key").asString()).isEqualTo("ui.lab.badDefaultDirectory");
        assertThat(en.get("message").asString())
                .isEqualTo("'nope' is not one of the configured directories. Choose one of: [ad, edirectory]");
        assertThat(spanish.status()).isEqualTo(400);
        JsonNode es = JSON.readTree(spanish.body());
        assertThat(es.get("key").asString()).isEqualTo("ui.lab.badDefaultDirectory");
        assertThat(es.get("message").asString())
                .isEqualTo("'nope' no es ninguno de los directorios configurados. Elija uno de: [ad, edirectory]");
        assertThat(none.status()).isEqualTo(400);
        assertThat(JSON.readTree(none.body()).get("key").asString()).isEqualTo("ui.lab.badDefaultDirectory");
        assertThat(snapshot(ws)).isEqualTo(before);
    }

    @Test
    @DisplayName("Without a workspace the lab API answers 400 ui.workspace.none")
    void testNoWorkspace() throws Exception {
        try (UiServer bare = UiCommand.start(0, Locale.ENGLISH, null, Map.of())) {
            Response response = UiHttp.get(bare, "/api/lab?profile=lab");
            assertThat(response.status()).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).get("key").asString()).isEqualTo("ui.workspace.none");
        }
    }
}
