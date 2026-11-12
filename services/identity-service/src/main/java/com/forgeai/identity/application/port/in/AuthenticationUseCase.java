package com.forgeai.identity.application.port.in;

import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.domain.model.User;

public interface AuthenticationUseCase {
    User register(String email, String username, String password, String firstName, String lastName);
    LoginResponse login(String identifier, String password);
    com.forgeai.identity.api.dto.RefreshTokenResponse refresh(String rawRefreshToken);
    void logout(java.util.UUID userId, String rawRefreshToken);
    default void logout(java.util.UUID userId, String rawRefreshToken, java.util.UUID sessionId) {
        logout(userId, rawRefreshToken);
    }
    void logoutAll(java.util.UUID userId);
}
