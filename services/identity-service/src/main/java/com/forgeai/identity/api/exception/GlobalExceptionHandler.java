package com.forgeai.identity.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import com.forgeai.identity.domain.exception.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({UserAlreadyExistsException.class, org.springframework.dao.DataIntegrityViolationException.class})
    public ResponseEntity<ApiErrorResponse> handleUserAlreadyExists(Exception ex, WebRequest request) {
        log.warn("Identity conflict / integrity violation: {}", ex.getMessage());
        String msg = (ex instanceof org.springframework.dao.DataIntegrityViolationException)
                ? "A record with this identifier already exists"
                : "An account or workspace with this identifier already exists.";
        return buildErrorResponse(HttpStatus.CONFLICT, "CONFLICT", msg, request);
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDataAccessException(org.springframework.dao.DataAccessException ex, WebRequest request) {
        log.error("Database access error in identity-service", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR",
                "Unable to complete identity request due to a database issue. Please try again in a moment.", request);
    }

    @ExceptionHandler({UserNotFoundException.class, org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(Exception ex, WebRequest request) {
        log.warn("Resource not found in identity-service: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested user, workspace, or identity resource could not be found.", request);
    }

    @ExceptionHandler({InvalidCredentialsException.class, CompromisedTokenException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(DomainException ex, WebRequest request) {
        log.warn("Authentication failure in identity-service: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid credentials or expired session. Please sign in again.", request);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountLocked(AccountLockedException ex, WebRequest request) {
        log.warn("Account locked: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "This account has been temporarily locked for security. Please contact your workspace administrator.", request);
    }

    @ExceptionHandler({InvalidUserStateException.class, AccessDeniedException.class, OrganizationAccessDeniedException.class, org.springframework.security.access.AccessDeniedException.class})
    public ResponseEntity<ApiErrorResponse> handleForbidden(Exception ex, WebRequest request) {
        log.warn("Access denied in identity-service: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action in this workspace.", request);
    }


    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, MethodArgumentNotValidException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, WebRequest request) {
        log.warn("Bad request in identity-service: {}", ex.getMessage());
        String message = ex instanceof MethodArgumentNotValidException ? "Validation failed: please check your submitted fields." : 
                         ex instanceof org.springframework.http.converter.HttpMessageNotReadableException ? "Malformed request payload." :
                         sanitizeClientMessage(ex.getMessage(), "The provided request parameters were invalid.");
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleAllExceptions(Exception ex, WebRequest request) {
        log.error("Unhandled exception in identity-service", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "We couldn't complete your sign-in or account request right now. Please try again.", request);
    }

    private String sanitizeClientMessage(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        if (raw.matches(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*")
                || raw.contains("Exception") || raw.contains("org.springframework") || raw.contains("SQL") || raw.length() > 200) {
            return fallback;
        }
        return raw.trim();
    }

    private ResponseEntity<ApiErrorResponse> buildErrorResponse(HttpStatus status, String code, String message, WebRequest request) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        String traceId = org.slf4j.MDC.get("traceId");
        String correlationId = org.slf4j.MDC.get("correlationId");
        ApiErrorResponse error = new ApiErrorResponse(Instant.now(), status.value(), code, message, path, traceId, correlationId);
        return new ResponseEntity<>(error, status);
    }
}
