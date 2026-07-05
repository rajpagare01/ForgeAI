package com.forgeai.identity.presentation.response;

public record TokenResponse(
    String accessToken,
    String refreshToken,
    long expiresIn
) {}
