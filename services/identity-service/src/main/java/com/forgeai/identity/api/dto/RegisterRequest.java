package com.forgeai.identity.api.dto;

public record RegisterRequest(
    String email,
    String username,
    String password,
    String firstName,
    String lastName
) {}
