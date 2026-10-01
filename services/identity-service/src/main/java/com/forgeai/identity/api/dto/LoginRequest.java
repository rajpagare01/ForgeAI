package com.forgeai.identity.api.dto;

public record LoginRequest(
    String identifier,
    String password
) {}
