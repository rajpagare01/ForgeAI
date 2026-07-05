package com.forgeai.identity.presentation.request;

import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RefreshTokenRequest(
    @NotNull(message = "Session ID is required")
    UUID sessionId,
    
    @NotBlank(message = "Refresh token is required")
    String refreshToken
) {}
