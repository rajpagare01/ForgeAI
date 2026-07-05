package com.forgeai.identity.presentation.controller;

import com.forgeai.identity.application.command.*;
import com.forgeai.identity.application.dto.AuthenticationResultDto;
import com.forgeai.identity.application.dto.TokenPairDto;
import com.forgeai.identity.application.usecase.*;
import com.forgeai.identity.presentation.factory.ResponseEntityFactory;
import com.forgeai.identity.presentation.mapper.AuthPresentationMapper;
import com.forgeai.identity.presentation.request.*;
import com.forgeai.identity.presentation.response.AuthenticationResponse;
import com.forgeai.identity.presentation.response.SuccessMessageResponse;
import com.forgeai.identity.presentation.response.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Endpoints for user registration and authentication")
public class AuthController {

    private final RegisterUserUseCase registerUserUseCase;
    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;
    private final VerifyEmailUseCase verifyEmailUseCase;
    private final ForgotPasswordUseCase forgotPasswordUseCase;
    private final ResetPasswordUseCase resetPasswordUseCase;
    private final AuthPresentationMapper mapper;

    public AuthController(
            RegisterUserUseCase registerUserUseCase,
            LoginUseCase loginUseCase,
            RefreshTokenUseCase refreshTokenUseCase,
            LogoutUseCase logoutUseCase,
            VerifyEmailUseCase verifyEmailUseCase,
            ForgotPasswordUseCase forgotPasswordUseCase,
            ResetPasswordUseCase resetPasswordUseCase,
            AuthPresentationMapper mapper) {
        this.registerUserUseCase = registerUserUseCase;
        this.loginUseCase = loginUseCase;
        this.refreshTokenUseCase = refreshTokenUseCase;
        this.logoutUseCase = logoutUseCase;
        this.verifyEmailUseCase = verifyEmailUseCase;
        this.forgotPasswordUseCase = forgotPasswordUseCase;
        this.resetPasswordUseCase = resetPasswordUseCase;
        this.mapper = mapper;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user")
    public ResponseEntity<SuccessMessageResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterUserCommand command = mapper.toCommand(request);
        registerUserUseCase.execute(command);
        return ResponseEntityFactory.created("users", "new", new SuccessMessageResponse("Registration successful. Please check your email to verify your account."));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate user and create session")
    public ResponseEntity<AuthenticationResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        
        LoginCommand command = mapper.toCommand(request, ipAddress, userAgent);
        AuthenticationResultDto result = loginUseCase.execute(command);
        return ResponseEntityFactory.ok(mapper.toResponse(result));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate refresh token to get new access token")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        RefreshTokenCommand command = mapper.toCommand(request);
        TokenPairDto result = refreshTokenUseCase.execute(command);
        return ResponseEntityFactory.ok(mapper.toResponse(result));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke current session")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request, Principal principal) {
        UUID userId = extractUserId(principal);
        LogoutCommand command = mapper.toLogoutCommand(request, userId);
        logoutUseCase.execute(command);
        return ResponseEntityFactory.noContent();
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Verify user email address")
    public ResponseEntity<SuccessMessageResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        VerifyEmailCommand command = mapper.toCommand(request);
        verifyEmailUseCase.execute(command);
        return ResponseEntityFactory.ok(new SuccessMessageResponse("Email verified successfully."));
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request password reset email")
    public ResponseEntity<SuccessMessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        ForgotPasswordCommand command = mapper.toCommand(request);
        forgotPasswordUseCase.execute(command);
        return ResponseEntityFactory.ok(new SuccessMessageResponse("If the email exists, a password reset link has been sent."));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Reset password using token")
    public ResponseEntity<SuccessMessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        ResetPasswordCommand command = mapper.toCommand(request);
        resetPasswordUseCase.execute(command);
        return ResponseEntityFactory.ok(new SuccessMessageResponse("Password has been reset successfully."));
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }

    private UUID extractUserId(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new IllegalArgumentException("User is not authenticated");
        }
        return UUID.fromString(principal.getName());
    }
}
