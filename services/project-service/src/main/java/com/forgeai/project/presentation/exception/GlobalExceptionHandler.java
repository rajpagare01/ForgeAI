package com.forgeai.project.presentation.exception;

import com.forgeai.project.domain.exception.DomainException;
import com.forgeai.project.domain.exception.DuplicateResourceException;
import com.forgeai.project.domain.exception.InvalidTaskAssignmentException;
import com.forgeai.project.domain.exception.InvalidTaskStatusTransitionException;
import com.forgeai.project.domain.exception.ResourceNotFoundException;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({ResourceNotFoundException.class, org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, sanitizeClientMessage(ex.getMessage(), "The requested project, requirement, or task could not be found."));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateResourceException ex) {
        log.warn("Duplicate resource: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, sanitizeClientMessage(ex.getMessage(), "An item with this identifier already exists in this project."));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccessException(org.springframework.dao.DataAccessException ex) {
        log.error("Database access exception in project-service", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to save changes due to a project data conflict. Please refresh and try again.");
    }

    @ExceptionHandler(InvalidTaskAssignmentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidAssignment(InvalidTaskAssignmentException ex) {
        log.warn("Invalid task assignment: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.UNPROCESSABLE_ENTITY, sanitizeClientMessage(ex.getMessage(), "The task could not be assigned to the specified user."));
    }

    @ExceptionHandler(InvalidTaskStatusTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidStatus(InvalidTaskStatusTransitionException ex) {
        log.warn("Invalid status transition: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, sanitizeClientMessage(ex.getMessage(), "Invalid task status transition."));
    }

    @ExceptionHandler({OptimisticLockException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(Exception ex) {
        log.warn("Optimistic locking conflict detected: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Resource has been modified concurrently by another transaction. Please retry.");
    }

    @ExceptionHandler(com.forgeai.project.domain.exception.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(com.forgeai.project.domain.exception.AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "You do not have permission to access or modify this project resource.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, sanitizeClientMessage(ex.getMessage(), "Invalid request parameters provided."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Bad Request");
        body.put("message", "Validation failed: please check your submitted fields.");
        body.put("fieldErrors", fieldErrors);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unhandled internal server error: ", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "We couldn't complete the project operation right now. Please try again in a moment.");
    }

    private String sanitizeClientMessage(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        if (raw.matches(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*")
                || raw.contains("Exception") || raw.contains("org.springframework") || raw.contains("SQL") || raw.length() > 200) {
            return fallback;
        }
        return raw.trim();
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        String traceId = org.slf4j.MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            body.put("traceId", traceId);
        }
        String correlationId = org.slf4j.MDC.get("correlationId");
        if (correlationId != null && !correlationId.isBlank()) {
            body.put("correlationId", correlationId);
        }
        return ResponseEntity.status(status).body(body);
    }
}
