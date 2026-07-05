package com.forgeai.identity.presentation.request;

import java.util.UUID;
import jakarta.validation.constraints.NotNull;

public record LogoutRequest(
    @NotNull(message = "Session ID is required")
    UUID sessionId
) {}
