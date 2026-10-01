package com.forgeai.identity.application.port.out;

import java.util.UUID;

public interface TokenProvider {
    String generateToken(UUID userId);
}
