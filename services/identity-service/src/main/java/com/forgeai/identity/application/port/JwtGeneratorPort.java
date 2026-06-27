package com.forgeai.identity.application.port;

import java.util.Map;
import java.util.UUID;

/**
 * Output port for generating signed JWT access tokens.
 * Implemented in the infrastructure layer using RS256 keys.
 */
public interface JwtGeneratorPort {

    /**
     * Generates a signed JWT access token for the given subject.
     *
     * @param userId   the subject (user ID)
     * @param sessionId the session this token belongs to
     * @param claims   additional claims to embed
     * @return the compact serialized JWT string
     */
    String generateAccessToken(UUID userId, UUID sessionId, Map<String, Object> claims);

    /**
     * Returns the configured access token TTL in seconds.
     *
     * @return TTL in seconds
     */
    long getAccessTokenTtlSeconds();
}
