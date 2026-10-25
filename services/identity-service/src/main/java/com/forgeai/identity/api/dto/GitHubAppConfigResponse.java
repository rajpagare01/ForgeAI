package com.forgeai.identity.api.dto;

public record GitHubAppConfigResponse(
    boolean configured,
    String clientId
) {}
