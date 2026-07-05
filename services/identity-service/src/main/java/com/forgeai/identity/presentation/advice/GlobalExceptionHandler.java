package com.forgeai.identity.presentation.advice;

import com.forgeai.identity.application.exception.ApplicationException;
import com.forgeai.identity.application.exception.AuthenticationFailedException;
import com.forgeai.identity.application.exception.RegistrationFailedException;
import com.forgeai.identity.application.exception.SessionOperationException;
import com.forgeai.identity.domain.exception.*;
import com.forgeai.identity.presentation.factory.ProblemDetailFactory;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for the Presentation Layer.
 * Maps Application and Domain Layer exceptions to RFC 7807 ProblemDetail responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ProblemDetailFactory problemDetailFactory;

    public GlobalExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
    }

    // --- Domain Exceptions ---

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException ex) {
        return createResponse(HttpStatus.UNAUTHORIZED, "Invalid Credentials", ex.getMessage(), "INVALID_CREDENTIALS");
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleUserNotFound(UserNotFoundException ex) {
        return createResponse(HttpStatus.NOT_FOUND, "User Not Found", ex.getMessage(), "USER_NOT_FOUND");
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleUserAlreadyExists(UserAlreadyExistsException ex) {
        return createResponse(HttpStatus.CONFLICT, "User Already Exists", ex.getMessage(), "USER_ALREADY_EXISTS");
    }

    @ExceptionHandler({PasswordPolicyException.class, InvalidPasswordException.class})
    public ResponseEntity<ProblemDetail> handlePasswordPolicy(DomainException ex) {
        return createResponse(HttpStatus.BAD_REQUEST, "Password Policy Violation", ex.getMessage(), "PASSWORD_POLICY_VIOLATION");
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ProblemDetail> handleEmailNotVerified(EmailNotVerifiedException ex) {
        return createResponse(HttpStatus.FORBIDDEN, "Email Not Verified", ex.getMessage(), "EMAIL_NOT_VERIFIED");
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ProblemDetail> handleAccountLocked(AccountLockedException ex) {
        return createResponse(HttpStatus.FORBIDDEN, "Account Locked", ex.getMessage(), "ACCOUNT_LOCKED");
    }

    @ExceptionHandler(AccountSuspendedException.class)
    public ResponseEntity<ProblemDetail> handleAccountSuspended(AccountSuspendedException ex) {
        return createResponse(HttpStatus.FORBIDDEN, "Account Suspended", ex.getMessage(), "ACCOUNT_SUSPENDED");
    }

    @ExceptionHandler(SessionExpiredException.class)
    public ResponseEntity<ProblemDetail> handleSessionExpired(SessionExpiredException ex) {
        return createResponse(HttpStatus.UNAUTHORIZED, "Session Expired", ex.getMessage(), "SESSION_EXPIRED");
    }

    @ExceptionHandler(SessionRevokedException.class)
    public ResponseEntity<ProblemDetail> handleSessionRevoked(SessionRevokedException ex) {
        return createResponse(HttpStatus.UNAUTHORIZED, "Session Revoked", ex.getMessage(), "SESSION_REVOKED");
    }
    
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomainException(DomainException ex) {
        return createResponse(HttpStatus.BAD_REQUEST, "Domain Error", ex.getMessage(), "DOMAIN_ERROR");
    }

    // --- Application Exceptions ---

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailed(AuthenticationFailedException ex) {
        return createResponse(HttpStatus.UNAUTHORIZED, "Authentication Failed", ex.getMessage(), "AUTH_FAILED");
    }

    @ExceptionHandler(RegistrationFailedException.class)
    public ResponseEntity<ProblemDetail> handleRegistrationFailed(RegistrationFailedException ex) {
        return createResponse(HttpStatus.CONFLICT, "Registration Failed", ex.getMessage(), "REGISTRATION_FAILED");
    }

    @ExceptionHandler(SessionOperationException.class)
    public ResponseEntity<ProblemDetail> handleSessionOperation(SessionOperationException ex) {
        return createResponse(HttpStatus.BAD_REQUEST, "Session Operation Failed", ex.getMessage(), "SESSION_OPERATION_FAILED");
    }

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ProblemDetail> handleApplicationException(ApplicationException ex) {
        return createResponse(HttpStatus.BAD_REQUEST, "Application Error", ex.getMessage(), "APP_ERROR");
    }

    // --- Validation & Spring Exceptions ---

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> 
            errors.put(error.getField(), error.getDefaultMessage()));
            
        ProblemDetail problem = problemDetailFactory.createValidationProblem("Invalid request body format", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getConstraintViolations().forEach(violation -> 
            errors.put(violation.getPropertyPath().toString(), violation.getMessage()));
            
        ProblemDetail problem = problemDetailFactory.createValidationProblem("Constraint violation", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        return createResponse(HttpStatus.BAD_REQUEST, "Malformed JSON Request", "The request body could not be parsed.", "MALFORMED_JSON");
    }
    
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingRequestHeader(MissingRequestHeaderException ex) {
        return createResponse(HttpStatus.BAD_REQUEST, "Missing Header", ex.getMessage(), "MISSING_HEADER");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = String.format("Parameter '%s' should be of type '%s'", ex.getName(), ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "Unknown");
        return createResponse(HttpStatus.BAD_REQUEST, "Type Mismatch", detail, "TYPE_MISMATCH");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex) {
        return createResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred.", "INTERNAL_ERROR");
    }

    private ResponseEntity<ProblemDetail> createResponse(HttpStatus status, String title, String detail, String errorCode) {
        ProblemDetail problem = problemDetailFactory.create(status, title, detail, URI.create("about:blank"), errorCode);
        return ResponseEntity.status(status).body(problem);
    }
}
