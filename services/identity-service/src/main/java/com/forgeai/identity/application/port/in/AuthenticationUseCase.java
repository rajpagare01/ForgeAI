package com.forgeai.identity.application.port.in;

import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.domain.model.User;

public interface AuthenticationUseCase {
    User register(String email, String username, String password, String firstName, String lastName);
    LoginResponse login(String identifier, String password);
}
