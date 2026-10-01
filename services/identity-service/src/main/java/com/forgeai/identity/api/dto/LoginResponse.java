package com.forgeai.identity.api.dto;

import java.util.UUID;

public record LoginResponse(
    String accessToken,
    UUID userId,
    String username,
    String email
) {}
