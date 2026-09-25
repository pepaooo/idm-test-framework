package com.idm.test.connector.rest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idm.test.connector.rest.model.IdmUser;
import com.idm.test.connector.rest.model.RestResponse;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private static final MediaType DEFAULT_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
     * Performs one HTTP request and reports exactly what came back.
     * <p>
     * Unlike {@link #get}, {@link #post}, {@link #put} and {@link #delete}, this never treats
     * an HTTP error status as a failure: a 404 or a 500 returns a successful
     * {@link OperationResult} whose {@link RestResponse#status()} carries the code. That is
     * the point — a scenario asserting "the account is gone, so this returns 404" cannot be
     * written against a method that turns 404 into an exception. Only a transport-level
     * problem (connection refused, timeout, DNS failure) is an operation failure here.
     *
     * @param method  GET, POST, PUT, PATCH, DELETE or HEAD, case-insensitive
     * @param url     the absolute URL
     * @param body    request body, serialized as JSON, or null for none
     * @param headers extra request headers, or null for none
     * @return the response, or a failure result; never throws
     */
    public OperationResult<RestResponse> exchange(
            String method, String url, Object body, Map<String, String> headers) {

        log.debug("{} request to: {}", method, url);

        HttpMethod httpMethod;
        try {
            httpMethod = HttpMethod.valueOf(method.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OperationResult.<RestResponse>builder()
                    .success(false)
                    .message("Unsupported HTTP method: " + method)
                    .errorType(ErrorType.VALIDATION_ERROR)
                    .throwable(e)
                    .build();
        }

        try {
            RestClient.RequestBodySpec spec = restClient
                    .method(httpMethod)
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON);

            if (headers != null) {
                headers.forEach(spec::header);
            }

            RestClient.RequestHeadersSpec<?> requestSpec;
            if (body != null) {
                requestSpec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            } else {
                requestSpec = spec;
            }

            RestClient.ResponseSpec responseSpec = requestSpec
                    .retrieve()
                    // Suppress the default error handling entirely: every status, including
                    // 4xx and 5xx, must reach us as data rather than as an exception.
                    .onStatus(status -> true, (request, response) -> { });

            ResponseEntity<String> entity = responseSpec.toEntity(String.class);

            Map<String, List<String>> plainHeaders = new LinkedHashMap<>();
            entity.getHeaders().forEach(plainHeaders::put);

            return OperationResult.<RestResponse>builder()
                    .success(true)
                    .data(new RestResponse(
                            entity.getStatusCode().value(),
                            plainHeaders,
                            parseBody(entity)))
                    .message(httpMethod + " " + url + " returned " + entity.getStatusCode().value())
                    .build();

        } catch (Exception e) {
            log.error("{} request failed for URL '{}': {}", method, url, e.getMessage());
            return OperationResult.<RestResponse>builder()
                    .success(false)
                    .message("Request failed: " + e.getMessage())
                    .errorType(determineErrorType(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Performs one HTTP request with a raw text body and returns the raw text response.
     * <p>
     * The counterpart of {@link #exchange} for callers that write the body themselves — XML,
     * SOAP envelopes, JSON as text. Nothing is serialized or parsed: the body is sent as bytes in
     * the charset its Content-Type names (UTF-8 by default), and the response is decoded with the
     * charset it declares (UTF-8 by default). As with exchange, every HTTP status is data; only a
     * transport-level problem is an operation failure.
     *
     * @param method  the HTTP method
     * @param url     the absolute URL
     * @param body    the request body, sent as-is, or null for none
     * @param headers request headers; Content-Type defaults to text/plain; charset=UTF-8 when there
     *                is a body, and Accept to *&#47;* when none is given
     * @return the response, or a failure result; never throws
     */
    public OperationResult<TextResponse> exchangeText(
            String method, String url, String body, Map<String, String> headers) {

        log.debug("{} request to: {}", method, url);

        try {
            HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase(Locale.ROOT));
            RestClient.RequestBodySpec spec = restClient.method(httpMethod).uri(url);

            String contentType = null;
            boolean acceptGiven = false;
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey().equalsIgnoreCase(HttpHeaders.CONTENT_TYPE)) {
                        contentType = header.getValue();
                    } else {
                        acceptGiven |= header.getKey().equalsIgnoreCase(HttpHeaders.ACCEPT);
                        spec.header(header.getKey(), header.getValue());
                    }
                }
            }
            if (!acceptGiven) {
                spec.accept(MediaType.ALL);
            }

            RestClient.RequestHeadersSpec<?> requestSpec = spec;
            if (body != null) {
                MediaType mediaType = contentType != null ? MediaType.parseMediaType(contentType) : DEFAULT_TEXT;
                Charset charset = mediaType.getCharset() != null ? mediaType.getCharset() : StandardCharsets.UTF_8;
                requestSpec = spec.contentType(mediaType).body(body.getBytes(charset));
            } else if (contentType != null) {
                spec.header(HttpHeaders.CONTENT_TYPE, contentType);
            }

            ResponseEntity<byte[]> entity = requestSpec
                    .retrieve()
                    // Every status, including 4xx and 5xx, must reach us as data.
                    .onStatus(status -> true, (request, response) -> { })
                    .toEntity(byte[].class);

            // HTTP header names are case-insensitive; one lower-case spelling lets a scenario
            // read them without guessing how the server capitalised them.
            Map<String, List<String>> lowerCased = new LinkedHashMap<>();
            entity.getHeaders().forEach((name, values) -> lowerCased
                    .computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                    .addAll(values));

            MediaType responseType = entity.getHeaders().getContentType();
            Charset responseCharset = responseType != null && responseType.getCharset() != null
                    ? responseType.getCharset()
                    : StandardCharsets.UTF_8;
            byte[] bytes = entity.getBody();
            String text = bytes == null ? "" : new String(bytes, responseCharset);

            return OperationResult.<TextResponse>builder()
                    .success(true)
                    .data(new TextResponse(entity.getStatusCode().value(), lowerCased, text))
                    .message(httpMethod + " " + url + " returned " + entity.getStatusCode().value())
                    .build();

        } catch (Exception e) {
            log.error("{} request failed for URL '{}': {}", method, url, e.getMessage());
            return OperationResult.<TextResponse>builder()
                    .success(false)
                    .message("Request failed: " + e.getMessage())
                    .errorType(determineErrorType(e))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * JSON becomes Maps, Lists and scalars so a scenario can walk it with
     * {@code ${created.body.id}}. Anything else is returned as its raw text rather than
     * guessed at, and an empty body is null rather than an empty string.
     */
    private Object parseBody(ResponseEntity<String> entity) {
        String text = entity.getBody();
        if (text == null || text.isBlank()) {
            return null;
        }

        MediaType contentType = entity.getHeaders().getContentType();
        boolean json = contentType != null
                && (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                    || contentType.getSubtype().endsWith("+json"));
        if (!json) {
            return text;
        }

        try {
            return objectMapper.readValue(text, Object.class);
        } catch (Exception e) {
            // The server said JSON and sent something else. Returning the raw text loses
            // nothing and lets the scenario assert on it; throwing here would turn a server
            // bug into an unrelated-looking connector failure.
            log.warn("Response claimed JSON but could not be parsed, returning raw text: {}",
                    e.getMessage());
            return text;
        }
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
