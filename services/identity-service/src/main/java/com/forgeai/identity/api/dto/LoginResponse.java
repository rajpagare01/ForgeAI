package com.forgeai.identity.api.dto;

import java.util.UUID;

public record LoginResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    UserDto user
) {
    public record UserDto(
        UUID id,
        String username,
        String email,
        String firstName,
        String lastName
    ) {}
}
