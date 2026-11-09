package com.forgeai.identity.application.port.out;

import java.util.List;
import java.util.UUID;

public interface TokenProvider {
    String generateToken(UUID userId);
    default String generateToken(UUID userId, UUID sessionId) {
        return generateToken(userId);
    }
    String generateToken(UUID userId, UUID orgId, String role, List<String> permissions);
    default String generateToken(UUID userId, UUID sessionId, UUID orgId, String role, List<String> permissions) {
        return generateToken(userId, orgId, role, permissions);
    }
    long getExpirationSeconds();
}
