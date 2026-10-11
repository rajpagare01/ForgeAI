package com.forgeai.identity.application.port.out;

import java.util.List;
import java.util.UUID;

public interface TokenProvider {
    String generateToken(UUID userId);
    String generateToken(UUID userId, UUID orgId, String role, List<String> permissions);
    long getExpirationSeconds();
}
