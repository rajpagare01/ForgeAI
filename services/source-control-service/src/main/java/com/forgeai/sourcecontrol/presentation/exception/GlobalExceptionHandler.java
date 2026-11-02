package com.forgeai.sourcecontrol.presentation.exception;

import com.forgeai.sourcecontrol.domain.exception.DuplicateResourceException;
import com.forgeai.sourcecontrol.domain.exception.InvalidWebhookSignatureException;
import com.forgeai.sourcecontrol.domain.exception.ResourceNotFoundException;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubApiException;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppNotConfiguredException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private String sanitizeClientMessage(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        if (raw.matches(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*")
                || raw.contains("Exception") || raw.contains("constraint") || raw.contains("SQL") || raw.contains("chk_")) {
            return fallback;
        }
        return raw;
    }

    private ProblemDetail enrichProblem(ProblemDetail problem, String errorCode) {
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("errorCode", errorCode);
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

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ProblemDetail handleInvalidWebhookSignature(InvalidWebhookSignatureException ex) {
        log.warn("Invalid webhook signature: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid webhook signature.");
        problem.setTitle("Unauthorized Webhook");
        problem.setType(URI.create("https://forgeai.com/errors/unauthorized-webhook"));
        problem.setProperty("message", "Invalid webhook signature.");
        return enrichProblem(problem, "WEBHOOK_UNAUTHORIZED");
    }

    @ExceptionHandler({ResourceNotFoundException.class, org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public ProblemDetail handleResourceNotFound(Exception ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        String safeDetail = sanitizeClientMessage(ex.getMessage(), "The requested repository or resource could not be found.");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, safeDetail);
        problem.setTitle("Resource Not Found");
        problem.setType(URI.create("https://forgeai.com/errors/not-found"));
        problem.setProperty("message", safeDetail);
        return enrichProblem(problem, "NOT_FOUND");
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicateResource(DuplicateResourceException ex) {
        log.warn("Duplicate resource conflict: {}", ex.getMessage());
        String safeDetail = sanitizeClientMessage(ex.getMessage(), "This repository or resource is already registered.");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, safeDetail);
        problem.setTitle("Conflict");
        problem.setType(URI.create("https://forgeai.com/errors/conflict"));
        problem.setProperty("message", safeDetail);
        return enrichProblem(problem, "RESOURCE_CONFLICT");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ProblemDetail handleIllegalArgument(Exception ex) {
        log.warn("Illegal argument or state: {}", ex.getMessage());
        String safeDetail = sanitizeClientMessage(ex.getMessage(), "Some of the provided parameters were invalid. Please review your input.");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, safeDetail);
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://forgeai.com/errors/bad-request"));
        problem.setProperty("message", safeDetail);
        return enrichProblem(problem, "BAD_REQUEST");
    }

    @ExceptionHandler(GitHubAppNotConfiguredException.class)
    public ProblemDetail handleGitHubNotConfigured(GitHubAppNotConfiguredException ex) {
        log.warn("GitHub App not configured: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "GitHub App integration is not configured on this server.");
        problem.setTitle("GitHub Integration Not Configured");
        problem.setType(URI.create("https://forgeai.com/errors/github-not-configured"));
        problem.setProperty("message", "GitHub App integration is not configured on this server.");
        problem.setProperty("resolution", "Set GITHUB_APP_ID, GITHUB_PRIVATE_KEY, and GITHUB_WEBHOOK_SECRET environment variables.");
        return enrichProblem(problem, "GITHUB_NOT_CONFIGURED");
    }

    @ExceptionHandler(GitHubAccessDeniedException.class)
    public ProblemDetail handleGitHubAccessDenied(GitHubAccessDeniedException ex) {
        log.warn("GitHub access denied: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access to the requested GitHub repository was denied.");
        problem.setTitle("GitHub Access Denied");
        problem.setType(URI.create("https://forgeai.com/errors/github-access-denied"));
        problem.setProperty("message", "Access to the requested GitHub repository was denied.");
        return enrichProblem(problem, "GITHUB_ACCESS_DENIED");
    }

    @ExceptionHandler(GitHubApiException.class)
    public ProblemDetail handleGitHubApiError(GitHubApiException ex) {
        log.warn("GitHub API error: {}", ex.getMessage());
        String safeDetail = "Unable to communicate with GitHub right now. Please verify your GitHub connection and try again.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, safeDetail);
        problem.setTitle("GitHub API Error");
        problem.setType(URI.create("https://forgeai.com/errors/github-api-error"));
        problem.setProperty("message", safeDetail);
        return enrichProblem(problem, "GITHUB_API_ERROR");
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ProblemDetail handleDataAccessException(org.springframework.dao.DataAccessException ex) {
        log.error("Database constraint or access error in source-control-service: {}", ex.getMessage(), ex);
        String safeDetail = "Unable to save changes due to a workspace or repository conflict. Please refresh and try again.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, safeDetail);
        problem.setTitle("Database Conflict");
        problem.setType(URI.create("https://forgeai.com/errors/conflict"));
        problem.setProperty("message", safeDetail);
        return enrichProblem(problem, "DATABASE_CONFLICT");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Unhandled exception in source-control-service", ex);
        String safeDetail = "We couldn't complete that request right now. Please try again in a moment.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, safeDetail);
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("https://forgeai.com/errors/internal-server-error"));
        problem.setProperty("message", safeDetail);
        return enrichProblem(problem, "INTERNAL_SERVER_ERROR");
    }
}
