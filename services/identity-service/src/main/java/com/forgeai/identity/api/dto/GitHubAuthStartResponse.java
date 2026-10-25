package com.forgeai.identity.api.dto;

public record GitHubAuthStartResponse(
    String authorizationUrl,
    String state
) {}
