package com.forgeai.identity.infrastructure.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.Collections;
import java.util.UUID;

public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final UUID userId;
    private final String token;

    public JwtAuthenticationToken(UUID userId, String token) {
        super(Collections.emptyList());
        this.userId = userId;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }
}
