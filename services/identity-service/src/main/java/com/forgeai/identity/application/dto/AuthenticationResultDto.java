package com.forgeai.identity.application.dto;

import java.util.UUID;

/**
 * The result of a successful authentication (login or token refresh).
 * Contains the token pair and the authenticated user's basic identity.
 *
 * @param userId       the authenticated user's identifier
 * @param email        the authenticated user's email
 * @param sessionId    the session that was created or refreshed
 * @param tokenPair    the JWT access token and opaque refresh token
 */
public record AuthenticationResultDto(
        UUID userId,
        String email,
        UUID sessionId,
        TokenPairDto tokenPair) {
}
