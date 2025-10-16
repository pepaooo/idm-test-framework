package com.idm.test.connector.rest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.idm.test.connector.rest.config.RestClientConfiguration;
import com.idm.test.connector.rest.model.IdmUser;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for IdmRestConnector.
 * Uses WireMock to mock REST API endpoints.
 */
@SpringBootTest(classes = {
    RestClientConfiguration.class,
    IdmRestConnector.class
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdmRestConnectorTest {

    @Autowired
    private IdmRestConnector connector;

    private static WireMockServer wireMockServer;
    private static String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(8089);
        wireMockServer.start();
        WireMock.configureFor("localhost", 8089);
        baseUrl = "http://localhost:8089";
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
    }

    @Test
    @Order(1)
    @DisplayName("Should perform GET request successfully")
    void testGetRequest() throws Exception {
        IdmUser mockUser = createMockUser("test.user");
        String responseBody = objectMapper.writeValueAsString(mockUser);

        stubFor(get(urlEqualTo("/users/test.user"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(responseBody)));

        OperationResult<IdmUser> result = connector.get(baseUrl + "/users/test.user", IdmUser.class);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get().getCn()).isEqualTo("test.user");

        verify(getRequestedFor(urlEqualTo("/users/test.user")));
    }

    @Test
    @Order(2)
    @DisplayName("Should perform POST request successfully")
    void testPostRequest() throws Exception {
        IdmUser requestUser = createMockUser("new.user");
        IdmUser responseUser = createMockUser("new.user");
        responseUser.setDn("cn=new.user,ou=people,dc=company,dc=com");

        String responseBody = objectMapper.writeValueAsString(responseUser);

        stubFor(post(urlEqualTo("/users"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(responseBody)));

        OperationResult<IdmUser> result = connector.post(baseUrl + "/users", requestUser, IdmUser.class);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get().getCn()).isEqualTo("new.user");
        assertThat(result.getData().get().getDn()).isNotNull();

        verify(postRequestedFor(urlEqualTo("/users")));
    }

    @Test
    @Order(3)
    @DisplayName("Should perform PUT request successfully")
    void testPutRequest() throws Exception {
        IdmUser updateUser = createMockUser("test.user");
        updateUser.setEmail("updated@test.com");

        String responseBody = objectMapper.writeValueAsString(updateUser);

        stubFor(put(urlEqualTo("/users/test.user"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(responseBody)));

        OperationResult<IdmUser> result = connector.put(baseUrl + "/users/test.user", updateUser, IdmUser.class);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get().getEmail()).isEqualTo("updated@test.com");

        verify(putRequestedFor(urlEqualTo("/users/test.user")));
    }

    @Test
    @Order(4)
    @DisplayName("Should perform DELETE request successfully")
    void testDeleteRequest() {
        stubFor(delete(urlEqualTo("/users/test.user"))
                .willReturn(aResponse()
                        .withStatus(204)));

        OperationResult<Void> result = connector.delete(baseUrl + "/users/test.user");

        assertThat(result.isSuccess()).isTrue();

        verify(deleteRequestedFor(urlEqualTo("/users/test.user")));
    }

    @Test
    @Order(5)
    @DisplayName("Should create user via IdM API")
    void testCreateUser() throws Exception {
        IdmUser user = createMockUser("john.doe");
        IdmUser createdUser = createMockUser("john.doe");
        createdUser.setDn("cn=john.doe,ou=people,dc=company,dc=com");

        String responseBody = objectMapper.writeValueAsString(createdUser);

        stubFor(post(urlEqualTo("/users"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(responseBody)));

        OperationResult<IdmUser> result = connector.createUser(baseUrl, user);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get().getCn()).isEqualTo("john.doe");
        assertThat(result.getData().get().getDn()).isNotNull();
    }

    @Test
    @Order(6)
    @DisplayName("Should get user via IdM API")
    void testGetUser() throws Exception {
        IdmUser mockUser = createMockUser("test.user");
        String responseBody = objectMapper.writeValueAsString(mockUser);

        stubFor(get(urlEqualTo("/users/test.user"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(responseBody)));

        OperationResult<IdmUser> result = connector.getUser(baseUrl, "test.user");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get().getCn()).isEqualTo("test.user");
    }

    @Test
    @Order(7)
    @DisplayName("Should update user via IdM API")
    void testUpdateUser() throws Exception {
        IdmUser user = createMockUser("test.user");
        user.setEmail("updated@test.com");

        String responseBody = objectMapper.writeValueAsString(user);

        stubFor(put(urlEqualTo("/users/test.user"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(responseBody)));

        OperationResult<IdmUser> result = connector.updateUser(baseUrl, "test.user", user);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get().getEmail()).isEqualTo("updated@test.com");
    }

    @Test
    @Order(8)
    @DisplayName("Should delete user via IdM API")
    void testDeleteUser() {
        stubFor(delete(urlEqualTo("/users/test.user"))
                .willReturn(aResponse()
                        .withStatus(204)));

        OperationResult<Void> result = connector.deleteUser(baseUrl, "test.user");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @Order(9)
    @DisplayName("Should handle 404 Not Found error")
    void testNotFoundError() {
        stubFor(get(urlEqualTo("/users/nonexistent"))
                .willReturn(aResponse()
                        .withStatus(404)));

        OperationResult<IdmUser> result = connector.get(baseUrl + "/users/nonexistent", IdmUser.class);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isPresent();
        assertThat(result.getErrorType().get()).isEqualTo(ErrorType.NOT_FOUND);
        assertThat(result.getMessage()).contains("404");
    }

    @Test
    @Order(10)
    @DisplayName("Should handle 500 Server Error")
    void testServerError() {
        stubFor(get(urlEqualTo("/users/error"))
                .willReturn(aResponse()
                        .withStatus(500)));

        OperationResult<IdmUser> result = connector.get(baseUrl + "/users/error", IdmUser.class);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isPresent();
        assertThat(result.getErrorType().get()).isEqualTo(ErrorType.SERVER_ERROR);
        assertThat(result.getMessage()).contains("500");
    }

    @Test
    @Order(11)
    @DisplayName("Should handle 400 Bad Request error")
    void testBadRequestError() {
        IdmUser invalidUser = new IdmUser();

        stubFor(post(urlEqualTo("/users"))
                .willReturn(aResponse()
                        .withStatus(400)));

        OperationResult<IdmUser> result = connector.post(baseUrl + "/users", invalidUser, IdmUser.class);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isPresent();
        assertThat(result.getErrorType().get()).isEqualTo(ErrorType.VALIDATION_ERROR);
    }

    @Test
    @Order(12)
    @DisplayName("Should handle connection error")
    void testConnectionError() {
        // Use invalid URL that doesn't exist
        String invalidUrl = "http://localhost:9999/users/test";

        OperationResult<IdmUser> result = connector.get(invalidUrl, IdmUser.class);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isPresent();
        // Should be NETWORK_ERROR or UNKNOWN_ERROR
        assertThat(result.getErrorType().get()).isIn(ErrorType.NETWORK_ERROR, ErrorType.UNKNOWN_ERROR);
    }

    /**
     * Helper method to create a mock IdM user.
     */
    private IdmUser createMockUser(String cn) {
        IdmUser user = new IdmUser();
        user.setCn(cn);
        user.setSurname("Doe");
        user.setGivenName("John");
        user.setFullName("John Doe");
        user.setEmail(cn + "@test.com");
        user.setDepartment("IT");
        user.setTitle("Software Engineer");
        user.setStatus("ACTIVE");
        return user;
    }
}
