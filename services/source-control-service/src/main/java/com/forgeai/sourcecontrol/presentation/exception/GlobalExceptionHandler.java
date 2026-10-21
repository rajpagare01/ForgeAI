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
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Unauthorized Webhook");
        problem.setType(URI.create("https://forgeai.com/errors/unauthorized-webhook"));
        return enrichProblem(problem, "WEBHOOK_UNAUTHORIZED");
    }

    @ExceptionHandler({ResourceNotFoundException.class, org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public ProblemDetail handleResourceNotFound(Exception ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        problem.setType(URI.create("https://forgeai.com/errors/not-found"));
        return enrichProblem(problem, "NOT_FOUND");
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicateResource(DuplicateResourceException ex) {
        log.warn("Duplicate resource conflict: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        problem.setType(URI.create("https://forgeai.com/errors/conflict"));
        return enrichProblem(problem, "RESOURCE_CONFLICT");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://forgeai.com/errors/bad-request"));
        return enrichProblem(problem, "BAD_REQUEST");
    }

    @ExceptionHandler(GitHubAppNotConfiguredException.class)
    public ProblemDetail handleGitHubNotConfigured(GitHubAppNotConfiguredException ex) {
        log.warn("GitHub App not configured: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("GitHub Integration Not Configured");
        problem.setType(URI.create("https://forgeai.com/errors/github-not-configured"));
        problem.setProperty("resolution", "Set GITHUB_APP_ID, GITHUB_PRIVATE_KEY, and GITHUB_WEBHOOK_SECRET environment variables.");
        return enrichProblem(problem, "GITHUB_NOT_CONFIGURED");
    }

    @ExceptionHandler(GitHubAccessDeniedException.class)
    public ProblemDetail handleGitHubAccessDenied(GitHubAccessDeniedException ex) {
        log.warn("GitHub access denied: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("GitHub Access Denied");
        problem.setType(URI.create("https://forgeai.com/errors/github-access-denied"));
        return enrichProblem(problem, "GITHUB_ACCESS_DENIED");
    }

    @ExceptionHandler(GitHubApiException.class)
    public ProblemDetail handleGitHubApiError(GitHubApiException ex) {
        log.error("GitHub API error: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("GitHub API Error");
        problem.setType(URI.create("https://forgeai.com/errors/github-api-error"));
        return enrichProblem(problem, "GITHUB_API_ERROR");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Internal server error: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("https://forgeai.com/errors/internal-server-error"));
        return enrichProblem(problem, "INTERNAL_SERVER_ERROR");
    }
}
