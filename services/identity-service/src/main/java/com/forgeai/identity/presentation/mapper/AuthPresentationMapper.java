package com.forgeai.identity.presentation.mapper;

import com.forgeai.identity.application.command.*;
import com.forgeai.identity.application.dto.AuthenticationResultDto;
import com.forgeai.identity.application.dto.TokenPairDto;
import com.forgeai.identity.application.dto.UserDto;
import com.forgeai.identity.presentation.request.*;
import com.forgeai.identity.presentation.response.AuthenticationResponse;
import com.forgeai.identity.presentation.response.TokenResponse;
import com.forgeai.identity.presentation.response.UserResponse;
import org.springframework.stereotype.Component;

/**
 * Maps Presentation Auth Requests to Application Commands,
 * and Application DTOs to Presentation Responses.
 */
@Component
public class AuthPresentationMapper {

    public RegisterUserCommand toCommand(RegisterRequest request) {
        return new RegisterUserCommand(request.email(), request.password());
    }

    public LoginCommand toCommand(LoginRequest request, String ipAddress, String userAgentHeader) {
        return new LoginCommand(
            request.email(), 
            request.password(), 
            ipAddress, 
            userAgentHeader != null ? userAgentHeader : request.userAgent(), 
            request.deviceId()
        );
    }

    public RefreshTokenCommand toCommand(RefreshTokenRequest request) {
        return new RefreshTokenCommand(request.sessionId(), request.refreshToken());
    }

    public LogoutCommand toLogoutCommand(LogoutRequest request, UUID userId) {
        return new LogoutCommand(request.sessionId(), userId);
    }

    public VerifyEmailCommand toCommand(VerifyEmailRequest request) {
        return new VerifyEmailCommand(request.token());
    }

    public ForgotPasswordCommand toCommand(ForgotPasswordRequest request) {
        return new ForgotPasswordCommand(request.email());
    }

    public ResetPasswordCommand toCommand(ResetPasswordRequest request) {
        return new ResetPasswordCommand(request.token(), request.newPassword());
    }

    public AuthenticationResponse toResponse(AuthenticationResultDto dto) {
        return new AuthenticationResponse(
            toResponse(dto.user()),
            toResponse(dto.tokens())
        );
    }

    public TokenResponse toResponse(TokenPairDto dto) {
        return new TokenResponse(dto.accessToken(), dto.refreshToken(), dto.expiresIn());
    }

    public UserResponse toResponse(UserDto dto) {
        return new UserResponse(
            dto.id(),
            dto.email(),
            dto.status(),
            dto.mfaEnabled(),
            dto.createdAt(),
            dto.updatedAt()
        );
    }
}
