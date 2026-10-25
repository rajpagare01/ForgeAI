package com.forgeai.identity.api.dto;

public record GitHubStatusResponse(
    boolean linked,
    GitHubIdentityDto identity
) {}
