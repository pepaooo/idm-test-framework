package com.idm.test.runner.ui.api;

import com.idm.test.engine.spi.Message;
import com.idm.test.runner.ui.UiSession;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Locale;

/**
 * Every failure of the workbench's controllers as {@link ApiError.Body} JSON, in the request's
 * language: {@link ApiError}s with their status; Spring MVC's own client errors (unreadable body,
 * missing or mistyped parameter, 405, 415, …) with their status and {@code ui.request.invalid}
 * (404: {@code ui.request.notFound}); anything else as 500 {@code ui.unexpected}, naming only the
 * exception's class (the details go to the log).
 */
@RestControllerAdvice
class ApiErrors extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    private final UiSession session;

    ApiErrors(UiSession session) {
        this.session = session;
    }

    @ExceptionHandler(ApiError.class)
    ResponseEntity<ApiError.Body> api(ApiError error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_JSON)
                .body(error.body(locale(request.getHeader("Accept-Language"))));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ApiError.Body> unexpected(RuntimeException e, HttpServletRequest request) {
        LOG.warn("Workbench request {} {} failed", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.Body.of(Message.of("runner.ui.unexpected", e.getClass().getSimpleName()),
                        locale(request.getHeader("Accept-Language"))));
    }

    /** Spring MVC's client errors keep their status and headers (e.g. {@code Allow}) with our body. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        Locale locale = locale(request.getHeader("Accept-Language"));
        Message message;
        if (statusCode.value() == HttpStatus.NOT_FOUND.value()) {
            message = Message.of("runner.ui.request.notFound");
        } else {
            message = Message.of("runner.ui.request.invalid", reason(ex, statusCode));
        }
        HttpHeaders out = new HttpHeaders();
        out.putAll(headers);
        out.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(statusCode).headers(out).body(ApiError.Body.of(message, locale));
    }

    /** A multipart request over its total limit (each file's own limit is the files API's). */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                         HttpHeaders headers, HttpStatusCode status,
                                                                         WebRequest request) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.Body.of(Message.of("runner.ui.file.uploadTooLarge"),
                        locale(request.getHeader("Accept-Language"))));
    }

    private static String reason(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof MissingServletRequestParameterException missing) {
            return missing.getParameterName();
        }
        HttpStatus known = HttpStatus.resolve(statusCode.value());
        return known != null ? known.value() + " " + known.getReasonPhrase() : String.valueOf(statusCode.value());
    }

    private Locale locale(String acceptLanguage) {
        return ApiError.locale(acceptLanguage, session.locale());
    }
}
