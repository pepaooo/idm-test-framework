package com.idm.test.connector.rest.client;

import com.idm.test.connector.rest.model.IdmUser;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * IdM REST API connector using Spring's RestClient.
 * Simple wrapper - delegates to Spring's RestClient.
 * <p>
 * Features:
 * - Generic REST operations (GET, POST, PUT, DELETE)
 * - IdM-specific user operations
 * - Error handling with OperationResult
 */
@Component
public class IdmRestConnector {

    private static final Logger log = LoggerFactory.getLogger(IdmRestConnector.class);

    private final RestClient restClient;

    public IdmRestConnector(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Generic GET request.
     *
     * @param url          the URL to call
     * @param responseType the expected response type
     * @param <T>          response type
     * @return operation result containing response
     */
    public <T> OperationResult<T> get(String url, Class<T> responseType) {
        log.debug("GET request to: {}", url);

        try {
            long startTime = System.currentTimeMillis();

            T response = restClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, responseEntity) -> {
                        throw new RuntimeException("Client error: " + responseEntity.getStatusCode());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, responseEntity) -> {
                        throw new RuntimeException("Server error: " + responseEntity.getStatusCode());
                    })
                    .body(responseType);

            long duration = System.currentTimeMillis() - startTime;

            log.debug("GET request completed in {}ms", duration);

            return OperationResult.<T>builder()
                    .success(true)
                    .data(response)
                    .message("Request successful")
                    .build();

        } catch (Exception e) {
            log.error("GET request failed for URL '{}': {}", url, e.getMessage());
            return OperationResult.<T>builder()
                    .success(false)
                    .message("Request failed: " + e.getMessage())
                    .errorType(determineErrorType(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Generic POST request.
     *
     * @param url          the URL to call
     * @param body         the request body
     * @param responseType the expected response type
     * @param <T>          response type
     * @return operation result containing response
     */
    public <T> OperationResult<T> post(String url, Object body, Class<T> responseType) {
        log.debug("POST request to: {}", url);

        try {
            long startTime = System.currentTimeMillis();

            T response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, responseEntity) -> {
                        throw new RuntimeException("Client error: " + responseEntity.getStatusCode());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, responseEntity) -> {
                        throw new RuntimeException("Server error: " + responseEntity.getStatusCode());
                    })
                    .body(responseType);

            long duration = System.currentTimeMillis() - startTime;

            log.debug("POST request completed in {}ms", duration);

            return OperationResult.<T>builder()
                    .success(true)
                    .data(response)
                    .message("Request successful")
                    .build();

        } catch (Exception e) {
            log.error("POST request failed for URL '{}': {}", url, e.getMessage());
            return OperationResult.<T>builder()
                    .success(false)
                    .message("Request failed: " + e.getMessage())
                    .errorType(determineErrorType(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Generic PUT request.
     *
     * @param url          the URL to call
     * @param body         the request body
     * @param responseType the expected response type
     * @param <T>          response type
     * @return operation result containing response
     */
    public <T> OperationResult<T> put(String url, Object body, Class<T> responseType) {
        log.debug("PUT request to: {}", url);

        try {
            long startTime = System.currentTimeMillis();

            T response = restClient.put()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, responseEntity) -> {
                        throw new RuntimeException("Client error: " + responseEntity.getStatusCode());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, responseEntity) -> {
                        throw new RuntimeException("Server error: " + responseEntity.getStatusCode());
                    })
                    .body(responseType);

            long duration = System.currentTimeMillis() - startTime;

            log.debug("PUT request completed in {}ms", duration);

            return OperationResult.<T>builder()
                    .success(true)
                    .data(response)
                    .message("Request successful")
                    .build();

        } catch (Exception e) {
            log.error("PUT request failed for URL '{}': {}", url, e.getMessage());
            return OperationResult.<T>builder()
                    .success(false)
                    .message("Request failed: " + e.getMessage())
                    .errorType(determineErrorType(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Generic DELETE request.
     *
     * @param url the URL to call
     * @return operation result indicating success or failure
     */
    public OperationResult<Void> delete(String url) {
        log.debug("DELETE request to: {}", url);

        try {
            long startTime = System.currentTimeMillis();

            restClient.delete()
                    .uri(url)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, responseEntity) -> {
                        throw new RuntimeException("Client error: " + responseEntity.getStatusCode());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, responseEntity) -> {
                        throw new RuntimeException("Server error: " + responseEntity.getStatusCode());
                    })
                    .toBodilessEntity();

            long duration = System.currentTimeMillis() - startTime;

            log.debug("DELETE request completed in {}ms", duration);

            return OperationResult.<Void>builder()
                    .success(true)
                    .message("Request successful")
                    .build();

        } catch (Exception e) {
            log.error("DELETE request failed for URL '{}': {}", url, e.getMessage());
            return OperationResult.<Void>builder()
                    .success(false)
                    .message("Request failed: " + e.getMessage())
                    .errorType(determineErrorType(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Creates a user in IdM via REST API.
     *
     * @param baseUrl the IdM base URL
     * @param user    the user to create
     * @return operation result containing created user
     */
    public OperationResult<IdmUser> createUser(String baseUrl, IdmUser user) {
        log.debug("Creating user in IdM: {}", user.getCn());

        String url = baseUrl + "/users";
        return post(url, user, IdmUser.class);
    }

    /**
     * Gets a user from IdM via REST API.
     *
     * @param baseUrl the IdM base URL
     * @param userId  the user ID
     * @return operation result containing user
     */
    public OperationResult<IdmUser> getUser(String baseUrl, String userId) {
        log.debug("Getting user from IdM: {}", userId);

        String url = baseUrl + "/users/" + userId;
        return get(url, IdmUser.class);
    }

    /**
     * Updates a user in IdM via REST API.
     *
     * @param baseUrl the IdM base URL
     * @param userId  the user ID
     * @param user    the user data to update
     * @return operation result containing updated user
     */
    public OperationResult<IdmUser> updateUser(String baseUrl, String userId, IdmUser user) {
        log.debug("Updating user in IdM: {}", userId);

        String url = baseUrl + "/users/" + userId;
        return put(url, user, IdmUser.class);
    }

    /**
     * Deletes a user from IdM via REST API.
     *
     * @param baseUrl the IdM base URL
     * @param userId  the user ID
     * @return operation result indicating success or failure
     */
    public OperationResult<Void> deleteUser(String baseUrl, String userId) {
        log.debug("Deleting user from IdM: {}", userId);

        String url = baseUrl + "/users/" + userId;
        return delete(url);
    }

    /**
     * Determines ErrorType based on exception.
     *
     * @param e the exception
     * @return the error type
     */
    private ErrorType determineErrorType(Exception e) {
        String message = e.getMessage();

        if (message != null) {
            if (message.contains("404") || message.contains("Not Found")) {
                return ErrorType.NOT_FOUND;
            } else if (message.contains("401") || message.contains("403") || message.contains("Unauthorized")) {
                return ErrorType.AUTHENTICATION_ERROR;
            } else if (message.contains("400") || message.contains("Bad Request")) {
                return ErrorType.VALIDATION_ERROR;
            } else if (message.contains("500") || message.contains("Server error")) {
                return ErrorType.SERVER_ERROR;
            } else if (message.contains("timeout") || message.contains("timed out")) {
                return ErrorType.TIMEOUT;
            } else if (message.contains("Connection") || message.contains("Network")) {
                return ErrorType.NETWORK_ERROR;
            }
        }

        return ErrorType.UNKNOWN_ERROR;
    }
}
