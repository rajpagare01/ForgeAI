package com.forgeai.identity.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a Session aggregate for API consumers.
 *
 * @param sessionId    the session's unique identifier
 * @param userId       the user who owns this session
 * @param deviceInfo   a human-readable device description
 * @param ipAddress    the IP address from which the session was created
 * @param userAgent    the User-Agent header captured at login
 * @param status       the session status (ACTIVE, EXPIRED, REVOKED)
 * @param createdAt    when the session was created
 * @param lastAccessed when the session was last used
 */
public record SessionDto(
        UUID sessionId,
        UUID userId,
        String deviceInfo,
        String ipAddress,
        String userAgent,
        String status,
        Instant createdAt,
        Instant lastAccessed) {
}
