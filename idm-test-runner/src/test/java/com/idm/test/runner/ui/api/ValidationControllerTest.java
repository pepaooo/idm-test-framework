package com.idm.test.runner.ui.api;

import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ValidationException;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.runner.InitCommand;
import com.idm.test.runner.ui.StepSchema;
import com.idm.test.runner.ui.UiCommand;
import com.idm.test.runner.ui.UiHttp;
import com.idm.test.runner.ui.UiHttp.Response;
import com.idm.test.runner.ui.UiServer;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.StepRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Problems panel's API and the editor's schema, over HTTP against a running workbench. */
@DisplayName("Workbench validation API")
class ValidationControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** An obviously fake value that must never come back in a response. */
    private static final String FAKE = "Fake-Secret-123";

    @TempDir
    static Path temp;
    private static Path workspace;
    private static UiServer server;

    @BeforeAll
    static void start() {
        workspace = temp.resolve("ws");
        InitCommand.run(workspace, new PrintStream(OutputStream.nullOutputStream()));
        server = UiCommand.start(0, Locale.ENGLISH, workspace);
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    private static final String TYPO = """
            name: verify a user
            steps:
              - ldap.verify:
                  dn: cn=jdoe,ou=users,o=lab
                  expct:
                    cn: jdoe
            """;

    // --- scenarios ----------------------------------------------------------------------------

    @Test
    @DisplayName("A typo'd parameter is an error on its own line, with the message idmtest validate gives")
    void testTypoOnItsLine() throws Exception {
        Response response = validate("scenarios/users/typo.yaml", TYPO);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<JsonNode> problems = problems(response);
        assertThat(problems).hasSize(1);
        JsonNode problem = problems.getFirst();
        assertThat(problem.propertyNames()).containsExactlyInAnyOrder("severity", "line", "column", "message", "key");
        assertThat(problem.get("severity").asString()).isEqualTo("error");
        assertThat(problem.get("line").asInt()).isEqualTo(5);
        assertThat(problem.get("column").asInt()).isEqualTo(7);
        assertThat(problem.get("key").asString()).isEqualTo("validation.step.unknownParam");
        assertThat(problem.get("message").asString()).isEqualTo(cliMessage("users/typo.yaml", TYPO));
    }

    @Test
    @DisplayName("A missing parameter is an error on the step's line; Spanish when the request asks for it")
    void testMissingParamOnStepLine() throws Exception {
        String yaml = "name: x\nsteps:\n  - ldap.create:\n      attributes: {cn: x}\n  - ldap.verify: {}\n";

        JsonNode problem = problems(validate("scenarios/x.yaml", yaml)).getFirst();
        assertThat(problem.get("line").asInt()).isEqualTo(3);
        assertThat(problem.get("column").asInt()).isEqualTo(5);
        assertThat(problem.get("key").asString()).isEqualTo("validation.step.missingParam");

        JsonNode spanish = problems(validate("scenarios/x.yaml", yaml, Map.of("Accept-Language", "es"))).getFirst();
        assertThat(spanish.get("message").asString()).contains("paso 1").contains("falta el parámetro");
    }

    @Test
    @DisplayName("A problem in a cleanup entry or a fixture points at it; a file-level problem at line 1")
    void testOtherLocations() throws Exception {
        String cleanup = "name: x\nsteps:\n  - ldap.delete: {dn: a}\ncleanup:\n  - ldap.delete:\n      dn: a\n      wait: true\n";
        JsonNode problem = problems(validate("scenarios/x.yaml", cleanup)).getFirst();
        assertThat(problem.get("line").asInt()).isEqualTo(7);
        assertThat(problem.get("key").asString()).isEqualTo("validation.cleanup.reserved");

        String fixture = "name: x\nfixtures:\n  user:\n    generate: nope\nsteps:\n  - ldap.delete: {dn: a}\n";
        problem = problems(validate("scenarios/x.yaml", fixture)).getFirst();
        assertThat(problem.get("line").asInt()).isEqualTo(3);
        assertThat(problem.get("key").asString()).isEqualTo("validation.fixture.unknownGenerator");

        problem = problems(validate("scenarios/x.yaml", "description: no name\n")).getFirst();
        assertThat(problem.get("line").asInt()).isEqualTo(1);
        assertThat(problem.get("column").asInt()).isEqualTo(1);
        assertThat(problem.get("key").asString()).isEqualTo("validation.load.nameRequired");
    }

    @Test
    @DisplayName("A valid scenario whose password is a ${…} reference has no problems; ${IDM_TEST_PW} is no warning")
    void testValidHasNoProblems() throws Exception {
        String yaml = "name: x\nfixtures:\n  user:\n    generate: random-user\nsteps:\n  - ldap.create:\n"
                + "      dn: cn=a,o=lab\n      attributes:\n        cn: a\n        userPassword: ${user.password}\n";
        assertThat(problems(validate("scenarios/x.yaml", yaml))).isEmpty();

        String env = "name: x\nsteps:\n  - ldap.create:\n      dn: cn=a,o=lab\n"
                + "      attributes:\n        cn: a\n        userPassword: ${IDM_TEST_PW}\n";
        assertThat(problems(validate("scenarios/x.yaml", env)))
                .as("the validator's own unknown-reference error only")
                .extracting(p -> p.get("severity").asString()).containsExactly("error");
    }

    @Test
    @DisplayName("A plain password is a warning on its line, in the request's language, and is never echoed")
    void testPlainPasswordWarning() throws Exception {
        String yaml = "name: x\nsteps:\n  - ldap.create:\n      dn: cn=a,o=lab\n"
                + "      attributes:\n        cn: a\n        userPassword: " + FAKE + "\n";

        Response response = validate("scenarios/x.yaml", yaml);
        List<JsonNode> problems = problems(response);
        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst().get("severity").asString()).isEqualTo("warning");
        assertThat(problems.getFirst().get("line").asInt()).isEqualTo(7);
        assertThat(problems.getFirst().get("key").asString()).isEqualTo("ui.lint.plainSecret");
        assertThat(problems.getFirst().get("message").asString())
                .isEqualTo("a password is written in plain text — use ${IDM_TEST_…} and put the value in secrets.env");
        assertThat(response.body()).doesNotContain(FAKE);

        Response spanish = validate("scenarios/x.yaml", yaml, Map.of("Accept-Language", "es"));
        assertThat(problems(spanish).getFirst().get("message").asString())
                .contains("contraseña").contains("${IDM_TEST_…}").contains("secrets.env");
    }

    @Test
    @DisplayName("Errors and warnings together, sorted by line")
    void testErrorAndWarning() throws Exception {
        String yaml = "name: x\nsteps:\n  - ldap.create:\n      dn: cn=a,o=lab\n      password: " + FAKE + "\n";

        Response response = validate("scenarios/x.yaml", yaml);
        List<JsonNode> problems = problems(response);
        assertThat(problems).extracting(p -> p.get("severity").asString()).containsExactly("error", "warning");
        assertThat(problems).extracting(p -> p.get("line").asInt()).containsExactly(5, 5);
        assertThat(response.body()).doesNotContain(FAKE);
    }

    @Test
    @DisplayName("A YAML error on a password's line has the parser's position, and does not echo the password")
    void testParseErrorDoesNotEchoSecret() throws Exception {
        String yaml = "name: x\nsteps:\n  - ldap.create:\n      dn: cn=a,o=lab\n      userPassword: \"" + FAKE + "\n";
        assertThatThrownBy(() -> ScenarioLoader.load(in(yaml), "x.yaml"))
                .as("the engine's own message would show the line").hasMessageContaining(FAKE);

        Response response = validate("scenarios/x.yaml", yaml);

        assertThat(response.body()).doesNotContain(FAKE);
        JsonNode error = problems(response).stream().filter(p -> p.get("severity").asString().equals("error"))
                .findFirst().orElseThrow();
        assertThat(error.get("key").asString()).isEqualTo("validation.load.parse");
        assertThat(error.get("line").asInt()).isEqualTo(5);
        assertThat(error.get("message").asString()).startsWith("x.yaml: could not parse YAML: ").doesNotContain("\n");

        // flow style: the parser's excerpt of the line would show the password, and masking by value cannot find it
        String flow = "name: x\nsteps:\n  - ldap.create: {dn: a, attributes: {userPassword: \"" + FAKE + "}}\n";
        Response flowResponse = validate("scenarios/x.yaml", flow);
        assertThat(flowResponse.body()).doesNotContain(FAKE);
        assertThat(problems(flowResponse).getFirst().get("line").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("A YAML error is placed where the YAML parser found it")
    void testParseErrorPosition() throws Exception {
        JsonNode error = problems(validate("scenarios/x.yaml", "name: x\nsteps:\n\t- ldap.delete: {dn: a}\n")).getFirst();

        assertThat(error.get("line").asInt()).isEqualTo(3);
        assertThat(error.get("column").asInt()).isEqualTo(1);
        assertThat(error.get("message").asString()).contains("TAB");
    }

    @Test
    @DisplayName("A validator message about a password's value does not echo it")
    void testValidatorMessageDoesNotEchoSecret() throws Exception {
        String yaml = "name: x\nsteps:\n  - ldap.create:\n      dn: cn=a,o=lab\n"
                + "      attributes:\n        userPassword: js:{ " + FAKE + " + }\n";

        Response response = validate("scenarios/x.yaml", yaml);

        assertThat(problems(response)).isNotEmpty();
        assertThat(response.body()).doesNotContain(FAKE);
    }

    // --- settings files, paths ----------------------------------------------------------------

    @Test
    @DisplayName("A settings file gets only the password check, not scenario validation")
    void testConfigFileLintOnly() throws Exception {
        String yaml = "idm:\n  test:\n    ldap:\n      password: " + FAKE + "\n";

        Response response = validate("config/application-lab.yml", yaml);

        List<JsonNode> problems = problems(response);
        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst().get("severity").asString()).isEqualTo("warning");
        assertThat(problems.getFirst().get("line").asInt()).isEqualTo(4);
        assertThat(response.body()).doesNotContain(FAKE);
        assertThat(problems(validate("config/application-lab.yml", "idm:\n  test: {}\n"))).isEmpty();
    }

    @Test
    @DisplayName("secrets.env is never accepted; paths outside scenarios/ and config/ are refused")
    void testRefusedPaths() throws Exception {
        assertError(validate("secrets.env", "IDM_TEST_PW=" + FAKE + "\n"), "ui.validate.secretsFile");
        assertError(validate("config/secrets.env", "x"), "ui.validate.secretsFile");
        for (String path : List.of("", "/etc/x.yaml", "../x.yaml", "scenarios/../x.yaml", "notes.yaml",
                "scenarios/x.txt", "config/sub/application-x.yml", "scenarios\\x.yaml", "scenarios//x.yaml")) {
            assertError(validate(path, "name: x\n"), "ui.validate.badPath");
        }
        assertError(UiHttp.send(server, "POST", "/api/validate", "{\"path\":\"scenarios/x.yaml\"}"), "ui.request.invalid");
        assertError(UiHttp.send(server, "POST", "/api/validate", "{\"content\":\"name: x\"}"), "ui.validate.badPath");
    }

    @Test
    @DisplayName("Validating never writes the file")
    void testNeverWrites() throws Exception {
        Path existing = workspace.resolve("scenarios/keep.yaml");
        Files.writeString(existing, "name: keep\n");

        validate("scenarios/keep.yaml", TYPO);
        validate("scenarios/new.yaml", TYPO);

        assertThat(Files.readString(existing)).isEqualTo("name: keep\n");
        assertThat(workspace.resolve("scenarios/new.yaml")).doesNotExist();
    }

    @Test
    @DisplayName("Without an open workspace the content is still validated")
    void testNoWorkspace() throws Exception {
        try (UiServer bare = UiCommand.start(0, Locale.ENGLISH)) {
            Response response = UiHttp.send(bare, "POST", "/api/validate",
                    JSON.writeValueAsString(Map.of("path", "scenarios/users/typo.yaml", "content", TYPO)));
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            assertThat(problems(response)).extracting(p -> p.get("line").asInt()).containsExactly(5);
        }
    }

    // --- schema -------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/schema/scenario serves the step-vocabulary schema, the same every time")
    void testSchema() throws Exception {
        Response first = UiHttp.get(server, "/api/schema/scenario");
        Response second = UiHttp.get(server, "/api/schema/scenario");

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.header("Content-Type")).startsWith("application/json");
        assertThat(JSON.readTree(first.body())).isEqualTo(JSON.readTree(StepSchema.of(StepRegistry.forValidation())));
        assertThat(second.body()).isEqualTo(first.body());
    }

    // --- helpers ------------------------------------------------------------------------------

    private static Response validate(String path, String content) throws IOException {
        return validate(path, content, Map.of());
    }

    private static Response validate(String path, String content, Map<String, String> headers) throws IOException {
        return UiHttp.send(server, "POST", "/api/validate",
                JSON.writeValueAsString(Map.of("path", path, "content", content)), headers);
    }

    private static List<JsonNode> problems(Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.propertyNames()).containsExactly("problems");
        return body.get("problems").valueStream().toList();
    }

    private static void assertError(Response response, String key) {
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("key").asString()).isEqualTo(key);
        assertThat(body.get("message").asString()).isNotBlank();
        assertThat(response.body()).doesNotContain(FAKE);
    }

    private static ByteArrayInputStream in(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    /** What {@code idmtest validate} prints for the same content. */
    private static String cliMessage(String name, String yaml) {
        try {
            EngineFactory.validator(StepRegistry.forValidation()).validate(ScenarioLoader.load(in(yaml), name), name);
            throw new AssertionError("expected a validation error");
        } catch (ValidationException e) {
            return Messages.render(e.detail(), Locale.ENGLISH);
        }
    }
}
