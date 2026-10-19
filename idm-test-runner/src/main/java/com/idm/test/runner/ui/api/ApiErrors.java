package com.idm.test.runner.ui.api;

import com.idm.test.engine.spi.Message;
import com.idm.test.runner.ui.UiSession;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;

/**
 * Every failure of this package's controllers as {@link ApiError.Body} JSON, in the request's
 * language: {@link ApiError}s with their status, an unreadable body or missing parameter as 400
 * {@code ui.request.invalid}, anything else as 500 {@code ui.unexpected} (naming only the
 * exception's class; the details go to the log).
 */
@RestControllerAdvice(basePackageClasses = ApiErrors.class)
class ApiErrors {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    private final UiSession session;

    ApiErrors(UiSession session) {
        this.session = session;
    }

    @ExceptionHandler(ApiError.class)
    ResponseEntity<ApiError.Body> api(ApiError error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).body(error.body(locale(request)));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError.Body> unreadable(Exception e, HttpServletRequest request) {
        String reason = e instanceof MissingServletRequestParameterException missing
                ? missing.getParameterName() : e.getClass().getSimpleName();
        return ResponseEntity.badRequest()
                .body(ApiError.Body.of(Message.of("runner.ui.request.invalid", reason), locale(request)));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ApiError.Body> unexpected(RuntimeException e, HttpServletRequest request) {
        LOG.warn("Workbench request {} {} failed", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.Body.of(Message.of("runner.ui.unexpected", e.getClass().getSimpleName()),
                        locale(request)));
    }

    private Locale locale(HttpServletRequest request) {
        return ApiError.locale(request.getHeader("Accept-Language"), session.locale());
    }
}
