package com.forgeai.identity.application.port;

import java.util.Map;
import java.util.Optional;

/**
 * Output port for verifying and decoding JWT access tokens.
 */
public interface TokenVerifierPort {

    /**
     * Verifies the JWT signature and extracts its claims.
     *
     * @param token the compact serialized JWT
     * @return the claims if valid, or empty if verification fails
     */
    Optional<Map<String, Object>> verify(String token);
}
