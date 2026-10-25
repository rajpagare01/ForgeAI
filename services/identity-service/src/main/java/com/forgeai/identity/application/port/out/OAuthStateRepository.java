package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.OAuthState;

import java.time.Instant;
import java.util.Optional;

public interface OAuthStateRepository {
    Optional<OAuthState> findByState(String state);
    OAuthState save(OAuthState oauthState);
    void cleanupExpiredStates(Instant now);
}
