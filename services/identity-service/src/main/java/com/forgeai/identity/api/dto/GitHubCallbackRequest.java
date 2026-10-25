package com.forgeai.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

public record GitHubCallbackRequest(
    @NotBlank(message = "OAuth code is required")
    String code,
    @NotBlank(message = "OAuth state is required")
    String state,
    String redirectUri
) {}
