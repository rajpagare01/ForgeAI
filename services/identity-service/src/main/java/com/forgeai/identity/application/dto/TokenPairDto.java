package com.forgeai.identity.application.dto;

/**
 * A pair of tokens issued upon successful authentication.
 *
 * @param accessToken  a short-lived JWT for API authorization
 * @param refreshToken an opaque token used to obtain new access tokens
 * @param expiresInSeconds the access token's TTL in seconds
 */
public record TokenPairDto(
        String accessToken,
        String refreshToken,
        long expiresInSeconds) {
}
