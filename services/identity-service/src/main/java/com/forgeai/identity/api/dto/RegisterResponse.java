package com.forgeai.identity.api.dto;

import java.util.UUID;

public record RegisterResponse(
    UUID id,
    String email,
    String username,
    String firstName,
    String lastName,
    String status
) {}
