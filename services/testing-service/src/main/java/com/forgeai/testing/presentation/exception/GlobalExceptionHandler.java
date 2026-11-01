package com.forgeai.testing.presentation.exception;

import com.forgeai.testing.domain.exception.DuplicateResourceException;
import com.forgeai.testing.domain.exception.InvalidTestRunStateException;
import com.forgeai.testing.domain.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private ProblemDetail enrichProblem(ProblemDetail problem, String errorCode) {
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("message", problem.getDetail());
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            problem.setProperty("traceId", traceId);
        }
        String correlationId = MDC.get("correlationId");
        if (correlationId != null && !correlationId.isBlank()) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }

    @ExceptionHandler({ResourceNotFoundException.class, org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public ProblemDetail handleResourceNotFound(Exception ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                sanitizeClientMessage(ex.getMessage(), "The requested test run or test suite could not be found."));
        problem.setTitle("Resource Not Found");
        problem.setType(URI.create("https://forgeai.com/errors/not-found"));
        return enrichProblem(problem, "NOT_FOUND");
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicateResource(DuplicateResourceException ex) {
        log.warn("Duplicate resource conflict: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                sanitizeClientMessage(ex.getMessage(), "A test execution record with this identifier already exists."));
        problem.setTitle("Conflict");
        problem.setType(URI.create("https://forgeai.com/errors/conflict"));
        return enrichProblem(problem, "RESOURCE_CONFLICT");
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ProblemDetail handleDataAccessException(org.springframework.dao.DataAccessException ex) {
        log.error("Database access error in testing-service", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unable to save test data due to a database issue. Please try again.");
        problem.setTitle("Database Error");
        problem.setType(URI.create("https://forgeai.com/errors/database-error"));
        return enrichProblem(problem, "DATABASE_ERROR");
    }

    @ExceptionHandler(InvalidTestRunStateException.class)
    public ProblemDetail handleInvalidState(InvalidTestRunStateException ex) {
        log.warn("Invalid test run state: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                sanitizeClientMessage(ex.getMessage(), "The test run is not in a valid state for this action."));
        problem.setTitle("Invalid State");
        problem.setType(URI.create("https://forgeai.com/errors/invalid-state"));
        return enrichProblem(problem, "INVALID_STATE");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                sanitizeClientMessage(ex.getMessage(), "Invalid test parameters provided."));
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://forgeai.com/errors/bad-request"));
        return enrichProblem(problem, "BAD_REQUEST");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed: please check submitted test data.");
        problem.setTitle("Validation Error");
        problem.setType(URI.create("https://forgeai.com/errors/validation-error"));
        return enrichProblem(problem, "VALIDATION_ERROR");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Internal server error in testing-service: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "We couldn't complete the test operation right now. Please try again in a moment.");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("https://forgeai.com/errors/internal-server-error"));
        return enrichProblem(problem, "INTERNAL_SERVER_ERROR");
    }

    private String sanitizeClientMessage(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        if (raw.matches(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*")
                || raw.contains("Exception") || raw.contains("org.springframework") || raw.contains("SQL")
                || raw.contains("org.xml") || raw.contains("Sax") || raw.length() > 200) {
            return fallback;
        }
        return raw.trim();
    }
}
