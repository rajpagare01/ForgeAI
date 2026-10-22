package com.forgeai.identity.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import com.forgeai.identity.domain.exception.*;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({UserAlreadyExistsException.class, org.springframework.dao.DataIntegrityViolationException.class})
    public ResponseEntity<ApiErrorResponse> handleUserAlreadyExists(Exception ex, WebRequest request) {
        String message = ex instanceof org.springframework.dao.DataIntegrityViolationException ? "A record with this identifier already exists" : ex.getMessage();
        return buildErrorResponse(HttpStatus.CONFLICT, "CONFLICT", message, request);
    }

    @ExceptionHandler({UserNotFoundException.class, org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(Exception ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler({InvalidCredentialsException.class, CompromisedTokenException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(DomainException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage(), request);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountLocked(AccountLockedException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.LOCKED, "ACCOUNT_LOCKED", ex.getMessage(), request);
    }

    @ExceptionHandler({InvalidUserStateException.class, AccessDeniedException.class, OrganizationAccessDeniedException.class, org.springframework.security.access.AccessDeniedException.class})
    public ResponseEntity<ApiErrorResponse> handleForbidden(Exception ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), request);
    }


    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, WebRequest request) {
        String message = ex instanceof MethodArgumentNotValidException ? "Validation failed" : 
                         ex instanceof org.springframework.http.converter.HttpMessageNotReadableException ? "Malformed request body" : ex.getMessage();
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleAllExceptions(Exception ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
    }

    private ResponseEntity<ApiErrorResponse> buildErrorResponse(HttpStatus status, String code, String message, WebRequest request) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        String traceId = org.slf4j.MDC.get("traceId");
        String correlationId = org.slf4j.MDC.get("correlationId");
        ApiErrorResponse error = new ApiErrorResponse(Instant.now(), status.value(), code, message, path, traceId, correlationId);
        return new ResponseEntity<>(error, status);
    }
}
